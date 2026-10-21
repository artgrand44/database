package artdb

import (
	"bytes"
	"encoding/binary"
	"fmt"
)

func size(data any) int {
	return len(writeObject(data))
}

func writeByte(data byte, buffer *bytes.Buffer) {
	buffer.WriteByte(data)
}

func writeBool(data bool, buffer *bytes.Buffer) {
	if data {
		buffer.WriteByte(1)
	} else {
		buffer.WriteByte(0)
	}
}

func writeBytes(data []byte, buffer *bytes.Buffer) {
	buffer.Write(data)
}

func writeInt(data int, buffer *bytes.Buffer) {
	if err := binary.Write(buffer, binary.BigEndian, int32(data)); err != nil {
		fmt.Println(err)
	}

	fmt.Println("after")
}

func writeInt16(data int16, buffer *bytes.Buffer) {
	binary.Write(buffer, binary.BigEndian, data)
}

func writeInt64(data int64, buffer *bytes.Buffer) {
	if err := binary.Write(buffer, binary.BigEndian, data); err != nil {
		fmt.Println(err)
	}
}

func writeList(data []any, buffer *bytes.Buffer) {
	writeInt(len(data), buffer)

	for _, v := range data {
		writeObjectTo(v, buffer)
	}

}

func writeString(data string, buffer *bytes.Buffer) {
	bytes := []byte(data)

	writeInt(len(bytes), buffer)
	buffer.Write(bytes)
}

func writeObject(obj any) []byte {
	var buf bytes.Buffer

	writeObjectTo(obj, &buf)

	return buf.Bytes()

}

func writeObjectTo(obj any, buffer *bytes.Buffer) {
	if obj == nil {
		writeByte(byte(ObjectTypeDBNull), buffer)
		return
	}

	switch v := obj.(type) {
	case bool:
		writeByte(byte(ObjectTypeBoolean), buffer)
		writeBool(v, buffer)
	case byte:
		writeByte(byte(ObjectTypeByte), buffer)
		writeByte(v, buffer)
	case float32:

	case int:
		writeByte(byte(ObjectTypeInteger), buffer)
		writeInt(v, buffer)
	case int16:
		writeByte(byte(ObjectTypeShort), buffer)
		writeInt16(v, buffer)
	case int64:
		writeByte(byte(ObjectTypeLong), buffer)
		writeInt64(v, buffer)
	case string:
		writeByte(byte(ObjectTypeString), buffer)
		writeString(v, buffer)
	case Document:
		writeByte(byte(ObjectTypeDocument), buffer)
		v.write(buffer)
	case *Document:
		writeByte(byte(ObjectTypeDocument), buffer)
		v.write(buffer)
	case DocumentSet:
		writeByte(byte(ObjectTypeDocumentSet), buffer)
		writeObjectTo(v, buffer)
	case *DocumentSet:
		writeByte(byte(ObjectTypeDocumentSet), buffer)
		v.write(buffer)
	case GUID:
		writeByte(byte(ObjectTypeGUID), buffer)
		v.write(buffer)
	case *GUID:
		writeByte(byte(ObjectTypeGUID), buffer)
		v.write(buffer)
	case []any:
		writeByte(byte(ObjectTypeList), buffer)
		writeList(v, buffer)
	default:
		fmt.Printf("Unknown type: %T\n", v)
	}

}
