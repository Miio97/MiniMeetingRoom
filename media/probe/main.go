package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"time"
)

func main() {
	secret := os.Getenv("MMR_MEDIA_PROBE_SECRET")
	if len(secret) < 32 || len(secret) > 128 {
		fmt.Fprintln(os.Stderr, "MMR_MEDIA_PROBE_SECRET_INVALID")
		os.Exit(2)
	}
	port := 8765
	if configured := os.Getenv("MMR_MEDIA_PROBE_PORT"); configured != "" {
		parsed, err := strconv.Atoi(configured)
		if err != nil || parsed < 1 || parsed > 65535 {
			fmt.Fprintln(os.Stderr, "MMR_MEDIA_PROBE_PORT_INVALID")
			os.Exit(2)
		}
		port = parsed
	}
	service, err := newService(secret)
	if err != nil {
		fmt.Fprintln(os.Stderr, "MMR_MEDIA_PROBE_INIT_FAILED")
		os.Exit(2)
	}
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		service.close()
		fmt.Fprintln(os.Stderr, "MMR_MEDIA_PROBE_BIND_FAILED")
		os.Exit(2)
	}
	server := &http.Server{Handler: service, ReadHeaderTimeout: 3 * time.Second,
		ReadTimeout: 5 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 10 * time.Second,
		MaxHeaderBytes: 4096, ErrorLog: log.New(quietWriter{}, "", 0)}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	shutdownRequested := make(chan struct{}, 1)
	shutdownDone := make(chan struct{})
	service.shutdown = func() {
		select {
		case shutdownRequested <- struct{}{}:
		default:
		}
	}
	go func() {
		defer close(shutdownDone)
		select {
		case <-ctx.Done():
		case <-shutdownRequested:
		}
		shutdownContext, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownContext)
		service.close()
	}()
	fmt.Println("MMR_MEDIA_PROBE_READY")
	if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
		fmt.Fprintln(os.Stderr, "MMR_MEDIA_PROBE_SERVE_FAILED")
		stop()
	}
	<-shutdownDone
	fmt.Println("MMR_MEDIA_PROBE_CLOSED")
}

type quietWriter struct{}

func (quietWriter) Write(data []byte) (int, error) { return len(data), nil }
