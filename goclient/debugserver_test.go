package artdb

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"testing"
	"time"
)

var stopServer bool

func handler(w http.ResponseWriter, r *http.Request) {
	fmt.Println("Got request for ", r.URL.Path[1:])

	fmt.Fprintf(w, "success")
	//w.WriteHeader(http.StatusOK)
}

func startServer() {
	http.HandleFunc("/", handler)

	fmt.Println("calling listen")
	if err := http.ListenAndServe(":8082", nil); err != nil {
		fmt.Println("Error starting server ", err)
	}

	fmt.Println("server started")
}

func sendPing(t *testing.T, cluster string, port int) {
	addr, err := net.ResolveUDPAddr("udp4", "224.0.0.1:9999")

	conn, err := net.ListenUDP("udp", nil)

	if err != nil {
		t.Errorf("connect error %v", err)
	}

	defer conn.Close()

	jsonBytes, err := json.Marshal(Message{Cluster: cluster, Port: port})

	if err != nil {
		t.Errorf("marshal %v", err)
	}

	n, err := conn.WriteToUDP(jsonBytes, addr)

	if err != nil {
		t.Errorf("write %v", err)
	}

	if n != len(jsonBytes) {
		t.Errorf("length")
	}
}

func sendInBackground(t *testing.T, cluster string, port int) {
	for !stopServer {
		sendPing(t, cluster, port)
		time.Sleep(2 * time.Second)
	}

	fmt.Println("End send loop")

}
