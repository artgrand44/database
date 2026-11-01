package artdb

import (
	"bytes"
)

type ClientArgs struct {
	shard      string
	sql        string
	parameters []any
}

func (this *ClientArgs) load(buffer *bytes.Buffer) {
	this.shard = readString(buffer)
	this.sql = readString(buffer)
	this.parameters = make([]any, 0)

	for range readInt(buffer) {
		this.parameters = append(this.parameters, readObjectFrom(buffer))
	}
}

func (this *ClientArgs) save() []byte {
	var buffer bytes.Buffer

	writeString("*", &buffer)
	writeString(this.sql, &buffer)
	writeInt(len(this.parameters), &buffer)

	for _, entry := range this.parameters {
		writeObjectTo(entry, &buffer)
	}

	return buffer.Bytes()
}
