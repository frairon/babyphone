# CAMHOST=pi@babyphone.fritz.box
# for safety reasons, rename the host so we don't accidentally deploy to the babyhpone
CAMHOST=invalid

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


# host used in Meadow network
MHOST=pi@babyphone
deploy-babyphone2:
	ssh ${MHOST} 'mkdir -p /home/pi/babyphone2'
	rsync -av . --exclude app --exclude-from .gitignore --exclude babyphone --exclude .git ${MHOST}:/home/pi/babyphone2/
	rsync -av ./babyphone/ --exclude-from .gitignore --exclude .git ${MHOST}:/home/pi/babyphone2/babyphone2
	ssh ${MHOST} 'cd /home/pi/babyphone2 && sudo python3 setup.py install'


# Packages to install
sys-setup:
	sudo apt-get install dstat git
	sudo apt-get install gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
	gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly gstreamer1.0-pulseaudio \
	gstreamer1.0-rtsp gstreamer1.0-tools pulseaudio
	sudo apt-get install libblas-dev liblapack-dev libatlas-base-dev gfortran

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
