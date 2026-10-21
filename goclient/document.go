package artdb

import "bytes"

type Document struct {
	zombie  bool
	entries map[string]any
}

func createDocument(zombie bool) Document {
	var doc Document

	doc.zombie = zombie
	doc.entries = make(map[string]any)

	return doc

}

func (this *Document) GetField(name string) any {
	return this.entries[name]
}

func (this *Document) SetField(name string, value any) {
	this.entries[name] = value
}

func (this *Document) read(buffer *bytes.Buffer) *Document {
	this.entries = make(map[string]any)

	count := readInt(buffer)

	for range count {
		key := readString(buffer)
		value := readObjectFrom(buffer)

		this.entries[key] = value
	}

	return this
}

func (this *Document) write(buffer *bytes.Buffer) {
	writeInt(len(this.entries), buffer)

	for key, value := range this.entries {
		writeString(key, buffer)
		writeObjectTo(value, buffer)
	}
}

func (this *Document) getSize() int {
	return size(this)
}
