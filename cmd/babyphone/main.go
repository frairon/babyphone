package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/frairon/babyphone/server"
)

var (
	addr            = flag.String("addr", "localhost:8080", "http service address")
	shutdownTimeout = 10 * time.Second
)

func main() {
	flag.Parse()
	log.SetFlags(0)
	http.HandleFunc("/", server.OnConnect)
	srv := &http.Server{Addr: *addr, Handler: http.DefaultServeMux}
	srv.RegisterOnShutdown(server.Shutdown)
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	done := make(chan struct{})

	go func() {
		defer close(done)
		log.Printf("Starting server at %s", *addr)
		err := srv.ListenAndServe()
		if err != nil {
			log.Printf("Error running server: %v", err)
		}
	}()

	err := server.VolumeStart()
	if err != nil {
		log.Fatalf("Error starting volume: %v", err)
	}

	select {
	case <-done:
	case <-sigs:
		ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		defer cancel()
		srv.Shutdown(ctx)
	}
}
