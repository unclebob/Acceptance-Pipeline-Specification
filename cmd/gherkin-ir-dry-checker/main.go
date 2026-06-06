package main

import (
	"encoding/json"
	"fmt"
	"os"

	"acceptance-pipeline-specification/internal/dry"
	"acceptance-pipeline-specification/internal/gherkin"
)

func main() {
	os.Exit(run())
}

func run() int {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: gherkin-ir-dry-checker <json-ir> <report-output>")
		return 2
	}

	input, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer input.Close()

	var feature gherkin.Feature
	if err := json.NewDecoder(input).Decode(&feature); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	output, err := os.Create(os.Args[2])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer output.Close()

	if err := dry.WriteJSON(output, dry.Analyze(feature)); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}
