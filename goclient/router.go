package artdb

import (
	"bytes"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/http2"
)

const NETWORK_ERROR = "There was a network error"

var totalPuts atomic.Int32
var totalEntries atomic.Int32
var totalWaitTime atomic.Int64

type BatchEntry struct {
	path   string
	body   []byte
	result []byte
	err    error
	done   bool
	waiter *sync.WaitGroup
	start  time.Time
}

type Router struct {
	handler        *DiscoveryHandler
	user           string
	password       string
	currentBatch   []*BatchEntry
	batchStartTime time.Time
	lock           sync.Mutex
}

var client *http.Client

var routerPool = sync.Pool{
	New: func() any {
		return make([]byte, 1000)
	},
}

func createRouter(handler *DiscoveryHandler, user string, password string) *Router {
	return &Router{handler: handler, user: user, password: password}
}

func (this *Router) batch(path string, body []byte) ([]byte, error) {
	this.lock.Lock()

	batchToSend := make([]*BatchEntry, 0)
	entry := BatchEntry{path: path, body: body, waiter: &sync.WaitGroup{}, start: time.Now()}

	if this.currentBatch == nil {
		this.currentBatch = make([]*BatchEntry, 0)
	}

	this.currentBatch = append(this.currentBatch, &entry)
	totalEntries.Add(1)

	if len(this.currentBatch) >= 5 {
		batchToSend = this.currentBatch
		this.currentBatch = nil
	}

	this.lock.Unlock()

	entry.waiter.Add(1)

	if len(batchToSend) != 0 {
		go this.processBatch(batchToSend)
	}

	entry.waiter.Wait()
	return entry.result, entry.err

}

func getAverageWeight() int64 {
	if totalEntries.Load() == 0 {
		return 0
	}

	return totalWaitTime.Load() / int64(totalEntries.Load())
}

func (this *Router) post(template string, body []byte) ([]byte, error) {
	for {
		host, success := this.handler.GetServer()

		if !success {
			return nil, errors.New("There are no servers available")
		}

		resp, err := post("http://"+host+template, this.user, this.password, body)

		if err != nil && strings.HasPrefix(err.Error(), NETWORK_ERROR) {
			this.handler.deleteServer(host)
		} else {
			return resp, err
		}
	}

}

func initRouter() {
	h2t := &http2.Transport{
		AllowHTTP: true,
		DialTLS: func(network, addr string, cfg *tls.Config) (net.Conn, error) {
			// Dial without TLS
			return net.Dial(network, addr)
		},
	}

	client = &http.Client{Timeout: 10 * time.Second, Transport: h2t}

}

func post(uri string, user string, password string, body []byte) ([]byte, error) {
	totalPuts.Add(1)
	req, err := http.NewRequest("POST", uri, bytes.NewBuffer(body))

	if err != nil {
		return nil, err
	}

	req.Header.Set("Content-Type", "application/json")
	req.SetBasicAuth(user, password)

	resp, err := client.Do(req)

	if err != nil {
		resp.Body.Close()
		return nil, errors.New(NETWORK_ERROR + err.Error())
	}

	defer resp.Body.Close()

	if resp.StatusCode == 418 {
		return nil, errors.New(resp.Header.Get("Warning"))
	}

	var buf bytes.Buffer

	if _, err = io.Copy(&buf, resp.Body); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func (this *Router) processBatch(entries []*BatchEntry) {
	var buff bytes.Buffer

	writeInt(len(entries), &buff)

	for _, entry := range entries {
		writeString(entry.path, &buff)
		writeInt(len(entry.body), &buff)
		writeBytes(entry.body, &buff)
		totalWaitTime.Add(time.Since(entry.start).Milliseconds())
	}

	response, err := this.post("/db/batch", buff.Bytes())

	if err != nil {
		fmt.Println(err)
	}

	responseBuffer := bytes.NewBuffer(response)

	for _, entry := range entries {
		success := readBool(responseBuffer)

		if success {
			len := readInt(responseBuffer)
			bytes := readBytes(len, responseBuffer)

			entry.result = bytes
			entry.err = nil
		} else {
			fmt.Println("got error in router")
			entry.result = nil
			entry.err = errors.New(readString(&buff))
		}

		entry.waiter.Done()
	}
}
