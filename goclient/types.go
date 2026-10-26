package artdb

type Blob struct {
	size       int
	bytes      []byte
	firstBlock int64
	onDisk     bool
}

type ObjectType byte

const (
	ObjectTypeUnknown ObjectType = iota // 0
	ObjectTypeBoolean
	ObjectTypeByte
	ObjectTypeCharacter
	ObjectTypeDouble
	ObjectTypeFloat
	ObjectTypeInteger
	ObjectTypeLong
	ObjectTypeShort
	ObjectTypeString
	ObjectTypeDocument
	ObjectTypeKey
	ObjectTypeList
	ObjectTypeDocumentSet
	ObjectTypeDBNull
	ObjectTypeBlob
	ObjectTypeGUID
	ObjectTypeDate
	ObjectTypeLogEntry
	ObjectTypeClientArgs
)
