package server

import (
	"fmt"
	"log"
	"math"
	"time"

	gst "github.com/frairon/go-gstreamer"
	multierror "github.com/hashicorp/go-multierror"
)

const (
	volumeMsgInterval = 500 * time.Millisecond
)

func init() {
	gst.Init(nil)
}

func VolumeStart(device string) error {
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

	if err = multErrs.ErrorOrNil(); err != nil {
		return err
	}

	if err = p.Bin.AddMany(pulsesrc, audioconvert, level, volume, fakesink); err != nil {
		return err
	}

	if err = pulsesrc.LinkMany(audioconvert, level, volume, fakesink); err != nil {
		return err
	}

	if device != "" {
		setProp(pulsesrc, "device", device)
	}
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
		switch msg.GetType() {
		case gst.MESSAGE_ERROR:
			err, dbg := msg.ParseError()
			log.Printf("Error is. %v, msg: %s", err, dbg)
		case gst.MESSAGE_ELEMENT:
			strct := msg.GetStructure()
			parseVolume(strct)
		case gst.MESSAGE_WARNING:
			err, dbg := msg.ParseWarning()
			log.Printf("volume gave a warning: %v, msg: %s", err, dbg)
		default:
			log.Printf("Message. %+v", msg.GetTypeName())
		}
	})
	log.Printf("Starting pipeline")
	p.SetState(gst.STATE_PLAYING)
	return nil
}

func parseVolume(strct *gst.Structure) {
	firstFloat := func(name string) (float64, error) {
		multi, err := strct.ArrayValue(name)
		if err != nil || len(multi) < 1 {
			return 0.0, fmt.Errorf("error getting array: %v", err)
		}
		value, is := multi[0].(float64)
		if !is {
			return 0.0, fmt.Errorf("value %s in %v is not float64 but %T", name, strct, multi[0])
		}
		return value, nil
	}

	rmsDb, err := firstFloat("rms")
	if err != nil {
		return
	}
	peakDb, err := firstFloat("peak")
	if err != nil {
		return
	}
	decayDb, err := firstFloat("decay")
	if err != nil {
		return
	}
	_ = peakDb
	_ = decayDb
	rms := math.Pow(10.0, rmsDb/10.0)
	if rms > 0.01 {
		log.Printf("volume: %.3f", rms)
	}
}
