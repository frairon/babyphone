


client:
	GST_DEBUG=WARN gst-launch-1.0 playbin uri=rtsp://127.0.0.1:8554/test


test1:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  test1.c -o test1 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./test1

vidserver:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  videoserver.c -o videoserver `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./videoserver


test2:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  test2.c -o test2 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./test2


remote:
	scp videoserver.c camhost:/tmp/videoserver.c
	ssh camhost 'cd /tmp && gcc -D PI `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  videoserver.c -o videoserver `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm'
	ssh camhost 'GST_DEBUG=WARN /tmp/videoserver & read; kill $$!'


clientremote:
	GST_DEBUG=WARN gst-launch-1.0 playbin latency=100 uri=rtsp://192.168.178.53:8554/test


mictest:
	gst-launch-1.0 pulsesrc ! audiochebband mode=band-pass lower-frequency=100 upper-frequency=6000 poles=4 ! audioconvert  ! queue min-threshold-time=1000000000 ! autoaudiosink



# test to transmit audio:
# server:
# GST_DEBUG=WARN gst-launch-1.0 pulsesrc ! audio/x-raw,rate=44100 ! audioconvert ! queue min-threshold-time=1000000000 ! avenc_aac ! rtpmp4apay ! udpsink host=192.168.178.56 port=5001
# client:
# GST_DEBUG=WARN gst-launch-1.0 udpsrc port=5001 caps="application/x-rtp,media=(string)audio,clock-rate=44100,config=40002410adca00" ! rtpmp4adepay !  avdec_aac ! audioconvert ! queue ! alsasink
