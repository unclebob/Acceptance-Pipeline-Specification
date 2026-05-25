package mutation

import (
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
