package mutation

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"

	"acceptance-pipeline-specification/internal/gherkin"
)

type scenarioManifest struct {
	Index         int     `json:"index"`
	Name          string  `json:"name"`
	ScenarioHash  string  `json:"scenario_hash"`
	MutationCount int     `json:"mutation_count"`
	Result        Summary `json:"result"`
	TestedAt      string  `json:"tested_at"`
}

type manifest struct {
	Version            int                `json:"version"`
	TestedAt           string             `json:"tested_at"`
	FeatureName        string             `json:"feature_name"`
	FeaturePath        string             `json:"feature_path"`
	BackgroundHash     string             `json:"background_hash"`
	ImplementationHash string             `json:"implementation_hash"`
	Scenarios          []scenarioManifest `json:"scenarios"`
}

type MutationMetadata struct {
	Stamp    string
	Manifest manifest
}

func ReadMutationMetadata(featurePath string) (MutationMetadata, bool) {
	contentBytes, err := os.ReadFile(featurePath)
	if err != nil {
		return MutationMetadata{}, false
	}
	return parseMutationMetadata(string(contentBytes))
}

func NewManifest(featurePath string, feature gherkin.Feature, report Report, implementationHash string) manifest {
	now := time.Now().UTC().Format(time.RFC3339)
	m := manifest{
		Version:            1,
		TestedAt:           now,
		FeatureName:        feature.Name,
		FeaturePath:        featurePath,
		BackgroundHash:     hashJSON(feature.Background),
		ImplementationHash: implementationHash,
		Scenarios:          []scenarioManifest{},
	}
	allMutations := Discover(feature)
	scenarioSummaries := scenarioSummaries(feature, report)
	for i, scenario := range feature.Scenarios {
		summary, ok := scenarioSummaries[i]
		if !ok || summary.Survived != 0 || summary.Errors != 0 {
			continue
		}
		m.Scenarios = append(m.Scenarios, scenarioManifest{
			Index:         i,
			Name:          scenario.Name,
			ScenarioHash:  hashJSON(scenario),
			MutationCount: mutationCountForScenario(allMutations, i),
			Result:        summary,
			TestedAt:      now,
		})
	}
	return m
}

func WriteManifestAndStamp(featurePath string, feature gherkin.Feature, report Report, implementationHash string) error {
	return WriteMutationMetadata(featurePath, feature, report, implementationHash, "hard", report.Summary.Survived == 0 && report.Summary.Errors == 0)
}

func WriteMutationMetadata(featurePath string, feature gherkin.Feature, report Report, implementationHash string, level string, writeStamp bool) error {
	contentBytes, err := os.ReadFile(featurePath)
	if err != nil {
		return err
	}
	previous, hasPrevious := parseMutationMetadata(string(contentBytes))
	cleaned := stripMutationMetadata(string(contentBytes))
	stamp := hashString(cleaned)
	m := NewManifest(featurePath, feature, report, implementationHash)
	if hasPrevious {
		mergeReusablePreviousScenarios(&m, previous.Manifest, feature, level)
	}
	manifestBytes, err := json.Marshal(m)
	if err != nil {
		return err
	}

	var builder strings.Builder
	if writeStamp {
		builder.WriteString("# mutation-stamp: sha256=")
		builder.WriteString(stamp)
		builder.WriteString("\n")
	}
	builder.WriteString("# acceptance-mutation-manifest-begin\n")
	builder.WriteString("# ")
	builder.Write(manifestBytes)
	builder.WriteString("\n")
	builder.WriteString("# acceptance-mutation-manifest-end\n\n")
	builder.WriteString(strings.TrimLeft(cleaned, "\n"))
	return os.WriteFile(featurePath, []byte(builder.String()), 0o644)
}

func FeatureStampValid(featurePath string) bool {
	contentBytes, err := os.ReadFile(featurePath)
	if err != nil {
		return false
	}
	metadata, ok := parseMutationMetadata(string(contentBytes))
	if !ok || metadata.Stamp == "" {
		return false
	}
	return metadata.Stamp == hashString(stripMutationMetadata(string(contentBytes)))
}

func mergeReusablePreviousScenarios(current *manifest, previous manifest, feature gherkin.Feature, level string) {
	existing := map[int]bool{}
	for _, entry := range current.Scenarios {
		existing[entry.Index] = true
	}
	allMutations := Discover(feature)
	for _, entry := range previous.Scenarios {
		if existing[entry.Index] {
			continue
		}
		if manifestEntryReusable(previous, *current, entry, level, feature, allMutations) {
			current.Scenarios = append(current.Scenarios, entry)
			existing[entry.Index] = true
		}
	}
}

func parseMutationMetadata(content string) (MutationMetadata, bool) {
	var metadata MutationMetadata
	lines := strings.Split(content, "\n")
	var manifestLines []string
	inManifest := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "# mutation-stamp: sha256=") {
			metadata.Stamp = strings.TrimPrefix(trimmed, "# mutation-stamp: sha256=")
			continue
		}
		if trimmed == "# acceptance-mutation-manifest-begin" {
			inManifest = true
			continue
		}
		if trimmed == "# acceptance-mutation-manifest-end" {
			inManifest = false
			continue
		}
		if inManifest {
			manifestLines = append(manifestLines, strings.TrimSpace(strings.TrimPrefix(trimmed, "#")))
		}
	}
	if len(manifestLines) == 0 {
		return metadata, metadata.Stamp != ""
	}
	manifestJSON := strings.Join(manifestLines, "")
	if err := json.Unmarshal([]byte(manifestJSON), &metadata.Manifest); err != nil {
		return MutationMetadata{}, false
	}
	return metadata, true
}

func stripMutationMetadata(content string) string {
	lines := strings.Split(content, "\n")
	result := make([]string, 0, len(lines))
	inManifest := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "# mutation-stamp:") {
			continue
		}
		if trimmed == "# acceptance-mutation-manifest-begin" {
			inManifest = true
			continue
		}
		if trimmed == "# acceptance-mutation-manifest-end" {
			inManifest = false
			continue
		}
		if inManifest {
			continue
		}
		result = append(result, line)
	}
	return strings.TrimLeft(strings.Join(result, "\n"), "\n")
}

func hashJSON(value any) string {
	bytes, _ := json.Marshal(value)
	return hashString(string(bytes))
}

func hashString(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func mutationCountForScenario(mutations []Mutation, scenarioIndex int) int {
	count := 0
	for _, mutation := range mutations {
		if mutation.Scenario == scenarioIndex {
			count++
		}
	}
	return count
}

func scenarioSummaries(feature gherkin.Feature, report Report) map[int]Summary {
	summaries := make(map[int]Summary, len(feature.Scenarios))
	if len(report.Results) == 0 && len(feature.Scenarios) == 1 && report.Summary.Total > 0 {
		summaries[0] = report.Summary
		return summaries
	}
	for _, result := range report.Results {
		scenarioIndex, err := scenarioIndexFromPath(result.Mutation.Path)
		if err != nil {
			continue
		}
		summary := summaries[scenarioIndex]
		summary.Total++
		switch result.Status {
		case Killed:
			summary.Killed++
		case Survived:
			summary.Survived++
		case Error:
			summary.Errors++
		}
		summaries[scenarioIndex] = summary
	}
	return summaries
}

func scenarioIndexFromPath(path string) (int, error) {
	const prefix = "$.scenarios["
	if !strings.HasPrefix(path, prefix) {
		return 0, fmt.Errorf("invalid mutation path %q", path)
	}
	remainder := strings.TrimPrefix(path, prefix)
	end := strings.Index(remainder, "]")
	if end < 0 {
		return 0, fmt.Errorf("invalid mutation path %q", path)
	}
	var index int
	if _, err := fmt.Sscanf(remainder[:end], "%d", &index); err != nil {
		return 0, err
	}
	return index, nil
}
