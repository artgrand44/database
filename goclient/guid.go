package artdb

import (
	"bytes"
	"net"
	"sync/atomic"
	"time"
)

type GUID struct {
	time    int64
	counter int64
	address []byte
}

var counter atomic.Int64

func createGUID() GUID {
	var guid GUID

	ips, err := net.LookupIP("localhost")

	if err != nil {
		panic(err)
	}

	guid.time = time.Now().UnixMilli()
	guid.counter = counter.Add(1)
	guid.address = ips[0]

	return guid

}

func (this GUID) read(buffer *bytes.Buffer) GUID {
	this.time = readInt64(buffer)
	this.counter = readInt64(buffer)
	this.address = readBytes(readInt(buffer), buffer)

	return this
}

func (this *GUID) write(buffer *bytes.Buffer) {
	writeInt64(this.time, buffer)
	writeInt64(this.counter, buffer)
	writeInt(len(this.address), buffer)
	writeBytes(this.address, buffer)
}
