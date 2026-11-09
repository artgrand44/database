package artdb

import (
	"bytes"
)

type Batch struct {
	database   string
	shard      string
	clientArgs []*ClientArgs
}

func (this *Batch) add(statement string, paramaters ...any) *Batch {
	this.clientArgs = append(this.clientArgs, &ClientArgs{sql: statement, parameters: paramaters})

	return this
}

func (this *Batch) load(buffer *bytes.Buffer) {
	this.clientArgs = make([]*ClientArgs, 0)

	for range readInt(buffer) {
		arg := &ClientArgs{}
		arg.load(buffer)
		this.clientArgs = append(this.clientArgs, arg)
	}
}

func (this *Batch) save() []byte {
	var buf bytes.Buffer

	writeInt(len(this.clientArgs), &buf)

	for _, arg := range this.clientArgs {
		writeBytes(arg.save(), &buf)
	}
	return buf.Bytes()

}
