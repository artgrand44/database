package artdb

import (
	"database/sql"
	"fmt"
	"testing"
	"time"
)

func TestDriver(t *testing.T) {

	fmt.Println("Start TestDriver")

	handler := GetHandler("defaultcluster")

	for handler.getServerCount() == 0 {
		time.Sleep(1 * time.Second)
	}

	sql.Register("mydriver", &DBDriver{})

	db, err := sql.Open("mydriver", "defaultcluster-admin-admin-batch:true")

	if err != nil {
		t.Errorf("error registering driver %v", err)
		return
	}

	defer db.Close()

	// Verify connection
	if err = db.Ping(); err != nil {
		t.Errorf("error pinging db %v", err)
		return
	}

	drop := "drop table if exists insert"
	create := "CREATE TABLE insert (id int NOT NULL PRIMARY KEY, last varchar(255) NOT NULL, first varchar(255), count int)"
	createIndex := "CREATE INDEX name ON insert(first)"
	insert := "insert into insert (id, first, last, count) values (?, 'art', 'grand', '1')"
	query := "select * from insert where first = 'art' order by id"

	if _, err = db.Exec(drop); err != nil {
		t.Errorf("error create %v", err)
		return
	}

	if _, err = db.Exec(create); err != nil {
		t.Errorf("error create %v", err)
		return
	}

	if _, err = db.Exec(createIndex); err != nil {
		t.Errorf("error create index %v", err)
		return
	}

	for idx := range 10 {
		if _, err = db.Exec(insert, idx); err != nil {
			t.Errorf("error inerrt %v", err)
			return
		}
	}

	// Execute query
	rows, err := db.Query(query)

	if err != nil {
		t.Errorf("error select %v", err)
	}

	if rows.Err() != nil {
		t.Errorf("error rows %v", rows.Err())
	}
	defer rows.Close()

	// Scan results

	prevId := -1

	fmt.Println("loop")
	for rows.Next() {
		var id int
		var last string
		var first string
		var count int

		if err := rows.Scan(&count, &first, &id, &last); err != nil {
			t.Errorf("error scan db %v", err)
		}

		if id != prevId+1 {
			t.Errorf("wrong id")
		}

		prevId = id
		fmt.Println(id, " ", last, " ", first, " ", count)
	}

}
