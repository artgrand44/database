package artdb

import (
	"fmt"
	"sync"
)

type Node struct {
	next  *Node
	prev  *Node
	key   string
	value *DocumentSet
}

type Cache struct {
	index map[string]*Node
	first *Node
	last  *Node
	limit int
	lock  sync.Mutex
}

func newCache(limit int) *Cache {
	return &Cache{index: make(map[string]*Node), limit: limit}
}

func buildCacheKey(sql string, args []any) string {
	return fmt.Sprintf("sql: %s args: %v", sql, args)
}

func (this *Cache) get(sql string, args []any) (*DocumentSet, bool) {
	this.lock.Lock()
	defer this.lock.Unlock()

	key := buildCacheKey(sql, args)

	if node, success := this.index[key]; success {
		this.modeToEnd(node)
		return node.value, success
	}

	return nil, false
}

func (this *Cache) add(sql string, args []any, set *DocumentSet) {
	this.lock.Lock()
	defer this.lock.Unlock()

	key := buildCacheKey(sql, args)

	if node, found := this.index[key]; found {
		node.value = set
		this.modeToEnd(node)
	} else {
		node := &Node{key: key, value: set}

		this.index[key] = node
		this.addToList(node)

		if len(this.index) > this.limit {
			oldest := this.first
			this.removeFromList(oldest)
			delete(this.index, oldest.key)
		}
	}
}

func (this *Cache) addToList(node *Node) {
	node.next = nil
	node.prev = this.last

	if this.first == nil {
		this.first = node
	}

	if this.last != nil {
		this.last.next = node
	}

}

func (this *Cache) modeToEnd(node *Node) {
	this.removeFromList(node)
	this.addToList(node)
}

func (this *Cache) removeFromList(node *Node) {
	if node.prev != nil {
		node.prev.next = node.next
	}

	if node.next != nil {
		node.next.prev = node.prev
	}

	if this.first == node {
		this.first = nil
	}

	if this.last == node {
		this.last = node.prev
	}
}
