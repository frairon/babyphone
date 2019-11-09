package server

import (
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type Message struct {
	Action string `json:"action"`
	Info   string `json:"info"`
}

type connectionType int

const (
	invalidConnType  connectionType = 0
	serverConnection connectionType = 1
	clientConnection connectionType = 2
)

type connectionState int

const (
	invalidConnState connectionState = 0
	unboundConnState connectionState = 1
	boundConnState   connectionState = 2
)

type Connection struct {
	writeM sync.Mutex
	c      *websocket.Conn

	creation time.Time
	connType connectionType
	state    connectionState

	// the other connection (server for client connection and the other way around)
	peer *Connection
}

func (c *Connection) lockForWrite() func() {
	c.writeM.Lock()
	return func() { c.writeM.Unlock() }
}

func (c *Connection) Close() {
	c.c.Close()
}

func (c *Connection) handleMessages() {
	for {
		mt, rawMsg, err := c.c.ReadMessage()
		if err != nil {
			break
		}
		if mt != websocket.TextMessage {
			log.Printf("Cannot read message of type: %d", mt)
			continue
		}

		var msg Message
		err = json.Unmarshal(rawMsg, &msg)
		if err != nil {
			log.Printf("Error reading message: %v: %v", string(rawMsg), err)
			continue
		}
		// action, exists := actions[msg.Action]
		// if !exists {
		// 	log.Printf("Unhandled action %s", msg.Action)
		// 	continue
		// }
		//
		// if err = action.execute(c, &msg); err != nil {
		// 	log.Printf("Error executing %s: %v", action.command, err)
		// 	continue
		// }
	}
}

func (c *Connection) shutdown(msg string) error {
	c.writeM.Lock()
	defer c.writeM.Unlock()
	err := c.c.WriteControl(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseGoingAway, msg),
		time.Now().Add(time.Second))
	if err != nil {
		return fmt.Errorf("Error closing Connection %+v: %v", c, err)
	}
	return nil
}

func (c *Connection) writeMessage(msg *Message) error {
	c.writeM.Lock()
	defer c.writeM.Unlock()
	marshalled, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("Error marshalling message %+v: %v", msg, err)
	}
	return c.c.WriteMessage(websocket.TextMessage, marshalled)
}

// func broadcast(msg *Message) {
// 	for Connection := range connections {
// 		err := Connection.writeMessage(msg)
// 		if err != nil {
// 			log.Printf("Error shutting down Connection: %v", err)
// 		}
// 	}
// }
