package artdb

import "bytes"

type DocumentSet struct {
	list      []Document
	file      string
	size      int64
	remove    bool
	serverUri string
}

const MAX_MEMORY = 1000

func (this *DocumentSet) read(buffer *bytes.Buffer) *DocumentSet {
	return nil
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
