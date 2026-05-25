package mutation

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
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

func WriteManifestAndStamp(featurePath string, feature gherkin.Feature, report Report, implementationHash string) error {
	contentBytes, err := os.ReadFile(featurePath)
	if err != nil {
		return err
	}
	cleaned := stripMutationMetadata(string(contentBytes))
	stamp := hashString(cleaned)
	now := time.Now().UTC().Format(time.RFC3339)

	m := manifest{
		Version:            1,
		TestedAt:           now,
		FeatureName:        feature.Name,
		FeaturePath:        featurePath,
		BackgroundHash:     hashJSON(feature.Background),
		ImplementationHash: implementationHash,
		Scenarios:          make([]scenarioManifest, len(feature.Scenarios)),
	}
	for i, scenario := range feature.Scenarios {
		m.Scenarios[i] = scenarioManifest{
			Index:         i,
			Name:          scenario.Name,
			ScenarioHash:  hashJSON(scenario),
			MutationCount: len(Discover(gherkin.Feature{Scenarios: []gherkin.Scenario{scenario}})),
			Result:        report.Summary,
			TestedAt:      now,
		}
	}
	manifestBytes, err := json.Marshal(m)
	if err != nil {
		return err
	}

	var builder strings.Builder
	builder.WriteString("# mutation-stamp: sha256=")
	builder.WriteString(stamp)
	builder.WriteString("\n")
	builder.WriteString("# acceptance-mutation-manifest-begin\n")
	builder.WriteString("# ")
	builder.Write(manifestBytes)
	builder.WriteString("\n")
	builder.WriteString("# acceptance-mutation-manifest-end\n\n")
	builder.WriteString(strings.TrimLeft(cleaned, "\n"))
	return os.WriteFile(featurePath, []byte(builder.String()), 0o644)
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
