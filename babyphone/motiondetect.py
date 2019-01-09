import logging

import asyncio


class MotionDetect(object):

    def __init__(self, babyphone):
        self._interval = 10
        self._takingPicture = False
        self._runner = None
        self._bp = babyphone

    def start(self):
        self._runner = asyncio.ensure_future(self._run())

    def stop(self):
        if not self._runner:
            return

        self._runner.cancel()
        self._runner = None

    @asyncio.coroutine
    def _takePicture(self):

        try:
            import picamera
            cam = picamera.Camera()
            # simulate to do something with the camera
            yield from asyncio.sleep(1)
        except Exception as e:
            logging.info("Error initializing camera: %s", e)
        finally:
            if cam:
                cam.close()

    @asyncio.coroutine
    def comparePictures(self):
        logging.info("comparing pictures...")
        pass

    @asyncio.coroutine
    def _run(self):
        while True:
            yield from asyncio.sleep(self._interval)

            anyoneStreaming = yield from self._bp.isAnyoneStreaming()
            if anyoneStreaming:
                logging.info(
                    "at least one connection is streaming, camera is busy, cannot do motion detection")
                continue

            try:
                self._takingPicture = True
                yield from self._takePicture()
            finally:
                self._takingPicture = False

            self._comparePictures()

    def isTakingPicture(self):
        return self._takingPicture
