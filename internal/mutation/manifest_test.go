package mutation

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"acceptance-pipeline-specification/internal/gherkin"
)

func TestWriteManifestAndStampUpdatesFeatureFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sample.feature")
	original := `Feature: Sample

Scenario Outline: S
  Then x is <x>

Examples:
  | x |
  | 1 |
`
	if err := os.WriteFile(path, []byte(original), 0o644); err != nil {
		t.Fatal(err)
	}

	feature := gherkin.Feature{
		Name: "Sample",
		Scenarios: []gherkin.Scenario{{
			Name:     "S",
			Steps:    []gherkin.Step{{Keyword: "Then", Text: "x is <x>", Parameters: []string{"x"}}},
			Examples: []map[string]string{{"x": "1"}},
		}},
	}
	report := Report{Summary: Summary{Total: 1, Killed: 1}}

	if err := WriteManifestAndStamp(path, feature, report, "impl-hash"); err != nil {
		t.Fatalf("WriteManifestAndStamp returned error: %v", err)
	}

	updatedBytes, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	updated := string(updatedBytes)
	for _, want := range []string{
		"# mutation-stamp: sha256=",
		"# acceptance-mutation-manifest-begin",
		`"feature_name":"Sample"`,
		`"implementation_hash":"impl-hash"`,
		"# acceptance-mutation-manifest-end",
		"Feature: Sample",
	} {
		if !strings.Contains(updated, want) {
			t.Fatalf("updated feature missing %q:\n%s", want, updated)
		}
	}

	if err := WriteManifestAndStamp(path, feature, report, "impl-hash"); err != nil {
		t.Fatalf("second WriteManifestAndStamp returned error: %v", err)
	}
	updatedBytes, _ = os.ReadFile(path)
	if got := strings.Count(string(updatedBytes), "acceptance-mutation-manifest-begin"); got != 1 {
		t.Fatalf("manifest block count = %d", got)
	}
}

func TestWriteMutationMetadataRecordsOnlyCleanScenarios(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sample.feature")
	if err := os.WriteFile(path, []byte(`Feature: Sample

Scenario Outline: Clean
  Then x is <x>

Examples:
  | x |
  | 1 |

Scenario Outline: Survived
  Then y is <y>

Examples:
  | y |
  | 2 |
`), 0o644); err != nil {
		t.Fatal(err)
	}
	feature := twoScenarioFeature()
	mutations := Discover(feature)
	report := Report{
		Summary: Summary{Total: 2, Killed: 1, Survived: 1},
		Results: []Result{
			makeResult(mutations[0], RunnerResult{Outcome: TestFailure}),
			makeResult(mutations[1], RunnerResult{Outcome: TestSuccess}),
		},
	}

	if err := WriteMutationMetadata(path, feature, report, "impl-a", "hard", false); err != nil {
		t.Fatalf("WriteMutationMetadata returned error: %v", err)
	}

	metadata, ok := ReadMutationMetadata(path)
	if !ok {
		t.Fatal("metadata was not readable")
	}
	if metadata.Stamp != "" {
		t.Fatalf("stamp = %q, want empty for partial run", metadata.Stamp)
	}
	if len(metadata.Manifest.Scenarios) != 1 {
		t.Fatalf("manifest scenario count = %d", len(metadata.Manifest.Scenarios))
	}
	if metadata.Manifest.Scenarios[0].Name != "Clean" {
		t.Fatalf("manifest scenario = %q", metadata.Manifest.Scenarios[0].Name)
	}
}

func TestDifferentialLevelsSelectReusableScenarios(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sample.feature")
	if err := os.WriteFile(path, []byte(`Feature: Sample

Scenario Outline: Clean
  Then x is <x>

Examples:
  | x |
  | 1 |

Scenario Outline: Dirty
  Then y is <y>

Examples:
  | y |
  | 2 |
`), 0o644); err != nil {
		t.Fatal(err)
	}
	feature := twoScenarioFeature()
	mutations := Discover(feature)
	report := Report{
		Summary: Summary{Total: 2, Killed: 1, Survived: 1},
		Results: []Result{
			makeResult(mutations[0], RunnerResult{Outcome: TestFailure}),
			makeResult(mutations[1], RunnerResult{Outcome: TestSuccess}),
		},
	}
	if err := WriteMutationMetadata(path, feature, report, "impl-a", "hard", false); err != nil {
		t.Fatal(err)
	}

	hardSame := runCounting(t, feature, path, "hard", "impl-a")
	if hardSame.Summary.Total != 1 || hardSame.Summary.SkippedScenarios != 1 || hardSame.Summary.SkippedMutations != 1 {
		t.Fatalf("hard same summary = %#v", hardSame.Summary)
	}

	hardChanged := runCounting(t, feature, path, "hard", "impl-b")
	if hardChanged.Summary.Total != 2 || hardChanged.Summary.SkippedScenarios != 0 {
		t.Fatalf("hard changed summary = %#v", hardChanged.Summary)
	}

	softChanged := runCounting(t, feature, path, "soft", "impl-b")
	if softChanged.Summary.Total != 1 || softChanged.Summary.SkippedScenarios != 1 || softChanged.Summary.SkippedMutations != 1 {
		t.Fatalf("soft changed summary = %#v", softChanged.Summary)
	}

	full := runCounting(t, feature, path, "full", "impl-a")
	if full.Summary.Total != 2 || full.Summary.SkippedScenarios != 0 {
		t.Fatalf("full summary = %#v", full.Summary)
	}
}

func twoScenarioFeature() gherkin.Feature {
	return gherkin.Feature{
		Name: "Sample",
		Scenarios: []gherkin.Scenario{
			{
				Name:     "Clean",
				Steps:    []gherkin.Step{{Keyword: "Then", Text: "x is <x>", Parameters: []string{"x"}}},
				Examples: []map[string]string{{"x": "1"}},
			},
			{
				Name:     "Dirty",
				Steps:    []gherkin.Step{{Keyword: "Then", Text: "y is <y>", Parameters: []string{"y"}}},
				Examples: []map[string]string{{"y": "2"}},
			},
		},
	}
}

func runCounting(t *testing.T, feature gherkin.Feature, path string, level string, implementationHash string) Report {
	t.Helper()
	report, err := Run(context.Background(), Config{
		Feature:            feature,
		FeaturePath:        path,
		WorkDir:            t.TempDir(),
		Level:              level,
		ImplementationHash: implementationHash,
		Workers:            1,
	}, RunnerFunc(func(_ context.Context, _ Job) RunnerResult {
		return RunnerResult{Outcome: TestFailure}
	}))
	if err != nil {
		t.Fatalf("Run returned error: %v", err)
	}
	return report
}
