package artdb

import (
	"bytes"
)

type ClientArgs struct {
	shard      string
	sql        string
	parameters []any
}

func (this *ClientArgs) read(buffer *bytes.Buffer) *ClientArgs {
	this.shard = readString(buffer)
	this.sql = readString(buffer)
	this.parameters = make([]any, 0)

	count := readInt(buffer)

	for range count {
		this.parameters = append(this.parameters, readObjectFrom(buffer))
	}

	return this
}

func (this *ClientArgs) write(buffer *bytes.Buffer) {
	writeString(this.shard, buffer)
	writeString(this.sql, buffer)
	writeInt(len(this.parameters), buffer)

	for _, entry := range this.parameters {
		writeObjectTo(entry, buffer)
	}

}
