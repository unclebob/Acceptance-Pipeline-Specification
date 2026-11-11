package main

import (
	"encoding/json"
	"fmt"
	"os"

	"acceptance-pipeline-specification/internal/heldout"
)

func main() {
	os.Exit(run())
}

func run() int {
	if len(os.Args) < 3 || len(os.Args) > 4 {
		fmt.Fprintln(os.Stderr, "usage: gherkin-heldout-gate <visible-results> <heldout-results> [max-gap]")
		return 2
	}

	maxGap := heldout.DefaultMaxGap
	if len(os.Args) == 4 {
		if _, err := fmt.Sscan(os.Args[3], &maxGap); err != nil {
			fmt.Fprintln(os.Stderr, "invalid max gap:", os.Args[3])
			return 1
		}
	}

	var visible, heldoutResults heldout.ResultsFile
	if err := readResults(os.Args[1], &visible); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err := readResults(os.Args[2], &heldoutResults); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	report, err := heldout.Gate(visible, heldoutResults, maxGap)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	encoded, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Println(string(encoded))
	if report.Status == "pass" {
		return 0
	}
	return 1
}

func readResults(path string, out *heldout.ResultsFile) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, out)
}
