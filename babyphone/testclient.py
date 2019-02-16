#!/usr/bin/env python

# WS client example

import asyncio
import websockets
import logging
import signal


@asyncio.coroutine
def runClient():
    websocket = yield from websockets.connect(
            'ws://localhost:8765')
    try:
        while True:
            msg = yield from websocket.recv()
            logging.info("message: %s", msg)
    finally:
        yield from websocket.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    loop = asyncio.get_event_loop()

    def signalStop():
        logging.info("stopping by signal")
        loop.stop()

    loop.add_signal_handler(signal.SIGINT, signalStop)

    loop.run_until_complete(runClient())
