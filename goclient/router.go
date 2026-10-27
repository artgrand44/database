package artdb

type Router struct {
	handler  *DiscoveryHandler
	user     string
	password string
}
