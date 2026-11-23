package artdb

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"strconv"
	"strings"
	"time"
)

type DBDriver struct {
}

type DBStatement struct {
	sql    string
	client *Client
}

type DBResult struct {
	insertId int64
	rowCount int64
}

type DBRows struct {
	documentSet *DocumentSet
	iter        *DocIterator
}

type DBConn struct {
	client *Client
}

type DBDriverContext struct {
}

type DBConnector struct {
	client *Client
}

func (this *DBConnector) Connect(context.Context) (driver.Conn, error) {
	return &DBConn{client: this.client}, nil
}

func (this *DBConnector) Driver() driver.Driver {
	return &DBDriver{}
}

func (this *DBConn) Prepare(sql string) (driver.Stmt, error) {
	return DBStatement{sql: sql, client: this.client}, nil
}

func (this DBStatement) Close() error {
	return nil
}

func (this DBStatement) NumInput() int {
	return -1
}
func (this DBStatement) Query(args []driver.Value) (driver.Rows, error) {
	params := make([]any, 0)

	for _, val := range args {
		params = append(params, val)
	}

	docs, err := this.client.ExecuteQuery(this.sql, params...)

	if err != nil {
		return nil, err
	} else {
		return &DBRows{documentSet: docs, iter: docs.iterator()}, nil
	}
}
func (this DBDriver) Open(name string) (driver.Conn, error) {
	client, err := createClientFromSQLName(name)

	if err != nil {
		return nil, err
	}

	return &DBConn{client: client}, nil
}
func (this *DBConn) Close() error {
	return nil
}

func (this *DBConn) Begin() (driver.Tx, error) {
	return nil, nil
}

func (this DBStatement) Exec(args []driver.Value) (driver.Result, error) {
	params := make([]any, 0)

	for _, val := range args {
		params = append(params, val)
	}

	if count, err := this.client.Execute(this.sql, params...); err != nil {
		return &DBResult{rowCount: int64(count)}, err
	}

	return &DBResult{}, nil
}

func (this *DBResult) LastInsertId() (int64, error) {
	return this.insertId, nil
}

func (this *DBResult) RowsAffected() (int64, error) {
	return this.rowCount, nil
}

func (this *DBRows) Columns() []string {
	result := make([]string, len(this.documentSet.columns))

	copy(result, this.documentSet.columns)

	return result
}

func (this *DBRows) convertValue(value any) (any, error) {
	switch v := value.(type) {
	case int:
		return int64(v), nil
	case int32:
		return int64(v), nil
	case int64:
		return v, nil
	case float32:
		return float64(v), nil
	case float64:
		return v, nil
	case string:
		return v, nil
	case bool:
		return v, nil
	case DBNull:
		return nil, nil
	default:
		return nil, errors.New("The column type is not supported")
	}
}
func (this *DBRows) Close() error {
	return nil
}

func (this *DBRows) Next(dest []driver.Value) error {
	if len(dest) > len(this.documentSet.columns) {
		return errors.New("The destionation list is too long")
	}

	doc1 := this.iter.next()

	if doc1 != nil {
		for idx, name := range this.Columns() {
			value, err := this.convertValue(doc1.GetField(name))

			if err == nil {
				dest[idx] = value
			} else {
				return err
			}
		}

		return nil
	} else {
		return io.EOF
	}

}

func createClientFromSQLName(name string) (*Client, error) {
	tokens := strings.Split(name, "-")

	if len(tokens) < 3 {
		return nil, errors.New("The name must have for format cluster:user:password:options")
	}

	cluster := tokens[0]
	user := tokens[1]
	password := tokens[2]

	batch := false
	cacheSize := 0
	ttl := 1 * time.Hour

	for _, token := range tokens[3:] {
		option := strings.Split(token, ":")

		if len(option) != 2 {
			return nil, errors.New("Invalid option " + token)
		}

		switch option[0] {
		case "batch":
			batch = (option[1] == "true")
		case "cache":
			if value, err := strconv.Atoi(option[2]); err != nil {
				return nil, errors.New("The cacne size must be an intefer")
			} else {
				cacheSize = value
			}
		case "ttl":
			if value, err := time.ParseDuration(option[2]); err != nil {
				return nil, errors.New("The cacne size must be an intefer")
			} else {
				ttl = value
			}
		default:
			return nil, errors.New("Invalid option " + token)
		}
	}

	client := CreateClient(cluster, user, password, cacheSize, ttl)
	client.batch = batch

	return client, nil
}

// This is the start of the open process.  The parameter is db-specific and can be used to open a Client

func (this *DBDriverContext) OpenConnector(name string) (driver.Connector, error) {
	client, err := createClientFromSQLName(name)

	if err != nil {
		return nil, err
	}
	return &DBConnector{client: client}, nil
}
