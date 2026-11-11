// Package heldout implements the held-out generalization barrier for the
// acceptance pipeline: a deterministic visible/held-out scenario split, a
// generalization-gap gate over acceptance results, and fingerprint-based
// drift detection for the held-out set.
package heldout

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"acceptance-pipeline-specification/internal/gherkin"
)

const (
	DefaultFraction = 0.2
	DefaultSeed     = "aps-heldout"
	DefaultMaxGap   = 0.05
)

// ValidStatuses is the closed set of acceptance result statuses.
var ValidStatuses = map[string]bool{"pass": true, "fail": true, "error": true}

func hashParts(parts ...string) string {
	h := sha256.Sum256([]byte(strings.Join(parts, "")))
	return hex.EncodeToString(h[:])
}

// CanonicalScenarioString builds the portable per-scenario fingerprint input.
// The Babashka implementation produces the same bytes. Fields are joined with
// NUL so no value can shift a field boundary. Example map keys are sorted.
func CanonicalScenarioString(s gherkin.Scenario) string {
	parts := []string{"scenario", s.Name}
	for _, step := range s.Steps {
		parts = append(parts, "step", step.Keyword, step.Text,
			strings.Join(step.Parameters, ","))
	}
	for _, row := range s.Examples {
		keys := make([]string, 0, len(row))
		for k := range row {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		cells := make([]string, 0, len(keys))
		for _, k := range keys {
			cells = append(cells, k+"="+row[k])
		}
		parts = append(parts, "example", strings.Join(cells, "\x00"))
	}
	return strings.Join(parts, "\x00")
}

// ScenarioFingerprint hashes a scenario together with the split seed.
func ScenarioFingerprint(seed string, s gherkin.Scenario) string {
	return hashParts(seed, "\x00", CanonicalScenarioString(s))
}

// Split carries the two disjoint feature subsets.
type Split struct {
	Visible gherkin.Feature
	Heldout gherkin.Feature
}

// SplitScenarios deterministically splits feature scenarios into visible and
// held-out subsets. Scenarios are ordered by sha256(seed + "\n" + name); the
// first ceil(n * fraction) become held-out. Original relative order is kept
// inside each subset. The background goes to both sides.
func SplitScenarios(feature gherkin.Feature, fraction float64, seed string) (Split, error) {
	if fraction <= 0 || fraction >= 1 {
		return Split{}, fmt.Errorf("heldout fraction must be between 0 and 1 exclusive: %v", fraction)
	}
	seen := make(map[string]bool, len(feature.Scenarios))
	for _, s := range feature.Scenarios {
		if strings.TrimSpace(s.Name) == "" {
			return Split{}, errors.New("every scenario needs a non-blank name")
		}
		if seen[s.Name] {
			return Split{}, fmt.Errorf("scenario names must be unique for a held-out split: %s", s.Name)
		}
		seen[s.Name] = true
	}
	n := len(feature.Scenarios)
	if n < 2 {
		return Split{}, fmt.Errorf("at least 2 scenarios are required for a held-out split, got %d", n)
	}
	heldoutCount := int(math.Ceil(float64(n) * fraction))
	if heldoutCount == 0 || heldoutCount == n {
		return Split{}, fmt.Errorf("heldout fraction must leave at least one scenario on each side: %d of %d", heldoutCount, n)
	}

	ordered := make([]gherkin.Scenario, n)
	copy(ordered, feature.Scenarios)
	sort.SliceStable(ordered, func(i, j int) bool {
		return hashParts(seed, "\n", ordered[i].Name) < hashParts(seed, "\n", ordered[j].Name)
	})
	heldoutNames := make(map[string]bool, heldoutCount)
	for _, s := range ordered[:heldoutCount] {
		heldoutNames[s.Name] = true
	}

	split := Split{}
	split.Visible.Name = feature.Name
	split.Heldout.Name = feature.Name
	split.Visible.Background = feature.Background
	split.Heldout.Background = feature.Background
	split.Visible.Scenarios = []gherkin.Scenario{}
	split.Heldout.Scenarios = []gherkin.Scenario{}
	for _, s := range feature.Scenarios {
		if heldoutNames[s.Name] {
			split.Heldout.Scenarios = append(split.Heldout.Scenarios, s)
		} else {
			split.Visible.Scenarios = append(split.Visible.Scenarios, s)
		}
	}
	return split, nil
}

// ScenarioFingerprintEntry is one held-out scenario fingerprint in a manifest.
type ScenarioFingerprintEntry struct {
	Name        string `json:"name"`
	Fingerprint string `json:"fingerprint"`
}

// Manifest fingerprints a held-out set so a later verify can detect drift,
// substitution, or a refreshed corpus.
type Manifest struct {
	Version         int                        `json:"version"`
	Feature         string                     `json:"feature"`
	Seed            string                     `json:"seed"`
	HeldoutFraction float64                    `json:"heldout-fraction"`
	Scenarios       []ScenarioFingerprintEntry `json:"scenarios"`
}

// BuildManifest fingerprints every scenario of a held-out feature.
func BuildManifest(feature gherkin.Feature, fraction float64, seed string, heldout gherkin.Feature) Manifest {
	entries := make([]ScenarioFingerprintEntry, 0, len(heldout.Scenarios))
	for _, s := range heldout.Scenarios {
		entries = append(entries, ScenarioFingerprintEntry{
			Name:        s.Name,
			Fingerprint: ScenarioFingerprint(seed, s),
		})
	}
	return Manifest{
		Version:         1,
		Feature:         feature.Name,
		Seed:            seed,
		HeldoutFraction: fraction,
		Scenarios:       entries,
	}
}

// VerifyReport compares a manifest with the current held-out IR.
type VerifyReport struct {
	Version  int    `json:"version"`
	Status   string `json:"status"`
	Expected int    `json:"expected"`
	Actual   int    `json:"actual"`
	Missing  int    `json:"missing"`
	Unknown  int    `json:"unknown"`
	Changed  int    `json:"changed"`
}

// VerifyManifest reports whether the held-out feature still matches its
// manifest fingerprints.
func VerifyManifest(manifest Manifest, heldout gherkin.Feature) VerifyReport {
	expected := make(map[string]string, len(manifest.Scenarios))
	for _, e := range manifest.Scenarios {
		expected[e.Name] = e.Fingerprint
	}
	actual := make(map[string]string, len(heldout.Scenarios))
	for _, s := range heldout.Scenarios {
		actual[s.Name] = ScenarioFingerprint(manifest.Seed, s)
	}
	missing, unknown, changed := 0, 0, 0
	for name, fp := range expected {
		actualFP, ok := actual[name]
		if !ok {
			missing++
			continue
		}
		if actualFP != fp {
			changed++
		}
	}
	for name := range actual {
		if _, ok := expected[name]; !ok {
			unknown++
		}
	}
	status := "verified"
	if missing > 0 || unknown > 0 || changed > 0 {
		status = "drift"
	}
	return VerifyReport{
		Version:  1,
		Status:   status,
		Expected: len(expected),
		Actual:   len(actual),
		Missing:  missing,
		Unknown:  unknown,
		Changed:  changed,
	}
}

// Result is one scenario outcome in a results file.
type Result struct {
	Name   string `json:"name"`
	Status string `json:"status"`
}

// ResultsFile is the minimal results format produced by a runner adapter.
type ResultsFile struct {
	Results []Result `json:"results"`
}

// SetSummary counts scenario outcomes for one set.
type SetSummary struct {
	Total int `json:"total"`
	Pass  int `json:"pass"`
	Fail  int `json:"fail"`
	Error int `json:"error"`
}

// GateReport is the printed generalization-gap verdict.
type GateReport struct {
	Version int        `json:"version"`
	Gate    string     `json:"gate"`
	Visible SetSummary `json:"visible"`
	Heldout SetSummary `json:"heldout"`
	Gap     float64    `json:"gap"`
	GapPP   float64    `json:"gap-pp"`
	MaxGap  float64    `json:"max-gap"`
	Status  string     `json:"status"`
}

func summarize(results ResultsFile) SetSummary {
	s := SetSummary{Total: len(results.Results)}
	for _, r := range results.Results {
		switch r.Status {
		case "pass":
			s.Pass++
		case "fail":
			s.Fail++
		case "error":
			s.Error++
		}
	}
	return s
}

func checkResults(label string, results ResultsFile) error {
	if len(results.Results) == 0 {
		return fmt.Errorf("%s results are empty", label)
	}
	for _, r := range results.Results {
		if strings.TrimSpace(r.Name) == "" {
			return fmt.Errorf("%s results contain an entry without a name", label)
		}
		if !ValidStatuses[r.Status] {
			return fmt.Errorf("%s results entry has an invalid status: %s", label, r.Status)
		}
	}
	return nil
}

// GateReport computes the reward hacking estimate P(visible)-P(held-out)
// and the pass/fail verdict against maxGap.
func Gate(visible, heldout ResultsFile, maxGap float64) (GateReport, error) {
	if maxGap < 0 {
		return GateReport{}, fmt.Errorf("max gap must not be negative: %v", maxGap)
	}
	if err := checkResults("visible", visible); err != nil {
		return GateReport{}, err
	}
	if err := checkResults("heldout", heldout); err != nil {
		return GateReport{}, err
	}
	vis := summarize(visible)
	hel := summarize(heldout)
	gap := float64(vis.Pass)/float64(vis.Total) - float64(hel.Pass)/float64(hel.Total)
	status := "fail"
	if gap <= maxGap {
		status = "pass"
	}
	return GateReport{
		Version: 1,
		Gate:    "heldout-gap",
		Visible: vis,
		Heldout: hel,
		Gap:     gap,
		GapPP:   gap * 100,
		MaxGap:  maxGap,
		Status:  status,
	}, nil
}

// InsideGitRepo reports whether path sits inside a directory tree that
// contains a .git entry.
func InsideGitRepo(path string) bool {
	dir, err := filepath.Abs(filepath.Dir(path))
	if err != nil {
		return false
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, ".git")); err == nil {
			return true
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return false
		}
		dir = parent
	}
}

// WriteJSON writes a pretty-printed JSON document.
func WriteJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o644)
}
