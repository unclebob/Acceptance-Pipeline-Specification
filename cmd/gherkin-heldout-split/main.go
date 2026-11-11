package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"acceptance-pipeline-specification/internal/gherkin"
	"acceptance-pipeline-specification/internal/heldout"
)

func main() {
	os.Exit(run())
}

func run() int {
	var manifestPath string
	var inRepoOK bool
	flags := flag.NewFlagSet(os.Args[0], flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	flags.StringVar(&manifestPath, "manifest", "", "write a held-out fingerprint manifest to this path")
	flags.BoolVar(&inRepoOK, "in-repo-ok", false, "allow writing the held-out IR inside a git repository")
	if err := flags.Parse(os.Args[1:]); err != nil {
		return 2
	}
	args := flags.Args()
	if len(args) < 3 || len(args) > 5 {
		fmt.Fprintln(os.Stderr, "usage: gherkin-heldout-split [options] <json-ir> <visible-output> <heldout-output> [heldout-fraction] [seed]")
		return 2
	}

	irPath, visiblePath, heldoutPath := args[0], args[1], args[2]
	fraction := heldout.DefaultFraction
	if len(args) > 3 {
		if _, err := fmt.Sscan(args[3], &fraction); err != nil {
			fmt.Fprintln(os.Stderr, "invalid heldout fraction:", args[3])
			return 1
		}
	}
	seed := heldout.DefaultSeed
	if len(args) > 4 && args[4] != "" {
		seed = args[4]
	}

	if !inRepoOK && heldout.InsideGitRepo(heldoutPath) {
		fmt.Fprintf(os.Stderr, "refusing to write held-out IR inside a git repository: %s (pass --in-repo-ok to override)\n", heldoutPath)
		return 1
	}

	input, err := os.Open(irPath)
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

	split, err := heldout.SplitScenarios(feature, fraction, seed)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	if err := heldout.WriteJSON(visiblePath, split.Visible); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if err := heldout.WriteJSON(heldoutPath, split.Heldout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if manifestPath != "" {
		manifest := heldout.BuildManifest(feature, fraction, seed, split.Heldout)
		if err := heldout.WriteJSON(manifestPath, manifest); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
	}
	return 0
}
