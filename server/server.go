package server

import (
	"log"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"
	"golang.org/x/sync/errgroup"
)

type Server struct {
	upgrader websocket.Upgrader
	m        sync.Mutex

	spaces map[string]*Space
}

type Space struct {
	writeM      sync.Mutex
	password    string
	connections map[string]*Connection
}

func (s *Space) shutdown(msg string) error {
	s.writeM.Lock()
	defer s.writeM.Unlock()
	var errs errgroup.Group
	for name, conn := range s.connections {
		errs.Go(func() error {
			log.Printf("shutting down connection %s", name)
			return conn.shutdown(msg)
		})
	}
	return errs.Wait()
}

func New() *Server {
	return &Server{
		spaces: make(map[string]*Space),
	}
}

func (s *Server) AddSpace(name, password string) {
	s.spaces[name] = &Space{
		password:    password,
		connections: make(map[string]*Connection),
	}
}

func (s *Server) Start(addr string) (chan struct{}, *http.Server) {
	http.HandleFunc("/", s.OnConnect)
	srv := &http.Server{Addr: addr, Handler: http.DefaultServeMux}
	srv.RegisterOnShutdown(s.Shutdown)
	done := make(chan struct{})
	go func() {
		defer close(done)
		log.Printf("Starting server at %s", addr)
		err := srv.ListenAndServe()
		if err != nil {
			log.Printf("Error running server: %v", err)
		}
	}()

	return done, srv
}

func (s *Server) OnConnect(w http.ResponseWriter, r *http.Request) {
	c, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Print("Upgrading to websockets failed", err)
		return
	}
	s.m.Lock()
	defer s.m.Unlock()
	conn := &Connection{
		c: c,
	}
	log.Printf("creating Connection")
	// if _, exists := s.connections[conn]; exists {
	// 	panic("Connection already present. This must not happen.")
	// }
	// s.connections[conn] = true
	go func() {
		defer conn.Close()
		conn.handleMessages()
	}()
}

func (s *Server) Shutdown() {
	s.m.Lock()
	defer s.m.Unlock()
	for _, space := range s.spaces {
		err := space.shutdown("server is shutting down")
		if err != nil {
			log.Printf("Error shutting down connection: %v", err)
		}
	}
}
