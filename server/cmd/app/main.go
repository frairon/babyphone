package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/frairon/babyphone/server"
)

var (
	addr            = flag.String("addr", "localhost:8080", "http service address")
	device          = flag.String("device", "", "device to use for audio input")
	shutdownTimeout = 10 * time.Second
)

func main() {
	flag.Parse()
	log.SetFlags(0)
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	s := server.New()
	done, srv := s.Start(*addr)

	select {
	case <-done:
	case <-sigs:
		ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		defer cancel()
		srv.Shutdown(ctx)
	}
}
