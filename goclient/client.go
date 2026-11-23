package artdb

import (
	"bytes"
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

func CreateBatchClient(cluster string, user string, password string) *Client {
	client := CreateClient(cluster, user, password, 0, 60*time.Hour)

	client.batch = true
	return client
}

func CreateClient(cluster string, user string, password string, cacheSize int, cacheTTL time.Duration) *Client {
	lock.Lock()
	defer lock.Unlock()

	info := ClientInfo{cluster: cluster, user: user, password: password}

	router, success := routers[info]

	if !success {
		router = createRouter(cluster, GetHandler(cluster), user, password)
		routers[info] = router
	}

	if cacheSize == 0 {
		return &Client{router: router}
	}

	cache, success := caches[info]

	if !success {
		cache = newCache(cacheSize, cacheTTL)
		caches[info] = cache
	}

	return &Client{router: router, cache: cache}
}
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

func (this *Client) Execute(statement string, parameters ...any) (int, error) {
	if !checkParameters(parameters) {
		return 0, errors.New("Some of the paramaters have invalid types")
	}

	cargs := ClientArgs{shard: "", sql: statement, parameters: parameters}
	body := cargs.save()

	var lower = strings.ToLower(statement)
	var data []byte
	var err error

	if (strings.HasPrefix(lower, "insert") || strings.HasPrefix(lower, "update") || strings.HasPrefix(lower, "delete")) && !this.batch {
		data, err = this.router.batch("/db/execute", body)
	} else {
		data, err = this.router.post("/db/execute", body)
	}

	if err != nil {
		return 0, err
	}

	buf := bytes.NewBuffer(data)

	return readInt(buf), nil

}

func (this *Client) ExecuteQuery(statement string, parameters ...any) (*DocumentSet, error) {
	if !checkParameters(parameters) {
		return nil, errors.New("Some of the paramaters have invalid types")
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
		bytes, err = this.router.batch("/db/executeQuery", body)
	}

	if err != nil {
		return nil, err
	}

	out := readObject(bytes)

	result := out.(*DocumentSet)

	if this.cache != nil && !result.isPaged() {
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
