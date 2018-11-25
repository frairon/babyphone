package server

import (
	"fmt"
	"log"
	"time"

	gst "github.com/frairon/go-gstreamer"
	multierror "github.com/hashicorp/go-multierror"
)

const (
	volumeMsgInterval = 1 * time.Second
)

func init() {
	gst.Init(nil)
}

func VolumeStart() error {
	var (
		multErrs *multierror.Error
	)

	createElement := func(factory string) *gst.Element {
		elem, err := gst.ElementFactoryMake(factory, factory)
		if err != nil {
			multErrs = multierror.Append(multErrs, fmt.Errorf("error creating element from factory %s: %v", factory, err))
			return nil
		}
		return elem
	}

	setProp := func(element *gst.Element, name string, value interface{}) {
		if err := element.Set(name, value); err != nil {
			multErrs = multierror.Append(multErrs, fmt.Errorf("Error setting value %s->%v in element %s", name, value, element.GetName()))
		}
	}

	p, err := gst.PipelineNew("volume")
	_ = p
	if err != nil {
		multErrs = multierror.Append(multErrs, fmt.Errorf("error creating volume pipeline: %v", err))
	}
	pulsesrc := createElement("pulsesrc")
	audioconvert := createElement("audioconvert")
	level := createElement("level")
	volume := createElement("volume")
	fakesink := createElement("fakesink")

	if err := multErrs.ErrorOrNil(); err != nil {
		return err
	}

	if err := p.Bin.AddMany(pulsesrc, audioconvert, level, volume, fakesink); err != nil {
		return err
	}

	if err := pulsesrc.LinkMany(audioconvert, level, volume, fakesink); err != nil {
		return err
	}
	setProp(pulsesrc, "device", "alsa_input.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-mono")
	setProp(pulsesrc, "volume", 10.0)
	setProp(level, "post-messages", true)
	setProp(level, "interval", int64(volumeMsgInterval))
	setProp(volume, "volume", 0.99)
	setProp(fakesink, "sync", true)

	bus, err := p.GetBus()
	if err != nil {
		return fmt.Errorf("Error getting bus from pipeline: %v", err)
	}
	bus.AddMessageCallback(func(msg *gst.Message) {
		log.Printf("Message. %+v", msg.GetTypeName())
	})
	log.Printf("Starting pipeline")
	p.SetState(gst.STATE_PLAYING)
	return nil
}
