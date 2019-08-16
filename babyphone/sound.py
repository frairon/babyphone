import alsaaudio
import sys
import audioop
import numpy as np
inp = alsaaudio.PCM(alsaaudio.PCM_CAPTURE, alsaaudio.PCM_NORMAL, device='plughw:CARD=Device')
inp.setchannels(1)
inp.setrate(8000)
inp.setformat(alsaaudio.PCM_FORMAT_S16_LE)
inp.setperiodsize(160)

import math

# from _faac_cffi import ffi, lib

# inputSamples = ffi.new('unsigned long*')
# maxBuffers = ffi.new('unsigned long*')
# encoder = lib.faacEncOpen(8000, 2, inputSamples, maxBuffers)
# cfg = lib.faacEncGetCurrentConfiguration(encoder)
# print("max buffers: %d"%maxBuffers[0])
# cfg.aacObjectType = 2 # LOW
# # cfg.inputFormat = FAAC_INPUT_FLOAT;
# cfg.mpegVersion = 0 # mpeg-4
# cfg.outputFormat = 0 # not adts
# cfg.allowMidside = 1
# cfg.shortctl = 0 # SHORTCTL_NORMAL
# cfg.useTns = 0 # use temporal noise shaping
# cfg.bitRate = 64000

# ok = lib.faacEncSetConfiguration(encoder, cfg)
# if ok == 0:
#     raise Exception("setting config failed")


# buf = ffi.new("char[%d]"%maxBuffers[0])
try:
    i = 1
    a = (1<<16)-1
    with open("audio.raw", "wb") as out:
        while True:
            l, data = inp.read()
            # npbuf = np.frombuffer(data, dtype=np.dtype('<i4'))

            # print(l, len(data), len(npbuf))

            if l:
                data = audioop.mul(data, 2, 4)
                avg = audioop.rms(data, 2)
                p = False
                # avg = math.sqrt(avg)
                if i == 0 or avg < i:
                    p = True
                    i = avg

                if a == 0 or avg > a:
                    p = True
                    a = avg

                if p:
                    print("min=%d, max=%d" % (i,a))

                if a and i and a != i:
                    level = float(avg - i) / float(a-i)
                    print("."*int(level*100))



                # val = ffi.cast("int32_t*", npbuf.ctypes.data)
                # numBufs = lib.faacEncEncode(encoder, val, 160, buf, maxBuffers[0])

                # int FAACAPI faacEncEncode(faacEncHandle hEncoder, int32_t * inputBuffer, unsigned int samplesInput,
        		# 	 unsigned char *outputBuffer,
        		# 	 unsigned int bufferSize);
                # used = buf[0:numBufs]
                # print(len(used))
                # b = np.frombuffer(ffi.buffer(buf, numBufs), dtype=np.ubyte)

                # print(numBufs, len(b))
                out.write(data)

finally:
    pass
    # lib.faacEncClose(encoder)
