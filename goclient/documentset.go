package artdb

import (
	"bytes"
	"fmt"
	"iter"
)

type DocumentSet struct {
	list      []*Document
	columns   []string
	file      string
	size      int
	remove    bool
	serverUri string
}

type DocIterator struct {
	currentIndex int
	set          *DocumentSet
}

const MAX_MEMORY = int(1000)

func (this *DocumentSet) AddDocument(doc *Document) {
	this.list = append(this.list, doc)
	this.size++
}

func (this *DocumentSet) Columns() iter.Seq[string] {
	return func(yield func(string) bool) {

		for _, col := range this.columns {
			if !yield(col) {
				return
			}
		}
	}
}
func (this *DocumentSet) Documents() iter.Seq[*Document] {
	return func(yield func(*Document) bool) {
		xiter := this.iterator()

		for doc := xiter.next(); doc != nil; doc = xiter.next() {
			if !yield(doc) {
				return
			}
		}
	}
}

func (this *DocumentSet) iterator() *DocIterator {
	return &DocIterator{currentIndex: -1, set: this}
}

func (this *DocIterator) next() *Document {
	if this.currentIndex+1 == len(this.set.list) && !this.refill() {
		return nil
	}

	this.currentIndex++
	return this.set.list[this.currentIndex]

}

func (this *DocIterator) refill() bool {
	if this.set.file != "" {
		path := fmt.Sprintf("%s/db/fetch?file=%s&index=%d&size=%d", this.set.serverUri, this.set.file, this.currentIndex, this.set.size)

		if data, err := post(path, "", "", make([]byte, 0)); err == nil {
			this.set.list = make([]*Document, 0)

			buff := bytes.NewBuffer(data)
			count := readInt(buff)

			for range count {
				this.set.list = append(this.set.list, (readObjectFrom(buff)).(*Document))
			}
		}

		this.currentIndex = 1
		return true
	}

	return false
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
		readByte(buffer)
		this.size = readInt(buffer)

		for idx := 0; idx < this.size; idx++ {
			obj := readObjectFrom(buffer)
			doc := obj.(*Document)

			this.list = append(this.list, doc)
		}
	}

	return this
}

func (this *DocumentSet) Size() int {
	return this.size
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
		writeByte(byte(ObjectTypeList), buffer)
		writeInt(len(this.list), buffer)

		for _, doc := range this.list {
			writeObjectTo(doc, buffer)
		}
	}

}
