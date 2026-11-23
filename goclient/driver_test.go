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
	create := "CREATE TABLE insert (id int NOT NULL PRIMARY KEY, last varchar(255) NOT NULL, first varchar(255), count int, abc varchar(255), a int)"
	createIndex := "CREATE INDEX name ON insert(first)"
	insert := "insert into insert (id, first, last, count) values (?, 'art', 'grand', '1')"
	query := "select * from insert where first = 'art' order by id"
	upper := "select upper(first) as UP from insert where first = 'art'"

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
	var id int
	var last string
	var first string
	var count int
	var abc sql.NullString
	var a sql.NullInt64

	for rows.Next() {
		if err := rows.Scan(&a, &abc, &count, &first, &id, &last); err != nil {
			t.Errorf("error scan db %v", err)
		}

		if id != prevId+1 {
			t.Errorf("wrong id")
		}

		if a.Valid {
			t.Errorf("a is valid")
		}

		prevId = id
		fmt.Println(id, " ", last, " ", first, " ", count, " ", abc.Valid)
	}

	xrows, err := db.Query(upper)

	if xrows.Err() != nil {
		t.Errorf("error upper rows %v", rows.Err())
	}

	defer xrows.Close()

	for xrows.Next() {
		if err := xrows.Scan(&first); err != nil {
			t.Errorf("error upper scan db %v", err)
		}

		if first != "ART" {
			t.Errorf("Not upper case")
		}
	}
	stmt, err := db.Prepare("select * from insert where first = ? order by id")

	if err != nil {
		t.Errorf("error prepate %v", err)
		return
	}
	defer stmt.Close()

	// Execute the prepared statement, passing in an id value for the
	// parameter whose placeholder is ?

	rows, err = stmt.Query("art")

	if err != nil {
		t.Errorf("error stmt.prepare %v", err)
		return
	}

	prevId = -1

	for rows.Next() {

		if err := rows.Scan(&a, &abc, &count, &first, &id, &last); err != nil {
			t.Errorf("error scan db %v", err)
		}

		if id != prevId+1 {
			t.Errorf("wrong id")
		}

		prevId = id
		fmt.Println(id, " ", last, " ", first, " ", count, " ", abc.Valid)
	}

}
