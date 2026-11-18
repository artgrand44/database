package artdb

import (
	"fmt"
	"math/rand"
	"os"
	"runtime/pprof"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type PounderContext struct {
	reads        atomic.Int32
	writes       atomic.Int32
	deletes      atomic.Int32
	deleteNextId atomic.Int32
	completions  atomic.Int32
	starts       *sync.WaitGroup
	goon         atomic.Bool
	startTime    time.Time
	items        int
	loops        int
	updaters     int
	threads      int
	t            *testing.T
	client       *Client
}

func testPounderCached(t *testing.T) {
	handler := GetHandler("defaultcluster")

	for handler.getServerCount() == 0 {
		time.Sleep(1 * time.Second)
	}

	batchClient := CreateBatchClient("defaultcluster", "admin", "admin")
	fmt.Println("after getting batch client")
	batchContext := createPounderContext(t, batchClient, 10000, 100000, 20, 150+20)

	batchContext.load()

	client := CreateClient("defaultcluster", "admin", "admin", 1000, 30*time.Second)
	context := createPounderContext(t, client, 10000, 10000, 20, 150+20)

	f, _ := os.Create("cpu.prof")
	pprof.StartCPUProfile(f)
	defer pprof.StopCPUProfile()

	context.startTest()
}

func testPounder(t *testing.T) {
	handler := GetHandler("defaultcluster")

	for handler.getServerCount() == 0 {
		time.Sleep(1 * time.Second)
	}

	batchClient := CreateBatchClient("defaultcluster", "admin", "admin")
	batchContext := createPounderContext(t, batchClient, 10000, 100000, 20, 150+20)

	batchContext.load()

	client := CreateClient("defaultcluster", "admin", "admin", 0, 5*time.Hour)
	context := createPounderContext(t, client, 10000, 100000, 20, 150+20)

	context.startTest()
	fmt.Println("Done")
}

func TestMini(t *testing.T) {
	fmt.Println("Start TestMini")

	handler := GetHandler("defaultcluster")

	for handler.getServerCount() == 0 {
		time.Sleep(1 * time.Second)
	}

	batchClient := CreateBatchClient("defaultcluster", "admin", "admin")
	fmt.Println("after getting batch client")
	batchContext := createPounderContext(t, batchClient, 10000, 1000, 20, 150+20)

	batchContext.load()

	client := CreateClient("defaultcluster", "admin", "admin", 1000, 30*time.Second)
	context := createPounderContext(t, client, 10000, 10000, 20, 150+20)

	f, _ := os.Create("cpu.prof")
	pprof.StartCPUProfile(f)
	defer pprof.StopCPUProfile()

	context.startTest()
}

func createPounderContext(t *testing.T, client *Client, items, loops, updaters, threads int) *PounderContext {
	var context PounderContext

	context.starts = &sync.WaitGroup{}
	context.starts.Add(threads)
	context.goon.Store(true)
	context.startTime = time.Now()
	context.t = t
	context.client = client
	context.items = items
	context.updaters = updaters
	context.loops = loops
	context.threads = threads

	return &context

}

func (this *PounderContext) delete(idx int32) {
	delete := "delete from pounder where ID = ?"

	if err := this.client.Execute(delete, getId(idx)); err != nil {
		this.t.Errorf("create failed %x", err)
	}
}

func (this *PounderContext) doSelects(index int) {
	nameQuery := "select * from pounder where FirstName = ?"
	idQuery := "select * from pounder where ID = ?"
	update := "update pounder set Age = '2' where ID = ? limit 1"

	for loop := 0; loop < this.loops && this.goon.Load(); loop++ {
		idx := this.getRandom()
		name := getName(idx)
		id := getId(idx)

		ids, err := this.client.ExecuteQuery(idQuery, id)

		if err != nil {
			this.t.Errorf("id query failed %x", err)
			return
		}

		if ids == nil {
			fmt.Println("No ids")
		}
		names, err := this.client.ExecuteQuery(nameQuery, name)

		if err != nil {
			this.t.Errorf("name query failed %x", err)
			return
		}

		this.reads.Add(2)

		if ids.size != 1 {
			this.t.Errorf("wrong id count %d for id %d", ids.size, id)
			return
		}

		doc := ids.iterator().next()

		if doc == nil || doc.GetField("ID") != int64(id) {
			this.t.Errorf("wrong id %d", id)
			return
		}

		if names.size != 1 {
			this.t.Errorf("wrong names count %d", names.size)
			return
		}

		doc = ids.iterator().next()

		if doc.GetField("FirstName") != name {
			this.t.Errorf("wrong name")
			return
		}

		if loop%10 == 0 {
			if err := this.client.Execute(update, index); err != nil {
				this.t.Errorf("bad update in doSearch %d", names.size)
				return
			}
			this.writes.Add(1)
		}

	}

}

func (this *PounderContext) doUpdates() {
	for loop := 0; loop < this.loops && this.goon.Load(); loop++ {
		id := -(this.deleteNextId.Add(1))

		this.insert(id)
		this.delete(id)
		this.writes.Add(1)
		this.deleteNextId.Add(1)
	}
}

func getId(idx int32) int32 {
	return idx
}

func getName(idx int32) string {
	return "Name" + strconv.Itoa(int(idx))
}

func (this *PounderContext) getRandom() int32 {
	const maxAttempts = 10000

	for i := 0; i < maxAttempts; i++ {
		val := rand.NormFloat64()*1 + 0
		if val >= 0.0 && val <= float64(this.items-1) {
			return int32(val)
		}
	}

	return int32(this.items / 2)
}

func (this *PounderContext) insert(idx int32) {
	insert := "insert into pounder (ID, FirstName, LastName, Age) values (?, ?, 'grand', '1')"

	if err := this.client.Execute(insert, getId(idx), getName(idx)); err != nil {
		this.t.Errorf("create failed %x", err)
	}
}

func (this *PounderContext) load() {
	drop := "drop table if exists pounder"
	create := "CREATE TABLE pounder (  ID int NOT NULL PRIMARY KEY, LastName varchar(255) NOT NULL default 'art', FirstName varchar(255), Age int)"
	createIndex := "CREATE INDEX firstname ON pounder(FirstName)"

	if err := this.client.Execute(drop); err != nil {
		fmt.Println("drop failed ", err)
	}

	if err := this.client.Execute(create); err != nil {
		this.t.Errorf("create failed %x", err)
	}

	if err := this.client.Execute(createIndex); err != nil {
		this.t.Errorf("create failed %x", err)
	}

	for idx := 0; idx < this.items; idx++ {
		this.insert(int32(idx))

		if idx%1000 == 0 {
			fmt.Println("Inserted ", idx)
		}
	}
}

func (this *PounderContext) runThread(index int) {
	this.starts.Done()

	if index < this.updaters {
		this.doUpdates()
	} else {
		this.doSelects(index)
	}

	count := (int32(this.threads) * 3) / 4
	comp := this.completions.Add(1)

	if comp >= count {
		this.goon.Store(false)
	}
}

func (this *PounderContext) startTest() {
	go this.statsLoop()

	for idx := range this.threads {
		go this.runThread(idx)
	}

	this.startTime = time.Now()

	for this.goon.Load() {
		time.Sleep(1 * time.Second)
	}

	this.stats()
	duration := time.Since(this.startTime)

	fmt.Println("Total time ", duration)

}

func (this *PounderContext) stats() {
	elapsed := time.Since(this.startTime)
	millis := int32(elapsed.Milliseconds())

	timePerRead := float64(millis) / float64(this.reads.Load())
	transactions := this.reads.Load() + this.writes.Load() + this.deletes.Load()
	tps := float64(transactions) / float64(millis) * 1000

	fmt.Printf("Per read %10.4f tps %d hits %d puts %d reads %d completed %d\n",
		timePerRead,
		int32(tps),
		getHitRatio(),
		totalPuts.Load(),
		this.writes.Load(),
		this.completions.Load())
}

func (this *PounderContext) statsLoop() {
	for this.goon.Load() {
		elapsed := time.Since(this.startTime)

		if elapsed.Seconds() > 20 {
			this.stats()
		}

		time.Sleep(5 * time.Second)
	}
}
