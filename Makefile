
# CAMHOST=pi@babyphone.fritz.box
# for safety reasons, rename the host so we don't accidentally deploy to the babyhpone
CAMHOST=invalid

.PHONY: client


test1:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  test1.c -o test1 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./test1

test2:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  test2.c -o test2 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./test2

remote:
	ssh ${CAMHOST} 'mkdir -p /home/pi/babyphone'
	scp babyphone/*.* ${CAMHOST}:/home/pi/babyphone/
	ssh ${CAMHOST} 'cd /home/pi/babyphone && gcc -D PI \
		`pkg-config --cflags glib-2.0 gstreamer-1.0 \
		gstreamer-rtsp-server-1.0 gstreamer-rtsp-1.0 gstreamer-net-1.0 gstreamer-base-1.0` \
		videoserver.c -o videoserver \
		`pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0 gstreamer-rtsp-1.0 gstreamer-net-1.0 gstreamer-base-1.0` -lm'
#	ssh ${CAMHOST} 'PATH=/usr/local/lib/nodejs/node-v10.1.0/bin:$$PATH pm2 restart index'

test-remote:
	ssh ${CAMHOST} 'mkdir -p /tmp/babyphone'
	scp server/*.* ${CAMHOST}:/tmp/babyphone/
	ssh ${CAMHOST} 'cd /tmp/babyphone && gcc -D PI \
		`pkg-config --cflags glib-2.0 gstreamer-1.0 \
		gstreamer-rtsp-server-1.0 gstreamer-rtsp-1.0 gstreamer-net-1.0 gstreamer-base-1.0` \
		videoserver.c -o videoserver \
		`pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0 gstreamer-rtsp-1.0 gstreamer-net-1.0 gstreamer-base-1.0` -lm'


install-remote:
	ssh ${CAMHOST} 'mkdir -p /home/pi/pythonbabyphone'
	rsync -av . --exclude app --exclude-from .gitignore --exclude .git ${CAMHOST}:/home/pi/pythonbabyphone/
	ssh ${CAMHOST} 'cd /home/pi/pythonbabyphone && sudo python3 setup.py install'
	ssh ${CAMHOST} 'cd /home/pi/pythonbabyphone && make autostart'


DEVHOST=pi@devpi.fritz.box
install-devpi:
	ssh ${DEVHOST} 'mkdir -p /home/pi/babyphone'
	rsync -av . --exclude app --exclude-from .gitignore --exclude .git ${DEVHOST}:/home/pi/babyphone/
	ssh ${DEVHOST} 'cd /home/pi/babyphone && sudo python3 setup.py install'
	ssh ${DEVHOST} 'sudo systemctl restart babyphone'

systemd-install:
	sudo cp systemd/* /etc/systemd/system/
	sudo systemctl daemon-reload

# install all system dependencies on the pie
prepare-system:
	sudo apt-get install python3-opencv -y

autostart:
	sudo cp systemd/* /etc/systemd/system/
	sudo systemctl daemon-reload
	sudo systemctl restart babyphone


test-audio:
	GST_DEBUG=WARN gst-launch-1.0 -m playbin latency=500000000 uri=rtsp://${CAMHOST}:8554/audio

test-audiovideo:
	GST_DEBUG=WARN gst-launch-1.0 -m playbin latency=500000000 uri=rtsp://${CAMHOST}:8554/audiovideo


mictest:
	gst-launch-1.0 pulsesrc ! audiochebband mode=band-pass lower-frequency=100 upper-frequency=6000 poles=4 ! audioconvert  ! queue min-threshold-time=1000000000 ! autoaudiosink


# host used in Meadow network
MHOST=pi@192.168.1.83
deploy-babyphone2:
	ssh ${MHOST} 'mkdir -p /home/pi/babyphone2'
	rsync -av . --exclude app --exclude-from .gitignore --exclude babyphone --exclude .git ${MHOST}:/home/pi/babyphone2/
	rsync -av ./babyphone --exclude app --exclude-from .gitignore --exclude .git ${MHOST}:/home/pi/babyphone2/babyphone2
	ssh ${MHOST} 'cd /home/pi/babyphone2 && sudo python3 setup.py install'



# test to transmit audio:
# server:
# GST_DEBUG=WARN gst-launch-1.0 pulsesrc ! audio/x-raw,rate=44100 ! audioconvert ! queue min-threshold-time=1000000000 ! avenc_aac ! rtpmp4apay ! udpsink host=192.168.178.56 port=5001
# client:
# GST_DEBUG=WARN gst-launch-1.0 udpsrc port=5001 caps="application/x-rtp,media=(string)audio,clock-rate=44100,config=40002410adca00" ! rtpmp4adepay !  avdec_aac ! audioconvert ! queue ! alsasink


# server
# gst-launch-1.0 pulsesrc device=alsa_input.usb-C-Media_Electronics_Inc._USB_PnP_Sound_Device-00.analog-mono ! alawenc ! rtppcmapay ! udpsink host=192.168.178.56 port=5001
# client
# gst-launch-1.0 udpsrc port=5001 caps="application/x-rtp,media=audio,channels=1,encoding-name=(string)PCMA,payload=(int)96,clock-rate=(int)8000" ! rtppcmadepay ! audio/x-alaw, rate=8000, channels=1 ! alawdec ! alsasink

# server
# gst-launch-1.0 pulsesrc device=alsa_input.usb-C-Media_Electronics_Inc._USB_PnP_Sound_Device-00.analog-mono ! audio/x-raw,rate=16000,channels=1 ! avenc_g722 ! rtpg722pay ! udpsink host=192.168.178.56 port=5001
# client
# gst-launch-1.0 udpsrc port=5001 caps="application/x-rtp,media=audio,channels=1,encoding-name=(string)G722,payload=(int)9,clock-rate=(int)8000" ! rtpg722depay ! audio/G722, rate=8000, channels=1 ! avdec_g722 ! alsasink


cclient:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0 gstreamer-net-1.0` \
		client.c -o client \
	 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0 gstreamer-net-1.0` -lm
	GST_DEBUG=WARN ./client

# Packages to install
sys-setup:
	sudo apt-get install dstat git
	sudo apt-get install gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
	gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-pulseaudio \
	gstreamer1.0-rtsp gstreamer1.0-tools pulseaudio

	sudo apt-get install libgstreamer-plugins-bad1.0-dev libgstreamer-plugins-base1.0-dev \
	libgstreamer1.0-dev libgstrtspserver-1.0-dev gstreamer1.0-libav

	sudo apt-get install python3-dev libatlas-base-dev python3-picamera python3-skimage libjasper-dev

## To find the name of the audio device
list-sources:
	pacmd list-sources

# taken and adopted from
# https://gist.github.com/ajfisher/a84889e64565d7a74888
hotspot-setup:
	apt-get install hostapd wpasupplicant dnsmasq

# 2018-12-09
# client just dies and does not play anything.
GST_DEBUG=INFO gst-launch-1.0 pulsesrc device=alsa_input.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-mono provide-clock=true do-timestamp=true latency-time=10000 ! avenc_g722 ! rtpg722pay ! udpsink host=192.168.178.60 port=5001
GST_DEBUG=INFO gst-launch-1.0 udpsrc caps="application/x-rtp,clock-rate=(int)8000" port=5001 ! rtpg722depay ! audio/G722, rate=8000, channels=1 ! avdec_g722 ! alsasink


decodeserver:
	rsync -av server/decodebin.c $(CAMHOST):/tmp/decodebin.c
	ssh $(CAMHOST) bash -c 'cd /tmp/ && gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-net-1.0` -o decodeserver /tmp/decodebin.c `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0 gstreamer-net-1.0` -lm'


deploy-hls:
	rsync -av babyphone/hlsstream.py pi@devpi.fritz.box:
