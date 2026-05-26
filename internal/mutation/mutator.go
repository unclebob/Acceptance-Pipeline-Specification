package mutation

import (
	"context"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"acceptance-pipeline-specification/internal/gherkin"
)

type Status string

const (
	Killed   Status = "killed"
	Survived Status = "survived"
	Error    Status = "error"
)

type RunnerOutcome string

const (
	TestSuccess         RunnerOutcome = "test_success"
	TestFailure         RunnerOutcome = "test_failure"
	InfrastructureError RunnerOutcome = "infrastructure_error"
)

type Mutation struct {
	ID          string
	Path        string
	Description string
	Original    string
	Mutated     string
	Scenario    int
	Example     int
	Key         string
}

type Config struct {
	Feature            gherkin.Feature
	FeaturePath        string
	WorkDir            string
	GeneratedDir       string
	Workers            int
	Level              string
	ImplementationHash string
	StatusInterval     time.Duration
	Status             func(StatusSnapshot)
}

type Job struct {
	Mutation     Mutation
	FeatureJSON  string
	GeneratedDir string
	WorkDir      string
}

type Runner interface {
	Run(context.Context, Job) RunnerResult
}

type RunnerFunc func(context.Context, Job) RunnerResult

func (f RunnerFunc) Run(ctx context.Context, job Job) RunnerResult {
	return f(ctx, job)
}

type RunnerResult struct {
	Outcome  RunnerOutcome `json:"outcome"`
	Output   string        `json:"output"`
	Error    string        `json:"error"`
	Duration int64         `json:"duration"`
}

type Summary struct {
	Total            int `json:"Total"`
	Killed           int `json:"Killed"`
	Survived         int `json:"Survived"`
	Errors           int `json:"Errors"`
	SkippedScenarios int `json:"SkippedScenarios,omitempty"`
	SkippedMutations int `json:"SkippedMutations,omitempty"`
}

type Report struct {
	Summary Summary  `json:"summary"`
	Results []Result `json:"results"`
}

type StatusSnapshot struct {
	Elapsed          time.Duration
	Total            int
	Completed        int
	Running          int
	Killed           int
	Survived         int
	Errors           int
	SkippedScenarios int
	SkippedMutations int
}

type Result struct {
	Mutation MutationView `json:"Mutation"`
	Status   Status       `json:"Status"`
	Output   string       `json:"Output"`
	Error    string       `json:"Error"`
	Duration int64        `json:"Duration"`
}

type MutationView struct {
	ID          string `json:"ID"`
	Path        string `json:"Path"`
	Description string `json:"Description"`
	Original    string `json:"Original"`
	Mutated     string `json:"Mutated"`
}

func Discover(feature gherkin.Feature) []Mutation {
	var mutations []Mutation
	for scenarioIndex, scenario := range feature.Scenarios {
		for exampleIndex, example := range scenario.Examples {
			keys := make([]string, 0, len(example))
			for key := range example {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				original := example[key]
				path := fmt.Sprintf("$.scenarios[%d].examples[%d].%s", scenarioIndex, exampleIndex, key)
				mutated := MutateValue(path, original)
				if mutated == original {
					continue
				}
				id := fmt.Sprintf("m%d", len(mutations)+1)
				mutations = append(mutations, Mutation{
					ID:          id,
					Path:        path,
					Description: fmt.Sprintf("%s: %s -> %s", path, original, mutated),
					Original:    original,
					Mutated:     mutated,
					Scenario:    scenarioIndex,
					Example:     exampleIndex,
					Key:         key,
				})
			}
		}
	}
	return mutations
}

func Apply(feature gherkin.Feature, mutation Mutation) gherkin.Feature {
	copied := cloneFeature(feature)
	copied.Scenarios[mutation.Scenario].Examples[mutation.Example][mutation.Key] = mutation.Mutated
	return copied
}

func Run(ctx context.Context, cfg Config, runner Runner) (Report, error) {
	if cfg.Workers < 1 {
		cfg.Workers = 1
	}
	if cfg.WorkDir == "" {
		cfg.WorkDir = "build/acceptance-mutation"
	}
	if cfg.GeneratedDir == "" {
		cfg.GeneratedDir = filepath.Join(cfg.WorkDir, "generated")
	}
	if cfg.Level == "" {
		cfg.Level = "hard"
	}

	mutations := Discover(cfg.Feature)
	skip := acceptedSkips(cfg, mutations)
	executableIndexes := make([]int, 0, len(mutations))
	for i, mutation := range mutations {
		if skip[mutation.Scenario] {
			continue
		}
		executableIndexes = append(executableIndexes, i)
	}
	report := Report{
		Summary: Summary{Total: len(executableIndexes)},
		Results: make([]Result, len(executableIndexes)),
	}
	for range skip {
		report.Summary.SkippedScenarios++
	}
	report.Summary.SkippedMutations = len(mutations) - len(executableIndexes)
	running := 0
	startedAt := time.Now()
	var mu sync.Mutex
	status := startStatusReporting(cfg, &mu, &report, &running, startedAt)
	defer status.stop()

	if len(executableIndexes) == 0 {
		return report, nil
	}

	if err := writeJSON(filepath.Join(cfg.WorkDir, "base", "feature.json"), cfg.Feature); err != nil {
		return Report{}, err
	}

	jobs := make(chan int)
	var wg sync.WaitGroup
	var firstErr error

	for worker := 0; worker < cfg.Workers; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for resultIndex := range jobs {
				mutation := mutations[executableIndexes[resultIndex]]
				mutationWorkDir := filepath.Join(cfg.WorkDir, "mutations", mutation.ID)
				featureJSON := filepath.Join(mutationWorkDir, "feature.json")
				mu.Lock()
				running++
				mu.Unlock()
				if err := writeJSON(featureJSON, Apply(cfg.Feature, mutation)); err != nil {
					mu.Lock()
					if firstErr == nil {
						firstErr = err
					}
					report.Results[resultIndex] = makeResult(mutation, RunnerResult{Outcome: InfrastructureError, Error: err.Error()})
					report.Summary.Errors++
					running--
					mu.Unlock()
					continue
				}

				result := runner.Run(ctx, Job{
					Mutation:     mutation,
					FeatureJSON:  featureJSON,
					GeneratedDir: cfg.GeneratedDir,
					WorkDir:      mutationWorkDir,
				})
				classified := makeResult(mutation, result)

				mu.Lock()
				report.Results[resultIndex] = classified
				switch classified.Status {
				case Killed:
					report.Summary.Killed++
				case Survived:
					report.Summary.Survived++
				case Error:
					report.Summary.Errors++
				}
				running--
				mu.Unlock()
			}
		}()
	}

	for i := range executableIndexes {
		select {
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return report, ctx.Err()
		case jobs <- i:
		}
	}
	close(jobs)
	wg.Wait()

	return report, firstErr
}

type statusReporter struct {
	done chan struct{}
	stop func()
}

func startStatusReporting(cfg Config, mu *sync.Mutex, report *Report, running *int, startedAt time.Time) statusReporter {
	if cfg.Status == nil || cfg.StatusInterval <= 0 {
		return statusReporter{stop: func() {}}
	}

	done := make(chan struct{})
	emit := func() {
		cfg.Status(snapshotStatus(mu, report, running, startedAt))
	}
	emit()
	go func() {
		ticker := time.NewTicker(cfg.StatusInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				emit()
			case <-done:
				return
			}
		}
	}()

	return statusReporter{
		done: done,
		stop: func() {
			close(done)
			emit()
		},
	}
}

func snapshotStatus(mu *sync.Mutex, report *Report, running *int, startedAt time.Time) StatusSnapshot {
	mu.Lock()
	defer mu.Unlock()
	completed := report.Summary.Killed + report.Summary.Survived + report.Summary.Errors
	return StatusSnapshot{
		Elapsed:          time.Since(startedAt),
		Total:            report.Summary.Total,
		Completed:        completed,
		Running:          *running,
		Killed:           report.Summary.Killed,
		Survived:         report.Summary.Survived,
		Errors:           report.Summary.Errors,
		SkippedScenarios: report.Summary.SkippedScenarios,
		SkippedMutations: report.Summary.SkippedMutations,
	}
}

func acceptedSkips(cfg Config, mutations []Mutation) map[int]bool {
	if cfg.Level == "full" || cfg.FeaturePath == "" {
		return nil
	}
	metadata, ok := ReadMutationMetadata(cfg.FeaturePath)
	if !ok {
		return nil
	}
	if len(metadata.Manifest.Scenarios) == 0 && FeatureStampValid(cfg.FeaturePath) {
		skip := map[int]bool{}
		for i := range cfg.Feature.Scenarios {
			skip[i] = true
		}
		return skip
	}
	current := NewManifest(cfg.FeaturePath, cfg.Feature, Report{}, cfg.ImplementationHash)
	skip := map[int]bool{}
	for _, entry := range metadata.Manifest.Scenarios {
		if !manifestEntryReusable(metadata.Manifest, current, entry, cfg.Level, cfg.Feature, mutations) {
			continue
		}
		skip[entry.Index] = true
	}
	return skip
}

func manifestEntryReusable(old manifest, current manifest, entry scenarioManifest, level string, feature gherkin.Feature, mutations []Mutation) bool {
	if old.Version != 1 {
		return false
	}
	if old.FeatureName != current.FeatureName || old.FeaturePath != current.FeaturePath {
		return false
	}
	if old.BackgroundHash != current.BackgroundHash {
		return false
	}
	if level == "hard" && old.ImplementationHash != current.ImplementationHash {
		return false
	}
	if entry.Index < 0 || entry.Index >= len(feature.Scenarios) {
		return false
	}
	scenario := feature.Scenarios[entry.Index]
	if entry.Name != scenario.Name || entry.ScenarioHash != hashJSON(scenario) {
		return false
	}
	if entry.Result.Survived != 0 || entry.Result.Errors != 0 {
		return false
	}
	if entry.MutationCount != mutationCountForScenario(mutations, entry.Index) {
		return false
	}
	return true
}

func MutateValue(path string, value string) string {
	trimmed := strings.TrimSpace(value)
	lower := strings.ToLower(trimmed)

	if strings.Contains(trimmed, ",") {
		parts := strings.Split(trimmed, ",")
		for i := range parts {
			parts[i] = strings.TrimSpace(parts[i])
		}
		index := int(seed(path, value) % uint64(len(parts)))
		parts[index] = MutateValue(path+"[]", parts[index])
		return strings.Join(parts, ", ")
	}
	if lower == "true" {
		return "false"
	}
	if lower == "false" {
		return "true"
	}
	if lower == "null" || lower == "nil" || lower == "none" {
		return "value"
	}
	if i, ok := parseInt(trimmed); ok {
		delta := int64(seed(path, value)%9) + 1
		if seed(path, value)%2 == 0 {
			delta = -delta
		}
		return strconv.FormatInt(i+delta, 10)
	}
	if f, ok := parseFloat(trimmed); ok {
		delta := float64((seed(path, value)%900)+100) / 100
		if seed(path, value)%2 == 0 {
			delta = -delta
		}
		return strconv.FormatFloat(f+delta, 'f', -1, 64)
	}
	return dither(path, value)
}

func makeResult(mutation Mutation, runnerResult RunnerResult) Result {
	status := Error
	switch runnerResult.Outcome {
	case TestFailure:
		status = Killed
	case TestSuccess:
		status = Survived
	case InfrastructureError:
		status = Error
	}
	return Result{
		Mutation: MutationView{
			ID:          mutation.ID,
			Path:        mutation.Path,
			Description: mutation.Description,
			Original:    mutation.Original,
			Mutated:     mutation.Mutated,
		},
		Status:   status,
		Output:   runnerResult.Output,
		Error:    runnerResult.Error,
		Duration: runnerResult.Duration,
	}
}

func WriteTextReport(w interface{ Write([]byte) (int, error) }, report Report) error {
	if _, err := fmt.Fprintf(w, "total=%d killed=%d survived=%d errors=%d", report.Summary.Total, report.Summary.Killed, report.Summary.Survived, report.Summary.Errors); err != nil {
		return err
	}
	if report.Summary.SkippedScenarios > 0 || report.Summary.SkippedMutations > 0 {
		if _, err := fmt.Fprintf(w, " skipped_scenarios=%d skipped_mutations=%d", report.Summary.SkippedScenarios, report.Summary.SkippedMutations); err != nil {
			return err
		}
	}
	if _, err := fmt.Fprintln(w); err != nil {
		return err
	}
	for _, result := range report.Results {
		if _, err := fmt.Fprintf(w, "%-8s %s\n", result.Status, result.Mutation.Description); err != nil {
			return err
		}
		if result.Status == Survived || result.Status == Error {
			if result.Error != "" {
				if _, err := fmt.Fprintf(w, "  error: %s\n", result.Error); err != nil {
					return err
				}
			}
			if result.Output != "" {
				if _, err := fmt.Fprintf(w, "  output:\n%s\n", result.Output); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func WriteJSONReport(w interface{ Write([]byte) (int, error) }, report Report) error {
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "  ")
	return encoder.Encode(report)
}

func cloneFeature(feature gherkin.Feature) gherkin.Feature {
	copied := gherkin.Feature{
		Name:       feature.Name,
		Background: append([]gherkin.Step(nil), feature.Background...),
		Scenarios:  make([]gherkin.Scenario, len(feature.Scenarios)),
	}
	for i, scenario := range feature.Scenarios {
		copied.Scenarios[i] = gherkin.Scenario{
			Name:     scenario.Name,
			Steps:    append([]gherkin.Step(nil), scenario.Steps...),
			Examples: make([]map[string]string, len(scenario.Examples)),
		}
		for j, example := range scenario.Examples {
			copied.Scenarios[i].Examples[j] = make(map[string]string, len(example))
			for key, value := range example {
				copied.Scenarios[i].Examples[j][key] = value
			}
		}
	}
	return copied
}

func writeJSON(path string, feature gherkin.Feature) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	return gherkin.WriteJSON(file, feature)
}

func parseInt(value string) (int64, bool) {
	if value == "" {
		return 0, false
	}
	i, err := strconv.ParseInt(value, 10, 64)
	return i, err == nil
}

func parseFloat(value string) (float64, bool) {
	if !strings.Contains(value, ".") {
		return 0, false
	}
	f, err := strconv.ParseFloat(value, 64)
	return f, err == nil && !math.IsInf(f, 0) && !math.IsNaN(f)
}

func dither(path, value string) string {
	if value == "" {
		return "x"
	}
	runes := []rune(value)
	index := int(seed(path, value) % uint64(len(runes)))
	if runes[index] >= 'a' && runes[index] <= 'z' {
		runes[index] = runes[index] - 'a' + 'A'
		return string(runes)
	}
	if runes[index] >= 'A' && runes[index] <= 'Z' {
		runes[index] = runes[index] - 'A' + 'a'
		return string(runes)
	}
	runes[index] = 'x'
	return string(runes)
}

func seed(parts ...string) uint64 {
	h := fnv.New64a()
	for _, part := range parts {
		_, _ = h.Write([]byte(part))
		_, _ = h.Write([]byte{0})
	}
	return h.Sum64()
}

func DurationNanos(start time.Time) int64 {
	return int64(time.Since(start))
}
