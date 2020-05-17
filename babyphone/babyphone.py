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
import datetime as dt
from babyphone import motiondetect
from babyphone.connection import Connection


class Babyphone(object):

    LIGHTS_GPIO = 24

    def __init__(self, loop):
        self.log = self._initLogger()
        self._videoFrameData = []
        self._loop = loop
        self.log.debug("starting babyphone")
        self.conns = set()
        self.motion = motiondetect.MotionDetect(self)

        self.nightMode = False
        self._audioEncoder = None

        self.log.debug("configuring GPIO")
        gpio.setmode(gpio.BCM)
        gpio.setup(self.LIGHTS_GPIO, gpio.OUT)

        self.executor = ThreadPoolExecutor(max_workers=4)
        self._running = threading.Event()
        self._streamingTask = None

    def _initLogger(self):
        log = logging.getLogger("babyphone")
        log.setLevel(logging.DEBUG)
        consoleHandler = logging.StreamHandler(stream=sys.stdout)
        consoleHandler.setFormatter(
            logging.Formatter("%(asctime)s [%(levelname)-5.5s]  %(message)s")
        )
        log.addHandler(consoleHandler)
        return log

    def start(self):
        if self._running.is_set():
            log.warning(
                "Attempting to start babyphone but it seems to be running already. Ignoring."
            )
            return

        self.log.info("Starting babyphone")

        self._running.set()
        self.log.debug("starting audio level checker")
        self.executor.submit(self._startAudioMonitoring)
        self.log.debug("...done")

        # self.log.debug("starting motion detection")
        # self.motion.start()
        # self.log.debug("done")

        self.log.debug("starting camera")
        self.cam = picamera.PiCamera(resolution=(320, 240), framerate=10)
        self.cam.rotation = 90
        self.log.debug("done")

    def stop(self):
        if not self._running.is_set():
            self.log.warn(
                "Babyphone stop called but it seems not to be running. Ignoring")
            return

        self.log.info("Stopping babyphone")
        self._running.clear()

        self.motion.stop()
        self.cam.close()

    def close(self):
        self.log.info("closing babyphone")
        self.stop()
        self.log.debug("shutting down thread pool executor")
        self.executor.shutdown()
        gpio.cleanup()

    @asyncio.coroutine
    def setNightMode(self, nightMode):
        if nightMode == self.nightMode:
            return

        # if there's a running stream, let's update the light status
        # right away
        if self.isAnyoneStreaming():
            self.setLights(nightMode)

        self.nightMode = nightMode

        if self.nightMode:
            # add settings to camera
            self.cam.brightness = 70
            self.cam.iso = 800
            self.cam.contrast = 0
            self.cam.awb_mode = "off"
            self.cam.awb_gains = (1, 1)
            self.cam.exposure_mode = "night"
        else:
            self.cam.brightness = 50
            self.cam.iso = 300
            self.cam.contrast = 0
            self.cam.awb_mode = "off"
            self.cam.awb_gains = (1, 1)
            self.cam.exposure_mode = "off"

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

    def _startAudioMonitoring(self):
        try:
            self.log.debug("initializing Alsa PCM device")
            inp = alsaaudio.PCM(
                alsaaudio.PCM_CAPTURE, alsaaudio.PCM_NORMAL,
                device="dmic_sv",  # babyphone
                # cardindex=2,      # dev pi
            )
            inp.setchannels(2)
            inp.setrate(48000)
            inp.setformat(alsaaudio.PCM_FORMAT_S32_LE)
            inp.setperiodsize(320)
            self.log.debug("...initialization done")

            maxRms = (1 << 31) - 1  # only 15 because it's signed
            lastSent = time.time()

            start = time.time()

            sampleState = None
            samples = []
            while True:
                if not self._running.is_set():
                    self.log.info("Stopping audio monitoring by signal")
                    break
                l, data = inp.read()

                if not l:
                    continue

                try:
                    data = audioop.tomono(data, 4, 1, 1)
                    (data, sampleState) = audioop.ratecv(
                        data, 4, 1, 48000, 8000, sampleState
                    )
                    data = audioop.mul(data, 4, 2)
                    rms = audioop.rms(data, 4)

                    asyncio.run_coroutine_threadsafe(
                        self._multicastAudio(
                            audioop.lin2alaw(data, 4), time.time() - start
                        ),
                        loop=self._loop,
                    )
                    samples.append(float(rms) / float(maxRms))

                    if time.time() - lastSent >= 1.0:
                        level = np.quantile(samples, 0.75)
                        samples = []
                        lastSent = time.time()
                        asyncio.run_coroutine_threadsafe(
                            self.broadcastVolume(level),
                            loop=self._loop,
                        )
                except audioop.error as e:
                    self.log.debug(
                        "error in audioop %s. continuing..." % str(e))
                    continue

        except (asyncio.CancelledError, CancelledError) as e:
            self.log.info(
                "Stopping audio monitoring since the task was cancelled")
        except Exception as e:
            self.log.error("Error monitoring audio")
            self.log.exception(e)

    async def broadcastVolume(self, level):
        # TODO: use multicast for connections that want it
        for conn in self.conns:
            await conn.sendVolume(level)

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
        data = base64.b64encode(bytes(audioData)).decode("ascii")
        pts = int(relativeTime * 1000000)

        for conn in self.conns:
            if conn.audioRequested():
                yield from conn.sendAudioPacket(data, pts)

    async def broadcastConfig(self):
        configuration = dict(
            night_mode=self.nightMode, motion_detection=self.motion.isRunning()
        )
        for conn in self.conns:
            await conn.sendConfig(configuration)

    @asyncio.coroutine
    def streamStatusUpdated(self):
        while True:
            if self.motion.isTakingPicture():
                self.log.debug("waiting for motion detection to finish")
                yield from asyncio.sleep(0.2)
            else:
                break

        if self.isAnyoneStreaming():
            if self._streamingTask is None or self._streamingTask.done():
                self._streamingTask = asyncio.ensure_future(self.startStream())
        else:
            if self._streamingTask and not self._streamingTask.done():
                self._streamingTask.cancel()

    @asyncio.coroutine
    def startStream(self):
        try:
            self.setLights(self.nightMode)
            self.log.info("Start recording with cam")

            self.cam.start_recording(
                self, format="h264", intra_period=10, profile="main", quality=23
            )
            # wait forever
            yield from asyncio.sleep(36000)

        except (asyncio.CancelledError, CancelledError) as e:
            self.log.info("streaming cancelled, will stop recording")
        except Exception as e:
            self.log.info("exception while streamcam")
            self.log.exception(e)
        finally:
            self.log.info("stopping the recording")
            self.cam.annotate_background = None
            self.cam.annotate_text = ""
            self.cam.stop_recording()
            self.setLights(False)

    def write(self, data):
        try:
            self._videoFrameData.extend(data)
            if self.cam.frame.complete:
                frame = self.cam.frame
                msg = dict(
                    action="vframe",
                    pts=frame.timestamp,
                    offset=frame.position,
                    timestamp=frame.timestamp,
                    now=int(time.time() * 1000),
                    data=base64.b64encode(
                        bytes(self._videoFrameData)).decode("ascii"),
                    type=0,
                )
                if frame == picamera.PiVideoFrameType.sps_header:
                    msg["type"] = 1
                asyncio.run_coroutine_threadsafe(
                    self.multicastVideo(msg), loop=self._loop)

                self._videoFrameData = []
        except Exception as e:
            self.log.exception(e)

    async def multicastVideo(self, msg):
        for con in self.conns:
            if con.streamRequested():
                await con.sendVideo(msg)

    async def broadcastSystemStatus(self, status):
        for conn in self.conns:
            await conn.sendSystemStatus(status)

    async def broadcastMovement(self, movement):
        for conn in self.conns:
            await conn.sendMovement(movement)

    def isAnyoneStreaming(self):
        return any([con.streamRequested() for con in self.conns])

    @asyncio.coroutine
    def shutdown(self, conn):
        self.log.info("Shutting down machine as requested by %s", str(conn))
        yield from self.broadcastSystemStatus("shutdown")
        # give the clients 2 seconds to disconnect
        yield from asyncio.sleep(2)
        subprocess.check_call(["sudo", "shutdown", "-h", "0"])

    @asyncio.coroutine
    def restart(self, conn):
        self.log.info("Restarting machine as requested by %s", str(conn))
        yield from self.broadcastSystemStatus("restart")
        # give the clients 2 seconds to disconnect
        yield from asyncio.sleep(2)
        subprocess.check_call(["sudo", "shutdown", "-r", "0"])

    @asyncio.coroutine
    def connect(self, websocket, path):
        c = Connection(self, websocket)

        yield from self.addConnection(c)

        yield from c.run()

    def removeConnection(self, conn):
        if conn not in self.conns:
            self.log.info(
                "connection %s cannot be removed, it's not connected", conn)
            return

        self.log.info("Removed connection, we have now %s", self.conns)

        if len(self.conns) == 0:
            self.stop()

    @asyncio.coroutine
    def addConnection(self, connection):
        # it's the first connection, let's start
        if len(self.conns) == 0:
            self.start()

        self.conns.add(connection)

        self.log.info("Added connection, we have now %s", self.conns)

    def setLights(self, on):
        self.log.info("turning lights %s", "on" if on else "off")
        gpio.output(self.LIGHTS_GPIO, bool(on))

    @asyncio.coroutine
    def getLastPictureAsBytes(self, refresh):
        if refresh:
            yield from self.motion.updatePicture(highRes=True)
        lastPicture = self.motion.lastPicture

        if lastPicture is None:
            return None

        return cv2.imencode(".png", lastPicture)[1].tostring()

    def getLastPictureTimestamp(self):
        return self.motion.lastPictureTimestamp
