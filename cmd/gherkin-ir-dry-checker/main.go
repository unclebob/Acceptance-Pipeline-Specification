package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"acceptance-pipeline-specification/internal/dry"
	"acceptance-pipeline-specification/internal/gherkin"
)

func main() {
	os.Exit(run())
}

func run() int {
	var includeExact bool
	flags := flag.NewFlagSet(os.Args[0], flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	flags.BoolVar(&includeExact, "include-exact", false, "include ordinary exact duplicate step text across scenarios")
	if err := flags.Parse(os.Args[1:]); err != nil {
		return 2
	}
	if flags.NArg() != 2 {
		fmt.Fprintln(os.Stderr, "usage: gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>")
		return 2
	}

	input, err := os.Open(flags.Arg(0))
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

	output, err := os.Create(flags.Arg(1))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer output.Close()

	report := dry.AnalyzeWithOptions(feature, dry.Options{IncludeExact: includeExact})
	if err := dry.WriteJSON(output, report); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}
