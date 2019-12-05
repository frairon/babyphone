package server

import (
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

	spaces map[string]*Space
	cfg    *Config
}

type Space struct {
	writeM      sync.Mutex
	upgrader    websocket.Upgrader
	server      *Server
	password    string
	name        string
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
			return conn.shutdown(msg)
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
		cfg:         s.cfg,
		name:        name,
		upgrader:    s.upgrader,
		password:    hashPassword(password),
		server:      s,
		connections: make(map[*Connection]bool),
	}
}

func (s *Server) Start(addr string) (chan struct{}, *http.Server) {
	for name, space := range s.spaces {
		http.HandleFunc(fmt.Sprintf("/%s", name), space.OnConnect)
	}
	http.HandleFunc("/test", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("hello world"))
	})
	srv := &http.Server{Addr: addr, Handler: http.DefaultServeMux}
	srv.RegisterOnShutdown(s.Shutdown)
	done := make(chan struct{})
	go func() {
		defer close(done)
		log.Printf("Starting server at %s", addr)
		err := srv.ListenAndServe()
		if err != nil && err != http.ErrServerClosed {
			log.Printf("Error running server: %v", err)
		}
	}()

	return done, srv
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
	}
	log.Printf("creating Connection")
	s.connections[conn] = true
	go func() {
		defer func() {
			conn.Close()
			s.writeM.Lock()
			defer s.writeM.Unlock()
			delete(s.connections, conn)
		}()
		log.Printf("running connection")
		conn.run()
		log.Printf("connection done")
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
