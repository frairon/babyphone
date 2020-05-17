
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

    @asyncio.coroutine
    def run(self):
        try:
            while True:
                message = yield from self._ws.recv()
                try:
                    yield from self._handleMessage(message)
                except Exception as e:
                    logging.error(
                        "Error handling message: %s (%s)", message, e)
            logging.info(
                "done message done loop. Will disconnect now, if we didn't do already"
            )
        except websockets.exceptions.ConnectionClosed as e:
            logging.info("websocket closed. terminating connection")
        finally:
            yield from self._disconnect()

    @asyncio.coroutine
    def _runHeartbeat(self):
        logging.info("Starting heartbeating to client")
        while True:
            try:
                yield from self._send({"action": "heartbeat"})
            except websockets.exceptions.ConnectionClosed as e:
                return

            yield from asyncio.sleep(1)

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

    @asyncio.coroutine
    def _send(self, obj):
        yield from self._ws.send(json.dumps(obj))

    @asyncio.coroutine
    def _handleMessage(self, message):
        msg = json.loads(message)
        if "action" not in msg:
            raise InvalidMessageException("action not in message")

        if msg["action"] == "shutdown":
            yield from self.bp.shutdown(self)
        elif msg["action"] == "restart":
            yield from self.bp.restart(self)
        elif msg["action"] == "startstream":
            self.state = self.STREAMING
        elif msg["action"] == "stopstream":
            self.state = self.IDLE
        elif msg["action"] == "_startstream":
            self._streamRequested = True
            yield from self.bp.streamStatusUpdated()
        elif msg["action"] == "_stopstream":
            self._streamRequested = False
            yield from self.bp.streamStatusUpdated()
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
            yield from self.bp.updateConfig(msg.get("configuration", {}))

        elif msg["action"] == "configuration_request":
            yield from self.bp.broadcastConfig()
        else:
            logging.error(
                "Unhandled message from connection %s: %s", self, message)

    @asyncio.coroutine
    def _disconnect(self):
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
