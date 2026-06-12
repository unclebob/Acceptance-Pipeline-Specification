package mutation

import (
	"context"
	"sync"
	"testing"
	"time"

	"acceptance-pipeline-specification/internal/gherkin"
)

func TestDiscoverMutationsOnlyExampleCellsInStableOrder(t *testing.T) {
	feature := gherkin.Feature{
		Name: "Withdrawals",
		Background: []gherkin.Step{{
			Keyword:    "Given",
			Text:       "an account balance of <balance>",
			Parameters: []string{"balance"},
		}},
		Scenarios: []gherkin.Scenario{{
			Name: "Withdraw cash",
			Steps: []gherkin.Step{{
				Keyword:    "When",
				Text:       "the customer withdraws <amount>",
				Parameters: []string{"amount"},
			}},
			Examples: []map[string]string{{
				"balance":   "100",
				"amount":    "20",
				"remaining": "80",
			}},
		}},
	}

	mutations := Discover(feature)
	if len(mutations) != 3 {
		t.Fatalf("mutation count = %d", len(mutations))
	}

	assertMutation(t, mutations[0], "m1", "$.scenarios[0].examples[0].amount", "20")
	assertMutation(t, mutations[1], "m2", "$.scenarios[0].examples[0].balance", "100")
	assertMutation(t, mutations[2], "m3", "$.scenarios[0].examples[0].remaining", "80")
}

func TestApplyMutationDeepCopiesFeatureAndPreservesBackground(t *testing.T) {
	feature := gherkin.Feature{
		Name:       "F",
		Background: []gherkin.Step{{Keyword: "Given", Text: "shared <x>", Parameters: []string{"x"}}},
		Scenarios: []gherkin.Scenario{{
			Name:     "S",
			Steps:    []gherkin.Step{{Keyword: "Then", Text: "value is <x>", Parameters: []string{"x"}}},
			Examples: []map[string]string{{"x": "1"}},
		}},
	}
	m := Discover(feature)[0]

	mutated := Apply(feature, m)
	if feature.Scenarios[0].Examples[0]["x"] != "1" {
		t.Fatalf("base feature was modified: %#v", feature.Scenarios[0].Examples[0])
	}
	if mutated.Scenarios[0].Examples[0]["x"] == "1" {
		t.Fatalf("mutation was not applied: %#v", mutated.Scenarios[0].Examples[0])
	}
	if mutated.Background[0].Text != "shared <x>" {
		t.Fatalf("background changed: %#v", mutated.Background)
	}
}

func TestRunClassifiesRunnerOutcomes(t *testing.T) {
	feature := gherkin.Feature{
		Name: "F",
		Scenarios: []gherkin.Scenario{{
			Name:     "S",
			Steps:    []gherkin.Step{{Keyword: "Then", Text: "x is <x>", Parameters: []string{"x"}}},
			Examples: []map[string]string{{"x": "1"}, {"x": "2"}, {"x": "3"}},
		}},
	}

	runner := RunnerFunc(func(ctx context.Context, job Job) RunnerResult {
		switch job.Mutation.ID {
		case "m1":
			return RunnerResult{Outcome: TestFailure, Output: "failed"}
		case "m2":
			return RunnerResult{Outcome: TestSuccess, Output: "passed"}
		default:
			return RunnerResult{Outcome: InfrastructureError, Error: "boom"}
		}
	})

	report, err := Run(context.Background(), Config{Feature: feature, WorkDir: t.TempDir(), Workers: 2}, runner)
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	if report.Summary.Killed != 1 || report.Summary.Survived != 1 || report.Summary.Errors != 1 {
		t.Fatalf("summary = %#v", report.Summary)
	}
	if report.Results[0].Status != Killed || report.Results[1].Status != Survived || report.Results[2].Status != Error {
		t.Fatalf("statuses = %#v", report.Results)
	}
}

func TestRunEmitsPeriodicStatusWhileMutationsAreRunning(t *testing.T) {
	feature := gherkin.Feature{
		Name: "F",
		Scenarios: []gherkin.Scenario{{
			Name:     "S",
			Steps:    []gherkin.Step{{Keyword: "Then", Text: "x is <x>", Parameters: []string{"x"}}},
			Examples: []map[string]string{{"x": "1"}},
		}},
	}

	var mu sync.Mutex
	var snapshots []StatusSnapshot
	runnerStarted := make(chan struct{})
	releaseRunner := make(chan struct{})
	runner := RunnerFunc(func(ctx context.Context, job Job) RunnerResult {
		close(runnerStarted)
		select {
		case <-releaseRunner:
		case <-ctx.Done():
			return RunnerResult{Outcome: InfrastructureError, Error: ctx.Err().Error()}
		}
		return RunnerResult{Outcome: TestFailure}
	})

	done := make(chan struct{})
	var report Report
	var err error
	go func() {
		defer close(done)
		report, err = Run(context.Background(), Config{
			Feature:        feature,
			WorkDir:        t.TempDir(),
			Workers:        1,
			StatusInterval: 5 * time.Millisecond,
			Status: func(snapshot StatusSnapshot) {
				mu.Lock()
				defer mu.Unlock()
				snapshots = append(snapshots, snapshot)
			},
		}, runner)
	}()

	select {
	case <-runnerStarted:
	case <-time.After(time.Second):
		t.Fatal("runner did not start")
	}
	waitForStatus(t, &mu, &snapshots, func(snapshot StatusSnapshot) bool {
		return snapshot.Running == 1 && snapshot.Completed == 0
	})
	close(releaseRunner)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("run did not finish")
	}
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}
	if report.Summary.Killed != 1 {
		t.Fatalf("summary = %#v", report.Summary)
	}
	waitForStatus(t, &mu, &snapshots, func(snapshot StatusSnapshot) bool {
		return snapshot.Running == 0 && snapshot.Completed == 1 && snapshot.Killed == 1
	})
}

func assertMutation(t *testing.T, mutation Mutation, id, path, original string) {
	t.Helper()
	if mutation.ID != id || mutation.Path != path || mutation.Original != original {
		t.Fatalf("mutation = %#v, want id=%s path=%s original=%s", mutation, id, path, original)
	}
	if mutation.Mutated == original {
		t.Fatalf("mutated value equals original for %#v", mutation)
	}
}

func waitForStatus(t *testing.T, mu *sync.Mutex, snapshots *[]StatusSnapshot, matches func(StatusSnapshot) bool) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		for _, snapshot := range *snapshots {
			if matches(snapshot) {
				mu.Unlock()
				return
			}
		}
		mu.Unlock()
		time.Sleep(time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	t.Fatalf("status snapshot not found in %#v", *snapshots)
}
