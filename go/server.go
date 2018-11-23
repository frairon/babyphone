package server

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

const (
	shutdownTimeout = 10 * time.Second
)

var (
	addr        = flag.String("addr", "localhost:8080", "http service address")
	upgrader    websocket.Upgrader
	m           sync.Mutex
	connections = make(map[*websocket.Conn]bool)
)

func handleMessage(msg *Message) error {
	action, exists := actions[msg.Action]
	if !exists {
		return fmt.Errorf("Unhandled action %s", msg.Action)
	}
	return action.execute()
}

func handleConnectionMessages(c *websocket.Conn) {
	defer func() {
		m.Lock()
		defer m.Unlock()
		delete(connections, c)
		c.Close()
	}()
	for {
		mt, rawMsg, err := c.ReadMessage()
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
		if err = handleMessage(&msg); err != nil {
			log.Printf("Error handling message: %+v: %v", msg, err)
			continue
		}
	}
}

func onConnect(w http.ResponseWriter, r *http.Request) {
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Print("Upgrading to websockets failed", err)
		return
	}
	m.Lock()
	defer m.Unlock()
	if _, exists := connections[c]; exists {
		panic("Connection already present. This must not happen.")
	}
	connections[c] = true
	go handleConnectionMessages(c)
}

func main() {
	flag.Parse()
	log.SetFlags(0)

	http.HandleFunc("/", onConnect)
	server := &http.Server{Addr: *addr, Handler: http.DefaultServeMux}
	server.RegisterOnShutdown(func() {
		m.Lock()
		defer m.Unlock()
		for connection := range connections {
			err := connection.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseGoingAway, "server is shutting down"),
				time.Now().Add(time.Second))
			if err != nil {
				log.Printf("Error closing connection %+v: %v", connection, err)
			}
		}
	})
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	done := make(chan struct{})

	go func() {
		defer close(done)
		err := server.ListenAndServe()
		if err != nil {
			log.Printf("Error running server: %v", err)
		}
	}()

	select {
	case <-done:
	case <-sigs:
		ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		defer cancel()
		server.Shutdown(ctx)
	}
}
