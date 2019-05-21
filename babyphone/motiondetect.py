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
        self._interval = 20
        self._takingPicture = False
        self._runner = None
        self._bp = babyphone
        self.log = logging.getLogger("babyphone")

        self.lastPicture = None
        self.lastPictureTimestamp = 0

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
            with picamera.PiCamera(resolution=(320, 240)) as cam:
                yield from asyncio.sleep(0.5)
                cam.rotation=90
                # simulate to do something with the camera
                stream = io.BytesIO()
                # cam.color_effects = (128,128)
                if nightMode:
                    cam.brightness = 90
                    cam.iso = 800
                    cam.contrast = 90
                    cam.awb_mode = 'off'
                    cam.awb_gains = (1, 1)
                    cam.exposure_mode = 'night'
                    self._bp.setLights(True)

                else:
                    cam.awb_mode = 'off'
                    # cam.brightness = 90
                    cam.awb_gains = (1, 1)
                    # cam.contrast = 90

                yield from asyncio.sleep(1.0)
                cam.capture(stream, format='jpeg')
                if nightMode:
                    self._bp.setLights(False)
            # Construct a numpy array from the stream
            data = np.fromstring(stream.getvalue(), dtype=np.uint8)
            # "Decode" the image from the array, preserving color
            image = cv2.cvtColor(cv2.imdecode(data, 1), cv2.COLOR_BGR2GRAY)

            self.log.info("Took picture")
            return image
        except Exception as e:
            self.log.info("Error taking picture: %s", e)

    @asyncio.coroutine
    def _calcMovement(self, img1, img2):
        s = 1.0 - compare_ssim(img1, img2)
        return s

    @asyncio.coroutine
    def _run(self):
        oldPicture = None

        nightMode = False

        diffValues = []
        maxVals = 20

        counter = 0
        while True:
            counter += 1
            yield from asyncio.sleep(self._interval)

            anyoneStreaming = yield from self._bp.isAnyoneStreaming()
            if anyoneStreaming:
                self.log.info(
                    "at least one connection is streaming, camera is busy, cannot do motion detection")
                continue

            try:
                self._takingPicture = True
                picture = yield from self._takePicture(nightMode)

                # taking picture failed for some reason
                if picture is None:
                    continue

                # analyse the image brightness
                brightness = self._imageBrightness(picture)

                # it seems to be too dark or too bright, let's try different
                # mode next time
                if brightness == -1:
                    nightMode = True
                    self.log.info(
                        "Image is too dark, will try in night mode next time")
                    cv2.imwrite("/home/pi/toodark-%d.png" %
                                time.time(), picture)
                    continue
                if brightness == 1:
                    nightMode = False
                    self.log.info(
                        "Image is too bright, will try in day mode next time")
                    cv2.imwrite("/home/pi/toobright-%d.png" %
                                time.time(), picture)

                    continue

                if oldPicture is not None:
                    movement = yield from self._calcMovement(oldPicture, picture)

                    if len(diffValues) < maxVals:
                        diffValues.append(movement)
                    else:
                        diffValues[counter % maxVals] = movement

                    avg = sum(diffValues) / float(len(diffValues))
                    stddev = math.sqrt(
                        sum(map(lambda x: pow(abs(x - avg), 2), diffValues)) / float(len(diffValues)))

                    moved = False
                    if abs(movement - avg) > 2 * stddev:
                        self.log.info("seems to have moved, take picture")
                        moved = True

                        self._interval = 5
                    else:
                        self._interval = 20

                    yield from self._bp.broadcast({
                        "action": 'movement',
                        'value': movement,
                        'moved': moved,
                    })

                oldPicture = picture

                # make a copy so we can modify it and save it
                self.lastPicture = picture.copy()
                self.lastPictureTimestamp = int(round(time.time(), 0))

                # # annotate with date and time
                # cv2.putText(self.lastPicture, datetime.now().strftime("%c"),
                #             (3, 237),
                #             cv2.FONT_HERSHEY_SIMPLEX,
                #             0.3,
                #             (255, 255, 255),
                #             lineType=cv2.LINE_AA)
                #
                # # save it to disk
                # cv2.imwrite("/home/pi/%d.png" % time.time(), self.lastPicture)

            except Exception as e:
                self.log.info("Error taking picture: %s. Trying next time", e)
            finally:
                self._takingPicture = False

    def _imageBrightness(self, img):
        hist = cv2.calcHist([img], [0], None, [10], [0, 256])
        if float(hist[0]) / float(img.size) > 0.99:
            return -1

        if float(hist[-1]) / float(img.size) > 0.99:
            return 1

        return 0

    def isTakingPicture(self):
        return self._takingPicture
