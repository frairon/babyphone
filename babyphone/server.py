#!/usr/bin/env python

import logging
import signal
import argparse
import asyncio
import babyphone
import websockets
import datetime

loop = asyncio.get_event_loop()


def signalStop():
    logging.info("stopping by signal")
    loop.stop()


async def writeStats():
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
        await asyncio.sleep(10)


if __name__ == '__main__':
    logging.basicConfig(format="%(asctime)s [%(levelname)-5.5s]  %(message)s",
                        level=logging.DEBUG)

    parser = argparse.ArgumentParser("Babyphone")
    parser.add_argument("--write-stats", dest="writeStats",
                        action="store_true", help="Enable writing stats to separate file for performance debugging. Requires psutil package")

    bp = babyphone.Babyphone()
    asyncio.ensure_future(writeStats())
    loop.add_signal_handler(signal.SIGINT, signalStop)
    loop.run_until_complete(websockets.serve(bp.connect, '0.0.0.0', 8765))
    loop.run_forever()
