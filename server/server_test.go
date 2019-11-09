package server

import (
	"log"
	"net/url"
	"testing"

	"github.com/gorilla/websocket"
	"github.com/stretchr/testify/require"
)

const (
	address = "frosi-babyphone.appspot.com:8080"
)

func TestFirst(t *testing.T) {
	log.SetFlags(0)

	// server := New()
	// done, srv := server.Start(address)

	// defer func() {
	// 	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	// 	defer cancel()
	// 	srv.Shutdown(ctx)
	// 	<-done
	// }()

	u := url.URL{Scheme: "ws", Host: address, Path: "/"}
	log.Printf("connecting to %s", u.String())

	c, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	require.Nil(t, err)
	defer c.Close()
	c.WriteMessage(websocket.TextMessage, []byte("hello world"))
	c.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
}
