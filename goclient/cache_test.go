package artdb

import (
	"testing"
)

func add(t *testing.T, cache *Cache, idx int) {
	doc := createDocument(false)
	set := DocumentSet{}

	doc.SetField("value", idx)
	set.addDocument(&doc)

	sql := "select doc where "
	args := []any{idx}

	if _, found := cache.get(sql, args); found {
		t.Errorf("Key is already there")
	}

	cache.add(sql, args, &set)

	if _, found := cache.get(sql, args); !found {
		t.Errorf("Key not added")
	}
}

func get(cache *Cache, idx int) bool {
	sql := "select doc where "
	args := []any{idx}

	_, found := cache.get(sql, args)

	return found
}
func update(t *testing.T, cache *Cache, idx int) {
	doc := createDocument(false)
	set := DocumentSet{}

	doc.SetField("newvalue", idx+1000)
	set.addDocument(&doc)

	sql := "select doc where "
	args := []any{idx}

	if _, found := cache.get(sql, args); !found {
		t.Errorf("Key is not there there")
	}

	cache.add(sql, args, &set)

	newSet, found := cache.get(sql, args)

	if !found {
		t.Errorf("Key not added")
	}

	newDoc := newSet.list[0]

	if newDoc.GetField("newvalue") != idx+1000 {
		t.Errorf("not updated")
	}

}
func TestCache(t *testing.T) {
	cache := newCache(100)

	for idx := range 100 {
		add(t, cache, idx)
	}

	if len(cache.index) != 100 {
		t.Errorf("write size after inserts")
	}

	for idx := range 100 {
		update(t, cache, idx)
	}

	if len(cache.index) != 100 {
		t.Errorf("write size after inserts")
	}

	for idx := 200; idx < 250; idx++ {
		add(t, cache, idx)
	}

	if len(cache.index) != 100 {
		t.Errorf("write size after inserts")
	}

	if get(cache, 0) {
		t.Errorf("First entry still in cache")
	}

	if !get(cache, 249) {
		t.Errorf("Recent entry not in cache")
	}
}
