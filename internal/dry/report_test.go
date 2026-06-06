package dry

import (
	"strings"
	"testing"

	"acceptance-pipeline-specification/internal/gherkin"
)

func TestAnalyzeReportsPlaceholderVariants(t *testing.T) {
	feature := gherkin.Feature{
		Name: "Rooms",
		Scenarios: []gherkin.Scenario{{
			Name: "movement",
			Steps: []gherkin.Step{
				{Keyword: "Then", Text: "the player is in room <destination_room>", Parameters: []string{"destination_room"}},
				{Keyword: "And", Text: "the player is in room <expected_player_room>", Parameters: []string{"expected_player_room"}},
				{Keyword: "And", Text: "the player is in room <transport_room>", Parameters: []string{"transport_room"}},
			},
		}},
	}

	report := Analyze(feature)

	finding := findKind(report, "placeholder-variant")
	if finding == nil {
		t.Fatalf("placeholder-variant finding not found in %#v", report.Findings)
	}
	if finding.Confidence != "high" {
		t.Fatalf("confidence = %q", finding.Confidence)
	}
	if finding.CanonicalCandidate != "the player is in room <value>" {
		t.Fatalf("canonical = %q", finding.CanonicalCandidate)
	}
	if finding.PatternCandidate != `^the player is in room (.+)$` {
		t.Fatalf("pattern = %q", finding.PatternCandidate)
	}
	if len(finding.Members) != 3 {
		t.Fatalf("member count = %d", len(finding.Members))
	}
}

func TestAnalyzeDoesNotReportCrossScenarioExactDuplicatesByDefault(t *testing.T) {
	feature := gherkin.Feature{
		Name: "Messages",
		Background: []gherkin.Step{
			{Keyword: "Given", Text: "the player hears message <message>", Parameters: []string{"message"}},
		},
		Scenarios: []gherkin.Scenario{{
			Name: "pit",
			Steps: []gherkin.Step{
				{Keyword: "Then", Text: "the player hears message <message>", Parameters: []string{"message"}},
			},
		}},
	}

	report := Analyze(feature)

	finding := findKind(report, "exact-duplicate")
	if finding != nil {
		t.Fatalf("exact-duplicate finding should be omitted by default: %#v", report.Findings)
	}
}

func TestAnalyzeReportsExactDuplicateOccurrencesWhenIncluded(t *testing.T) {
	feature := gherkin.Feature{
		Name: "Messages",
		Background: []gherkin.Step{
			{Keyword: "Given", Text: "the player hears message <message>", Parameters: []string{"message"}},
		},
		Scenarios: []gherkin.Scenario{{
			Name: "pit",
			Steps: []gherkin.Step{
				{Keyword: "Then", Text: "the player hears message <message>", Parameters: []string{"message"}},
			},
		}},
	}

	report := AnalyzeWithOptions(feature, Options{IncludeExact: true})

	finding := findKind(report, "exact-duplicate")
	if finding == nil {
		t.Fatalf("exact-duplicate finding not found in %#v", report.Findings)
	}
	if len(finding.Members) != 1 || len(finding.Members[0].Locations) != 2 {
		t.Fatalf("members = %#v", finding.Members)
	}
}

func TestAnalyzeReportsDuplicateStepsWithinScenarioByDefault(t *testing.T) {
	feature := gherkin.Feature{
		Name: "Messages",
		Scenarios: []gherkin.Scenario{{
			Name: "pit",
			Steps: []gherkin.Step{
				{Keyword: "Then", Text: "the game is lost"},
				{Keyword: "And", Text: "the game is lost"},
			},
		}, {
			Name: "bat",
			Steps: []gherkin.Step{
				{Keyword: "Then", Text: "the game is lost"},
			},
		}},
	}

	report := Analyze(feature)

	finding := findKind(report, "duplicate-in-scenario")
	if finding == nil {
		t.Fatalf("duplicate-in-scenario finding not found in %#v", report.Findings)
	}
	if len(finding.Members) != 1 || len(finding.Members[0].Locations) != 2 {
		t.Fatalf("members = %#v", finding.Members)
	}
	if got := *finding.Members[0].Locations[0].ScenarioIndex; got != 0 {
		t.Fatalf("scenario index = %d", got)
	}
}

func TestAnalyzeReportsPossibleSynonym(t *testing.T) {
	feature := gherkin.Feature{
		Name: "Output",
		Scenarios: []gherkin.Scenario{{
			Name: "display",
			Steps: []gherkin.Step{
				{Keyword: "Then", Text: "the output contains line <message>", Parameters: []string{"message"}},
				{Keyword: "And", Text: "the output contains prompt <prompt>", Parameters: []string{"prompt"}},
			},
		}},
	}

	report := Analyze(feature)

	finding := findKind(report, "possible-synonym")
	if finding == nil {
		t.Fatalf("possible-synonym finding not found in %#v", report.Findings)
	}
	if finding.Score < 0.45 {
		t.Fatalf("score = %v", finding.Score)
	}
}

func TestWriteJSONIncludesSummary(t *testing.T) {
	report := Analyze(gherkin.Feature{
		Name: "F",
		Scenarios: []gherkin.Scenario{{
			Name: "S",
			Steps: []gherkin.Step{
				{Keyword: "Then", Text: "x is <x>", Parameters: []string{"x"}},
			},
		}},
	})

	var output strings.Builder
	if err := WriteJSON(&output, report); err != nil {
		t.Fatalf("WriteJSON returned error: %v", err)
	}
	if !strings.Contains(output.String(), `"schema_version": 1`) {
		t.Fatalf("output missing schema version: %s", output.String())
	}
	if !strings.Contains(output.String(), `"step_occurrences": 1`) {
		t.Fatalf("output missing step occurrences: %s", output.String())
	}
}

func findKind(report Report, kind string) *Finding {
	for i := range report.Findings {
		if report.Findings[i].Kind == kind {
			return &report.Findings[i]
		}
	}
	return nil
}
