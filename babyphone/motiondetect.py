import asyncio
import io
import logging

import cv2
import numpy as np
import picamera
from skimage.measure import compare_ssim

class MotionDetect(object):

    def __init__(self, babyphone):
        self._interval = 5
        self._takingPicture = False
        self._runner = None
        self._bp = babyphone
        self.log = logging.getLogger("babyphone")

    def start(self):
        self._runner = asyncio.ensure_future(self._run())

    def stop(self):
        if not self._runner:
            return

        self._runner.cancel()
        self._runner = None

    @asyncio.coroutine
    def _takePicture(self, nightMode):

        try:
            with picamera.PiCamera(resolution=(640,480)) as cam:
                yield from asyncio.sleep(1)
                # simulate to do something with the camera
                stream = io.BytesIO()
                if nightMode:
                    self._bp.setLights(True)
                    yield from async.sleep(0.1)
                cam.capture(stream, format='jpeg')
                if nightMode:
                    self._bp.setLights(False)
            # Construct a numpy array from the stream
            data = np.fromstring(stream.getvalue(), dtype=np.uint8)
            # "Decode" the image from the array, preserving color
            image = cv2.imdecode(data, 1)
            self.log.info("Took picture")
            return image
        except Exception as e:
            self.log.info("Error taking picture: %s", e)

    @asyncio.coroutine
    def _comparePictures(self, img1, img2):

        self.log.info("comparing pictures...")
        s = compare_ssim(cv2.cvtColor(img1, cv2.COLOR_BGR2GRAY), cv2.cvtColor(img2, cv2.COLOR_BGR2GRAY))
        self.log.info("image sim: %f" % s)

    @asyncio.coroutine
    def _run(self):
        oldPicture = None

        nightMode = False

        while True:
            self.log.info("Motion detect runner next loop")
            yield from asyncio.sleep(self._interval)

            anyoneStreaming = yield from self._bp.isAnyoneStreaming()
            if anyoneStreaming:
                self.log.info(
                    "at least one connection is streaming, camera is busy, cannot do motion detection")
                continue

            try:
                self._takingPicture = True
                picture = yield from self._takePicture(nightMode)

                # it seems to be too dark or too bright, let's try different mode next time
                if self._isTooDark(picture):
                    nightMode = True
                    self.log.info("Image is too dark, will try in night mode next time")
                    continue
                if self._isTooBright(picture):
                    nightMode = False
                    self.log.info("Image is too bright, will try in day mode next time")
                    continue

                if oldPicture is not None:
                    yield from self._comparePictures(oldPicture, picture)

                oldPicture = picture
            except Exception as e:
                self.log.info("Error taking picture: %s. Trying next time", e)
            finally:
                self._takingPicture = False

    def _isTooDark(self, image):
        return False

    def _isTooBright(self, image):
        return False

    def isTakingPicture(self):
        return self._takingPicture
