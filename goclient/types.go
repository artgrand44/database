package artdb

type Blob struct {
	size       int
	bytes      []byte
	firstBlock int64
	onDisk     bool
}

type ObjectType byte

/*
Boolean,
	Byte, 1
	Character,2
	Double,3
	Float,4
	Integer,5
	Long,5
	Short,7
	String,8
	Document,9
	Key,10
	List,11
	ArrayList, 12
	LinkedList, 13
	DocumentSet, 14
	DBNull,
	Blob,
	GUID,
	Date,
	LogEntry,
*/

const (
	ObjectTypeUnknown   ObjectType = iota // 0
	ObjectTypeByte                        //1
	ObjectTypeCharacter                   //2
	ObjectTypeDouble                      //3
	ObjectTypeFloat                       //4
	ObjectTypeInteger                     //5
	ObjectTypeLong                        //6
	ObjectTypeShort                       //7
	ObjectTypeString                      //8
	ObjectTypeDocument                    //9
	ObjectTypeKey                         //10
	ObjectTypeList                        // 11
	ObjectTypeArrayList                   //12
	ObjectTypeLinkedList
	ObjectTypeDocumentSet
	ObjectTypeDBNull
	ObjectTypeBlob
	ObjectTypeGUID
	ObjectTypeDate
	ObjectTypeLogEntry
	ObjectTypeClientArgs
)
