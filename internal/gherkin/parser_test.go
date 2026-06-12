package gherkin

import (
	"strings"
	"testing"
)

func TestParseFeatureWithBackgroundScenarioOutlineAndExamples(t *testing.T) {
	const src = `
Feature: Withdrawals

Background:
  Given an account balance of <balance>

Scenario Outline: Withdraw cash
  When the customer withdraws <amount>
  Then the remaining balance is <remaining>

Examples:
  | balance | amount | remaining |
  | 100     | 20     | 80        |
  | 50      | 5      | 45        |
`

	feature, err := Parse(strings.NewReader(src))
	if err != nil {
		t.Fatalf("Parse returned error: %v", err)
	}

	if feature.Name != "Withdrawals" {
		t.Fatalf("feature name = %q", feature.Name)
	}
	if len(feature.Background) != 1 {
		t.Fatalf("background count = %d", len(feature.Background))
	}
	if feature.Background[0].Text != "an account balance of <balance>" {
		t.Fatalf("background text = %q", feature.Background[0].Text)
	}
	if got := feature.Background[0].Parameters; len(got) != 1 || got[0] != "balance" {
		t.Fatalf("background parameters = %#v", got)
	}

	if len(feature.Scenarios) != 1 {
		t.Fatalf("scenario count = %d", len(feature.Scenarios))
	}
	scenario := feature.Scenarios[0]
	if scenario.Name != "Withdraw cash" {
		t.Fatalf("scenario name = %q", scenario.Name)
	}
	if len(scenario.Steps) != 2 {
		t.Fatalf("step count = %d", len(scenario.Steps))
	}
	if len(scenario.Examples) != 2 {
		t.Fatalf("example count = %d", len(scenario.Examples))
	}
	if scenario.Examples[0]["amount"] != "20" {
		t.Fatalf("first amount = %q", scenario.Examples[0]["amount"])
	}
}

func TestParseRejectsMissingFeature(t *testing.T) {
	_, err := Parse(strings.NewReader("Scenario: orphan\n  Given something\n"))
	if err == nil {
		t.Fatal("expected missing feature error")
	}
}

func TestParseRejectsExamplesOutsideScenario(t *testing.T) {
	_, err := Parse(strings.NewReader("Feature: Bad\n\nExamples:\n  | x |\n  | y |\n"))
	if err == nil {
		t.Fatal("expected examples outside scenario error")
	}
}

func TestParseRejectsExampleCellCountMismatch(t *testing.T) {
	const src = `
Feature: Bad
Scenario Outline: mismatch
  Given <x>
Examples:
  | x | y |
  | 1 |
`
	_, err := Parse(strings.NewReader(src))
	if err == nil {
		t.Fatal("expected cell count mismatch error")
	}
}
