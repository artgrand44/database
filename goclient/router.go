package artdb

import (
	"bytes"
	"errors"
	"fmt"
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

func (this *Router) post(_ string, template string, body []byte, args ...any) ([]byte, error) {
	var prevHost = ""

	for {
		host, success := this.handler.GetServer()

		if !success || host == prevHost {
			return nil, errors.New("No available servers")
		}

		prevHost = host

		path := "http//" + host + fmt.Sprintf(template, args...)

		var buffer bytes.Buffer
		buffer.Write(body)

		req, _ := http.NewRequest("POST", path, &buffer)

		req.Header.Set("Content-Type", "application/json")
		req.SetBasicAuth(this.user, this.password)

		resp, err := client.Do(req)

		if err == nil {
			return io.ReadAll(resp.Body)
		}

		defer resp.Body.Close()
	}

}

func initRouter() {
	transport := &http.Transport{Protocols: &http.Protocols{}}

	transport.Protocols.SetHTTP1(false)
	transport.Protocols.SetHTTP2(true)

	client = &http.Client{Timeout: 10 * time.Second, Transport: transport}

}
