package artdb

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"testing"
	"time"
)

var stopServer bool

func getBatch(r *http.Request) *Batch {
	body, err := io.ReadAll(r.Body)

	if err != nil {
		fmt.Println("Got error reading body ", err)
		return nil
	}

	batch := Batch{}

	batch.load(bytes.NewBuffer(body))
	return &batch
}

func getClientArgs(r *http.Request) *ClientArgs {
	body, err := io.ReadAll(r.Body)

	if err != nil {
		fmt.Println("Got error reading body ", err)
		return nil
	}

	args := ClientArgs{}

	args.load(bytes.NewBuffer(body))
	return &args
}

func handler(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path

	switch path {
	case "/db/execute":
		args := getClientArgs(r)

		if args != nil {
			fmt.Println("execute ", args)
		}
	case "/db/executeQuery":
		args := getClientArgs(r)

		if args != nil {
			fmt.Println("execute Query", args)
		}

		doc := createDocument(false)
		set := DocumentSet{}

		doc.SetField("value", "hi")

		set.addDocument(&doc)
		w.Write(writeObject(set))
	case "/db/executeBatch":
		batch := getBatch(r)

		if batch != nil {
			fmt.Println("execute Batch", batch)
		}
		fmt.Fprintf(w, "success")
	case "/metrics":
	default:
		fmt.Println("Bad path ", path)
		fmt.Fprintf(w, "success")
	}
}

func startServer() {
	http.DefaultServeMux = http.NewServeMux()
	http.HandleFunc("/", handler)

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
