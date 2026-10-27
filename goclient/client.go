package artdb

import (
	"sync"
)

type Client struct {
	router *Router
	cache  *Cache
}

type ClientInfo struct {
	cluster  string
	user     string
	password string
}

var routers = make(map[ClientInfo]*Router)
var caches = make(map[ClientInfo]*Cache)
var lock sync.Mutex

func NewClient(cluster string, user string, password string) *Client {
	lock.Lock()
	defer lock.Unlock()

	info := ClientInfo{cluster: cluster, user: user, password: password}

	router, success := routers[info]

	if !success {
		router = &Router{handler: GetHandler(cluster), user: user, password: password}
		routers[info] = router
	}

	cache, success := caches[info]

	if !success {
		cache := newCache(1000)
		caches[info] = cache
	}

	return &Client{router: router, cache: cache}
}
