#!/usr/bin/env python

import json
import logging
import signal
import subprocess

import asyncio
import websockets
import motiondetect

logging.basicConfig(format='%(asctime)s %(message)s', level=logging.DEBUG)


nightMode = False

motionDetect = motiondetect.MotionDetect()


class Connection(object):

    conns = set()

    def __init__(self, websocket):
        self._ws = websocket
        self.streamRequested = False

        logging.info("Client connected")
        Connection.conns.add(self)

        self._heartbeat = asyncio.ensure_future(self.heartbeat())

    async def heartbeat(self):
        logging.info("Starting heartbeating to client")
        while True:
            try:
                await asyncio.sleep(1)
                await self._send({'action': 'heartbeat'})
            except Exception as e:
                logging.error("Error heartbeating: %s", e)
                self.disconnect()

    async def _send(self, obj):
        await self._ws.send(json.dumps(obj))

    def handleMessage(self, message):
        msg = json.loads(message)

        if 'action' not in msg:
            raise InvalidMessageException("action not in message")

        if msg['action'] == 'shutdown':
            shutdown()
        elif msg['action'] == 'startstream':
            self.streamRequested = True
        elif msg['action'] == 'stopstream':
            self.streamRequested = False

    def disconnect(self):
        try:
            self._heartbeat.cancel()
            # idempotent
            self._ws.close()
        finally:
            # remove from set of cnnections
            Connection.conns.remove(self)

    def __str__(self):
        return "Connection {ws.host}".format(ws=self._ws)

    @classmethod
    async def broadcast(cls, obj):
        for con in cls.conns:
            await con._send(obj)

    @classmethod
    async def isAnyoneStreaming(cls):
        return any([con.streamRequested for con in cls.conns])

def shutdown(conn):
    logging.info("Shutting down machine as requested by %s", str(conn))
    Connection.broadcast({
        "action": "status_update",
        "status": "shutting-down"
    })
    subprocess.check_call(['sudo', 'shutdown', '-h', '0'])


class InvalidMessageException(Exception):
    pass


async def connect(websocket, path):
    conn = Connection(websocket)
    try:
        if websocket.closed:
            return
        async for message in websocket:
            try:
                conn.handleMessage(message)
            except Exception as e:
                logging.Error("Error handling message: %s", e)
    finally:
        conn.disconnect()

loop = asyncio.get_event_loop()


def signalStop():
    logging.info("stopping by signal")
    loop.stop()


loop.add_signal_handler(signal.SIGINT, signalStop)
loop.run_until_complete(websockets.serve(connect, '0.0.0.0', 8765))
loop.run_forever()
