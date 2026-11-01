package artdb

import (
	"errors"
	"strings"
	"sync"
	"time"
)

type Client struct {
	router   *Router
	cache    *Cache
	batch    bool
	database string
}

type ClientInfo struct {
	cluster  string
	user     string
	password string
}

var routers = make(map[ClientInfo]*Router)
var caches = make(map[ClientInfo]*Cache)
var lock sync.Mutex

func checkParameters(args []any) bool {
	for _, arg := range args {
		if !isSerializable(arg) {
			return false
		}
	}

	return true
}

func (this *Client) CreateBatch() *Batch {
	return &Batch{database: this.database, shard: "", clientArgs: make([]*ClientArgs, 0)}
}

func (this *Client) ExecuteBatch(batch *Batch) error {
	if batch == nil {
		return errors.New("The batch cannot be nil")
	}

	body := batch.save()

	_, err := this.router.post("/db/executeBatch", body)

	return err
}

func (this *Client) Execute(statement string, parameters ...any) error {
	if !checkParameters(parameters) {
		return errors.New("Some of the paramaters have invalid tyoes")
	}

	cargs := ClientArgs{shard: "", sql: statement, parameters: parameters}
	body := cargs.save()
	lower := strings.ToLower(statement)

	if (strings.HasPrefix(lower, "insert") || strings.HasPrefix(lower, "update") || strings.HasPrefix(lower, "delete")) && !this.batch {
		_, err := this.router.batch("/db/execute", body)

		return err
	} else {
		_, err := this.router.post("/db/execute", body)

		return err
	}
}

func (this *Client) ExecuteQuery(statement string, parameters ...any) (*DocumentSet, error) {
	if !checkParameters(parameters) {
		return nil, errors.New("Some of the paramaters have invalid tyoes")
	}

	if strings.HasPrefix(strings.ToLower(statement), "select") && this.cache != nil {
		set, found := this.cache.get(statement, parameters)

		if found {
			return set, nil
		}
	}

	var cargs = ClientArgs{shard: "", sql: statement, parameters: parameters}
	var body = cargs.save()
	var bytes []byte
	var err error

	if this.batch {
		bytes, err = this.router.post("/db/executeQuery", body)
	} else {
		bytes, err = this.router.batch("/db/execute", body)
	}

	if err != nil {
		return nil, err
	}

	out := readObject(bytes)

	result := out.(*DocumentSet)

	if this.cache != nil {
		this.cache.add(statement, parameters, result)
	}

	return result, nil

}

func init() {
	initAll()
}

func initAll() {
	initDiscovery()
	initRouter()
}

func NewBatchClient(cluster string, user string, password string) *Client {
	client := NewClient(cluster, user, password, 0, 60*time.Hour)

	client.batch = true
	return client
}

func NewClient(cluster string, user string, password string, cacheSize int, cacheTTL time.Duration) *Client {
	lock.Lock()
	defer lock.Unlock()

	info := ClientInfo{cluster: cluster, user: user, password: password}

	router, success := routers[info]

	if !success {
		router = &Router{handler: GetHandler(cluster), user: user, password: password}
		routers[info] = router
	}

	if cacheSize == 0 {
		return &Client{router: router}
	}

	cache, success := caches[info]

	if !success {
		cache := newCache(cacheSize, cacheTTL)
		caches[info] = cache
	}

	return &Client{router: router, cache: cache}
}
