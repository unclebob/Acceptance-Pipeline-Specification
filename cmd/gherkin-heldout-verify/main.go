package main

import (
	"encoding/json"
	"fmt"
	"os"

	"acceptance-pipeline-specification/internal/gherkin"
	"acceptance-pipeline-specification/internal/heldout"
)

func main() {
	os.Exit(run())
}

func run() int {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: gherkin-heldout-verify <manifest> <heldout-ir>")
		return 2
	}

	manifestData, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	var manifest heldout.Manifest
	if err := json.Unmarshal(manifestData, &manifest); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	irData, err := os.ReadFile(os.Args[2])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	var feature gherkin.Feature
	if err := json.Unmarshal(irData, &feature); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	report := heldout.VerifyManifest(manifest, feature)
	encoded, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Println(string(encoded))
	if report.Status == "verified" {
		return 0
	}
	return 1
}
