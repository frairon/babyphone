package server

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"golang.org/x/sync/errgroup"
)

type Config struct {
	LoginDelay time.Duration
}

type Server struct {
	upgrader websocket.Upgrader
	m        sync.Mutex

	srv  *http.Server
	done chan struct{}

	spaces map[string]*Space
	cfg    *Config
}

type Space struct {
	writeM   sync.Mutex
	upgrader websocket.Upgrader
	server   *Server
	password string
	name     string
	servers  map[string]*Connection
	clients  map[string]*Connection

	connections map[*Connection]bool
	cfg         *Config
}

func NewConfig() *Config {
	return &Config{
		LoginDelay: 2 * time.Second,
	}
}

func (s *Space) shutdown(msg string) error {
	s.writeM.Lock()
	defer s.writeM.Unlock()
	var errs errgroup.Group
	for conn := range s.connections {
		errs.Go(func() error {
			log.Printf("shutting down connection %v", conn.String())
			conn.shutdown(msg)
			return nil
		})
	}
	return errs.Wait()
}

func New(cfg *Config) *Server {
	return &Server{

		cfg:    cfg,
		spaces: make(map[string]*Space),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
		},
	}
}

func (s *Server) AddSpace(name, password string) {
	if _, exists := s.spaces[name]; exists {
		panic(fmt.Sprintf("duplicate space name %s", name))
	}
	s.spaces[name] = &Space{
		cfg:      s.cfg,
		name:     name,
		upgrader: s.upgrader,
		password: hashPassword(password),
		server:   s,

		servers:     make(map[string]*Connection),
		clients:     make(map[string]*Connection),
		connections: make(map[*Connection]bool),
	}
}

// Shutdown closes the underlying http server and disconnects all connections
func (s *Server) Shutdown(timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	err := s.srv.Shutdown(ctx)
	if err != nil {
		return fmt.Errorf("Error shutting down server: %v", err)
	}
	<-s.done
	return nil
}

// Start starts the server creating endpoints for all configured spaces
func (s *Server) Start(addr string) error {
	mux := new(http.ServeMux)

	for name, space := range s.spaces {
		mux.HandleFunc(fmt.Sprintf("/%s", name), space.OnConnect)
	}
	mux.HandleFunc("/test", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("hello world"))
	})
	s.srv = &http.Server{Addr: addr, Handler: mux}
	s.srv.RegisterOnShutdown(s.preShutdown)
	s.done = make(chan struct{})
	go func() {
		defer close(s.done)
		log.Printf("Starting server at %s", addr)
		err := s.srv.ListenAndServe()
		if err != nil && err != http.ErrServerClosed {
			log.Printf("Error running server: %v", err)
		}
	}()

	return nil
}
func (s *Space) registerAsClient(name string, conn *Connection) error {
	if _, exists := s.clients[name]; exists {
		return fmt.Errorf("Duplicate client name: %s", name)
	}
	s.clients[name] = conn
	return nil
}

func (s *Space) registerAsServer(name string, conn *Connection) error {
	if _, exists := s.servers[name]; exists {
		return fmt.Errorf("Duplicate server name: %s", name)
	}
	s.servers[name] = conn
	return nil
}

func (s *Space) connectionForTypeAndName(t connectionType, name string) *Connection {
	// we do not accept empty names
	if name == "" {
		return nil
	}

	for conn := range s.connections {
		if name == conn.NameIfConnType(t) {
			return conn
		}
	}
	return nil
}

func (s *Space) OnConnect(w http.ResponseWriter, r *http.Request) {
	c, err := s.upgrader.Upgrade(w, r, nil)
	log.Printf("Space %s got connection, upgrading to websocket", s.name)
	if err != nil {
		log.Printf("Upgrading to websockets failed: %v", err)
		return
	}
	s.writeM.Lock()
	defer s.writeM.Unlock()
	conn := &Connection{
		cfg:      s.cfg,
		creation: time.Now(),
		space:    s,
		c:        c,
		state:    unboundConnState,
		clients:  make(map[string]*Connection),
	}
	s.connections[conn] = true
	go func() {
		defer func() {
			conn.Close()
			s.writeM.Lock()
			defer s.writeM.Unlock()
			delete(s.connections, conn)
		}()
		conn.run()
	}()
}

func (s *Server) preShutdown() {
	s.m.Lock()
	defer s.m.Unlock()
	for _, space := range s.spaces {
		err := space.shutdown("server is shutting down")
		if err != nil {
			log.Printf("Error shutting down connection: %v", err)
		}
	}
}
