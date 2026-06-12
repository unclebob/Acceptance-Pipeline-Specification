package main

import (
	"fmt"
	"os"

	"acceptance-pipeline-specification/internal/gherkin"
)

func main() {
	os.Exit(run())
}

func run() int {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: gherkin-parser <feature-file> <json-output>")
		return 2
	}

	input, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer input.Close()

	feature, err := gherkin.Parse(input)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	output, err := os.Create(os.Args[2])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer output.Close()

	if err := gherkin.WriteJSON(output, feature); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}
