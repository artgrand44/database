package artdb

import (
	"fmt"
	"testing"
	"time"
)

func TestIntegration(t *testing.T) {

	handler := GetHandler("defaultcluster")

	for handler.getServerCount() == 0 {
		time.Sleep(1 * time.Second)
	}

	client := CreateBatchClient("defaultcluster", "admin", "admin")

	client.batch = true

	drop := "drop table if exists insert"
	create := "CREATE TABLE insert (id int NOT NULL PRIMARY KEY, last varchar(255) NOT NULL, first varchar(255), count int)"
	createIndex := "CREATE INDEX name ON insert(first)"

	fmt.Println("call exec")
	client.Execute(drop)

	if err := client.Execute(create); err != nil {
		t.Errorf("create failed %x", err)
	}

	if err := client.Execute(createIndex); err != nil {
		t.Errorf("create index failed %s", err)
	}

	insert := "insert into insert (id, first, last, count) values ('1', 'art', 'grand', '1')"
	update := "update insert set count='2' where id = '1'"
	delete := "delete from insert where id = '1'"
	query := "select * from insert where id = '1'"

	if err := client.Execute(insert); err != nil {
		t.Errorf("insert failed %s", err)
	}

	set, err := client.ExecuteQuery(query)

	if err != nil {
		t.Errorf("query failed %s", err)
	}

	if set.Size() != 1 {
		t.Errorf("wrong doc count")
	}

	doc := set.iterator().next()

	if doc == nil {
		t.Errorf("Empty document set")
	}

	if doc.GetField("first") != "art" {
		t.Errorf("Wrong first name %s", doc.GetField("first"))
	}

	if doc.GetField("last") != "grand" {
		t.Errorf("Wrong last name")
	}

	if err := client.Execute(update); err != nil {
		t.Errorf("update failed %s", err)
	}

	set, err = client.ExecuteQuery(query)

	if err != nil {
		t.Errorf("query failed after update %x", err)
	}

	if set.Size() != 1 {
		t.Errorf("wrong doc count after update")
	}

	doc = set.iterator().next()

	if doc.GetField("count") != int64(2) {
		t.Errorf("wrong count field after update")
	}

	if err = client.Execute(delete); err != nil {
		t.Errorf("delete failed %x", err)
	}

	set, err = client.ExecuteQuery(query)

	if err != nil {
		t.Errorf("query failed after update %x", err)
	}

	if set.Size() != 0 {
		t.Errorf("wrong doc count after delete")
	}

	insertTemplate := "insert into insert (id, first, last, count) values (?, ?, ?, '1')"

	for idx := range 10 {
		if err := client.Execute(insertTemplate, idx, idx, idx); err != nil {
			t.Errorf("failure after insert with params %x", err)
		}
	}

	set, err = client.ExecuteQuery(query)

	if err != nil {
		t.Errorf("query failed after insert with params %x", err)
	}

	if set.Size() != 1 {
		t.Errorf("wrong doc count after insert with params")
	}

	if err = client.Execute(delete); err != nil {
		t.Errorf("second delete failed %x", err)
	}

	set, err = client.ExecuteQuery(query)

	if err != nil {
		t.Errorf("query failed after second delete %x", err)
	}

	if set.Size() != 0 {
		t.Errorf("wrong doc count after second delete")
	}

	batch := client.CreateBatch()

	batchTemplate := "insert into insert (id, first, last, count) values (?, ?, ?, ?)"

	for idx := range 10 {
		batch.add(batchTemplate, 100+idx, idx, idx, idx)
	}

	if err = client.ExecuteBatch(batch); err != nil {
		t.Errorf("execBatch failed %x", err)
	}

	set, err = client.ExecuteQuery("select * from insert where id = '101'")

	if err != nil {
		t.Errorf("query failed after batch %x", err)
	}

	if set.Size() != 1 {
		t.Errorf("wrong doc count after batcg params")
	}
}
