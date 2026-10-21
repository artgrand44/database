package artdb

import "bytes"

type DocumentSet struct {
	list      []*Document
	file      string
	size      int64
	remove    bool
	serverUri string
}

const MAX_MEMORY = int(1000)

func (this *DocumentSet) addDocument(doc *Document) {
	this.list = append(this.list, doc)
}

func (this *DocumentSet) read(buffer *bytes.Buffer) *DocumentSet {
	if readBool(buffer) {
		this.size = readInt64(buffer)
		this.file = readString(buffer)
		this.serverUri = readString(buffer)

		for idx := 0; idx < min(int(this.size), MAX_MEMORY); idx++ {
			this.list = append(this.list, (&Document{}).read(buffer))
		}

	} else {
		list := readList(buffer)

		for _, field := range list {
			this.list = append(this.list, field.(*Document))
		}
	}

	return this
}

func (this *DocumentSet) write(buffer *bytes.Buffer) {
	if this.file != "" {
		writeBool(true, buffer)
		writeInt64(this.size, buffer)
		writeString(this.file, buffer)
		writeString(this.serverUri, buffer)

		count := 0

		for _, doc := range this.list {
			writeObjectTo(doc, buffer)
			count++

			if count == MAX_MEMORY {
				break
			}

		}
	} else {
		writeObjectTo(this.list, buffer)
	}

}

func (this *DocumentSet) getSize() int {
	return size(this)
}
