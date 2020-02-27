import base64
import io
import logging
import math
import time
from datetime import datetime

import asyncio
import cv2
import numpy as np
import picamera
from skimage.measure import compare_ssim


class MotionDetect(object):
    def __init__(self, babyphone):
        self._takingPicture = False
        self._runner = None
        self._bp = babyphone
        self.log = logging.getLogger("babyphone")

        # poll every 500ms
        self._pollInterval = 0.5

        self.lastPicture = None
        self.lastPictureTimestamp = 0
        self._moved = False

        self._counter = 0
        self._maxVals = 20
        self._movementValues = []


    def start(self):
        self._runner = asyncio.ensure_future(self._run())

    def stop(self):
        if not self._runner:
            return

        self._runner.cancel()
        self._runner = None

    def isRunning(self):
        return self._runner is not None

    @asyncio.coroutine
    def _takePicture(self, nightMode, highRes=False):

        oldRes = self._bp.cam.resolution
        try:
            stream = io.BytesIO()

            self._takingPicture = True
            if nightMode:
                self._bp.setLights(True)
                yield from asyncio.sleep(0.2)

            # let the camera adjust to the new light settings
            if highRes:
                self._bp.cam.resolution=(800, 600)
            self._bp.cam.capture(stream, format="jpeg")

            # Construct a numpy array from the stream
            data = np.fromstring(stream.getvalue(), dtype=np.uint8)
            # "Decode" the image from the array, preserving color
            image = cv2.cvtColor(cv2.imdecode(data, 1), cv2.COLOR_BGR2GRAY)

            self.log.info("Took picture")
            return image
        finally:
            self._takingPicture = False
            self._bp.cam.resolution = oldRes

            if nightMode:
                self._bp.setLights(False)



    @asyncio.coroutine
    def _calcMovement(self, img1, img2):
        s = 1.0 - compare_ssim(img1, img2)
        return s

    @asyncio.coroutine
    def _run(self):

        self.log.info("Starting motion detection")

        while True:
            self._counter += 1
            yield from asyncio.sleep(self._pollInterval)

            if self.lastPictureTimestamp is not None and time.time() < self.lastPictureTimestamp+self._nextPictureDelay():
                continue

            if self._bp.isAnyoneStreaming():
                self.log.info(
                    "at least one connection is streaming, camera is busy, cannot do motion detection"
                )
                continue

            try:
                oldPicture, newPicture = yield from self.updatePicture()
                if newPicture is not None:
                    yield from self._detectMovement(oldPicture, newPicture)

            except asyncio.CancelledError:
                self.log.info("Stopping motion detection as the task was cancelled")
                return
            except Exception as e:
                self.log.info("Error taking picture: %s", e)

    @asyncio.coroutine
    def updatePicture(self, highRes=False):
        picture = yield from self._takePicture(self._bp.nightMode, highRes)

        # taking picture failed for some reason
        if picture is None:
            return None, None

        # analyse the image brightness
        brightness = self._imageBrightness(picture)

        # it seems to be too dark or too bright, let's try different
        # mode next time
        if brightness == -1:
            self.log.info("Image is too dark, will try in night mode next time")
            yield from self._bp.setNightMode(True)
        elif brightness == 1:
            self.log.info("Image is too bright, will try in day mode next time")
            yield from self._bp.setNightMode(False)

        oldPicture = self.lastPicture

        self.lastPicture = picture.copy()
        self.lastPictureTimestamp = int(round(time.time(), 0))

        return oldPicture, self.lastPicture

    def _nextPictureDelay(self):
        if self._moved:
            return 4
        else:
            return 20

    @asyncio.coroutine
    def _detectMovement(self, oldPicture, newPicture):
        movement = yield from self._calcMovement(self.lastPicture, newPicture)

        if len(self._movementValues) < self._maxVals:
            self._movementValues.append(movement)
        else:
            self._movementValues[self._counter % self._maxVals] = movement

        avg = sum(self._movementValues) / float(len(self._movementValues))
        stddev = math.sqrt(
            sum(map(lambda x: pow(abs(x - avg), 2), self._movementValues))
            / float(len(self._movementValues))
        )

        moved = False
        if abs(movement - avg) > 2 * stddev:
            self._moved = True
        else:
            self._moved = False

        yield from self._bp.broadcast(
            {
                "action": "movement",
                "movement": dict(
                    value=movement,
                    moved=moved,
                    interval_millis=self._nextPictureDelay()*1000,
                ),
            }
        )

        return moved


    def _imageBrightness(self, img):
        hist = cv2.calcHist([img], [0], None, [10], [0, 256])

        # too dark
        if float(hist[0]) / float(img.size) > 0.99:
            return -1

        #too bright
        if float(hist[-1]) / float(img.size) > 0.7:
            return 1

        return 0

    def isTakingPicture(self):
        return self._takingPicture
