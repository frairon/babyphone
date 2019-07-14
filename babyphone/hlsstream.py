import asyncio
import io
import shlex
import subprocess as sp
import time
from datetime import datetime

import picamera
from quart import Quart, render_template

app = Quart(__name__)

@app.cli.command('run')
def run():
    app.run(host="0.0.0.0", port=5000)

async def readError(proc):
    print("starting error reader")
    while True:
        read = await proc.stderr.readline()
        if read:
            print("Error: %s"%read)
        if proc.returncode is not None:
            break

cam = None

class iterator(object):
    def __init__(self):
        self.q = asyncio.Queue(maxsize=1000, loop=asyncio.get_event_loop())

    async def generator(self):
        while self.q is not None:
            g = await self.q.get()
            self.q.task_done()
            yield g

    async def put(self, batch):
        self.q.put_nowait(batch)

    def close(self):
        self.q = None

class Writer(object):

    def __init__(self):
        self.cam = picamera.PiCamera(resolution=(320, 240), framerate=10)
        self.writers = set()
        self.proc = None
        self.errTask = None
        self.reader=None

        self.totalLen = 0

    async def start(self):
        await self.start_converter()
        self.reader = asyncio.create_task(self.start_reader())

    async def start_converter(self):
        rawcmd='ffmpeg -f h264 -framerate 10 -probesize 32 -i - -c:v copy -f mpegts -fflags flush_packets -movflags +faststart -'
        cmd = shlex.split(rawcmd)
        print("executing")
        print(cmd)
        print("starting proc")
        self.proc = await asyncio.create_subprocess_shell(
            rawcmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE)
        self.errTask = asyncio.create_task(readError(self.proc))

    async def start_reader(self):

        bytesSum = 0
        reported = 0
        i=0
        start = time.time()

        print("starting reader")
        while True:
            i+=1
            data = await self.proc.stdout.read(1000)
            if data:
                for w in self.writers:
                    await w.put(data)

                print("bytes output: %d (%.2f/s)" % (bytesSum, float(bytesSum-reported)/float(time.time()-start)))
                reported = bytesSum
                start = time.time()

                bytesSum += len(data)

    async def close(self):
        if self.errTask:
            print("stopping err-reader-task")
            self.errTask.cancel()

        if self.proc:
            print("killing ffmpeg")
            self.proc.kill()

        if self.cam:
            print("closing cam")
            self.cam.close()

        if self.reader:
            print("closing reader")
            self.reader.cancel()

    async def newGenerator(self):
        print("creating new generator")
        it = iterator()
        self.writers.add(it)
        print("adding output to writer")

        if len(self.writers) == 1:
            await self.start_recording()

        return it

    async def start_recording(self):
        print("First output added, start recording")
        self.cam.start_recording(self, format='h264', profile='main', quality=23)

    async def remove(self, output):
        print("removing output from writer")
        self.writers.remove(output)
        if len(self.writers) == 0:
            print("last output removed, stop recording")
            cam.stop_recording()


    def write(self, data):
        self.proc.stdin.write(data)
        self.totalLen += len(data)


writer = None

@app.before_serving
async def init():
    global writer
    writer = Writer()
    await asyncio.sleep(1.0)
    await writer.start()

@app.after_serving
async def deinit():
    if cam:
        cam.close()
    if writer:
        await writer.close()

@app.route('/stream.ts')
async def stream():
    gen = await writer.newGenerator()
    return gen.generator(), 200, {
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    'Pragma': 'no-cache',
    'Content-Type':'video/MP2T',
    'Connection':'close',
    'Expires': '0'
    }

@app.route("/")
async def idx():
    return await render_template("template.html.j2")
