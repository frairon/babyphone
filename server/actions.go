package server

import "log"

type Action struct {
	command string
	execute func(c *Connection, msg *Message) error
}

var (
	actions = make(map[string]*Action)
	// ShutdownAction shuts down the whole machine
	ShutdownAction = newAction("shutdown", func(c *Connection, msg *Message) error {
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
	// GetStateAction returns the current state to the caller
	GetStateAction = newAction("get-state", func(c *Connection, msg *Message) error {
		mState.Lock()
		defer mState.Unlock()
		return c.writeMessage(&Message{
			Action: "state",
			Info:   currentState.String(),
		})
	})

	ActivateStateAction = newAction("activate-state", func(c *Connection, msg *Message) error {
		return nil
	})
)

func newAction(action string, executer func(c *Connection, msg *Message) error) *Action {
	a := &Action{
		command: action,
		execute: executer,
	}
	actions[action] = a
	return a
}

type Message struct {
	Action string `json:"action"`
	Info   string `json:"info"`
}
