import asyncio
import audioop
import base64
import json
import logging
import subprocess
import sys
import threading
import time
from concurrent.futures import CancelledError, ThreadPoolExecutor

import alsaaudio
import cv2
import numpy as np
import picamera
import RPi.GPIO as gpio
import websockets.exceptions
from babyphone import motiondetect


class InvalidMessageException(Exception):
    pass


log = None


def initLogger():
    global log
    log = logging.getLogger("babyphone")
    log.setLevel(logging.DEBUG)
    consoleHandler = logging.StreamHandler(stream=sys.stdout)
    consoleHandler.setFormatter(
        logging.Formatter("%(asctime)s [%(levelname)-5.5s]  %(message)s")
    )
    log.addHandler(consoleHandler)


class Babyphone(object):

    LIGHTS_GPIO = 24

    def __init__(self, loop):
        self._data = []
        self._loop = loop
        log.debug("starting babyphone")
        self.conns = set()
        self.motion = motiondetect.MotionDetect(self)

        self.nightMode = False
        self._audioEncoder = None

        log.debug("configuring GPIO")
        gpio.setmode(gpio.BCM)
        gpio.setup(self.LIGHTS_GPIO, gpio.OUT)

        self.executor = ThreadPoolExecutor(max_workers=4)
        self.stopEvent = threading.Event()

        log.debug("starting audio level checker")
        self.executor.submit(self._startAudioMonitoring, self.stopEvent)
        log.debug("...done")

        log.debug("starting motion detection")
        self.motion.start()
        log.debug("done")

        log.debug("starting camera")
        self.cam = picamera.PiCamera(resolution=(320, 240), framerate=10)
        self.cam.rotation = 90
        log.debug("done")
        self.streamingTask = None

    @asyncio.coroutine
    def setNightMode(self, nightMode):
        if nightMode == self.nightMode:
            return

        self.nightMode = nightMode

        yield from self.broadcastConfig()

    @asyncio.coroutine
    def setMotionDetection(self, motionDetection):
        if motionDetection == self.motion.isRunning():
            return

        if motionDetection:
            self.motion.start()
        else:
            self.motion.stop()

        yield from self.broadcastConfig()

    def _startAudioMonitoring(self, event):
        try:
            log.debug("initializing Alsa PCM device")
            inp = alsaaudio.PCM(
                alsaaudio.PCM_CAPTURE, alsaaudio.PCM_NORMAL, device="dmic_sv"
            )
            inp.setchannels(2)
            inp.setrate(48000)
            inp.setformat(alsaaudio.PCM_FORMAT_S32_LE)
            inp.setperiodsize(320)
            log.debug("...initialization done")

            maxRms = (1 << 31) - 1  # only 15 because it's signed
            lastSent = time.time()

            start = time.time()

            sampleState = None
            while not event.is_set():
                l, data = inp.read()

                if l:
                    try:
                        data = audioop.tomono(data, 4, 1, 1)
                        (data, sampleState) = audioop.ratecv(
                            data, 4, 1, 48000, 8000, sampleState
                        )
                        data = audioop.mul(data, 4, 2)
                        rms = audioop.rms(data, 4)

                        asyncio.run_coroutine_threadsafe(
                            self._multicastAudio(audioop.lin2alaw(data, 4), time.time()-start),
                            loop=self._loop,
                        )
                        if time.time() - lastSent >= 1.0:
                            level = float(rms) / float(maxRms)
                            lastSent = time.time()
                            asyncio.run_coroutine_threadsafe(
                                self.broadcast({"action": "volume", "volume": level}),
                                loop=self._loop,
                            )
                    except audioop.error as e:
                        log.debug("error in audioop %s. continuing..." % str(e))
                        continue

        except (asyncio.CancelledError, CancelledError) as e:
            log.info("Stopping audio monitoring since the task was cancelled")
        except Exception as e:
            log.error("Error monitoring audio")
            log.exception(e)

    @asyncio.coroutine
    def updateConfig(self, cfg):
        nightMode = cfg.get("night_mode")
        if nightMode is not None:
            yield from self.setNightMode(nightMode)

        motionDetection = cfg.get("motion_detection")
        if motionDetection is not None:
            yield from self.setMotionDetection(motionDetection)

        yield from self.broadcastConfig()

    @asyncio.coroutine
    def _multicastAudio(self, audioData, relativeTime):
        msg = dict(
            action="audio",
            audio=dict(
                data=base64.b64encode(bytes(audioData)).decode("ascii"),
                pts=int(relativeTime * 1000000),
            ),
        )
        for conn in self.conns:
            if conn.audioRequested:
                yield from conn._send(msg)

    @asyncio.coroutine
    def broadcastConfig(self):
        yield from self.broadcast(
            dict(
                action="configuration",
                configuration=dict(
                    night_mode=self.nightMode, motion_detection=self.motion.isRunning()
                ),
            )
        )

    @asyncio.coroutine
    def streamStatusUpdated(self):
        while True:
            if self.motion.isTakingPicture():
                log.debug("waiting for motion detection to finish")
                yield from asyncio.sleep(0.2)
            else:
                break

        if self.isAnyoneStreaming():
            if self.streamingTask is None or self.streamingTask.done():
                self.streamingTask = asyncio.ensure_future(self.startStream())
        else:
            if self.streamingTask and not self.streamingTask.done():
                self.streamingTask.cancel()

    @asyncio.coroutine
    def startStream(self):
        try:
            log.info("Start recording with cam")
            self.cam.start_recording(
                self, format="h264", intra_period=10, profile="main", quality=23
            )
            # wait forever
            yield from asyncio.sleep(3600)
        except (asyncio.CancelledError, CancelledError) as e:
            log.info("streaming cancelled, will stop recording")
        except Exception as e:
            log.info("exception while streamcam ")
            log.exception(e)
        finally:
            log.info("stopping the recording")
            self.cam.stop_recording()

    def write(self, data):
        try:
            # log.info("receiving data")
            self._data.extend(data)
            # log.info("checking for frame complete")
            if self.cam.frame.complete:
                frame = self.cam.frame
                log.info("got frame %s", str(frame.timestamp))
                msg = dict(
                    action="vframe",
                    pts=frame.timestamp,
                    offset=frame.position,
                    timestamp=frame.timestamp ,
                    data=base64.b64encode(bytes(self._data)).decode("ascii"),
                    type=0,
                )
                if frame == picamera.PiVideoFrameType.sps_header:
                    msg["type"] = 1
                # log.info("broadcasting message")
                asyncio.run_coroutine_threadsafe(self.broadcast(msg), loop=self._loop)
                # self._loop.run_until_complete(t)

                self._data = []
        except Exception as e:
            log.exception(e)

    @asyncio.coroutine
    def broadcast(self, obj):
        for con in self.conns:
            yield from con._send(obj)

    def isAnyoneStreaming(self):
        return any([con.streamRequested for con in self.conns])

    def close(self):
        log.info("closing babyphone")
        self.stopEvent.set()

        log.debug("shutting down thread pool executor")
        self.executor.shutdown()
        log.debug("...done")
        # self.motion.stop()
        # self.streamServer.cancel()
        self.cam.close()
        gpio.cleanup()

    @asyncio.coroutine
    def shutdown(self, conn):
        log.info("Shutting down machine as requested by %s", str(conn))
        yield from self.broadcast({"action": "systemstatus", "status": "shutdown"})
        # give the clients 2 seconds to disconnect
        yield from asyncio.sleep(2)
        subprocess.check_call(["sudo", "shutdown", "-h", "0"])

    @asyncio.coroutine
    def restart(self, conn):
        log.info("Restarting machine as requested by %s", str(conn))
        yield from self.broadcast({"action": "systemstatus", "status": "restart"})
        # give the clients 2 seconds to disconnect
        yield from asyncio.sleep(2)
        subprocess.check_call(["sudo", "shutdown", "-r", "0"])

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
        # if self.motion.isTakingPicture():
        #     return

        # check if anyone needs lights
        needLights = any([con.useLights for con in self.conns])

        # turn the lights on if anyone needs them and anyone is streaming
        self.setLights(needLights and self.isAnyoneStreaming())

    def setLights(self, on):
        log.info("turning lights %s", "on" if on else "off")
        gpio.output(self.LIGHTS_GPIO, bool(on))

    def getLastPictureAsBytes(self):
        lastPicture = self.motion.lastPicture
        if lastPicture is None:
            return None

        return cv2.imencode(".png", lastPicture)[1].tostring()

    def getLastPictureTimestamp(self):
        return self.motion.lastPictureTimestamp


class Connection(object):

    IDLE = 0
    STREAMING = 1

    def __init__(self, babyphone, websocket):
        self._ws = websocket
        self.bp = babyphone

        self.useLights = False
        self.streamRequested = False
        self.audioRequested = False

        log.info("Client connected")

        self._heartbeat = asyncio.ensure_future(self.heartbeat())

    @asyncio.coroutine
    def run(self):
        try:
            while True:
                message = yield from self._ws.recv()
                try:
                    yield from self.handleMessage(message)
                except Exception as e:
                    log.error("Error handling message: %s (%s)", message, e)
            log.info(
                "done message done loop. Will disconnect now, if we didn't do already"
            )
        except websockets.exceptions.ConnectionClosed as e:
            log.info("websocket closed. terminating connection")
        finally:
            yield from self.disconnect()

    @asyncio.coroutine
    def heartbeat(self):
        log.info("Starting heartbeating to client")
        while True:
            try:
                yield from self._send({"action": "heartbeat"})
            except websockets.exceptions.ConnectionClosed as e:
                return

            yield from asyncio.sleep(1)

    @asyncio.coroutine
    def _send(self, obj):
        yield from self._ws.send(json.dumps(obj))

    @asyncio.coroutine
    def handleMessage(self, message):
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
        elif msg["action"] == "lights":
            self.useLights = msg.get("lights", 0) == 1
        elif msg["action"] == "lightson":  # deprecated, remove
            self.bp.setLights(True)
        elif msg["action"] == "lightsoff":  # deprecated, remove
            self.bp.setLights(False)
        elif msg["action"] == "_startstream":
            self.streamRequested = True
            yield from self.bp.streamStatusUpdated()
        elif msg["action"] == "_stopstream":
            self.streamRequested = False
            yield from self.bp.streamStatusUpdated()
        elif msg["action"] == "startaudio":
            self.audioRequested = True
        elif msg["action"] == "stopaudio":
            self.audioRequested = False
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
            log.error("Unhandled message from connection %s: %s", self, message)

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
