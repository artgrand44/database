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

func TestMap(t *testing.T) {
	top := createDocument(false)
	bot := createDocument(false)

	top.SetField("int", 1)
	top.SetField("string", "top")
	top.SetField("sub", bot)
	bot.SetField("int", 2)
	bot.SetField("string", "bot")

	bytes := writeObject(top)
	out := readObject(bytes)

	result, ok := out.(Document)

	if !ok {
		t.Errorf("error converting output")
	}

	if result.GetField("string") != "top" {
		t.Errorf("Wrong string field")
	}

	sub := result.GetField("sub")

	subdoc, ok := sub.(Document)

	if !ok {
		t.Errorf("error converting subddoc")
	}

	if subdoc.GetField("string") != "bot" {
		t.Errorf("Wrong string field for bot")
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
