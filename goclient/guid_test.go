package artdb

import (
	"fmt"
	"testing"
	"time"
)

func TestGUID1(t *testing.T) {
	handler := GetHandler("defaultcluster")

	for handler.getServerCount() == 0 {
		time.Sleep(1 * time.Second)
	}

	client := CreateBatchClient("defaultcluster", "admin", "admin")
	fmt.Println("after getting batch client")

	drop := "drop table if exists duptest"
	create := "CREATE TABLE duptest (id GUID NOT NULL PRIMARY KEY, name varchar(255), color varchar(255))"
	createNameIndex := "CREATE UNIQUE INDEX name ON duptest(name)"
	createColorIndex := "CREATE INDEX color ON duptest(color)"

	if err := client.Execute(drop); err != nil {
		t.Errorf("drop failed %x", err)
		return
	}

	if err := client.Execute(create); err != nil {
		t.Errorf("create %x", err)
		return
	}
	if err := client.Execute(createNameIndex); err != nil {
		t.Errorf("create index failed %x", err)
		return
	}
	if err := client.Execute(createColorIndex); err != nil {
		t.Errorf("create color %x", err)
		return
	}

	guid1 := createGUID()

	if err := client.Execute("insert into duptest (id, name, color ) values (?, 'art', 'red')", guid1); err != nil {
		t.Errorf("inert failed %x", err)
		return
	}

	docs, err := client.ExecuteQuery("select * from duptest where id = ?", guid1)

	if err != nil {
		t.Errorf("select %x", err)
	}

	if docs.Size() != 1 {
		t.Errorf("wrong count %d", docs.Size())
	}
}
