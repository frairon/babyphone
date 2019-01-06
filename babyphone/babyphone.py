

import json
import logging
import subprocess

import asyncio
import motiondetect
import pigpio

pigpio.exceptions = True


class InvalidMessageException(Exception):
    pass


async def handleVideoserverOutput(proc, bp):
    volumeBuffer = [0, 0, 0, 0, 0, 0]
    bufIdx = 0
    buflen = len(volumeBuffer)

    while True:
        # proc is terminated
        if proc.returncode is not None:
            break

        # try to read a line
        line = await proc.stdout.readline()

        try:
            parsed = json.loads(line)
        except json.JSONDecodeError as e:
            logging.info(
                "Error parsing videoserver output as json: %s", parsed)
            continue

        logging.info("videoserver output %s", line)

        normRms = parsed.get('normrms', -1)
        if normRms == -1:
            continue

        volumeBuffer[bufIdx] = normRms
        bufIdx = (bufIdx + 1) % buflen

        await bp.broadcast({'action': 'volume',
                            'volume': float(sum(volumeBuffer)) / float(buflen)
                            })


async def runStreamServer(bp):
    while True:
        try:
            proc = await asyncio.create_subprocess_exec(["videoserver"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            await handleVideoserverOutput(proc, bp)
        finally:
            if proc and proc.returncode is None:
                proc.terminate()


class Babyphone(object):

    LIGHTS_GPIO = 24

    def __init__(self):
        self.conns = set()
        self.motion = motiondetect.MotionDetect(self)
        self.pi = pigpio.pi()

        if self.pi is None or not self.pi.connected:
            raise Exception("Error initializing pi")
        logging.info("pi version is %s",
                     self.pi.get_hardware_revision())

        self.pi.set_mode(self.LIGHTS_GPIO, pigpio.OUTPUT)

        self.streamServer = asyncio.ensure_future(runStreamServer(self))

    async def broadcast(self, obj):
        for con in self.conns:
            await con._send(obj)

    async def isAnyoneStreaming(self):
        return any([con.isStreaming() for con in self.conns])

    def close(self):
        self.motion.stop()
        self.streamServer.cancel()

    def shutdown(self, conn):
        logging.info("Shutting down machine as requested by %s", str(conn))
        self.bp.broadcast({
            "action": "status_update",
            "status": "shutting-down"
        })
        subprocess.check_call(['sudo', 'shutdown', '-h', '0'])

    async def connect(self, websocket, path):
        Connection(self, websocket)

    def removeConnection(self, conn):
        self.conns.remove(conn)

    async def updateState(self):

        # do not touch the lights if motion detect is currently active.
        if self.motiondetect.isTakingPicture():
            return

        # check if anyone needs lights
        needLights = any([con.useLights for con in self.conns])

        # turn the lights on if anyone needs them and anyone is streaming
        self.setLights(needLights and self.isAnyoneStreaming())

    def setLights(self, on):
        logging.info("turning lights %s", "on" if on else "off")
        self.pi.write(self.LIGHTS_GPIO, 1 if on else 0)

class Connection(object):

    IDLE = 0
    STREAMING = 1

    def __init__(self, babyphone, websocket):
        self._ws = websocket
        self.bp = babyphone
        self.state = self.IDLE

        self.useLights = False

        logging.info("Client connected")

        self._heartbeat = asyncio.ensure_future(self.heartbeat())

    def isStreaming(self):
        return self.state in [self.STREAMING]

    async def run(self):
        try:
            if self._ws.closed:
                return
            for message in self._ws:
                try:
                    await self.handleMessage(message)
                except Exception as e:
                    logging.Error("Error handling message: %s", e)
        finally:
            await self.disconnect()

    async def heartbeat(self):
        logging.info("Starting heartbeating to client")
        while True:
            try:
                await asyncio.sleep(1)
                await self._send({'action': 'heartbeat'})
            except Exception as e:
                logging.error("Error heartbeating: %s", e)
                await self.disconnect()

    async def _send(self, obj):
        await self._ws.send(json.dumps(obj))

    async def handleMessage(self, message):
        msg = json.loads(message)

        if 'action' not in msg:
            raise InvalidMessageException("action not in message")

        if msg['action'] == 'shutdown':
            self.bp.shutdown()
        elif msg['action'] == 'startstream':
            self.state = self.STREAMING
        elif msg['action'] == 'stopstream':
            self.state = self.IDLE
        elif msg['action'] == 'lights':
            self.useLights = msg.get('lights', 0) == 1
        elif msg['action'] == 'lightson':  # deprecated, remove
            self.bp.setLights(True)
        elif msg['action'] == 'lightsoff':  # deprecated, remove
            self.bp.setLights(False)
        else:
            logging.error(
                "Unhandled message from connection %s: %s", self, message)

        await self.bp.updateState()

    async def disconnect(self):
        try:
            self._heartbeat.cancel()
            # idempotent
            self._ws.close()
        finally:
            # remove from set of cnnections
            self.bp.removeConnection(self)
            await self.bp.updateState()

    def __str__(self):
        return "Connection {ws.host}".format(ws=self._ws)
