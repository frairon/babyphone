
import asyncio
import websockets.exceptions
import logging
import json
import base64


class InvalidMessageException(Exception):
    pass


class Connection(object):

    IDLE = 0
    STREAMING = 1

    def __init__(self, babyphone, websocket):
        self._ws = websocket
        self.bp = babyphone

        self._streamRequested = False
        self._audioRequested = False

        logging.info("Client connected")

        self._heartbeat = asyncio.ensure_future(self._runHeartbeat())

    def streamRequested(self):
        return self._streamRequested

    def audioRequested(self):
        return self._audioRequested

    async def run(self):
        try:
            while True:
                message = await self._ws.recv()
                try:
                    await self._handleMessage(message)
                except Exception as e:
                    logging.error(
                        "Error handling message: %s (%s)", message, e)
            logging.info(
                "done message done loop. Will disconnect now, if we didn't do already"
            )
        except websockets.exceptions.ConnectionClosed as e:
            logging.info("websocket closed. terminating connection")
        finally:
            await self._disconnect()

    async def _runHeartbeat(self):
        logging.info("Starting heartbeating to client")
        while True:
            try:
                await self._send({"action": "heartbeat"})
            except websockets.exceptions.ConnectionClosed as e:
                return

            await asyncio.sleep(1)

    async def sendAudioPacket(self, data, pts):
        msg = dict(
            action="audio",
            audio=dict(
                data=data,
                pts=pts,
            ),
        )
        await self._send(msg)

    async def sendVideo(self, data):
        await self._send(data)

    async def sendVolume(self, level):
        await self._send({"action": "volume", "volume": level})

    async def sendMovement(self, movement):
        await self._send(movement)

    async def sendConfig(self, config):
        msg = dict(
            action="configuration",
            configuration=config,
        )

        await self._send(msg)

    async def sendSystemStatus(self, status):
        msg = dict(
            action="systemstatus",
            status=status,
        )
        await self._send(msg)

    async def _send(self, obj):
        await self._ws.send(json.dumps(obj))

    async def _handleMessage(self, message):
        msg = json.loads(message)
        if "action" not in msg:
            raise InvalidMessageException("action not in message")

        if msg["action"] == "shutdown":
            await self.bp.shutdown(self)
        elif msg["action"] == "restart":
            await self.bp.restart(self)
        elif msg["action"] == "startstream":
            self.state = self.STREAMING
        elif msg["action"] == "stopstream":
            self.state = self.IDLE
        elif msg["action"] == "_startstream":
            self._streamRequested = True
            await self.bp.streamStatusUpdated()
        elif msg["action"] == "_stopstream":
            self._streamRequested = False
            await self.bp.streamStatusUpdated()
        elif msg["action"] == "startaudio":
            self._audioRequested = True
        elif msg["action"] == "stopaudio":
            self._audioRequested = False
        elif msg["action"] == "motiondetect":
            if msg.get("value", False):
                self.motion.start()
            else:
                self.motion.stop()
        elif msg["action"] == "configuration_update":
            # update the config
            await self.bp.updateConfig(msg.get("configuration", {}))

        elif msg["action"] == "configuration_request":
            await self.bp.broadcastConfig()
        else:
            logging.error(
                "Unhandled message from connection %s: %s", self, message)

    async def _disconnect(self):
        logging.info("disconnecting websocket")
        try:
            self._heartbeat.cancel()
            # idempotent
            self._ws.close()
        finally:
            # remove from set of cnnections
            self.bp.removeConnection(self)

    def __str__(self):
        return "Connection {ws.host}".format(ws=self._ws)
