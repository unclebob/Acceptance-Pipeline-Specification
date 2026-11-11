package heldout

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"acceptance-pipeline-specification/internal/gherkin"
)

func sampleFeature() gherkin.Feature {
	return gherkin.Feature{
		Name:       "Withdrawals",
		Background: []gherkin.Step{{Keyword: "Given", Text: "an account", Parameters: []string{"account"}}},
		Scenarios: []gherkin.Scenario{
			{Name: "withdraw cash"},
			{Name: "deposit cash"},
			{Name: "check balance"},
			{Name: "block overdraft"},
			{Name: "close account"},
		},
	}
}

func TestSplitDeterministicAndDisjoint(t *testing.T) {
	feature := sampleFeature()
	split, err := SplitScenarios(feature, 0.2, "seed-a")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(split.Visible.Scenarios) != 4 || len(split.Heldout.Scenarios) != 1 {
		t.Fatalf("expected 4 visible and 1 held-out, got %d and %d",
			len(split.Visible.Scenarios), len(split.Heldout.Scenarios))
	}
	seen := map[string]bool{}
	for _, s := range append(append([]gherkin.Scenario{}, split.Visible.Scenarios...), split.Heldout.Scenarios...) {
		if seen[s.Name] {
			t.Fatalf("scenario in both subsets: %s", s.Name)
		}
		seen[s.Name] = true
	}
	if len(seen) != 5 {
		t.Fatalf("expected all 5 scenarios across subsets, got %d", len(seen))
	}
	again, _ := SplitScenarios(feature, 0.2, "seed-a")
	if again.Heldout.Scenarios[0].Name != split.Heldout.Scenarios[0].Name {
		t.Fatalf("split is not deterministic")
	}
	if split.Visible.Name != "Withdrawals" || split.Heldout.Name != "Withdrawals" {
		t.Fatalf("feature name not preserved")
	}
	if len(split.Visible.Background) != 1 || len(split.Heldout.Background) != 1 {
		t.Fatalf("background must go to both subsets")
	}
}

func TestSplitRejectsInvalidInput(t *testing.T) {
	cases := []struct {
		name     string
		feature  gherkin.Feature
		fraction float64
		want     string
	}{
		{"duplicate names", gherkin.Feature{Scenarios: []gherkin.Scenario{{Name: "a"}, {Name: "a"}}}, 0.5, "unique"},
		{"too few scenarios", gherkin.Feature{Scenarios: []gherkin.Scenario{{Name: "a"}}}, 0.5, "at least 2"},
		{"fraction out of range", sampleFeature(), 1.0, "between 0 and 1"},
		{"fraction empties one side", gherkin.Feature{Scenarios: []gherkin.Scenario{{Name: "a"}, {Name: "b"}}}, 0.6, "each side"},
		{"blank name", gherkin.Feature{Scenarios: []gherkin.Scenario{{Name: "a"}, {Name: " "}, {Name: "c"}}}, 0.5, "non-blank"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := SplitScenarios(tc.feature, tc.fraction, "s")
			if err == nil {
				t.Fatalf("expected error containing %q, got nil", tc.want)
			}
			if !contains(err.Error(), tc.want) {
				t.Fatalf("expected error containing %q, got %q", tc.want, err.Error())
			}
		})
	}
}

func contains(s, sub string) bool {
	return strings.Contains(s, sub)
}

func TestGateComputesGap(t *testing.T) {
	allPass := ResultsFile{Results: []Result{
		{Name: "a", Status: "pass"}, {Name: "b", Status: "pass"},
		{Name: "c", Status: "pass"}, {Name: "d", Status: "pass"},
	}}
	twoFail := ResultsFile{Results: []Result{
		{Name: "e", Status: "pass"}, {Name: "f", Status: "fail"},
		{Name: "g", Status: "fail"}, {Name: "h", Status: "pass"},
	}}
	generalizing, err := Gate(allPass, allPass, 0.05)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if generalizing.Status != "pass" || generalizing.Gap != 0 {
		t.Fatalf("expected pass with gap 0, got %s %v", generalizing.Status, generalizing.Gap)
	}
	memorizing, err := Gate(allPass, twoFail, 0.05)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if memorizing.Status != "fail" || memorizing.Gap != 0.5 || memorizing.GapPP != 50 {
		t.Fatalf("expected fail with gap 0.5/50pp, got %s %v %v", memorizing.Status, memorizing.Gap, memorizing.GapPP)
	}
	threePass := ResultsFile{Results: []Result{
		{Name: "e", Status: "pass"}, {Name: "f", Status: "pass"},
		{Name: "g", Status: "pass"}, {Name: "h", Status: "error"},
	}}
	atLimit, _ := Gate(allPass, threePass, 0.25)
	if atLimit.Status != "pass" {
		t.Fatalf("gap exactly at the limit must pass, got %s", atLimit.Status)
	}
	if atLimit.Heldout.Error != 1 {
		t.Fatalf("errors must be counted separately")
	}
}

func TestGateRejectsInvalidInput(t *testing.T) {
	empty := ResultsFile{}
	if _, err := Gate(empty, empty, 0.05); err == nil || !contains(err.Error(), "empty") {
		t.Fatalf("expected empty error, got %v", err)
	}
	badStatus := ResultsFile{Results: []Result{{Name: "a", Status: "skipped"}}}
	if _, err := Gate(badStatus, badStatus, 0.05); err == nil || !contains(err.Error(), "invalid status") {
		t.Fatalf("expected invalid status error, got %v", err)
	}
	if _, err := Gate(sampleResults(), sampleResults(), -0.1); err == nil || !contains(err.Error(), "negative") {
		t.Fatalf("expected negative gap error, got %v", err)
	}
}

func sampleResults() ResultsFile {
	return ResultsFile{Results: []Result{{Name: "a", Status: "pass"}}}
}

func TestManifestVerifyRoundtrip(t *testing.T) {
	feature := sampleFeature()
	split, err := SplitScenarios(feature, 0.2, "seed-a")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	manifest := BuildManifest(feature, 0.2, "seed-a", split.Heldout)
	if manifest.Version != 1 || manifest.Feature != "Withdrawals" || len(manifest.Scenarios) != 1 {
		t.Fatalf("unexpected manifest: %+v", manifest)
	}
	report := VerifyManifest(manifest, split.Heldout)
	if report.Status != "verified" || report.Changed != 0 {
		t.Fatalf("expected verified, got %+v", report)
	}
	tampered := split.Heldout
	tampered.Scenarios[0].Steps = append(tampered.Scenarios[0].Steps, gherkin.Step{Keyword: "Then", Text: "sneaky"})
	report = VerifyManifest(manifest, tampered)
	if report.Status != "drift" || report.Changed != 1 {
		t.Fatalf("expected drift with changed=1, got %+v", report)
	}
	grown := split.Heldout
	grown.Scenarios = append(grown.Scenarios, gherkin.Scenario{Name: "new scenario"})
	report = VerifyManifest(manifest, grown)
	if report.Status != "drift" || report.Unknown != 1 {
		t.Fatalf("expected drift with unknown=1, got %+v", report)
	}
}

func TestCanonicalScenarioStringMatchesSpec(t *testing.T) {
	s := gherkin.Scenario{
		Name:     "withdraw",
		Steps:    []gherkin.Step{{Keyword: "When", Text: "withdraw 20"}},
		Examples: []map[string]string{{"balance": "100", "amount": "20"}},
	}
	want := "scenario\x00withdraw\x00step\x00When\x00withdraw 20\x00\x00example\x00amount=20\x00balance=100"
	if got := CanonicalScenarioString(s); got != want {
		t.Fatalf("canonical string mismatch:\n got %q\nwant %q", got, want)
	}
	if ScenarioFingerprint("a", s) == ScenarioFingerprint("b", s) {
		t.Fatalf("fingerprint must depend on the seed")
	}
}

func TestInsideGitRepo(t *testing.T) {
	repo := t.TempDir()
	if err := os.MkdirAll(filepath.Join(repo, ".git"), 0o755); err != nil {
		t.Fatal(err)
	}
	nested := filepath.Join(repo, "heldout", "h.json")
	if !InsideGitRepo(nested) {
		t.Fatalf("expected %s to be inside a git repo", nested)
	}
	if InsideGitRepo(filepath.Join(t.TempDir(), "h.json")) {
		t.Fatalf("expected temp dir to be outside any git repo")
	}
}
