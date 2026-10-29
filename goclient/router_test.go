package artdb

import (
	"fmt"
	"testing"
	"time"
)

func TestRouter(t *testing.T) {

	initAll()
	go startServer()

	router := Router{user: "admin", password: "admin", handler: GetHandler("router")}
	cargs := ClientArgs{sql: "select a from b", shard: "", parameters: make([]any, 0)}

	go sendServerPings(t)
	time.Sleep(10 * time.Second)

	result, err := router.post("router", "/execute", cargs.toBytes())

	if err != nil {
		t.Errorf("error from post %v", err)
	}

	if string(result) != "success" {
		t.Errorf("Bad result from rest call")
	}

	fmt.Println("Router test success")
	stopDiscovery()
}

func sendServerPings(t *testing.T) {
	for range 1000 {
		sendInBackground(t, "router", 8082)
	}
}
