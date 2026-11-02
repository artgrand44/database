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
	path      string
	body      []byte
	result    []byte
	err       error
	lock      *sync.Mutex
	condition *sync.Cond
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
	router := &Router{handler: handler, user: user, password: password}

	go drainQueue(router)

	return router
}

func (this *Router) batch(path string, body []byte) ([]byte, error) {
	this.lock.Lock()

	if this.currentBatch == nil {
		this.currentBatch = make([]BatchEntry, 0)
	}

	entry := BatchEntry{path: path, body: body, lock: &sync.Mutex{}, condition: &sync.Cond{}}

	this.currentBatch = append(this.currentBatch, entry)

	if len(this.currentBatch) == 10 {
		this.queue <- this.currentBatch
		this.currentBatch = nil
	}

	this.lock.Unlock()

	entry.lock.Lock()
	defer entry.lock.Unlock()

	entry.condition.Wait()

	return entry.result, entry.err

}

func drainQueue(router *Router) {
	for {
		entries := <-router.queue

		var buff bytes.Buffer

		writeInt(len(entries), &buff)

		for _, entry := range entries {
			writeString(entry.path, &buff)
			writeInt(len(entry.body), &buff)
			writeBytes(entry.body, &buff)
		}

		response, err := router.post("/db/batch", buff.Bytes())

		if err != nil {
			fmt.Println(err)
		}

		responseBuffer := bytes.NewBuffer(response)

		for _, entry := range entries {
			entry.lock.Lock()

			success := readBool(responseBuffer)

			if success {
				entry.result = nil
				entry.err = errors.New(readString(&buff))
			} else {
				entry.result = readBytes(readInt(responseBuffer), &buff)
				entry.err = nil
			}

			entry.condition.Signal()
			entry.lock.Unlock()
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
