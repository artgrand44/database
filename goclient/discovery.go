package artdb

import (
	"encoding/json"
	"fmt"
	"maps"
	"net"
	"slices"
	"sync"
	"sync/atomic"
	"time"
)

type Message struct {
	Cluster string
	Shard   string
	Port    int
	Started bool
}

type DiscoveryHandler struct {
	lock       *sync.RWMutex
	servers    []string
	timestamps map[string]int64
	slot       int
}

var handlers map[string]*DiscoveryHandler = make(map[string]*DiscoveryHandler)
var handlersLock sync.Mutex
var connection *net.UDPConn
var stopping atomic.Bool
var serverTTL = 2 * time.Minute
var pollDelay = 500 * time.Millisecond
var checkDelay = 30 * time.Second

func GetHandler(cluster string) *DiscoveryHandler {
	handlersLock.Lock()

	handler, found := handlers[cluster]

	if !found {
		handler = &DiscoveryHandler{lock: &sync.RWMutex{}, timestamps: make(map[string]int64)}
		handlers[cluster] = handler
	}

	handlersLock.Unlock()

	return handler
}

func (this *DiscoveryHandler) GetServer() (string, bool) {
	this.lock.RLock()
	defer this.lock.RUnlock()

	if len(this.servers) == 0 {
		return "", false
	}

	this.slot = (this.slot + 1) % len(this.servers)

	return this.servers[this.slot], true
}

func (this *DiscoveryHandler) getServerCount() int {
	return len(this.servers)
}

func checkTimeouts() {
	for !stopping.Load() {
		handlersLock.Lock()

		list := slices.Collect(maps.Values(handlers))

		handlersLock.Unlock()

		now := time.Now().UnixMilli()

		for _, handler := range list {
			handler.lock.Lock()

			for uri, timeout := range handler.timestamps {
				if timeout < now {
					fmt.Println("deleting " + uri)
					delete(handler.timestamps, uri)

					idx := slices.Index(handler.servers, uri)
					handler.servers = slices.Delete(handler.servers, idx, idx+1)
				}
			}

			handler.lock.Unlock()
		}

		time.Sleep(checkDelay)
	}

	fmt.Println("end check loop")
}

func (this *DiscoveryHandler) deleteServer(uri string) {
	this.lock.Lock()

	delete(this.timestamps, uri)

	idx := slices.Index(this.servers, uri)
	this.servers = slices.Delete(this.servers, idx, idx+1)

	this.lock.Unlock()

}
func convertToURI(addr *net.UDPAddr, port int) string {
	host := addr.IP.String()

	if addr.IP.To4() == nil && addr.IP.To16() != nil {
		host = fmt.Sprintf("[%s]", host)
	}

	return fmt.Sprintf("%s:%d", host, port)
}

func poll() {
	for !stopping.Load() {
		buf := make([]byte, 1024)

		if len, remoteAddr, err := connection.ReadFromUDP(buf); err != nil {
			fmt.Println("Error reading:", err)
			continue
		} else {
			var message Message

			if err := json.Unmarshal(buf[:len], &message); err != nil {
				fmt.Println("Error unmarshalling:", err)
				continue
			}

			handlersLock.Lock()

			handler, found := handlers[message.Cluster]

			if !found {
				handler = &DiscoveryHandler{lock: &sync.RWMutex{}, timestamps: make(map[string]int64)}
				handlers[message.Cluster] = handler
			}

			handlersLock.Unlock()
			handler.lock.Lock()

			var uri = convertToURI(remoteAddr, message.Port)

			if _, found := handler.timestamps[uri]; !found {
				handler.servers = append(handler.servers, uri)
			}

			handler.timestamps[uri] = time.Now().UnixMilli() + serverTTL.Milliseconds()
			handler.lock.Unlock()

			time.Sleep(pollDelay)
		}
	}

	fmt.Println("end poll loop")

}

func initDiscovery() {

	// Replace with your multicast or broadcast address
	addr := "224.0.0.1:9999" // Example multicast group

	// Resolve the UDP address
	udpAddr, err := net.ResolveUDPAddr("udp4", addr)
	if err != nil {
		panic(err)
	}

	// Listen on the group address and port
	if conn, err := net.ListenUDP("udp4", udpAddr); err != nil {
		panic(err)
	} else {
		connection = conn
	}

	go poll()
	go checkTimeouts()

}

func stopDiscovery() {
	stopping.Store(true)
}
