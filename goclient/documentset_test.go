package artdb

import (
	"fmt"
	"testing"
)

func TestIterator(t *testing.T) {
	set := buildDocumentSet()
	size := set.size

	count := 0

	for doc := range ListDocs(set) {
		fmt.Println(doc)
		count++
	}

	if count != size {
		t.Errorf("wrong size for ranger")
	}

	xiter := set.iterator()

	count = 0

	for xiter.next() != nil {
		count++
	}

	if count != size {
		t.Errorf("wrong size for iter")
	}

}
