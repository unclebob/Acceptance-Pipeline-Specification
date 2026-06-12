package gherkin

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strings"
)

type Feature struct {
	Name       string     `json:"name"`
	Background []Step     `json:"background,omitempty"`
	Scenarios  []Scenario `json:"scenarios"`
}

type Scenario struct {
	Name     string              `json:"name"`
	Steps    []Step              `json:"steps"`
	Examples []map[string]string `json:"examples"`
}

type Step struct {
	Keyword    string   `json:"keyword"`
	Text       string   `json:"text"`
	Parameters []string `json:"parameters,omitempty"`
}

var parameterPattern = regexp.MustCompile(`<([A-Za-z0-9_]+)>`)

type section int

const (
	sectionNone section = iota
	sectionBackground
	sectionScenario
	sectionExamples
)

func Parse(r io.Reader) (Feature, error) {
	var feature Feature
	var current *Scenario
	var currentSection section
	var headers []string

	scanner := bufio.NewScanner(r)
	for lineNo := 1; scanner.Scan(); lineNo++ {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		switch {
		case strings.HasPrefix(line, "Feature:"):
			feature.Name = strings.TrimSpace(strings.TrimPrefix(line, "Feature:"))
			current = nil
			currentSection = sectionNone
			headers = nil

		case line == "Background:":
			current = nil
			currentSection = sectionBackground
			headers = nil

		case strings.HasPrefix(line, "Scenario Outline:"):
			scenario := Scenario{
				Name:     strings.TrimSpace(strings.TrimPrefix(line, "Scenario Outline:")),
				Examples: []map[string]string{},
			}
			feature.Scenarios = append(feature.Scenarios, scenario)
			current = &feature.Scenarios[len(feature.Scenarios)-1]
			currentSection = sectionScenario
			headers = nil

		case strings.HasPrefix(line, "Scenario:"):
			scenario := Scenario{
				Name:     strings.TrimSpace(strings.TrimPrefix(line, "Scenario:")),
				Examples: []map[string]string{},
			}
			feature.Scenarios = append(feature.Scenarios, scenario)
			current = &feature.Scenarios[len(feature.Scenarios)-1]
			currentSection = sectionScenario
			headers = nil

		case line == "Examples:":
			if current == nil {
				return Feature{}, fmt.Errorf("line %d: examples outside scenario", lineNo)
			}
			currentSection = sectionExamples
			headers = nil

		case strings.HasPrefix(line, "|"):
			if currentSection != sectionExamples || current == nil {
				continue
			}
			cells := parseTableRow(line)
			if headers == nil {
				headers = cells
				continue
			}
			if len(cells) != len(headers) {
				return Feature{}, fmt.Errorf("line %d: example row has %d cells, header has %d", lineNo, len(cells), len(headers))
			}
			example := make(map[string]string, len(headers))
			for i, header := range headers {
				example[header] = cells[i]
			}
			current.Examples = append(current.Examples, example)

		case isStep(line):
			step := parseStep(line)
			switch currentSection {
			case sectionBackground:
				feature.Background = append(feature.Background, step)
			case sectionScenario, sectionExamples:
				if current == nil {
					return Feature{}, fmt.Errorf("line %d: step outside scenario", lineNo)
				}
				current.Steps = append(current.Steps, step)
				currentSection = sectionScenario
			default:
				return Feature{}, fmt.Errorf("line %d: step outside background or scenario", lineNo)
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return Feature{}, err
	}
	if feature.Name == "" {
		return Feature{}, fmt.Errorf("missing feature declaration")
	}
	if feature.Scenarios == nil {
		feature.Scenarios = []Scenario{}
	}
	return feature, nil
}

func WriteJSON(w io.Writer, feature Feature) error {
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	return encoder.Encode(feature)
}

func parseTableRow(line string) []string {
	line = strings.TrimSpace(line)
	line = strings.TrimPrefix(line, "|")
	line = strings.TrimSuffix(line, "|")
	parts := strings.Split(line, "|")
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
	}
	return parts
}

func isStep(line string) bool {
	for _, keyword := range []string{"Given ", "When ", "Then ", "And "} {
		if strings.HasPrefix(line, keyword) {
			return true
		}
	}
	return false
}

func parseStep(line string) Step {
	parts := strings.SplitN(line, " ", 2)
	text := ""
	if len(parts) == 2 {
		text = strings.TrimSpace(parts[1])
	}
	return Step{
		Keyword:    parts[0],
		Text:       text,
		Parameters: parameters(text),
	}
}

func parameters(text string) []string {
	matches := parameterPattern.FindAllStringSubmatch(text, -1)
	result := make([]string, 0, len(matches))
	for _, match := range matches {
		result = append(result, match[1])
	}
	return result
}
