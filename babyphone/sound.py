import alsaaudio
import sys
import audioop
import numpy as np
inp = alsaaudio.PCM(alsaaudio.PCM_CAPTURE, alsaaudio.PCM_NORMAL)
inp.setchannels(2)
inp.setrate(48000)
inp.setformat(alsaaudio.PCM_FORMAT_S32_LE)
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
def process(out, alawout):
    state = None
    while True:
        l, data = inp.read()

        if l:
            data = audioop.tomono(data, 4, 1, 1)
            # data = audioop.mul(data, 4, 4)
            (data, state) = audioop.ratecv(data, 4, 1, 48000, 8000, state)
            avg = audioop.rms(data, 4)

            alaw = audioop.lin2alaw(data, 4)

            if a and i and a != i:
                level = float(avg - i) / float(a-i)
                print("."*int(level*100))

            out.write(data)
            alawout.write(alaw)

try:
    i = 1
    a = (1<<32)-1
    with open("audio.alaw", "wb") as alawout:
        with open("audio.raw", "wb") as out:
            process(out, alawout)


finally:
    pass
    # lib.faacEncClose(encoder)
