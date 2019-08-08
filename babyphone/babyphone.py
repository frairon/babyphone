import asyncio
import base64
import json
import logging
import subprocess
import sys
import time
import audioop
import alsaaudio
import threading
from concurrent.futures import ThreadPoolExecutor, CancelledError

import cv2
import numpy as np
import picamera
import RPi.GPIO as gpio
import websockets.exceptions

from babyphone2 import motiondetect


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

    def __init__(self, loop):
        self._data = []
        self._loop = loop
        log.debug("starting babyphone")
        self.conns = set()
        self.motion = motiondetect.MotionDetect(self)

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
        self.cam.rotation=90
        log.debug("done")
        self.streamingTask = None

    def _startAudioMonitoring(self, event):
        try:
            log.debug("initializing Alsa PCM device")
            inp = alsaaudio.PCM(alsaaudio.PCM_CAPTURE, alsaaudio.PCM_NORMAL, device='plughw:CARD=Device')
            inp.setchannels(1)
            inp.setrate(8000)
            inp.setformat(alsaaudio.PCM_FORMAT_S16_LE)
            inp.setperiodsize(160)
            log.debug("...initialization done")

            maxRms = (1<<15)-1 # only 15 because it's signed
            lastSent = time.time()
            while not event.is_set():
                l, data = inp.read()

                if l:
                    try:
                        data = audioop.mul(data, 2, 4)
                        rms = audioop.rms(data, 2)

                        if time.time() - lastSent >= 0.5:
                            level = float(rms) / float(maxRms)
                            lastSent = time.time()
                            asyncio.run_coroutine_threadsafe(self.broadcast({'action': 'volume',
                                                     'volume': level,
                                                     }), loop=self._loop)
                    except audioop.error:
                        continue

        except (asyncio.CancelledError, CancelledError) as e:
            log.info("Stopping audio monitoring since the task was cancelled")
        except Exception as e:
            log.error("Error monitoring audio")
            log.exception(e)





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
            self.cam.start_recording(self, format='h264', intra_period=10, profile='main', quality=23)
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
                # log.info("got frame %s: %s", str(self.cam.frame), self._data[:10])
                frame = self.cam.frame
                msg = dict(
                    action="vframe",
                    offset=frame.position,
                    timestamp=frame.timestamp,
                    data=base64.b64encode(bytes(self._data)).decode('ascii'),
                    type=0,
                )
                if frame == picamera.PiVideoFrameType.sps_header:
                    msg['type'] = 1
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
        return None
        # lastPicture = self.motion.lastPicture
        # if lastPicture is None:
        #     return None
        #
        # return cv2.imencode(".png", lastPicture)[1].tostring()

    def getLastPictureTimestamp(self):
        return None
        # return self.motion.lastPictureTimestamp

class Connection(object):

    IDLE = 0
    STREAMING = 1

    def __init__(self, babyphone, websocket):
        self._ws = websocket
        self.bp = babyphone

        self.useLights = False
        self.streamRequested = False

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
                    log.error("Error handling message: %s", e)
            log.info(
                "done message done loop. Will disconnect now, if we didn't do already")
        except websockets.exceptions.ConnectionClosed as e:
            log.info("websocket closed. terminating connection")
        finally:
            yield from self.disconnect()

    @asyncio.coroutine
    def heartbeat(self):
        log.info("Starting heartbeating to client")
        while True:
            try:
                yield from self._send({'action': 'heartbeat'})
            except websockets.exceptions.ConnectionClosed as e:
                return

            yield from asyncio.sleep(1)

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
        elif msg['action'] == '_startstream':
            self.streamRequested = True
            yield from self.bp.streamStatusUpdated()
        elif msg['action'] == '_stopstream':
            self.streamRequested = False
            yield from self.bp.streamStatusUpdated()
        elif msg['action'] == 'motiondetect':
            if msg.get('value', False):
                self.motion.start()
            else:
                self.motion.stop()
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
