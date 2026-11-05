package artdb

import (
	"bytes"
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

func (this *DocumentSet) addDocument(doc *Document) {
	this.list = append(this.list, doc)
	this.size++
}

func (this *DocumentSet) iterator() DocIterator {
	return DocIterator{currentIndex: -1, set: this}
}

func ListDocs(set *DocumentSet) iter.Seq[*Document] {
	return func(yield func(*Document) bool) {
		iterator := set.iterator()

		for iterator.hasNext() {
			if !yield(iterator.next()) {
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
			obj := readObjectFrom(buffer)
			doc := obj.(*Document)

			this.list = append(this.list, doc)
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
		writeInt(len(this.list), buffer)

		for _, doc := range this.list {
			writeObjectTo(doc, buffer)
		}
	}

}

func (this *DocIterator) hasNext() bool {
	return this.currentIndex+1 < len(this.set.list)
}

func (this *DocIterator) next() *Document {
	this.currentIndex++
	return this.set.list[this.currentIndex]
}
