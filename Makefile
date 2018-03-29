


build:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` test-launch.c -o test-launch `pkg-config --libs --static glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`

run: build
	GST_DEBUG=WARN ./test-launch "v4l2src ! video/x-raw,width=640,height=480 ! x264enc ! rtph264pay name=pay0 pt=96"

client:
	GST_DEBUG=WARN gst-launch-1.0 playbin latency=50 uri=rtsp://127.0.0.1:8554/test


test:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  test1.c -o test1 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./test1
