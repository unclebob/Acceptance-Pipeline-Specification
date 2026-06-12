package dry

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"regexp"
	"sort"
	"strings"

	"acceptance-pipeline-specification/internal/gherkin"
)

type Report struct {
	SchemaVersion int       `json:"schema_version"`
	FeatureName   string    `json:"feature_name"`
	Summary       Summary   `json:"summary"`
	Findings      []Finding `json:"findings"`
}

type Summary struct {
	StepOccurrences int `json:"step_occurrences"`
	UniqueSteps     int `json:"unique_steps"`
	Findings        int `json:"findings"`
}

type Options struct {
	IncludeExact bool
}

type Finding struct {
	Kind               string   `json:"kind"`
	Confidence         string   `json:"confidence"`
	CanonicalCandidate string   `json:"canonical_candidate,omitempty"`
	PatternCandidate   string   `json:"pattern_candidate,omitempty"`
	Members            []Member `json:"members"`
	Reason             string   `json:"reason"`
	SuggestedAction    string   `json:"suggested_action"`
	Score              float64  `json:"score,omitempty"`
}

type Member struct {
	Text      string     `json:"text"`
	Locations []Location `json:"locations"`
}

type Location struct {
	Section       string `json:"section"`
	ScenarioIndex *int   `json:"scenario_index,omitempty"`
	ScenarioName  string `json:"scenario_name,omitempty"`
	StepIndex     int    `json:"step_index"`
	Keyword       string `json:"keyword"`
}

type stepEntry struct {
	text       string
	keyword    string
	location   Location
	normalized string
	tokens     map[string]bool
}

var parameterPattern = regexp.MustCompile(`<([A-Za-z0-9_]+)>`)
var nonTokenPattern = regexp.MustCompile(`[^a-z0-9]+`)

func Analyze(feature gherkin.Feature) Report {
	return AnalyzeWithOptions(feature, Options{})
}

func AnalyzeWithOptions(feature gherkin.Feature, options Options) Report {
	entries := collectSteps(feature)
	byText := membersByText(entries)
	findings := []Finding{}
	findings = append(findings, duplicateInScenarioFindings(entries)...)
	if options.IncludeExact {
		findings = append(findings, exactDuplicateFindings(byText)...)
	}
	findings = append(findings, placeholderVariantFindings(entries, byText)...)
	findings = append(findings, similarityFindings(byText)...)
	sortFindings(findings)

	return Report{
		SchemaVersion: 1,
		FeatureName:   feature.Name,
		Summary: Summary{
			StepOccurrences: len(entries),
			UniqueSteps:     len(byText),
			Findings:        len(findings),
		},
		Findings: findings,
	}
}

func WriteJSON(w io.Writer, report Report) error {
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	return encoder.Encode(report)
}

func collectSteps(feature gherkin.Feature) []stepEntry {
	var entries []stepEntry
	for i, step := range feature.Background {
		entries = append(entries, newStepEntry(step, Location{
			Section:   "background",
			StepIndex: i,
			Keyword:   step.Keyword,
		}))
	}
	for scenarioIndex, scenario := range feature.Scenarios {
		for stepIndex, step := range scenario.Steps {
			index := scenarioIndex
			entries = append(entries, newStepEntry(step, Location{
				Section:       "scenario",
				ScenarioIndex: &index,
				ScenarioName:  scenario.Name,
				StepIndex:     stepIndex,
				Keyword:       step.Keyword,
			}))
		}
	}
	return entries
}

func newStepEntry(step gherkin.Step, location Location) stepEntry {
	return stepEntry{
		text:       step.Text,
		keyword:    step.Keyword,
		location:   location,
		normalized: normalizePlaceholders(step.Text),
		tokens:     tokens(step.Text),
	}
}

func membersByText(entries []stepEntry) map[string]Member {
	result := map[string]Member{}
	for _, entry := range entries {
		member := result[entry.text]
		member.Text = entry.text
		member.Locations = append(member.Locations, entry.location)
		result[entry.text] = member
	}
	return result
}

func exactDuplicateFindings(byText map[string]Member) []Finding {
	var findings []Finding
	for _, member := range byText {
		if len(member.Locations) < 2 {
			continue
		}
		findings = append(findings, Finding{
			Kind:               "exact-duplicate",
			Confidence:         "high",
			CanonicalCandidate: member.Text,
			PatternCandidate:   exactPattern(member.Text),
			Members:            []Member{member},
			Reason:             "same step text appears more than once in the IR",
			SuggestedAction:    "Treat this as a vocabulary reuse audit; repeated use across scenarios is usually acceptable.",
		})
	}
	return findings
}

func duplicateInScenarioFindings(entries []stepEntry) []Finding {
	groups := map[string]Member{}
	for _, entry := range entries {
		key := scenarioDuplicateKey(entry)
		member := groups[key]
		member.Text = entry.text
		member.Locations = append(member.Locations, entry.location)
		groups[key] = member
	}

	var findings []Finding
	for _, member := range groups {
		if len(member.Locations) < 2 {
			continue
		}
		findings = append(findings, Finding{
			Kind:               "duplicate-in-scenario",
			Confidence:         "high",
			CanonicalCandidate: member.Text,
			PatternCandidate:   exactPattern(member.Text),
			Members:            []Member{member},
			Reason:             "same step text appears more than once in the same background or scenario",
			SuggestedAction:    "Review the scenario for an accidental repeated step; keep it only if the repeated execution is intentional.",
		})
	}
	return findings
}

func placeholderVariantFindings(entries []stepEntry, byText map[string]Member) []Finding {
	groups := map[string]map[string]bool{}
	for _, entry := range entries {
		if entry.normalized == entry.text {
			continue
		}
		if groups[entry.normalized] == nil {
			groups[entry.normalized] = map[string]bool{}
		}
		groups[entry.normalized][entry.text] = true
	}

	var findings []Finding
	for normalized, texts := range groups {
		if len(texts) < 2 {
			continue
		}
		members := membersForTexts(texts, byText)
		findings = append(findings, Finding{
			Kind:               "placeholder-variant",
			Confidence:         "high",
			CanonicalCandidate: canonicalFromNormalized(normalized),
			PatternCandidate:   regexFromNormalized(normalized),
			Members:            members,
			Reason:             "step text is identical after replacing placeholder names with generic slots",
			SuggestedAction:    "Review the feature wording and normalize the Gherkin if the different placeholder names do not add meaning.",
		})
	}
	return findings
}

func similarityFindings(byText map[string]Member) []Finding {
	texts := sortedTexts(byText)
	var findings []Finding
	seen := map[string]bool{}
	for i := range texts {
		for j := i + 1; j < len(texts); j++ {
			left := texts[i]
			right := texts[j]
			leftNorm := normalizePlaceholders(left)
			rightNorm := normalizePlaceholders(right)
			if leftNorm == rightNorm {
				continue
			}
			score := jaccard(tokens(leftNorm), tokens(rightNorm))
			if score < 0.45 {
				continue
			}
			kind := "possible-synonym"
			confidence := "medium"
			reason := "step texts share many non-placeholder tokens and may describe the same concept"
			if score >= 0.72 {
				kind = "near-duplicate"
				confidence = "medium"
				reason = "step texts are highly similar after placeholder normalization"
			}
			key := findingKey(kind, []string{left, right})
			if seen[key] {
				continue
			}
			seen[key] = true
			members := []Member{byText[left], byText[right]}
			sortMembers(members)
			findings = append(findings, Finding{
				Kind:            kind,
				Confidence:      confidence,
				Members:         members,
				Reason:          reason,
				SuggestedAction: "Review manually before editing; normalize the Gherkin only when the different wording is accidental drift.",
				Score:           round(score),
			})
		}
	}
	return findings
}

func normalizePlaceholders(text string) string {
	index := 0
	return parameterPattern.ReplaceAllStringFunc(text, func(string) string {
		index++
		return fmt.Sprintf("<_%d>", index)
	})
}

func canonicalFromNormalized(normalized string) string {
	index := 0
	return regexp.MustCompile(`<_[0-9]+>`).ReplaceAllStringFunc(normalized, func(string) string {
		index++
		if index == 1 {
			return "<value>"
		}
		return fmt.Sprintf("<value_%d>", index)
	})
}

func regexFromNormalized(normalized string) string {
	var builder strings.Builder
	builder.WriteString("^")
	last := 0
	for _, match := range regexp.MustCompile(`<_[0-9]+>`).FindAllStringIndex(normalized, -1) {
		builder.WriteString(regexp.QuoteMeta(normalized[last:match[0]]))
		builder.WriteString("(.+)")
		last = match[1]
	}
	builder.WriteString(regexp.QuoteMeta(normalized[last:]))
	builder.WriteString("$")
	return builder.String()
}

func exactPattern(text string) string {
	return "^" + regexp.QuoteMeta(text) + "$"
}

func tokens(text string) map[string]bool {
	lowered := strings.ToLower(parameterPattern.ReplaceAllString(text, " "))
	parts := strings.Fields(nonTokenPattern.ReplaceAllString(lowered, " "))
	result := map[string]bool{}
	for _, part := range parts {
		if len(part) <= 1 || isStopWord(part) {
			continue
		}
		result[part] = true
	}
	return result
}

func isStopWord(token string) bool {
	switch token {
	case "a", "an", "and", "are", "is", "of", "the", "to", "with", "in", "has", "have":
		return true
	default:
		return false
	}
}

func jaccard(left, right map[string]bool) float64 {
	if len(left) == 0 && len(right) == 0 {
		return 0
	}
	intersection := 0
	union := map[string]bool{}
	for token := range left {
		union[token] = true
		if right[token] {
			intersection++
		}
	}
	for token := range right {
		union[token] = true
	}
	return float64(intersection) / float64(len(union))
}

func membersForTexts(texts map[string]bool, byText map[string]Member) []Member {
	members := make([]Member, 0, len(texts))
	for text := range texts {
		members = append(members, byText[text])
	}
	sortMembers(members)
	return members
}

func sortedTexts(byText map[string]Member) []string {
	texts := make([]string, 0, len(byText))
	for text := range byText {
		texts = append(texts, text)
	}
	sort.Strings(texts)
	return texts
}

func sortMembers(members []Member) {
	sort.Slice(members, func(i, j int) bool {
		return members[i].Text < members[j].Text
	})
}

func sortFindings(findings []Finding) {
	sort.Slice(findings, func(i, j int) bool {
		leftRank := kindRank(findings[i].Kind)
		rightRank := kindRank(findings[j].Kind)
		if leftRank != rightRank {
			return leftRank < rightRank
		}
		if findings[i].Score != findings[j].Score {
			return findings[i].Score > findings[j].Score
		}
		return findingSortText(findings[i]) < findingSortText(findings[j])
	})
}

func kindRank(kind string) int {
	switch kind {
	case "duplicate-in-scenario":
		return 0
	case "exact-duplicate":
		return 1
	case "placeholder-variant":
		return 2
	case "near-duplicate":
		return 3
	default:
		return 4
	}
}

func findingSortText(finding Finding) string {
	if len(finding.Members) == 0 {
		return finding.Kind
	}
	texts := make([]string, 0, len(finding.Members))
	for _, member := range finding.Members {
		texts = append(texts, member.Text)
	}
	return strings.Join(texts, "\x00")
}

func findingKey(kind string, texts []string) string {
	sort.Strings(texts)
	return kind + "\x00" + strings.Join(texts, "\x00")
}

func scenarioDuplicateKey(entry stepEntry) string {
	if entry.location.Section == "background" {
		return "background\x00" + entry.text
	}
	scenarioIndex := -1
	if entry.location.ScenarioIndex != nil {
		scenarioIndex = *entry.location.ScenarioIndex
	}
	return fmt.Sprintf("scenario\x00%d\x00%s", scenarioIndex, entry.text)
}

func round(value float64) float64 {
	return math.Round(value*1000) / 1000
}
