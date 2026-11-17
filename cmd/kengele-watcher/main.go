// Command kengele-watcher is the guardian-side watcher. It will follow an
// activist's encrypted check-ins and raise the alarm when one is missed.
// For now it only prints its version.
package main

import "fmt"

// version is set at build time with -ldflags "-X main.version=...".
var version = "0.0.0-dev"

func main() {
	fmt.Println("kengele-watcher", version)
}
