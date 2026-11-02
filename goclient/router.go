package artdb

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

type BatchEntry struct {
	path     string
	body     []byte
	result   []byte
	err      error
	complete chan bool
	done     bool
}

type Router struct {
	handler        *DiscoveryHandler
	user           string
	password       string
	currentBatch   []BatchEntry
	batchStartTime time.Time
	lock           sync.Mutex
	queue          chan []BatchEntry
}

var client *http.Client

func createRouter(handler *DiscoveryHandler, user string, password string) *Router {
	router := &Router{handler: handler, user: user, password: password, queue: make(chan []BatchEntry, 1000)}

	go drainQueue(router)

	return router
}

func (this *Router) batch(path string, body []byte) ([]byte, error) {
	this.lock.Lock()

	if this.currentBatch == nil {
		this.currentBatch = make([]BatchEntry, 0)
	}

	entry := BatchEntry{path: path, body: body}

	this.currentBatch = append(this.currentBatch, entry)

	if len(this.currentBatch) >= 1 {
		fmt.Println("Adding entry to queue ", len(this.currentBatch))
		this.queue <- this.currentBatch
		fmt.Println("after entry added")
		this.currentBatch = nil
	}

	this.lock.Unlock()

	fmt.Println("before waiting for complete")
	entry.done = <-entry.complete

	fmt.Println("***after wait")

	return entry.result, entry.err

}

func drainQueue(router *Router) {
	for {
		entries := <-router.queue

		fmt.Println("***got entry from q")
		var buff bytes.Buffer

		writeInt(len(entries), &buff)

		for _, entry := range entries {
			fmt.Println("writing patth ", entry.path, " len ", len(entry.body), " str ", string(entry.body))
			writeString(entry.path, &buff)
			writeInt(len(entry.body), &buff)
			writeBytes(entry.body, &buff)
		}

		fmt.Println("sending /db/batch")
		response, err := router.post("/db/batch", buff.Bytes())

		if err != nil {
			fmt.Println(err)
		}

		responseBuffer := bytes.NewBuffer(response)

		count := readInt(responseBuffer)

		if count != len(entries) {
			fmt.Println("Wrong count for response")
		}

		for _, entry := range entries {
			success := readBool(responseBuffer)
			fmt.Println("got success ", success)
			if success {
				fmt.Println("settting entry.result")
				entry.result = readBytes(readInt(responseBuffer), &buff)

				fmt.Println("set result to ", string(entry.result))
				entry.err = nil
			} else {
				entry.result = nil
				entry.err = errors.New(readString(&buff))
			}

			fmt.Println("setting complete")
			entry.complete <- true
			fmt.Println("after stting complete")
		}
	}
}

func (this *Router) post(template string, body []byte) ([]byte, error) {
	var prevHost = ""

	for {
		host, success := this.handler.GetServer()

		if !success || host == prevHost {
			return nil, errors.New("There are no servers available")
		}

		req, err := http.NewRequest("POST", "http://"+host+template, bytes.NewBuffer(body))

		if err != nil {
			return nil, err
		}

		req.Header.Set("Content-Type", "application/json")
		req.SetBasicAuth(this.user, this.password)

		resp, err := client.Do(req)

		if err != nil {
			this.handler.deleteServer(host)
			return nil, err
		}

		prevHost = host

		defer resp.Body.Close()

		if resp.StatusCode == 418 {
			return nil, errors.New(resp.Header.Get("Warning"))
		}

		return io.ReadAll(resp.Body)

	}

}

func initRouter() {
	transport := &http.Transport{Protocols: &http.Protocols{}}

	transport.Protocols.SetHTTP1(false)
	transport.Protocols.SetHTTP2(true)

	client = &http.Client{Timeout: 10 * time.Second, Transport: transport}

}
