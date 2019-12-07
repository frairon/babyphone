package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	address = "localhost:8080"
)

var (
	disconnected = &Message{
		Action: "__DISCONNECTED__",
	}
)

type testConnection struct {
	c *websocket.Conn
}

func (tc *testConnection) Close() {
	err := tc.c.WriteControl(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseGoingAway, "test is over"),
		time.Now().Add(100*time.Millisecond))
	if err != nil {
		log.Printf("Error closing test connection: %v", err)
	}
	err = tc.c.Close()
	if err != nil {
		log.Printf("Error closing underlying connection: %v", err)
	}
}

func (tc *testConnection) writeMessage(t *testing.T, msg *Message) {
	marshalled, err := json.Marshal(msg)
	assert.Nil(t, err)
	err = tc.c.WriteMessage(websocket.TextMessage, marshalled)
	assert.Nil(t, err)
}

func (tc *testConnection) nextMessage(t *testing.T) *Message {
	done := make(chan struct{})
	var msg *Message
	go func() {
		defer close(done)
		var readMsg Message
		_, rawMsg, err := tc.c.ReadMessage()
		if err != nil {
			if _, ok := err.(*websocket.CloseError); ok {
				msg = disconnected
			} else {
				log.Printf("Error reading message: %v", err)
			}
			return
		}
		err = json.Unmarshal(rawMsg, &readMsg)
		if err != nil {
			log.Printf("Error unmarshalling message: %v", err)
		}
		msg = &readMsg
	}()
	select {
	case <-done:
		return msg
	case <-time.After(2 * time.Second):
		t.Fatalf("Message read timed out")
	}
	return nil
}
func waitForServer() {
	for i := 0; i < 10; i++ {
		time.Sleep(100 * time.Millisecond)
		resp, err := http.Get(fmt.Sprintf("http://%s/test", address))
		if err == nil && resp.StatusCode == 200 {
			break
		}
	}
}

func TestSetup(t *testing.T) {
	log.SetFlags(0)

	cfg := NewConfig()
	cfg.LoginDelay = 0

	server := New(cfg)
	server.AddSpace("test-space", "test123")
	server.Start(address)
	waitForServer()

	defer func() {
		// give the unit tests some time to finish before killing the server
		time.Sleep(100 * time.Millisecond)
		server.Shutdown(3 * time.Second)
	}()

	u := url.URL{Scheme: "ws", Host: address, Path: "test-space"}

	dialer := &websocket.Dialer{
		Proxy:            http.ProxyFromEnvironment,
		HandshakeTimeout: 1 * time.Second,
	}

	t.Run("invalid-url", func(t *testing.T) {
		_, _, err := dialer.Dial(fmt.Sprintf("ws://%s/inexistent-space", address), nil)
		require.NotNil(t, err)
	})

	t.Run("setup_wrong_password", func(t *testing.T) {
		c, _, err := dialer.Dial(u.String(), nil)
		conn := &testConnection{
			c: c,
		}
		require.Nil(t, err)
		defer conn.Close()

		conn.writeMessage(t, &Message{
			Action: "setup",
			Setup: &Setup{
				Name:     "test",
				Password: "wrong-password",
				Type:     clientConnection,
			},
		})
		// the next message indicates that we're closed
		msg := conn.nextMessage(t)
		assert.True(t, msg == disconnected)
	})

	t.Run("setup_ok", func(t *testing.T) {
		c, _, err := dialer.Dial(u.String(), nil)
		conn := &testConnection{
			c: c,
		}
		require.Nil(t, err)
		defer conn.Close()

		conn.writeMessage(t, &Message{
			Action: "setup",
			Setup: &Setup{
				Password: "test123",
				Type:     clientConnection,
			},
		})
		msg := conn.nextMessage(t)
		assert.Equal(t, msg.Action, "ok")
	})
}

func TestClientServer(t *testing.T) {
	log.SetFlags(0)

	cfg := NewConfig()
	cfg.LoginDelay = 0

	server := New(cfg)
	server.AddSpace("test-space", "test123")
	server.Start(address)
	waitForServer()

	defer func() {
		// give the unit tests some time to finish before killing the server
		time.Sleep(100 * time.Millisecond)
		server.Shutdown(3 * time.Second)
	}()

	u := url.URL{Scheme: "ws", Host: address, Path: "test-space"}

	dialer := &websocket.Dialer{
		Proxy:            http.ProxyFromEnvironment,
		HandshakeTimeout: 1 * time.Second,
	}

	t.Run("client-server", func(t *testing.T) {
		c, _, err := dialer.Dial(u.String(), nil)
		assert.Nil(t, err)
		conn := &testConnection{
			c: c,
		}
		conn.writeMessage(t, &Message{
			Action: "setup",
			Setup: &Setup{
				Password: "test123",
				Type:     clientConnection,
			},
		})
		// eat the ok
		assert.True(t, conn.nextMessage(t) != nil)
	})
}
