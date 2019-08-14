#!/usr/bin/env python

import argparse
import logging
import signal
import sys
from datetime import datetime

import asyncio
import websockets
from babyphone2 import babyphone, discovery

loop = asyncio.get_event_loop()


def signalStop():
    logging.info("stopping by signal")
    loop.stop()


@asyncio.coroutine
def writeStats():
    import psutil
    logFormatter = logging.Formatter(
        "%(asctime)s [%(levelname)-5.5s]  %(message)s")
    fileHandler = logging.FileHandler(
        datetime.now().strftime("babyphone-%Y-%m-%d-stats.log"))
    fileHandler.setFormatter(logFormatter)

    MB = 1024 * 1024
    statLogger = logging.Logger("stats", logging.INFO)
    statLogger.addHandler(fileHandler)

    while True:
        cpu = psutil.cpu_percent(interval=None)

        memory = psutil.virtual_memory()
        statLogger.info("CPU: %.2f Memory left: %sMb",
                        cpu, memory.available / MB)
        yield from asyncio.sleep(10)


@asyncio.coroutine
def runWebserver(bp):
    from aiohttp import web

    logging.info("starting application server")

    def latest(request):
        return web.Response(
            body=bp.getLastPictureAsBytes(),
            content_type='image/png',
            headers={
                'picture-time': "%s" % bp.getLastPictureTimestamp(),
            },
        )

    app = web.Application()
    app.add_routes([web.get('/latest', latest)])

    runner = web.AppRunner(app)
    logging.info("setup runner")
    yield from runner.setup()
    site = web.TCPSite(runner, '0.0.0.0', 8081)
    logging.info("starting site")
    yield from site.start()

if __name__ == '__main__':
    parser = argparse.ArgumentParser("Babyphone")
    parser.add_argument("--write-stats", dest="writeStats",
                        action="store_true", help="Enable writing stats to separate file for performance debugging. Requires psutil package")
    args = parser.parse_args()
    babyphone.initLogger()
    try:
        bp = babyphone.Babyphone(loop)
        if args.writeStats:
            asyncio.ensure_future(writeStats())

        loop.add_signal_handler(signal.SIGINT, signalStop)
        logging.info("starting websockets server")
        discovery.createDiscoveryServer(loop)
        loop.run_until_complete(websockets.serve(bp.connect, '0.0.0.0', 8080))
        loop.run_until_complete(runWebserver(bp))
        loop.run_forever()
    finally:
        bp.close()
