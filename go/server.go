package server

import (
	"flag"
	"log"
	"net/http"

	"github.com/gorilla/websocket"
)

var addr = flag.String("addr", "localhost:8080", "http service address")

var upgrader = websocket.Upgrader{} // use default options

type connection struct {
	conn *websocket.Conn
}

func (c *connection) handleMessage(messageType int, content []byte) error {
	return nil
}

func (c *connection) close() {
	c.conn.Close()
}

func wsConnect(w http.ResponseWriter, r *http.Request) {
	c, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Print("Upgrading to websockets failed", err)
		return
	}
	conn := &connection{conn: c}
	defer conn.close()
	for {
		mt, message, err := c.ReadMessage()
		if err != nil {
			break
		}
		err = conn.handleMessage(mt, message)
		if err != nil {
			break
		}
	}
}

func main() {
	flag.Parse()
	log.SetFlags(0)
	http.HandleFunc("/", wsConnect)
	log.Fatal(http.ListenAndServe(*addr, nil))
}
