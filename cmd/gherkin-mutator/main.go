package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"acceptance-pipeline-specification/internal/gherkin"
	"acceptance-pipeline-specification/internal/mutation"
)

func main() {
	os.Exit(run())
}

func run() int {
	var featurePath string
	var workDir string
	var generatedDir string
	var workers int
	var timeoutText string
	var statusIntervalText string
	var level string
	var runnerWorkerText string
	var implementationHash string
	var implementationHashSet bool
	var jsonReport bool

	flags := flag.NewFlagSet(os.Args[0], flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	flags.StringVar(&featurePath, "feature", "features/a-feature.feature", "Gherkin feature file to parse and mutate")
	flags.StringVar(&workDir, "work-dir", "build/acceptance-mutation", "directory where mutation files are written")
	flags.StringVar(&generatedDir, "generated-dir", "", "directory containing generated acceptance tests")
	flags.IntVar(&workers, "workers", 1, "maximum mutation workers")
	flags.StringVar(&timeoutText, "timeout", "", "timeout for the full mutation run")
	flags.StringVar(&statusIntervalText, "status-interval", "30s", "periodic status interval")
	flags.StringVar(&level, "level", "hard", "differential mutation level: full, hard, or soft")
	flags.StringVar(&runnerWorkerText, "runner-worker", "", "persistent runner adapter command")
	flags.Func("implementation-hash", "override generated metadata implementation hash", func(value string) error {
		implementationHash = value
		implementationHashSet = true
		return nil
	})
	flags.BoolVar(&jsonReport, "json", false, "emit JSON report")
	if err := flags.Parse(os.Args[1:]); err != nil {
		return 2
	}
	if level != "full" && level != "hard" && level != "soft" {
		fmt.Fprintln(os.Stderr, "--level must be full, hard, or soft")
		return 2
	}
	if runnerWorkerText == "" {
		fmt.Fprintln(os.Stderr, "--runner-worker is required")
		return 2
	}

	file, err := os.Open(featurePath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer file.Close()

	feature, err := gherkin.Parse(file)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	ctx := context.Background()
	cancel := func() {}
	if timeoutText != "" {
		timeout, err := time.ParseDuration(timeoutText)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 2
		}
		ctx, cancel = context.WithTimeout(ctx, timeout)
	}
	defer cancel()

	statusInterval, err := time.ParseDuration(statusIntervalText)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 2
	}

	effectiveGeneratedDir := generatedDir
	if effectiveGeneratedDir == "" {
		effectiveGeneratedDir = workDir + "/generated"
	}
	hashOverride := ""
	if implementationHashSet {
		hashOverride = implementationHash
	}
	implementationHash = mutation.ResolveImplementationHash(effectiveGeneratedDir, featurePath, hashOverride)

	runner, err := mutation.NewWorkerPoolRunner(ctx, mutation.WorkerPoolConfig{
		Command: strings.Fields(runnerWorkerText),
		Workers: workers,
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer runner.Close()

	report, err := mutation.Run(ctx, mutation.Config{
		Feature:            feature,
		FeaturePath:        featurePath,
		WorkDir:            workDir,
		GeneratedDir:       generatedDir,
		Workers:            workers,
		Level:              level,
		ImplementationHash: implementationHash,
		StatusInterval:     statusInterval,
		Status:             writeStatus,
	}, runner)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
	}

	writeStamp := report.Summary.Survived == 0 && report.Summary.Errors == 0 && err == nil
	if manifestErr := mutation.WriteMutationMetadata(featurePath, feature, report, implementationHash, level, writeStamp); manifestErr != nil {
		fmt.Fprintln(os.Stderr, manifestErr)
		err = manifestErr
	}

	if jsonReport {
		if err := mutation.WriteJSONReport(os.Stdout, report); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
	} else {
		if err := mutation.WriteTextReport(os.Stdout, report); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
	}

	if report.Summary.Survived > 0 || report.Summary.Errors > 0 || err != nil {
		return 1
	}
	return 0
}

func writeStatus(status mutation.StatusSnapshot) {
	fmt.Fprintf(os.Stderr, "status elapsed=%s total=%d completed=%d running=%d killed=%d survived=%d errors=%d",
		status.Elapsed.Round(time.Millisecond),
		status.Total,
		status.Completed,
		status.Running,
		status.Killed,
		status.Survived,
		status.Errors,
	)
	if status.SkippedScenarios > 0 || status.SkippedMutations > 0 {
		fmt.Fprintf(os.Stderr, " skipped_scenarios=%d skipped_mutations=%d", status.SkippedScenarios, status.SkippedMutations)
	}
	fmt.Fprintln(os.Stderr)
}
