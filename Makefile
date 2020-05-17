BP_HOST=

install-devpi:
	$(MAKE) BP_HOST=pi@devpi.fritz.box remote-install

install-bp:
	$(MAKE) BP_HOST=pi@babyphone.fritz.box remote-install

remote-install:
	ssh ${BP_HOST} 'mkdir -p /home/pi/babyphone'
	rsync -av . --exclude .history --exclude app --exclude-from .gitignore --exclude .git ${BP_HOST}:/home/pi/babyphone/
	ssh ${BP_HOST} 'cd /home/pi/babyphone && sudo python3 setup.py install'
	ssh ${BP_HOST} 'sudo systemctl restart babyphone'

systemd-install:
	ssh ${BP_HOST} 'cd /home/pi/babyphone && sudo cp systemd/* /etc/systemd/system/'
	ssh ${BP_HOST} 'sudo systemctl daemon-reload'
	ssh ${BP_HOST} 'sudo systemctl enable babyphone'
	# to disable autostart, run `sudo systemctl disable babyphone`s

# install all system dependencies on the pie
prepare-system:
	sudo apt-get install python3-opencv -y

# Packages to install
sys-setup:
	sudo apt-get install dstat git
	sudo apt-get install libblas-dev liblapack-dev libatlas-base-dev gfortran

	sudo apt-get install python3-dev libatlas-base-dev python3-picamera python3-skimage libjasper-dev

# taken and adopted from
# https://gist.github.com/ajfisher/a84889e64565d7a74888
hotspot-setup:
	apt-get install hostapd wpasupplicant dnsmasq


push2babyphone-dev:
	ssh pi@babyphone.fritz.box 'mkdir -p /tmp/babyphone-dev'
	rsync -av . --exclude app --exclude-from .gitignore --exclude .git pi@babyphone.fritz.box:/tmp/babyphone-dev
	ssh pi@babyphone.fritz.box 'cd /tmp/babyphone-dev && sudo python3 setup.py install && sudo systemctl restart babyphone'


# install the i2sstuff
# https://github.com/opencardev/snd-i2s_rpi/blob/master/README.md
