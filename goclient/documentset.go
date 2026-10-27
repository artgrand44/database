package artdb

import (
	"bytes"
	"iter"
)

type DocumentSet struct {
	list      []*Document
	file      string
	size      int
	remove    bool
	serverUri string
}

const MAX_MEMORY = int(1000)

func (this *DocumentSet) addDocument(doc *Document) {
	this.list = append(this.list, doc)
}

func ListDocs(set *DocumentSet) iter.Seq[*Document] {
	return func(yield func(*Document) bool) {
		for _, v := range set.list {
			if !yield(v) {
				return
			}
		}
	}
}

func (this *DocumentSet) read(buffer *bytes.Buffer) *DocumentSet {
	if readBool(buffer) {
		this.size = readInt(buffer)
		this.file = readString(buffer)
		this.serverUri = readString(buffer)

		for idx := 0; idx < min(int(this.size), MAX_MEMORY); idx++ {
			this.list = append(this.list, (&Document{}).read(buffer))
		}

	} else {
		this.size = readInt(buffer)

		for idx := 0; idx < this.size; idx++ {
			this.list = append(this.list, (&Document{}).read(buffer))
		}
	}

	return this
}

func (this *DocumentSet) write(buffer *bytes.Buffer) {
	if this.file != "" {
		writeBool(true, buffer)
		writeInt(this.size, buffer)
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
		writeBool(false, buffer)
		for _, doc := range this.list {
			writeObjectTo(doc, buffer)
		}
	}

}
