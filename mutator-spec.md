# Mutator Specification

## Purpose

This document specifies `gherkin-mutator`, the portable command that mutates
Gherkin example values in the parser-defined JSON IR and runs acceptance test
entry points to determine whether those tests detect the changed specification
data.

Acceptance mutation means mutating specification-derived example data. It does
not mean mutation testing application source code.

The parser and JSON IR are specified in [parser-spec.md](parser-spec.md).

## Generator and Runtime Assumption

Generated test functions should be tied to scenario structure, not literal
example values.

For value-only mutations, changing the JSON IR should not require regenerating
different test functions. Generated tests should execute whatever JSON IR is
supplied for the current run.

Recommended model:

```text
base feature IR
  -> generate acceptance test entry points once

each mutated IR
  -> run the same generated entry points against the mutated JSON data
```

Generated metadata format and filename normalization are specified in
[acceptance-generator.md](acceptance-generator.md). The mutator reads one
metadata file per feature under:

```text
<generated-dir>/metadata/<feature-metadata-name>.json
```

The mutator should read `implementation_hash` from that metadata by default. It
must verify that metadata `feature_path` matches the current `--feature` path
before trusting the hash. An explicit command-line implementation hash may
override the metadata value for debugging or unusual project layouts.

## Mutator Command

```text
gherkin-mutator [options]
```

Options:

```text
--feature <path>
    Gherkin feature file to parse and mutate.
    Default: features/a-feature.feature

--work-dir <path>
    Directory where mutation work files are written.
    Default: build/acceptance-mutation

--generated-dir <path>
    Directory where generated acceptance tests for the mutation run are written.
    Default: <work-dir>/generated

--workers <count>
    Maximum number of mutation workers to run in parallel.
    Values less than 1 must be treated as 1.

--timeout <duration>
    Timeout for the full acceptance mutation run.
    Duration syntax is implementation-defined but should support seconds.

--status-interval <duration>
    Interval for periodic status lines while mutations are running.
    Default: 30s. A value of 0 disables periodic status.

--level <level>
    Differential mutation level: full, hard, or soft.
    Default: hard.

--runner-worker <command>
    Persistent runner adapter command. Required. The mutator starts worker
    processes once and sends mutation jobs over stdin/stdout.

--implementation-hash <hash>
    Override the implementation hash read from
    <generated-dir>/metadata/<feature-metadata-name>.json.

--json
    Emit JSON report instead of text report.
```

Exit codes:

```text
0  all executed mutations were killed and no errors occurred
1  at least one mutation survived, or at least one mutation produced an error
2  command-line usage or option parsing error
```

## High-Level Flow

```text
feature file
  -> parse base JSON IR
  -> discover executable scenario structure
  -> use generated acceptance test entry points
  -> discover candidate mutations from example values
  -> apply differential skip rules
  -> execute each non-skipped mutation using generated tests and mutated IR
  -> classify mutation results
  -> print final report
  -> update scenario manifest and feature mutation stamp
```

## Mutation Scope

The mutator creates candidate mutations only from scenario example values.

It must not mutate:

```text
feature names
scenario names
step text
step keywords
background steps
example headers
source code
generated test logic
```

Each mutation changes exactly one example cell.

The base JSON IR must not be modified in place. Each mutation is applied to a
deep copy of the base IR.

## Mutation Discovery Algorithm

For each scenario, in scenario order:

1. If the scenario has no examples, skip it.
2. For each example row, in row order:
3. For each example key, in lexicographic order:
4. Read the original string value.
5. Compute the mutated value using the value mutation rules.
6. If the mutated value equals the original value, skip it.
7. If a project-specific equivalent mutation filter rejects the mutation, skip
   it.
8. Create one mutation that changes only that cell.

## Mutation Identity

Mutation IDs must be stable and deterministic for a fixed input IR and mutation
implementation:

```text
m1
m2
m3
...
```

Mutation paths use:

```text
$.scenarios[<scenario_index>].examples[<example_index>].<key>
```

Indexes are zero-based. Keys are literal example object keys.

Descriptions use:

```text
<path>: <original> -> <mutated>
```

## Value Mutation Rules

Values are strings. The mutator infers a portable value type from the string
content and changes the value without using project-specific semantics.

Random choices must be pseudo-random and deterministic for a fixed mutation
path and original value.

Before selecting a mutation rule:

```text
trimmed = value with leading and trailing whitespace removed
```

Rules are applied in this order:

1. If `trimmed` contains a comma, treat it as a comma-delimited list. Split on
   commas, trim each item, mutate one selected item recursively using these
   rules, and join the list with `, `. The selected item must be chosen
   pseudo-randomly and deterministically.
2. If lowercase `trimmed` is `true` or `false`, mutate it to the opposite
   lowercase boolean value.
3. If lowercase `trimmed` is `null`, `nil`, or `none`, mutate it to a non-empty
   dithered string.
4. If `trimmed` is a base-10 integer, mutate it to the decimal representation
   of the integer plus a pseudo-random nonzero integer delta.
5. If `trimmed` is a finite base-10 floating point number, mutate it to the
   decimal representation of the number plus a pseudo-random nonzero floating
   point delta.
6. If `trimmed` is an ISO-8601 date, time, or date-time value, mutate it by a
   pseudo-random nonzero amount appropriate to the represented precision.
7. If `trimmed` is a recognized duration value, mutate it by a pseudo-random
   nonzero amount while preserving valid duration syntax.
8. Otherwise, dither the original untrimmed string.

String dithering must produce a different string by applying one small edit,
such as insertion, deletion, replacement, adjacent-character swap, or case
change. Empty strings are dithered by inserting a character.

The portable mutator must not define command, enum, or domain-specific swaps.
Project-specific semantic mutations belong in the project adapter or in a
project-specific mutator extension.

Examples:

```text
20                  -> 27
3.14                -> 2.89
true                -> false
2026-05-13          -> 2026-05-15
2, 5, 8             -> 2, 11, 8
accepted            -> accfpted
message with spaces -> message with spcaes
```

## Equivalent Mutation Filters

Projects may define filters that skip semantically equivalent mutations.

Filters are project-specific and belong outside the portable mutator core.

Filter requirements:

1. Filters must be deterministic.
2. Filters must run before creating a mutation entry.
3. The final report's `total` count must include only mutations that were
   executed.
4. Filtered mutations should not appear in the result list unless the project
   explicitly adds a separate skipped report.

## Mutation Execution

The mutator creates:

```text
<work-dir>/
  base/
    feature.json
  generated/
    <generated acceptance tests>
    metadata/
      <feature-metadata-name>.json
  mutations/
    m1/
      feature.json
    m2/
      feature.json
```

For each mutation:

1. Deep-copy the base IR.
2. Apply the single example-cell change.
3. Write the mutated IR to `<work-dir>/mutations/<mutation-id>/feature.json`.
4. Ask the runner adapter to execute the generated tests against that mutated
   IR.
5. Capture outcome, output, error text, and duration.
6. Classify the result.

The generated test functions should not change from mutation to mutation.

The full mutator run may have a timeout. When the timeout expires, no new
mutations may be started, running mutation jobs should be cancelled, and
unfinished mutations should be reported as `error`.

## Runner Adapter

The runner adapter is project-specific. It hides whether the project uses
`go test`, `pytest`, `mvn test`, `clojure -M:test`, `npm test`, or another
test mechanism.

The portable mutator must not link directly to project test code.

Runner outcomes:

```text
test_success          generated tests ran and passed
test_failure          generated tests ran and failed
infrastructure_error  tests could not be started, completed, or evaluated
```

Classification:

```text
test_failure          -> killed
test_success          -> survived
infrastructure_error  -> error
```

Persistent worker mode is required. The mutator starts up to `--workers`
adapter processes once and sends mutation jobs over stdin/stdout. Each worker
stays hot and may evaluate many mutations.

The worker protocol uses newline-delimited JSON.

Job request:

```json
{
  "id": "m1",
  "feature_json": "build/acceptance-mutation/mutations/m1/feature.json",
  "generated_dir": "build/acceptance-mutation/generated",
  "work_dir": "build/acceptance-mutation/mutations/m1",
  "timeout": "30s"
}
```

Job response:

```json
{
  "id": "m1",
  "outcome": "test_failure",
  "output": "<test runner output>",
  "error": "",
  "duration": 125000000
}
```

Worker process rules:

1. Each input line is one JSON job request.
2. Each output line is one JSON job response.
3. A worker must not write non-protocol data to standard output.
4. Diagnostics must be written to standard error.
5. If a worker exits unexpectedly, every in-flight job assigned to it becomes
   an `error`.

## Status Reporting

Status lines must be written to standard error. Standard output is reserved for
the final text or JSON report.

The mutator should emit:

1. One status line after mutation discovery and before executing the first
   mutation.
2. One status line at least every `--status-interval` while at least one
   mutation is still running.
3. One status line when execution finishes, before the final report is emitted.

Status format:

```text
status elapsed=<duration> total=<total> completed=<completed> running=<running> killed=<killed> survived=<survived> errors=<errors> skipped_scenarios=<count> skipped_mutations=<count>
```

## Differential Mutation

Differential mutation reuses previous successful mutation results when it can
prove relevant feature content and generated acceptance files have not changed.

Differential mutation is an optimization only. It must not change the meaning
of `killed`, `survived`, or `error`.

### Feature Mutation Stamp

A feature mutation stamp may be used as a whole-file shortcut when the feature
has no scenario manifest and the selected level is not `full`.

The stamp records a hash of the feature content excluding the stamp line
itself:

```gherkin
# mutation-stamp: sha256=<feature-content-hash>
```

A stale, missing, malformed, or mismatched stamp must not be trusted.

### Scenario Manifest

A scenario manifest may be used for scenario-level reuse. It is stored as a
comment block near the top of the feature file:

```gherkin
# acceptance-mutation-manifest-begin
# { ... JSON manifest ... }
# acceptance-mutation-manifest-end
```

The manifest JSON must contain:

```json
{
  "version": 1,
  "tested_at": "<timestamp>",
  "feature_name": "<feature name>",
  "feature_path": "<feature path>",
  "background_hash": "<hash>",
  "implementation_hash": "<generated-files-hash>",
  "scenarios": [
    {
      "index": 0,
      "name": "<scenario name>",
      "scenario_hash": "<hash>",
      "mutation_count": 0,
      "result": {
        "Total": 0,
        "Killed": 0,
        "Survived": 0,
        "Errors": 0
      },
      "tested_at": "<timestamp>"
    }
  ]
}
```

The `background_hash` covers all background steps. The `scenario_hash` covers
the scenario name, scenario steps, example headers, and example values. The
`implementation_hash` is the generator-provided hash of generated acceptance
files only.

Differential levels:

```text
full  ignore stamps and manifests; execute every mutation
hard  reuse only when feature identity, scenario content, background content,
      and implementation hash all match
soft  reuse when feature identity, scenario content, and background content
      match, even if the implementation hash changed
```

`hard` is the default.

A scenario may be skipped only when:

1. The manifest version is supported.
2. The manifest feature name and feature path match the current feature.
3. The manifest background hash matches the current background hash.
4. The manifest implementation hash is valid for the selected differential
   level.
5. The manifest has an entry for the same scenario index.
6. The entry scenario name and scenario hash match the current scenario.
7. The entry has zero survived mutations and zero errors.

Skipped scenarios keep their previous manifest entries. Executed scenarios
receive new result summaries and timestamps. Deleted scenarios must be removed
from the next manifest.

Every run should write a fresh scenario manifest containing scenarios that have
zero survived mutations and zero errors. A successful run with no surviving
mutations and no errors should also write a fresh feature mutation stamp.

## Reports

The default text report starts with one summary line:

```text
total=<total> killed=<killed> survived=<survived> errors=<errors>
```

When differential mutation skips scenarios, the report should also include:

```text
skipped_scenarios=<count> skipped_mutations=<count>
```

Then it prints one line per result:

```text
<status> <path>: <original> -> <mutated>
```

Status should be left-aligned to 8 characters.

When `--json` is supplied, the report must be a JSON object with:

```text
summary.Total             number
summary.Killed            number
summary.Survived          number
summary.Errors            number
summary.SkippedScenarios  number, when differential mutation skipped scenarios
summary.SkippedMutations  number, when differential mutation skipped scenarios
results                   array
```

Each result object must include mutation identity, status, output, error text,
and duration.

## Mutator Conformance Checklist

1. Mutator generates mutations only for example cell values.
2. Mutator produces stable mutation IDs, paths, and descriptions.
3. Mutator applies the portable value mutation rules.
4. Mutator deep-copies the IR before applying each mutation.
5. Mutator runs the same generated test entry points against each mutated JSON
   IR.
6. Mutator requires persistent worker mode.
7. Mutator classifies failing generated tests as `killed`.
8. Mutator classifies passing generated tests as `survived`.
9. Mutator classifies parsing, IR writing, timeout, runner, protocol, and
   infrastructure failures as `error`.
10. Mutator exits with `1` when any mutation survives or errors.
11. Mutator emits text and JSON reports in stable order.
12. Mutator emits periodic status lines to standard error.
13. Mutator supports differential levels `full`, `hard`, and `soft`, with
    `hard` as the default.
14. Mutator ignores stamps and manifests at `full` level.
15. Mutator at `hard` level skips only clean manifest scenarios whose feature
    identity, background hash, scenario hash, and implementation hash match.
16. Mutator at `soft` level skips clean manifest scenarios whose feature
    identity, background hash, and scenario hash match, even when the
    implementation hash differs.
17. Mutator rejects stale manifests when the background hash changes and reruns
    changed scenarios when their scenario hash changes.
18. Mutator writes clean scenarios into the manifest on every run.
