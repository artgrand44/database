package artdb

import (
	"fmt"
	"testing"
	"time"
)

func TestDiscovery(t *testing.T) {
	serverTTL = 100 * time.Millisecond
	pollDelay = 50 * time.Microsecond
	checkDelay = 5 * time.Second

	const cluster = "cluster"
	const first = "127.0.0.51:8080"
	const cirst = "127.0.0.51:80801"

	initAll()

	handler := GetHandler(cluster)

	for range 10 {
		sendPing(t, "cluster", 8080)
	}

	if handler.getServerCount() != 1 {
		t.Errorf("wrong server count")
	}

	fmt.Printf("uri is %s\n", handler.servers[0])

	for range 10 {
		sendPing(t, "cluster", 8081)
	}

	if handler.getServerCount() != 2 {
		t.Errorf("wrong second server count")
	}

	server1, _ := handler.GetServer()
	server2, _ := handler.GetServer()

	if server1 == server2 {
		t.Errorf("both servers match ")
	}

	go sendInBackground(t, "cluster", 8081)

	for len(handler.servers) != 1 {
		time.Sleep(1 * time.Second)
	}

	stopDiscovery()

	if len(handler.servers) != 1 {
		t.Errorf("not killed")
	}

	fmt.Println("Success")
}
