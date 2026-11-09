package artdb

import (
	"bytes"
)

type ClientArgs struct {
	database   string
	shard      string
	sql        string
	parameters []any
}

func (this *ClientArgs) load(buffer *bytes.Buffer) {
	this.database = readString(buffer)
	this.shard = readString(buffer)
	this.sql = readString(buffer)
	this.parameters = make([]any, 0)

	readByte(buffer)

	for range readInt(buffer) {
		this.parameters = append(this.parameters, readObjectFrom(buffer))
	}
}

func (this *ClientArgs) save() []byte {
	var buffer bytes.Buffer

	this.write(&buffer)

	return buffer.Bytes()
}

func (this *ClientArgs) write(buffer *bytes.Buffer) {
	writeString(this.database, buffer)
	writeString("*", buffer)
	writeString(this.sql, buffer)
	writeByte(byte(ObjectTypeList), buffer)
	writeInt(len(this.parameters), buffer)

	for _, entry := range this.parameters {
		writeObjectTo(entry, buffer)
	}
}
