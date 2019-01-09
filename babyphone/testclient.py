#!/usr/bin/env python

# WS client example

import asyncio
import websockets
import logging
import signal

logging.basicConfig(level=logging.DEBUG)

@asyncio.async
def runClient():
    websocket = yield from websockets.connect(
            'ws://localhost:8765'):
    try:
        while True:
            msg = await websocket.recv()
            logging.info("message: %s", msg)
    finally:
        yield from websocket.close()


loop = asyncio.get_event_loop()

def signalStop():
    logging.info("stopping by signal")
    loop.stop()

loop.add_signal_handler(signal.SIGINT, signalStop)

loop.run_until_complete(runClient())
