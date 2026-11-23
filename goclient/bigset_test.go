package artdb

import (
	"fmt"
	"strconv"
	"testing"
	"time"
)

func TestBigSet(t *testing.T) {
	fmt.Println("Start TesstBigSet")

	handler := GetHandler("defaultcluster")

	for handler.getServerCount() == 0 {
		time.Sleep(1 * time.Second)
	}

	client := CreateBatchClient("defaultcluster", "admin", "admin")
	fmt.Println("after getting batch client")

	drop := "drop table if exists bigset"
	create := "CREATE TABLE bigset (id int NOT NULL PRIMARY KEY, first varchar(255), last varchar(255), count int)"
	insert := "insert into bigset (id, first, last, count ) values (?, 'art', ?, ?)"
	createIndex := "CREATE INDEX bigset ON bigset(count)"
	query := "select * from bigset where count < 5000 order by count"

	if _, err := client.Execute(drop); err != nil {
		t.Errorf("drop failed %x", err)
		return
	}

	if _, err := client.Execute(create); err != nil {
		t.Errorf("create %x", err)
		return
	}
	if _, err := client.Execute(createIndex); err != nil {
		t.Errorf("create index failed %x", err)
		return
	}

	for idx := range 2000 {
		if _, err := client.Execute(insert, idx, "last"+strconv.Itoa(idx), idx); err != nil {
			t.Errorf("inert failed %x", err)
			return
		}
	}

	set, err := client.ExecuteQuery(query)

	if err != nil {
		t.Errorf("select %x", err)
	}

	if set.Size() != 2000 {
		t.Errorf("wrong count %d", set.Size())
	}

	count := 0
	iter := set.iterator()

	for doc := iter.next(); doc != nil; doc = iter.next() {
		field := doc.GetField("count")

		if field != int64(count) {
			fmt.Println("doc ", doc)
			t.Error("bad count field ", field, " count = ", count)
		}

		count++
	}

}
