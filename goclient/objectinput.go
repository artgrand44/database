package artdb

import (
	"bytes"
	"encoding/binary"
)

func readByte(buffer *bytes.Buffer) byte {
	result, _ := buffer.ReadByte()

	return result
}

func readBool(buffer *bytes.Buffer) bool {
	return readByte(buffer) == 1
}

func readBytes(count int, buffer *bytes.Buffer) []byte {
	bytes := make([]byte, count)

	buffer.Read(bytes)
	return bytes
}

func readInt(buffer *bytes.Buffer) int {
	data := readBytes(4, buffer)

	return int(binary.BigEndian.Uint32(data))
}

func readInt16(buffer *bytes.Buffer) int16 {
	data := readBytes(2, buffer)

	return int16(binary.BigEndian.Uint16(data))
}

func readInt64(buffer *bytes.Buffer) int64 {
	data := readBytes(8, buffer)

	return int64(binary.BigEndian.Uint64(data))
}

func readString(buffer *bytes.Buffer) string {
	count := readInt(buffer)

	return string(readBytes(count, buffer))
}

func readObject(data []byte) any {
	var buffer bytes.Buffer

	buffer.Write(data)

	return readObjectFrom(&buffer)
}

func readObjectFrom(buffer *bytes.Buffer) any {
	typ := ObjectType(readByte(buffer))

	switch typ {
	case ObjectTypeBoolean:
		return readBool(buffer)
	case ObjectTypeByte:
		return readByte(buffer)
	case ObjectTypeFloat:
		return 0.0
	case ObjectTypeInteger:
		return readInt(buffer)
	case ObjectTypeLong:
		return readInt64(buffer)
	case ObjectTypeShort:
		return readInt16(buffer)
	case ObjectTypeString:
		return readString(buffer)
	case ObjectTypeDocument:
		return createDocument(false).read(buffer)
	case ObjectTypeDocumentSet:
		return (&DocumentSet{}).read(buffer)
	case ObjectTypeGUID:
		return GUID{}.read(buffer)
	default:
		return nil
	}

}
