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

type quietWriter struct{}

func (quietWriter) Write(data []byte) (int, error) { return len(data), nil }
func main() {
	secret := os.Getenv("MMR_MEDIA_CONTROL_SECRET")
	port, _ := strconv.Atoi(os.Getenv("MMR_MEDIA_CONTROL_PORT"))
	if port == 0 {
		port = 8766
	}
	if len(secret) < 43 || len(secret) > 128 || port < 1 || port > 65535 {
		fmt.Fprintln(os.Stderr, "MMR_VIDEO_CONFIG_INVALID")
		os.Exit(2)
	}
	engine, err := newEngine(secret)
	if err != nil {
		fmt.Fprintln(os.Stderr, "MMR_VIDEO_INIT_FAILED")
		os.Exit(2)
	}
	diagnostics := startDiagnostics()
	defer diagnostics.close()
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		fmt.Fprintln(os.Stderr, "MMR_VIDEO_BIND_FAILED")
		os.Exit(2)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	engine.shutdown = stop
	server := &http.Server{Handler: engine, ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 10 * time.Second, MaxHeaderBytes: 4096, ErrorLog: log.New(quietWriter{}, "", 0)}
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				engine.expire()
				if time.Now().Unix()%5 == 0 {
					diagnostics.sample(engine)
				}
			case <-ctx.Done():
				engine.close()
				_ = server.Close()
				return
			}
		}
	}()
	fmt.Println("MMR_VIDEO_SERVICE_READY")
	if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
		fmt.Fprintln(os.Stderr, "MMR_VIDEO_SERVE_FAILED")
	}
	engine.close()
	diagnostics.sample(engine)
}
