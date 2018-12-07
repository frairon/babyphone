
CAMHOST=pi@babyphone.fritz.box

.PHONY: client


test1:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  test1.c -o test1 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./test1

test2:
	gcc `pkg-config --cflags glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0`  test2.c -o test2 `pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0` -lm
	./test2

remote:
	ssh ${CAMHOST} 'mkdir -p /home/pi/babyphone'
	scp server/*.* ${CAMHOST}:/home/pi/babyphone/
	ssh ${CAMHOST} 'cd /home/pi/babyphone && gcc -D PI \
		`pkg-config --cflags glib-2.0 gstreamer-1.0 \
		gstreamer-rtsp-server-1.0 gstreamer-net-1.0` \
		videoserver.c -o videoserver \
		`pkg-config --libs glib-2.0 gstreamer-1.0 gstreamer-rtsp-server-1.0 gstreamer-net-1.0` -lm'
	ssh ${CAMHOST} 'PATH=/usr/local/lib/nodejs/node-v10.1.0/bin:$$PATH pm2 restart index'

remote-logs:
	ssh ${CAMHOST} 'pm2 logs index --lines=100'


# setting up the index with pm2:
# go to babyphone folder
# pm2 start index.js
# pm2 startup
# --> execute the script being printed


test-audio:
	GST_DEBUG=WARN gst-launch-1.0 -m playbin latency=500000000 uri=rtsp://${CAMHOST}:8554/audio

test-audiovideo:
	GST_DEBUG=WARN gst-launch-1.0 -m playbin latency=500000000 uri=rtsp://${CAMHOST}:8554/audiovideo


mictest:
	gst-launch-1.0 pulsesrc ! audiochebband mode=band-pass lower-frequency=100 upper-frequency=6000 poles=4 ! audioconvert  ! queue min-threshold-time=1000000000 ! autoaudiosink



# test to transmit audio:
# server:
# GST_DEBUG=WARN gst-launch-1.0 pulsesrc ! audio/x-raw,rate=44100 ! audioconvert ! queue min-threshold-time=1000000000 ! avenc_aac ! rtpmp4apay ! udpsink host=192.168.178.60 port=5001
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


# list devices:
# pacmd list-sources
# then use "device.id

# Packages to install
sys-setup:
	sudo apt-get install dstat git
	sudo apt-get install gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
	gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-pulseaudio \
	gstreamer1.0-rtsp gstreamer1.0-tools pulseaudio

	sudo apt-get install libgstreamer-plugins-bad1.0-dev libgstreamer-plugins-base1.0-dev \
	libgstreamer1.0-dev libgstrtspserver-1.0-dev gstreamer1.0-libav

setup-server:
	curl -sL https://deb.nodesource.com/setup_8.x | sudo -E bash -
	sudo apt-get install -y nodejs
	sudo npm install -g pm2

	# on raspi-zero:
	# wget https://nodejs.org/dist/v10.1.0/node-v10.1.0-linux-armv6l.tar.xz
	# sudo mkdir /usr/local/lib/nodejs
  # sudo tar -xJvf node-v10.1.0-linux-armv6l.tar.xz -C /usr/local/lib/nodejs
  # sudo mv /usr/local/lib/nodejs/node-$VERSION-$DISTRO /usr/local/lib/nodejs/node-v10.1.0
	# add to .profile:
	# export NODEJS_HOME=/usr/local/lib/nodejs/node-v10.1.0/bin
	#export PATH=$NODEJS_HOME:$PATH

	# install and configure logrotate
	pm2 install pm2-logrotate
	# rotate every hour
	pm2 set pm2-logrotate:rotateInterval '0 */1 * * *'
	# keep the last 10 logs
	pm2 set pm2-logrotate:retain 10


## To find the name of the audio device



goserver:
	go build -o build/babyphone github.com/frairon/babyphone/cmd/babyphone

docker:
	docker build -t rpi-crosscompiler .

RPIDIR := $(HOME)/projects/raspberrypi
RPITOOL_BIN := $(RPIDIR)/tools/arm-bcm2708/arm-rpi-4.9.3-linux-gnueabihf/bin
RPITOOL_SYSROOT := $(RPIDIR)/tools/arm-bcm2708/arm-rpi-4.9.3-linux-gnueabihf/arm-linux-gnueabihf/sysroot
get-libs:
	mkdir -p $(HOME)/projects/raspberrypi/babyphone/usr
	rsync -a --info=progress2 --exclude="lib/cups/**" pi@babyphone.fritz.box:/usr $(HOME)/projects/raspberrypi/babyphone
	rsync -a --info=progress2 pi@babyphone.fritz.box:/lib $(HOME)/projects/raspberrypi/babyphone/lib

install-go:
		GOOS=linux \
		GOARCH=arm \
		GOARM=6 \
		go build -x -v -o build/rpi/babyphone github.com/frairon/babyphone/cmd/babyphone


docker-go:
	docker run -it \
		-v $(HOME)/projects/raspberrypi/babyphone/usr/lib/arm-linux-gnueabihf/:/usr/lib/arm-linux-gnueabihf \
		-v $(HOME)/projects/raspberrypi/babyphone/usr/include/arm-linux-gnueabihf/:/usr/include/arm-linux-gnueabihf \
		-v $(GOPATH)/src:/home/rpi/tools/src \
		-v $(HOME)/projects/raspberrypi/babyphone/lib/arm-linux-gnueabihf/:/lib/arm-linux-gnueabihf/ \
		--rm rpi-crosscompiler \
		bash -c 'cd /home/rpi/tools/src/github.com/frairon/babyphone/ && make build-in-docker'

build-in-docker:
	CC=/home/rpi/tools/tools-master/arm-bcm2708/arm-rpi-4.9.3-linux-gnueabihf/bin/arm-linux-gnueabihf-gcc \
		CGO_ENABLED=1 \
		GOOS=linux \
		GOARCH=arm \
		CGO_CFLAGS="-I/usr/include/arm-linux-gnueabihf" \
		CGO_LDFLAGS="-L/usr/lib/arm-linux-gnueabihf -L/lib/arm-linux-gnueabihf" \
		GOARM=7 \
		go build -v -o build/rpi/babyphone github.com/frairon/babyphone/cmd/babyphone || bash
