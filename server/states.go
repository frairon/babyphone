package server

import (
	"os/exec"
	"sync"
)

type State interface {
	String() string
	Activate() error
	Deactivate() error
}

var (
	mState       sync.Mutex
	currentState State

	Idle      State = new(idle)
	Babyphone State = new(babyphone)
)

type idle int

func (idle) String() string    { return "idle" }
func (idle) Activate() error   { return nil }
func (idle) Deactivate() error { return nil }

type babyphone struct {
	proc *exec.Cmd
}

func (b *babyphone) String() string {
	return "babyphone"
}

func (b *babyphone) Activate() error {
	b.proc = exec.Command("./videoserver")
	b.proc.Start()
	return nil
}
func (b *babyphone) Deactivate() error {
	return nil
}
