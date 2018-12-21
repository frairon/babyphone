
# 2018-12-11
# new working
#server
/usr/bin/arecord -D mic -c1 -r 16000 -f S16_LE -t wav -V mono | GST_DEBUG=INFO gst-launch-1.0 -m fdsrc ! audioparse ! decodebin ! audioconvert ! queue ! audioresample ! avenc_g722 ! rtpg722pay ! rtpjitterbuffer ! udpsink host=192.168.178.60 port=5001
#client
GST_DEBUG=INFO gst-launch-1.0 udpsrc caps="application/x-rtp, media=(string)audio, encoding-name=(string)G722, payload=(int)9, encoding-params=(string)1, clock-rate=(int)16000" port=5001 ! rtpjitterbuffer ! rtpg722depay ! queue ! avdec_g722 ! autoaudiosink

# --> receiver has wrong bit rate so get's constant underflows and does the voice is too high
/usr/bin/arecord -D mic -c1 -r 16000 -f S16_LE -t wav -V mono | GST_DEBUG=INFO gst-launch-1.0 -m fdsrc ! audioparse ! decodebin ! audioconvert ! queue ! audioresample ! alawenc ! rtppcmapay ! rtpjitterbuffer ! udpsink host=192.168.178.60 port=5001

#client
GST_DEBUG=INFO gst-launch-1.0 udpsrc caps="application/x-rtp, media=(string)audio, encoding-name=(string)G722, payload=(int)8, clock-rate=(int)8000" port=5001 ! rtpjitterbuffer ! rtppcmadepay ! queue ! alawdec ! autoaudiosink


# 2018-12-12
# it's actually working:
/usr/bin/arecord -D mic -c1 -r 48000 -f S16_LE -t raw -V mono | GST_DEBUG=INFO gst-launch-1.0 -m fdsrc ! audioparse rate=48000 depth=16 endianness=little channels=1 ! audioresample ! "audio/x-raw,channels=(int)1,rate=(int)16000,layout=(string)interleaved,format=(string)S16LE" ! audioconvert noise-shaping=GST_AUDIO_NOISE_SHAPING_SIMPLE ! avenc_g722  ! rtpg722pay ! rtpjitterbuffer  ! "application/x-rtp, media=(string)audio, payload=(int)9, clock-rate=(int)8000, encoding-name=(string)G722,encoding-params=(string)1" ! rtpjitterbuffer ! udpsink host=192.168.178.60 port=5001

GST_DEBUG=INFO gst-launch-1.0 udpsrc caps="application/x-rtp, media=(string)audio, encoding-name=(string)G722, payload=(int)9,encoding-param=(string)1, clock-rate=(int)8000" port=5001 ! .recv_rtp_sink_0 rtpbin ! rtpjitterbuffer ! rtpg722depay ! avdec_g722 ! queue ! autoaudiosink


# 2018-12-17
GST_DEBUG=INFO gst-launch-1.0 pulsesrc device="alsa_input.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-mono" ! "audio/x-raw,rate=48000,format=(string)S16LE,layout=(string)interleaved,channels=1" ! audioparse rate=48000 depth=16 endianness=little channels=1 ! audioresample ! "audio/x-raw,channels=(int)1,rate=(int)16000,layout=(string)interleaved,format=(string)S16LE" ! audioconvert noise-shaping=GST_AUDIO_NOISE_SHAPING_SIMPLE ! avenc_g722  ! rtpg722pay ! rtpjitterbuffer  ! "application/x-rtp, media=(string)audio, payload=(int)9, clock-rate=(int)8000, encoding-name=(string)G722,encoding-params=(string)1" ! rtpjitterbuffer ! udpsink host=192.168.178.60 port=5001

GST_DEBUG=INFO gst-launch-1.0 udpsrc caps="application/x-rtp, media=(string)audio, encoding-name=(string)G722, payload=(int)9,encoding-param=(string)1, clock-rate=(int)8000" port=5001 ! .recv_rtp_sink_0 rtpbin ! rtpjitterbuffer ! rtpg722depay ! avdec_g722 ! queue ! autoaudiosink

# 2018-12-18
GST_DEBUG=INFO gst-launch-1.0 pulsesrc device="alsa_input.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-mono" ! "audio/x-raw,rate=48000,format=(string)S16LE,layout=(string)interleaved,channels=1" ! audioparse rate=48000 depth=16 endianness=little channels=1 ! audioresample ! "audio/x-raw,channels=(int)1,rate=(int)16000,layout=(string)interleaved,format=(string)S16LE" ! audioconvert noise-shaping=GST_AUDIO_NOISE_SHAPING_SIMPLE ! rtpL16pay  ! rtpjitterbuffer  ! "	application/x-rtp, media=(string)audio, payload=(int)96, clock-rate=(int)16000, encoding-name=(string)L16, channels=(int)1" ! rtpjitterbuffer ! udpsink host=192.168.178.60 port=5001

GST_DEBUG=INFO gst-launch-1.0 udpsrc caps="application/x-rtp, media=(string)audio, payload=(int)96, clock-rate=(int)16000, encoding-name=(string)L16, channels=(int)1" port=5001 ! .recv_rtp_sink_0 rtpbin ! rtpjitterbuffer ! rtpL16depay ! autoaudiosink
