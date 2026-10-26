package artdb

import (
	"testing"
)

func TestInt(t *testing.T) {
	bytes := writeObject(25)

	if len(bytes) != 5 {
		t.Errorf("Wrong length for integer bytes %d", len(bytes))
	}

	if bytes[4] != 25 {
		t.Errorf("Wrong value for integer bytes ")
	}

	out := readObject(bytes)

	outInt, ok := out.(int)

	if !ok {
		t.Errorf("error converting output")
	}

	if outInt != 25 {
		t.Errorf("Wrong deserialized value for integer bytes ")
	}
}
func TestInt16(t *testing.T) {
	bytes := writeObject(int16(25))

	if len(bytes) != 3 {
		t.Errorf("Wrong length for integer bytes %d", len(bytes))
	}

	if bytes[2] != 25 {
		t.Errorf("Wrong value for integer bytes ")
	}

	out := readObject(bytes)

	outInt, ok := out.(int16)

	if !ok {
		t.Errorf("error converting output")
	}

	if outInt != 25 {
		t.Errorf("Wrong deserialized value for integer bytes ")
	}
}
func TestInt64(t *testing.T) {
	bytes := writeObject(int64(25))

	if len(bytes) != 9 {
		t.Errorf("Wrong length for integer bytes %d", len(bytes))
	}

	if bytes[8] != 25 {
		t.Errorf("Wrong value for integer bytes ")
	}

	out := readObject(bytes)

	outInt, ok := out.(int64)

	if !ok {
		t.Errorf("error converting output")
	}

	if outInt != 25 {
		t.Errorf("Wrong deserialized value for integer bytes ")
	}
}

func TestByte(t *testing.T) {
	bytes := writeObject(byte(25))

	if len(bytes) != 2 {
		t.Errorf("Wrong length for integer bytes %d", len(bytes))
	}

	if bytes[1] != 25 {
		t.Errorf("Wrong value for integer bytes ")
	}

	out := readObject(bytes)

	outInt, ok := out.(byte)

	if !ok {
		t.Errorf("error converting output")
	}

	if outInt != 25 {
		t.Errorf("Wrong deserialized value for integer bytes ")
	}
}

func TestBool(t *testing.T) {
	bytes := writeObject(true)

	if len(bytes) != 2 {
		t.Errorf("Wrong length for integer bytes %d", len(bytes))
	}

	out := readObject(bytes)

	outInt, ok := out.(bool)

	if !ok {
		t.Errorf("error converting output")
	}

	if outInt != true {
		t.Errorf("Wrong deserialized value for integer bytes ")
	}
}

func TestString(t *testing.T) {
	bytes := writeObject("Hello art")
	out := readObject(bytes)

	outInt, ok := out.(string)

	if !ok {
		t.Errorf("error converting output")
	}

	if outInt != "Hello art" {
		t.Errorf("Wrong deserialized value for integer bytes ")
	}
}
func TestClientArgs(t *testing.T) {
	var args = ClientArgs{shard: "", sql: "select x from y", parameters: []any{"a", 1, 2}}

	bytes := writeObject(args)
	out := readObject(bytes)

	result, ok := out.(*ClientArgs)

	if !ok {
		t.Errorf("error converting output")
	}

	if result.shard != args.shard {
		t.Errorf("wrong shard")
	}

	if result.sql != args.sql {
		t.Errorf("wrong wrong sql")
	}

	if len(result.parameters) != len(args.parameters) {
		t.Errorf("wrong param length")
	}

	for idx := 0; idx < len(result.parameters); idx++ {
		if result.parameters[idx] != args.parameters[idx] {
			t.Errorf("wrong param")
		}
	}

}

func TestDocumentSet(t *testing.T) {
	set := &DocumentSet{}

	for idx := range 10 {
		doc := createDocument(false)

		doc.SetField("index", idx)
		set.addDocument(&doc)
	}

	bytes := writeObject(set)
	out := readObject(bytes)

	result, ok := out.(*DocumentSet)

	if !ok {
		t.Errorf("error converting output")
	}

	slot := 0

	for doc := range ListDocs(result) {
		field := doc.GetField("index")

		num, ok := field.(int)

		if !ok {
			t.Errorf("error converting output")
		}

		if slot != num {
			t.Errorf("wrong num")
		}

		slot++
	}

	if slot != 10 {
		t.Errorf("wrong size for docset")
	}
}
func TestGUID(t *testing.T) {
	guid := createGUID()
	bytes := writeObject(guid)

	out := readObject(bytes)

	result, ok := out.(GUID)

	if !ok {
		t.Errorf("error converting output for guid")
	}

	if result.time != guid.time {
		t.Errorf("Bad fime for guid")
	}

}
