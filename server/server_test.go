package server

import (
	"context"
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

func TestFirst(t *testing.T) {
	log.SetFlags(0)

	cfg := NewConfig()
	cfg.LoginDelay = 0

	server := New(cfg)
	server.AddSpace("test-space", "test123")
	done, srv := server.Start(address)
	waitForServer()

	defer func() {
		// give the unit tests some time to finish before killing the server
		time.Sleep(100 * time.Millisecond)
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		err := srv.Shutdown(ctx)
		if err != nil {
			log.Printf("Error shutting down server: %v", err)
		}
		<-done
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

	t.Run("setup_passwd_fail", func(t *testing.T) {
		c, _, err := dialer.Dial(u.String(), nil)
		conn := &testConnection{
			c: c,
		}
		require.Nil(t, err)
		time.Sleep(200 * time.Millisecond)
		defer conn.Close()

		conn.writeMessage(t, &Message{
			Action: "setup",
			Setup: &Setup{
				Name:     "test",
				Password: "test123",
				Type:     clientConnection,
			},
		})
		time.Sleep(100 * time.Millisecond)
		msg := conn.nextMessage(t)
		log.Printf("msg %#v", msg)
		assert.True(t, msg != nil)
		assert.Equal(t, msg.Action, "ok")
	})
	// c.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
}
