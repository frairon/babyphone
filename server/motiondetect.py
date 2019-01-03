import asyncio
import logging
import server

class MotionDetect(object):

    def __init__(self):
        self._interval = 10
        self._camBlocked = False
        self._runner = None

    def start(self):
        self._runner = asyncio.ensure_future(self._run())

    def stop(self):
        if not self._runner:
            return

        self._runner.cancel()
        self._runner = None

    async def _takePicture(self):
        try:
            import picamera
            cam = picamera.Camera()
            # simulate to do something with the camera
            await asyncio.sleep(1)
        except Exception as e:
            logging.info("Error initializing camera: %s", e)
        finally:
            if cam:
                cam.close()

    async def comparePictures(self):
        logging.info("comparing pictures...")
        pass

    async def _run(self):
        while True:
            await asyncio.sleep(self._interval)

            if await server.Connection.isAnyoneStreaming():
                logging.info("at least one connection is streaming, camera is busy, cannot do motion detection")
                continue

            try:
                self._camBlocked = True
                await self._takePicture()
            finally:
                self._camBlocked = False

            self._comparePictures()
