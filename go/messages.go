package server

import "log"

type Action struct {
	command string
	execute func() error
}

var (
	actions  map[string]*Action
	Shutdown = newAction("shutdown", func() error {
		cmd := sudo("shutdown", "-h", "0")
		if err := cmd.Run(); err != nil {
			out, err := cmd.CombinedOutput()
			if err != nil {
				out = []byte(err.Error())
			}
			log.Printf("Shutdown output: %s", string(out))
			log.Printf("Error shutting down: %v", err)
		}
		return nil
	})
)

func newAction(action string, executer func() error) *Action {
	a := &Action{
		command: action,
		execute: executer,
	}
	actions[action] = a
	return a
}

type Message struct {
	Action string `json:"action"`
}
