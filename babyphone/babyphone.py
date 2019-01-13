

import json
import logging
import subprocess
import sys
import time

import websockets.exceptions

import asyncio
import RPi.GPIO as gpio
from babyphone import motiondetect


class InvalidMessageException(Exception):
    pass


log = None


def initLogger():
    global log
    log = logging.getLogger('babyphone')
    log.setLevel(logging.DEBUG)
    consoleHandler = logging.StreamHandler(stream=sys.stdout)
    consoleHandler.setFormatter(logging.Formatter(
        "%(asctime)s [%(levelname)-5.5s]  %(message)s"))
    log.addHandler(consoleHandler)

@asyncio.coroutine
def handleVideoserverOutput(proc, bp):
    volumeBuffer = [0, 0, 0, 0, 0, 0]
    bufIdx = 0
    buflen = len(volumeBuffer)

    lastSent = time.time()
    while True:
        # proc is terminated
        if proc.returncode is not None:
            break

        # try to read a line
        line = yield from proc.stdout.readline()
        parsed = {}
        try:
            parsed = json.loads(line.decode('utf-8'))
        except json.JSONDecodeError as e:
            # log.debug(
            #     "Error parsing videoserver output as json: %s", parsed)
            continue

        # log.debug("videoserver output %s", line)

        normRms = parsed.get('normrms', -1)
        if normRms == -1:
            continue

        volumeBuffer[bufIdx] = normRms
        bufIdx = (bufIdx + 1) % buflen

        if time.time() - lastSent >= 1.0:
            lastSent = time.time()
            yield from bp.broadcast({'action': 'volume',
                                     'volume': float(sum(volumeBuffer)) / float(buflen)
                                     })


@asyncio.coroutine
def runStreamServer(bp):
    while True:
        proc = None
        try:
            log.debug("starting videoserver")
            proc = yield from asyncio.create_subprocess_exec("/home/pi/babyphone/videoserver",
                                                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            yield from handleVideoserverOutput(proc, bp)
        except Exception as e:
            log.error("Received while running the videoserver: %s", e)
        finally:
            if proc and proc.returncode is None:
                proc.terminate()
            if proc:
                log.info("return code was %s", proc.returncode)
            log.info("Video server ended. Restarting it.")
            # sleep to avoid busy loop in case of permanent error
            yield from asyncio.sleep(3)


class Babyphone(object):

    LIGHTS_GPIO = 24

    def __init__(self):
        log.debug("starting babyphone")
        self.conns = set()
        self.motion = motiondetect.MotionDetect(self)

        log.debug("configuring GPIO")
        gpio.setmode(gpio.BCM)
        gpio.setup(self.LIGHTS_GPIO, gpio.OUT)

        log.debug("starting streaming server")
        self.streamServer = asyncio.ensure_future(runStreamServer(self))

        log.debug("...done")

    @asyncio.coroutine
    def broadcast(self, obj):
        for con in self.conns:
            yield from con._send(obj)

    @asyncio.coroutine
    def isAnyoneStreaming(self):
        return any([con.isStreaming() for con in self.conns])

    def close(self):
        self.motion.stop()
        self.streamServer.cancel()
        gpio.cleanup()

    def shutdown(self, conn):
        log.info("Shutting down machine as requested by %s", str(conn))
        self.broadcast({
            "action": "status_update",
            "status": "shutting-down"
        })
        subprocess.check_call(['sudo', 'shutdown', '-h', '0'])

    @asyncio.coroutine
    def connect(self, websocket, path):
        c = Connection(self, websocket)
        self.conns.add(c)
        yield from c.run()

    def removeConnection(self, conn):
        self.conns.remove(conn)

    @asyncio.coroutine
    def updateState(self):

        # do not touch the lights if motion detect is currently active.
        if self.motion.isTakingPicture():
            return

        # check if anyone needs lights
        needLights = any([con.useLights for con in self.conns])

        # turn the lights on if anyone needs them and anyone is streaming
        self.setLights(needLights and self.isAnyoneStreaming())

    def setLights(self, on):
        log.info("turning lights %s", "on" if on else "off")
        gpio.output(self.LIGHTS_GPIO, bool(on))


class Connection(object):

    IDLE = 0
    STREAMING = 1

    def __init__(self, babyphone, websocket):
        self._ws = websocket
        self.bp = babyphone
        self.state = self.IDLE

        self.useLights = False

        log.info("Client connected")

        self._heartbeat = asyncio.ensure_future(self.heartbeat())

    def isStreaming(self):
        return self.state in [self.STREAMING]

    @asyncio.coroutine
    def run(self):
        try:
            while True:
                message = yield from self._ws.recv()
                try:
                    yield from self.handleMessage(message)
                except Exception as e:
                    log.error("Error handling message: %s", e)
            log.info(
                "done message done loop. Will disconnect now, if we didn't do already")
        except websockets.exceptions.ConnectionClosed as e:
            log.info("websocket cosed. terminating connection")
        finally:
            yield from self.disconnect()

    @asyncio.coroutine
    def heartbeat(self):
        log.info("Starting heartbeating to client")
        while True:
            try:
                yield from asyncio.sleep(1)
                yield from self._send({'action': 'heartbeat'})
            except websockets.exceptions.ConnectionClosed as e:
                return

    @asyncio.coroutine
    def _send(self, obj):
        yield from self._ws.send(json.dumps(obj))

    @asyncio.coroutine
    def handleMessage(self, message):
        msg = json.loads(message)

        if 'action' not in msg:
            raise InvalidMessageException("action not in message")

        if msg['action'] == 'shutdown':
            self.bp.shutdown(self)
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
            log.error(
                "Unhandled message from connection %s: %s", self, message)

        # yield from self.bp.updateState()

    @asyncio.coroutine
    def disconnect(self):
        log.info("disconnecting websocket")
        try:
            self._heartbeat.cancel()
            # idempotent
            self._ws.close()
        finally:
            # remove from set of cnnections
            self.bp.removeConnection(self)
            yield from self.bp.updateState()

    def __str__(self):
        return "Connection {ws.host}".format(ws=self._ws)
