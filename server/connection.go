package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var (
	upgrader    websocket.Upgrader
	m           sync.Mutex
	connections = make(map[*Connection]bool)
)

type Connection struct {
	writeM sync.Mutex
	c      *websocket.Conn
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
		action, exists := actions[msg.Action]
		if !exists {
			log.Printf("Unhandled action %s", msg.Action)
			continue
		}

		if err = action.execute(c, &msg); err != nil {
			log.Printf("Error executing %s: %v", action.command, err)
			continue
		}
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

func broadcast(msg *Message) {
	for Connection := range connections {
		err := Connection.writeMessage(msg)
		if err != nil {
			log.Printf("Error shutting down Connection: %v", err)
		}
	}
}

func OnConnect(w http.ResponseWriter, r *http.Request) {
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Print("Upgrading to websockets failed", err)
		return
	}
	m.Lock()
	defer m.Unlock()
	conn := &Connection{
		c: c,
	}
	if _, exists := connections[conn]; exists {
		panic("Connection already present. This must not happen.")
	}
	connections[conn] = true
	go func() {
		defer conn.Close()
		conn.handleMessages()
	}()
}

func Shutdown() {
	m.Lock()
	defer m.Unlock()
	for connection := range connections {
		err := connection.shutdown("server is shutting down")
		if err != nil {
			log.Printf("Error shutting down connection: %v", err)
		}
	}
}
