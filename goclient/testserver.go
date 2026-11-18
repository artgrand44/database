package artdb

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
)

var stopServer bool

func handler(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path

	switch path {
	case "/db/batch":
		var outBuffer bytes.Buffer
		body, _ := io.ReadAll(r.Body)

		buf := bytes.NewBuffer(body)

		count := readInt(buf)

		for range count {
			readString(buf)
			data := readBytes(readInt(buf), buf)

			args := ClientArgs{}

			args.load(bytes.NewBuffer(data))

			sqlBytes := []byte(args.sql)

			writeBool(true, &outBuffer)
			writeInt(len(sqlBytes), &outBuffer)
			writeBytes(sqlBytes, &outBuffer)
		}

		w.Write(outBuffer.Bytes())
	case "/db/execute":
	case "/db/executeQuery":
		set := buildDocumentSet()
		w.Write(writeObject(set))
	case "/db/executeBatch":

	case "/metrics":
	default:
		fmt.Fprintf(w, "success")
	}
}

func startServer() {
	http.DefaultServeMux = http.NewServeMux()
	http.HandleFunc("/", handler)

	if err := http.ListenAndServe(":8082", nil); err != nil {
		fmt.Println("Error starting server ", err)
	}

	fmt.Println("server started")
}
