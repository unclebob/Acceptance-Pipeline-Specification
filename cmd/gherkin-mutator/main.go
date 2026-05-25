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
	var runnerText string
	var runnerWorkerText string
	var implementationHash string
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
	flags.StringVar(&runnerText, "runner", "", "simple runner adapter command")
	flags.StringVar(&runnerWorkerText, "runner-worker", "", "persistent runner adapter command")
	flags.StringVar(&implementationHash, "implementation-hash", "unknown", "implementation hash for manifest writing")
	flags.BoolVar(&jsonReport, "json", false, "emit JSON report")
	if err := flags.Parse(os.Args[1:]); err != nil {
		return 2
	}
	if level != "full" && level != "hard" && level != "soft" {
		fmt.Fprintln(os.Stderr, "--level must be full, hard, or soft")
		return 2
	}
	if runnerText == "" && runnerWorkerText == "" {
		fmt.Fprintln(os.Stderr, "--runner or --runner-worker is required")
		return 2
	}
	if runnerText != "" && runnerWorkerText != "" {
		fmt.Fprintln(os.Stderr, "--runner and --runner-worker are mutually exclusive")
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

	if statusInterval > 0 {
		fmt.Fprintf(os.Stderr, "status elapsed=0s total=%d completed=0 running=0 killed=0 survived=0 errors=0\n", len(mutation.Discover(feature)))
	}

	var runner mutation.Runner
	var closeRunner func() error
	if runnerWorkerText != "" {
		workerRunner, err := mutation.NewWorkerPoolRunner(ctx, mutation.WorkerPoolConfig{
			Command: strings.Fields(runnerWorkerText),
			Workers: workers,
		})
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		runner = workerRunner
		closeRunner = workerRunner.Close
	} else {
		runner = mutation.CommandRunner{Command: strings.Fields(runnerText)}
		closeRunner = func() error { return nil }
	}
	defer closeRunner()

	report, err := mutation.Run(ctx, mutation.Config{
		Feature:      feature,
		WorkDir:      workDir,
		GeneratedDir: generatedDir,
		Workers:      workers,
	}, runner)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
	}

	if statusInterval > 0 {
		completed := report.Summary.Killed + report.Summary.Survived + report.Summary.Errors
		fmt.Fprintf(os.Stderr, "status elapsed=done total=%d completed=%d running=0 killed=%d survived=%d errors=%d\n", report.Summary.Total, completed, report.Summary.Killed, report.Summary.Survived, report.Summary.Errors)
	}

	success := report.Summary.Survived == 0 && report.Summary.Errors == 0 && err == nil
	if success {
		if manifestErr := mutation.WriteManifestAndStamp(featurePath, feature, report, implementationHash); manifestErr != nil {
			fmt.Fprintln(os.Stderr, manifestErr)
			err = manifestErr
		}
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
