package mutation

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"testing"

	"acceptance-pipeline-specification/internal/gherkin"
)

func TestWorkerPoolRunnerUsesPersistentWorkerProtocol(t *testing.T) {
	if os.Getenv("ACCEPTANCE_TEST_WORKER") == "1" {
		runWorkerHelper()
		return
	}

	feature := gherkin.Feature{
		Name: "F",
		Scenarios: []gherkin.Scenario{{
			Name:     "S",
			Steps:    []gherkin.Step{{Keyword: "Then", Text: "x is <x>", Parameters: []string{"x"}}},
			Examples: []map[string]string{{"x": "1"}, {"x": "2"}},
		}},
	}

	runner, err := NewWorkerPoolRunner(context.Background(), WorkerPoolConfig{
		Command: []string{os.Args[0], "-test.run=TestWorkerPoolRunnerUsesPersistentWorkerProtocol"},
		Workers: 1,
		Env:     []string{"ACCEPTANCE_TEST_WORKER=1"},
	})
	if err != nil {
		t.Fatalf("NewWorkerPoolRunner returned error: %v", err)
	}
	defer runner.Close()

	report, err := Run(context.Background(), Config{Feature: feature, WorkDir: t.TempDir(), Workers: 2}, runner)
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	if report.Summary.Killed != 1 || report.Summary.Survived != 1 || report.Summary.Errors != 0 {
		t.Fatalf("summary = %#v", report.Summary)
	}
}

func runWorkerHelper() {
	scanner := bufio.NewScanner(os.Stdin)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var request WorkerRequest
		if err := json.Unmarshal(scanner.Bytes(), &request); err != nil {
			fmt.Fprintf(os.Stderr, "bad request: %v\n", err)
			os.Exit(1)
		}
		outcome := TestFailure
		if request.ID == "m2" {
			outcome = TestSuccess
		}
		_ = encoder.Encode(WorkerResponse{
			ID:       request.ID,
			Outcome:  outcome,
			Output:   "ok",
			Duration: 1,
		})
	}
	os.Exit(0)
}

func TestWorkerPoolRunnerReportsProtocolMismatch(t *testing.T) {
	runner := &WorkerPoolRunner{
		workers: []*workerProcess{{
			requests:  make(chan workerCall),
			closeDone: make(chan struct{}),
		}},
	}
	close(runner.workers[0].closeDone)

	result := runner.Run(context.Background(), Job{Mutation: Mutation{ID: "m1"}})
	if result.Outcome != InfrastructureError {
		t.Fatalf("outcome = %q", result.Outcome)
	}
}

var _ = exec.ErrDot
