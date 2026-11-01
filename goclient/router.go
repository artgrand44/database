package artdb

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"time"
)

type Router struct {
	handler  *DiscoveryHandler
	user     string
	password string
}

var client *http.Client

func (this *Router) batch(path string, body []byte) ([]byte, error) {
	return nil, nil
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
