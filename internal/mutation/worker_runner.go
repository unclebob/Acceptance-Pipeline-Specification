package mutation

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync/atomic"
	"time"
)

type WorkerPoolConfig struct {
	Command []string
	Workers int
	Env     []string
}

type WorkerRequest struct {
	ID           string `json:"id"`
	FeatureJSON  string `json:"feature_json"`
	GeneratedDir string `json:"generated_dir"`
	WorkDir      string `json:"work_dir"`
	Timeout      string `json:"timeout,omitempty"`
}

type WorkerResponse struct {
	ID       string        `json:"id"`
	Outcome  RunnerOutcome `json:"outcome"`
	Output   string        `json:"output"`
	Error    string        `json:"error"`
	Duration int64         `json:"duration"`
}

type WorkerPoolRunner struct {
	workers []*workerProcess
	next    atomic.Uint64
}

type workerCall struct {
	ctx      context.Context
	request  WorkerRequest
	response chan RunnerResult
}

type workerProcess struct {
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	requests  chan workerCall
	closeDone chan struct{}
}

func NewWorkerPoolRunner(ctx context.Context, cfg WorkerPoolConfig) (*WorkerPoolRunner, error) {
	if len(cfg.Command) == 0 {
		return nil, fmt.Errorf("missing worker command")
	}
	if cfg.Workers < 1 {
		cfg.Workers = 1
	}

	pool := &WorkerPoolRunner{workers: make([]*workerProcess, 0, cfg.Workers)}
	for i := 0; i < cfg.Workers; i++ {
		worker, err := startWorker(ctx, cfg)
		if err != nil {
			pool.Close()
			return nil, err
		}
		pool.workers = append(pool.workers, worker)
	}
	return pool, nil
}

func (r *WorkerPoolRunner) Run(ctx context.Context, job Job) RunnerResult {
	if len(r.workers) == 0 {
		return RunnerResult{Outcome: InfrastructureError, Error: "no worker processes"}
	}
	request := WorkerRequest{
		ID:           job.Mutation.ID,
		FeatureJSON:  job.FeatureJSON,
		GeneratedDir: job.GeneratedDir,
		WorkDir:      job.WorkDir,
	}
	call := workerCall{
		ctx:      ctx,
		request:  request,
		response: make(chan RunnerResult, 1),
	}
	worker := r.workers[int(r.next.Add(1)-1)%len(r.workers)]

	select {
	case <-ctx.Done():
		return RunnerResult{Outcome: InfrastructureError, Error: ctx.Err().Error()}
	case <-worker.closeDone:
		return RunnerResult{Outcome: InfrastructureError, Error: "worker exited"}
	case worker.requests <- call:
	}

	select {
	case <-ctx.Done():
		return RunnerResult{Outcome: InfrastructureError, Error: ctx.Err().Error()}
	case <-worker.closeDone:
		return RunnerResult{Outcome: InfrastructureError, Error: "worker exited"}
	case result := <-call.response:
		return result
	}
}

func (r *WorkerPoolRunner) Close() error {
	var firstErr error
	for _, worker := range r.workers {
		close(worker.requests)
		if worker.stdin != nil {
			if err := worker.stdin.Close(); err != nil && firstErr == nil {
				firstErr = err
			}
		}
		if worker.cmd != nil && worker.cmd.Process != nil {
			_ = worker.cmd.Wait()
		}
	}
	return firstErr
}

func startWorker(ctx context.Context, cfg WorkerPoolConfig) (*workerProcess, error) {
	cmd := exec.CommandContext(ctx, cfg.Command[0], cfg.Command[1:]...)
	cmd.Env = append(os.Environ(), cfg.Env...)
	cmd.Stderr = os.Stderr

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}

	worker := &workerProcess{
		cmd:       cmd,
		stdin:     stdin,
		requests:  make(chan workerCall),
		closeDone: make(chan struct{}),
	}
	go worker.loop(stdout)
	return worker, nil
}

func (w *workerProcess) loop(stdout io.Reader) {
	defer close(w.closeDone)

	encoder := json.NewEncoder(w.stdin)
	scanner := bufio.NewScanner(stdout)
	for call := range w.requests {
		start := time.Now()
		if err := encoder.Encode(call.request); err != nil {
			call.response <- RunnerResult{Outcome: InfrastructureError, Error: err.Error(), Duration: int64(time.Since(start))}
			return
		}
		if !scanner.Scan() {
			errText := "worker exited without response"
			if err := scanner.Err(); err != nil {
				errText = err.Error()
			}
			call.response <- RunnerResult{Outcome: InfrastructureError, Error: errText, Duration: int64(time.Since(start))}
			return
		}
		var response WorkerResponse
		if err := json.Unmarshal(scanner.Bytes(), &response); err != nil {
			call.response <- RunnerResult{Outcome: InfrastructureError, Error: "invalid worker JSON: " + err.Error(), Duration: int64(time.Since(start))}
			continue
		}
		if response.ID != call.request.ID {
			call.response <- RunnerResult{Outcome: InfrastructureError, Error: fmt.Sprintf("worker response id %q does not match request id %q", response.ID, call.request.ID), Duration: int64(time.Since(start))}
			continue
		}
		duration := response.Duration
		if duration == 0 {
			duration = int64(time.Since(start))
		}
		call.response <- RunnerResult{
			Outcome:  response.Outcome,
			Output:   response.Output,
			Error:    response.Error,
			Duration: duration,
		}
	}
}
