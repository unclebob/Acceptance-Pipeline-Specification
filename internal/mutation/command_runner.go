package mutation

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"
)

type CommandRunner struct {
	Command []string
}

func (r CommandRunner) Run(ctx context.Context, job Job) RunnerResult {
	if len(r.Command) == 0 {
		return RunnerResult{Outcome: InfrastructureError, Error: "missing runner command"}
	}
	start := time.Now()
	cmd := exec.CommandContext(ctx, r.Command[0], r.Command[1:]...)
	cmd.Env = append(os.Environ(),
		"ACCEPTANCE_MUTATION_ID="+job.Mutation.ID,
		"ACCEPTANCE_FEATURE_JSON="+job.FeatureJSON,
		"ACCEPTANCE_GENERATED_DIR="+job.GeneratedDir,
		"ACCEPTANCE_WORK_DIR="+job.WorkDir,
	)
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		return RunnerResult{
			Outcome:  InfrastructureError,
			Output:   stdout.String(),
			Error:    strings.TrimSpace(fmt.Sprintf("%v\n%s", err, stderr.String())),
			Duration: int64(time.Since(start)),
		}
	}

	var result RunnerResult
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil {
		return RunnerResult{
			Outcome:  InfrastructureError,
			Output:   stdout.String(),
			Error:    "invalid runner JSON: " + err.Error(),
			Duration: int64(time.Since(start)),
		}
	}
	if result.Duration == 0 {
		result.Duration = int64(time.Since(start))
	}
	if stderr.Len() > 0 && result.Error == "" {
		result.Error = strings.TrimSpace(stderr.String())
	}
	return result
}
