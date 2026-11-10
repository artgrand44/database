package artdb

import (
	"testing"
)

func TestIterator(t *testing.T) {
	set := buildDocumentSet()
	size := set.size

	count := 0

	for range set.Documents() {
		count++
	}

	if count != size {
		t.Errorf("wrong size for range")
	}
	count = 0
	xiter := set.iterator()

	count = 0

	for xiter.next() != nil {
		count++
	}

	if count != size {
		t.Errorf("wrong size for iter")
	}

}
