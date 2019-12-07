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
	// If the connection type is server, then this is the password
	// clients need to pass in order to connect to the server
	ServerPassword string `json:"server_password"`
	Name           string `json:"name"`
}

type Connect struct {
	Server   string `json:"server"`
	Password string `json:"password"`
}
type ConnectionStatus struct {
	Status string `json:"status"`
}

type Message struct {
	Action           string            `json:"action"`
	Info             string            `json:"info"`
	Setup            *Setup            `json:"setup"`
	Connect          *Connect          `json:"connect"`
	ConnectionStatus *ConnectionStatus `json:"connection_status"`
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

	// if the connection is a server, this is the password to connect to it.
	serverPassword string
	serverName     string

	// for server connections: the connected clients
	clients map[string]*Connection

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

	if c.connType == serverConnection {
		if setup.Name == "" {
			c.shutdown("Server connections need a name")
		}
		c.serverName = setup.Name
		c.serverPassword = hashPassword(setup.ServerPassword)
	}

	// switch c.connType {
	// case clientConnection:
	// 	err := c.space.registerAsClient(c.clientName(), c)
	// 	if err != nil {
	// 		log.Printf("Error registering as client: %v", err)
	// 		c.shutdown("registration error")
	// 	}
	// case serverConnection:
	// 	err := c.space.registerAsServer(setup.Name, c)
	// 	if err != nil {
	// 		log.Printf("Error registering as server: %v", err)
	// 		c.shutdown("registration error")
	// 	}
	// }
	err := c.writeMessage(&Message{Action: "ok"})
	if err != nil {
		log.Printf("error writing: %v", err)
	}
}

// NameIfConnType returns the name of the connection (depending on its type),
// if the type matches the actual type.
func (c *Connection) NameIfConnType(t connectionType) string {
	if c.connType != t {
		return ""
	}
	switch t {
	case clientConnection:
		return c.c.RemoteAddr().String()

	case serverConnection:
		return c.serverName
	}
	return ""
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
	switch msg.Action {
	case "connect":
		connect := msg.Connect
		if connect == nil {
			return
		}
		if c.server != nil {
			log.Printf("Connection already connected to server. Closing")
			c.shutdown("protocol error")
			return
		}
		serverConn := c.space.connectionForTypeAndName(serverConnection, connect.Server)
		if serverConn == nil {
			c.writeMessage(&Message{
				Action: "connection_status",
				ConnectionStatus: &ConnectionStatus{
					Status: "server_not_found",
				},
			})
			return
		}
		err := serverConn.connectClient(c, connect)
		if err != nil {
			log.Printf("Error connecting to server: %v", err)
			c.shutdown("protocol error")
			return
		}
		c.server = serverConn
	}

}

func (c *Connection) connectClient(client *Connection, conn *Connect) error {
	if c.connType != serverConnection {
		return fmt.Errorf("Connection is not a server")
	}

	time.Sleep(c.cfg.LoginDelay)
	if c.serverPassword != hashPassword(conn.Password) {
		return fmt.Errorf("Password did not match")
	}

	// authenticated, so let's add it to the list
	clientName := client.NameIfConnType(clientConnection)
	if _, exists := c.clients[clientName]; exists {
		return fmt.Errorf("duplicate client name: %s", clientName)
	}
	c.clients[clientName] = client

	return nil
}

func (c *Connection) shutdown(msg string) {
	c.writeM.Lock()
	defer c.writeM.Unlock()
	log.Printf("Shutting down connection with message: %s", msg)
	err := c.c.WriteControl(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseGoingAway, msg),
		time.Now().Add(time.Second))
	if err != nil {
		log.Printf("Error closing Connection %+v: %v", c, err)
	}
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
