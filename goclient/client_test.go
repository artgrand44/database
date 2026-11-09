package artdb

import (
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestClient(t *testing.T) {
	initAll()
	go startServer()

	go sendServerPings(t)
	time.Sleep(10 * time.Second)

	doWork(t, CreateBatchClient("router", "admin", "admin"))

}

func TestBatchClient(t *testing.T) {
	initAll()
	go startServer()

	go sendServerPings(t)
	time.Sleep(10 * time.Second)

	client := CreateClient("router", "admin", "admin", 0, 12*time.Hour)
	comp := &sync.WaitGroup{}

	for range 10 {
		comp.Add(1)
	}
	for range 10 {
		go doUpdates(t, comp, client)
	}

	comp.Wait()
	fmt.Println("batch test complete")

}
func doUpdates(t *testing.T, comp *sync.WaitGroup, client *Client) {
	for range 1 {
		cargs := ClientArgs{shard: "", sql: "insert a into b", parameters: make([]any, 0)}
		body := cargs.save()
		resp, err := client.router.batch("/db/execute", body)

		if err != nil {
			t.Errorf("error from execute %v", err)
		}

		result := string(resp)

		if result != "insert a into b" {
			t.Errorf("bad result %s", result)
		}
	}

	comp.Add(-1)
}
func doWork(t *testing.T, client *Client) {

	for range 1 {
		if err := client.Execute("insert a into b", 1, "a"); err != nil {
			t.Errorf("error from execute %v", err)
		}
	}
	/*
		for range 1 {
			if set, err := client.ExecuteQuery("select a from b"); err != nil {
				t.Errorf("error from query %v", err)
			} else {
				fmt.Println("select returned ", set)
			}
		}

		batch := client.CreateBatch()

		batch.add("insert a into b", 1, "a")

		if err := client.ExecuteBatch(batch); err != nil {
			t.Errorf("error from batch %v", err)
		}*/

}
