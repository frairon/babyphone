


build:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` test-launch.c -o test-launch `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`
