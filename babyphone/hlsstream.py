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
    while True:
        read = await proc.stderr.readline()
        if read:
            print("Error: %s"%read)
        if proc.returncode is not None:
            break

loop = asyncio.get_event_loop()

cam = None

class Writer(object):

    def __init__(self):
        self.writers = set()

        self.totalLen = 0

    async def add(self, output):
        self.writers.add(output)
        print("adding output to writer")
        if len(self.writers) == 1:
            print("First output added, start recording")
            cam.start_recording(self, format='h264', profile='main', quality=23)

    async def remove(self, output):
        print("removing output from writer")
        self.writers.remove(output)
        if len(self.writers) == 0:
            print("last output removed, stop recording")
            cam.stop_recording()

    async def writeToWriter(self, writer, data):
        writer.write(data)
        await writer.drain()

    def write(self, data):
        # print("wrote %d bytes" % len(data))
        for w in self.writers:
            loop.run_until_complete(self.writeToWriter(w, data))

        self.totalLen += len(data)


writer = Writer()

@app.before_serving
async def init():
    global cam
    cam = picamera.PiCamera(resolution=(320, 240), framerate=10)
    await asyncio.sleep(1.0)

@app.after_serving
async def deinit():
    if cam:
        cam.close()

@app.route('/stream.ts')
async def stream():
    async def async_generator():
        try:
            rawcmd='ffmpeg -i - -c:v copy -f mpegts -movflags +faststart -avioflags direct -'
            cmd = shlex.split(rawcmd)
            print("executing")
            print(cmd)
            # proc = sp.Popen(cmd, stdout=sp.PIPE, stderr=sp.PIPE)
            print("starting proc")
            proc = await asyncio.create_subprocess_shell(
                rawcmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE)
            errTask = asyncio.create_task(readError(proc))
            bytesSum = 0
            reported = 0
            i=0
            start = time.time()
            print("creating task for the cam")
            asyncio.create_task(writer.add(proc.stdin))
            print("done creating task for cam")
            while True:
                i+=1
                data = await proc.stdout.read(1000)
                if data:
                    yield data

                    bytesSum += len(data)
                    if True: #i % 10 == 0:
                        print("bytes output: %d (%.2f/s)" % (bytesSum, float(bytesSum-reported)/float(time.time()-start)))
                        reported = bytesSum
                        start = time.time()
                # terminated
                if proc.returncode is not None:
                    break

            print("done streaming")
        except Exception as e:
            print("generator has exception", e)

        finally:
            print("cancelling cam task")
            await writer.remove(proc.stdin)
            errTask.cancel()
            streaming=False
            proc.kill()

    return async_generator(), 200, {
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    'Pragma': 'no-cache',
    'Content-Type':'video/MP2T',
    'Connection':'close',
    'Expires': '0'
    }

@app.route("/")
async def idx():
    return await render_template("template.html.j2")
