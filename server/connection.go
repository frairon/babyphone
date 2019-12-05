package server

import (
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type Setup struct {
	Type     connectionType `json:"connection_type"`
	Password string         `json:"password"`
	Name     string         `json:"name"`
}

type Message struct {
	Action string `json:"action"`
	Info   string `json:"info"`
	Setup  *Setup `json:"setup"`
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
	cfg      *Config
	writeM   sync.Mutex
	c        *websocket.Conn
	space    *Space
	creation time.Time
	connType connectionType
	state    connectionState
	name     string

	// for server connections: the connected clients
	clients []*Connection

	// for client connection: the server
	server *Connection
}

func (c *Connection) String() string {
	return fmt.Sprintf("Connection %s", c.c.RemoteAddr().String())
}

func (c *Connection) disconnectIfInvalid() {
	if c.connType == invalidConnType {
		log.Printf("Connection couldn't be setup within timeout, closing")
		c.shutdown("invalid type")
	}
}

func (c *Connection) run() {
	log.Printf("Starting connection")
	go func() {
		<-time.After(10 * time.Second)
		c.disconnectIfInvalid()
	}()

	c.handleMessages()

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
			if cerr, is := err.(*websocket.CloseError); is {
				log.Printf("[server] Connection closed. Code %d: %s", cerr.Code, cerr.Text)
			} else {
				log.Printf("[server] Error reading message, stopping: %v", err)
			}
			break
		}
		if mt != websocket.TextMessage {
			log.Printf("Cannot read message of type: %d", mt)
			continue
		}

		var msg Message
		err = json.Unmarshal(rawMsg, &msg)
		if err != nil {
			log.Printf("Error unmarshalling message: %v: %v", string(rawMsg), err)
			continue
		}
		log.Printf("received message: %#v", msg)
		if msg.Action == "setup" {
			log.Printf("setting up...")
			c.setup(msg.Setup)
			log.Printf("Setup done")
			continue
		}

		// depending on the type of connection, we'll handle differently
		switch c.connType {
		case invalidConnType:
			c.shutdown("protocol error")
		case serverConnection:
			c.handleServerMessage(&msg)
		case clientConnection:
			c.handleClientMessage(&msg)
		}
	}
}

func (c *Connection) setup(setup *Setup) {
	if setup == nil || c.connType != invalidConnType {
		c.shutdown("protocol error")
		return
	}
	log.Printf("Setup message: %#v", setup)
	// sleep before the password check to avoid brute force
	time.Sleep(c.cfg.LoginDelay)
	if hashPassword(setup.Password) != c.space.password {
		c.shutdown("password error")
	}
	c.connType = setup.Type
	err := c.writeMessage(&Message{Action: "ok"})
	if err != nil {
		log.Printf("error writing: %v", err)
	}
}

func (c *Connection) handleServerMessage(msg *Message) {
	// for _, client := range c.clients{
	// 	client.writeMessage(*msg))
	// if c.peer != null {
	// 	c.peer.writeMessage(msg)
	// 	return
	// }
}

func (c *Connection) handleClientMessage(msg *Message) {
}

func (c *Connection) shutdown(msg string) error {
	c.writeM.Lock()
	defer c.writeM.Unlock()
	log.Printf("Shutting down connection with message: %s", msg)
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
	log.Printf("Writing message %#v", msg)
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
