# Parser and Mutator Specification

## Purpose

This document specifies two portable command-line tools:

```text
gherkin-parser
gherkin-mutator
```

The parser converts a small, deterministic Gherkin subset into a JSON
intermediate representation.

The mutator changes Gherkin example values in that JSON representation and
runs generated acceptance tests to determine whether the tests detect those
changes.

Acceptance mutation in this document means mutating specification-derived
example data. It does not mean mutation testing of application source code.

## Design Constraints

The tools must be:

1. Portable across projects and implementation languages.
2. Deterministic for a fixed feature file and implementation version.
3. Friendly to agents and CI systems.
4. Efficient enough for repeated use during development.
5. Explicit about the boundary between portable tooling and project-specific
   test execution.

The parser, mutator, generator, runtime, and runner adapter should share one
canonical JSON IR. The mutator must not maintain a separate interpretation of
the feature file.

## Recommended Implementation Language

Go is the preferred implementation language for the portable tools.

Reasons:

1. The parser and mutator are small CLI tools.
2. Go produces simple single-file binaries.
3. Go has strong standard-library support for JSON, filesystem operations,
   subprocesses, timeouts, and concurrency.
4. The mutator needs worker orchestration, cancellation, and periodic status
   reporting.

The specification does not require Go. A conforming implementation may use any
language that preserves the command and data contracts below.

## Command Summary

```text
gherkin-parser <feature-file> <json-output>

gherkin-mutator [options]
```

The parser is a pure translator from Gherkin to JSON.

The mutator owns parsing, mutation discovery, mutation execution, result
classification, status reporting, final reporting, and manifest/stamp updates.

## Gherkin Parser

### Parser Command

The parser accepts exactly two positional arguments:

```text
gherkin-parser <feature-file> <json-output>
```

Exit codes:

```text
0  parse succeeded and JSON IR was written
1  input/output/parsing error
2  command-line usage error
```

The parser reads the source feature file and writes pretty-printed JSON.

### Supported Gherkin Subset

The parser intentionally supports a small subset of Gherkin.

Supported declarations:

```gherkin
Feature: <feature name>
Background:
Scenario: <scenario name>
Scenario Outline: <scenario name>
Examples:
```

Supported step keywords:

```text
Given
When
Then
And
```

Unsupported syntax includes:

```text
tags
rules
localized keywords
escaped pipes
quoted table cells
multiline cells
doc strings
data tables attached to steps
semantic comments
```

### General Parsing Rules

1. Blank lines are ignored.
2. Lines whose first non-whitespace character is `#` are ignored.
3. Leading and trailing whitespace are removed before classifying each line.
4. Free-form lines that do not match supported syntax are ignored.
5. Order must be preserved for background steps, scenarios, scenario steps, and
   example rows.
6. Example object key traversal is not guaranteed by JSON object order; any
   consumer that needs stable key order must sort keys explicitly.

### Feature Rules

A valid feature file must contain:

```gherkin
Feature: <feature name>
```

The feature name is the trimmed text after `Feature:`.

A missing feature declaration is a parsing error.

New feature files should contain exactly one feature declaration. If multiple
feature declarations are encountered, an implementation must either reject the
file or consistently use one documented behavior.

### Background Rules

A feature may contain one background:

```gherkin
Background:
  Given a configured project state
```

Background steps are stored separately in the JSON IR. The runtime prepends
them to every scenario execution.

Portable behavior is undefined for multiple background sections. New feature
files should use at most one.

### Scenario Rules

The parser accepts:

```gherkin
Scenario: <scenario name>
Scenario Outline: <scenario name>
```

Both forms produce the same JSON shape.

A scenario without examples is valid. It executes once with an empty example
object and cannot be mutated.

A scenario with examples executes once per example row and can be mutated.

### Step Rules

A step line must start with one of:

```text
Given
When
Then
And
```

The keyword and text are stored separately.

For this input:

```gherkin
Then the result is <result>
```

the keyword is:

```text
Then
```

and the text is:

```text
the result is <result>
```

A step outside a background or scenario is a parsing error.

### Parameter Rules

Parameters are placeholders inside step text:

```text
<parameter_name>
```

Parameter names must match:

```text
[A-Za-z0-9_]+
```

The parser records parameter names in the order they appear in the step text.
Repeated parameter names must be preserved as repeated entries.

The parser does not expand parameters. Parameter expansion is a runtime
responsibility.

### Examples Table Rules

An examples section starts with:

```gherkin
Examples:
```

It must appear inside a scenario.

Rows are pipe-delimited:

```gherkin
| name | count |
| one  | 1     |
| two  | 2     |
```

Parsing rules:

1. A table row is recognized only when the trimmed line starts with `|`.
2. Leading and trailing `|` characters are removed.
3. The remaining text is split on `|`.
4. Each cell is trimmed.
5. The first row after `Examples:` is the header row.
6. Every data row must have the same number of cells as the header row.
7. Header names become JSON object keys.
8. Cell values are stored as strings.

An examples data row with a cell count different from the header count is a
parsing error.

Examples outside a scenario are a parsing error.

## JSON Intermediate Representation

The JSON IR is the canonical structure consumed by the generator, runtime, and
mutator.

### Feature Object

```json
{
  "name": "Feature name",
  "background": [
    {
      "keyword": "Given",
      "text": "a configured project state",
      "parameters": []
    }
  ],
  "scenarios": [
    {
      "name": "Scenario name",
      "steps": [
        {
          "keyword": "Then",
          "text": "the result is <result>",
          "parameters": ["result"]
        }
      ],
      "examples": [
        {
          "result": "accepted"
        }
      ]
    }
  ]
}
```

Required fields:

```text
name       string
scenarios  array of scenario objects
```

Optional fields:

```text
background  array of step objects; omit or use [] when absent
```

### Scenario Object

```json
{
  "name": "Scenario name",
  "steps": [],
  "examples": []
}
```

Required fields:

```text
name      string
steps     array of step objects
examples  array of objects whose keys and values are strings
```

If `examples` is empty, the runtime must execute the scenario once with an
empty example object.

### Step Object

```json
{
  "keyword": "Given",
  "text": "the input is <input>",
  "parameters": ["input"]
}
```

Required fields:

```text
keyword  one of "Given", "When", "Then", or "And"
text     string
```

Optional fields:

```text
parameters  array of strings; omit or use [] when no placeholders are present
```

The `parameters` field is derived from `text`. Consumers should treat `text` as
authoritative and may validate that `parameters` agrees with the placeholders
found in `text`.

### Example Object

An example object maps column names to string values:

```json
{
  "input": "42",
  "command": "calculate total",
  "expected_status": "accepted"
}
```

All values must be strings, even when they represent numbers, booleans, lists,
dates, commands, messages, or enums.

## Generator and Runtime Assumption

The mutator depends on an important generator/runtime rule:

Generated test functions should be tied to the scenario structure, not to the
literal example values.

For value-only mutations, changing the JSON IR should not require regenerating
different test functions. The generated tests should execute whatever JSON IR
is supplied for the current run.

Recommended model:

```text
base feature IR
  -> generate acceptance test entry points once

each mutated IR
  -> run the same generated entry points against the mutated JSON data
```

The generator may still embed or copy the original IR for normal acceptance
runs. For mutation runs, however, the runner adapter must be able to point the
generated tests at the current mutated JSON file.

The mutator should regenerate tests only when the feature structure changes or
when the local generator/runtime requires regeneration. Since portable
mutations change only example values, the efficient default is to generate once
from the base IR and reuse those generated tests for every mutation.

## Gherkin Mutator

### Mutator Command

The mutator command exposes:

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

--runner <command>
    Simple runner adapter command. The mutator invokes this command for each
    mutation unless a worker runner is supplied.

--runner-worker <command>
    Persistent runner adapter command. The mutator starts worker processes once
    and sends mutation jobs over stdin/stdout.

--json
    Emit JSON report instead of text report.
```

Exit codes:

```text
0  all executed mutations were killed and no errors occurred
1  at least one mutation survived, or at least one mutation produced an error
2  command-line usage or option parsing error
```

### High-Level Mutator Flow

The mutator performs:

```text
feature file
  -> parse base JSON IR
  -> discover executable scenario structure
  -> generate acceptance tests once from the base IR
  -> discover candidate mutations from example values
  -> apply differential skip rules
  -> execute each non-skipped mutation using generated tests and mutated IR
  -> classify mutation results
  -> print final report
  -> update scenario manifest and feature mutation stamp on successful run
```

### Mutation Scope

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

### Mutation Discovery Algorithm

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

### Mutation Identity

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

### Value Mutation Rules

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

### Equivalent Mutation Filters

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

### Work Directory Layout

The mutator creates:

```text
<work-dir>/
  base/
    feature.json
  generated/
    <generated acceptance tests>
  mutations/
    m1/
      feature.json
    m2/
      feature.json
```

The base IR is written to:

```text
<work-dir>/base/feature.json
```

Generated tests are written once to:

```text
<work-dir>/generated/
```

Each mutation writes its mutated IR to:

```text
<work-dir>/mutations/<mutation-id>/feature.json
```

Parallel workers may execute different mutations concurrently. Each mutation
must write only inside its own mutation work directory.

### Per-Mutation Execution

For each mutation:

1. Deep-copy the base IR.
2. Apply the single example-cell change.
3. Write the mutated IR to the mutation work directory.
4. Ask the runner adapter to execute the generated tests against that mutated
   IR.
5. Capture outcome, output, error text, and duration.
6. Classify the result.

The generated test functions should not change from mutation to mutation.

### Timeout

The full mutator run may have a timeout.

When the timeout expires:

1. No new mutations may be started.
2. Running mutation jobs should be cancelled.
3. Unfinished mutations should be reported as `error`.
4. Timeout errors should include useful diagnostic text.

## Runner Adapter

The runner adapter is project-specific. It hides whether the project uses
`go test`, `pytest`, `mvn test`, `clojure -M:test`, `npm test`, or another
test mechanism.

The portable mutator must not link directly to project test code.

### Runner Outcomes

The adapter must distinguish:

```text
test_success          generated tests ran and passed
test_failure          generated tests ran and failed
infrastructure_error  tests could not be started, completed, or evaluated
```

The mutator maps these outcomes to mutation statuses:

```text
test_failure          -> killed
test_success          -> survived
infrastructure_error  -> error
```

A failed generated test is a successful mutation result because it means the
acceptance tests detected the changed specification value.

### Simple Runner Mode

Simple mode invokes a project command once per mutation.

The mutator should avoid invoking a shell by default. It should execute a
command with explicit argv where the implementation language supports that.

An implementation may support a shell command string for convenience, but shell
execution should be documented because quoting and portability become
project-specific concerns.

The simple runner receives at least:

```text
mutation id
mutated feature JSON path
generated test directory
mutation work directory
timeout or cancellation signal
```

Recommended environment variables:

```text
ACCEPTANCE_MUTATION_ID
ACCEPTANCE_FEATURE_JSON
ACCEPTANCE_GENERATED_DIR
ACCEPTANCE_WORK_DIR
```

Recommended result contract:

1. The adapter writes a JSON result object to standard output.
2. The adapter writes diagnostics to standard error.
3. Exit code `0` means a valid result JSON was written.
4. Nonzero exit means the adapter itself failed, which the mutator classifies
   as `error`.

Result JSON:

```json
{
  "id": "m1",
  "outcome": "test_failure",
  "output": "<combined test output>",
  "error": "",
  "duration": 125000000
}
```

`duration` is implementation-defined but must be documented and stable.
Nanoseconds are recommended for Go implementations.

Simple runner mode is easy to implement and debug, but it may be slow because
it can start a fresh project test process for every mutation.

### Persistent Worker Mode

Persistent worker mode is preferred for larger projects.

In this mode, the mutator starts up to `--workers` adapter processes once and
sends mutation jobs over stdin/stdout. Each worker stays hot and may evaluate
many mutations.

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
3. Responses may be emitted in the same order as requests for a single worker.
4. A worker must not write non-protocol data to standard output.
5. Diagnostics must be written to standard error.
6. If a worker exits unexpectedly, every in-flight job assigned to it becomes
   an `error`.
7. The mutator may start replacement workers unless the full run timeout has
   expired.

Persistent worker mode avoids starting a shell or full test runtime per
mutation. This is especially important for JVM, Clojure, Gradle, Maven, and
large JavaScript projects.

## Result Classification

Each executed mutation has one status:

```text
killed
survived
error
```

Classification:

```text
killed   generated tests failed after the mutation was applied
survived generated tests passed after the mutation was applied
error    parsing, IR writing, runner startup, timeout, protocol, or infrastructure failed
```

A killed mutation means the acceptance tests detected the changed value.

A survived mutation means the acceptance tests did not detect the changed
value and should be investigated.

An error is not a test-quality result. It means the mutation could not be
evaluated reliably.

## Status Reporting

The mutator should emit periodic status lines while a mutation run is active.

Status lines must be written to standard error. Standard output is reserved for
the final text or JSON report.

The mutator should emit:

1. One status line after mutation discovery and before executing the first
   mutation.
2. One status line at least every `--status-interval` while at least one
   mutation is still running.
3. One status line when execution finishes, before the final report is emitted.

Status lines should be single-line, stable, human-readable records:

```text
status elapsed=<duration> total=<total> completed=<completed> running=<running> killed=<killed> survived=<survived> errors=<errors> skipped_scenarios=<count> skipped_mutations=<count>
```

`skipped_scenarios` and `skipped_mutations` may be omitted when no
differential skip occurred.

`completed` counts only executed mutations that have reached `killed`,
`survived`, or `error`.

`running` counts mutations currently assigned to workers.

The final status line should have `running=0` and `completed` equal to the
executed mutation total.

## Differential Mutation

Differential mutation reuses previous successful mutation results when it can
prove relevant feature content and mutation implementation have not changed.

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

A scenario manifest may be used for scenario-level reuse.

It is stored as a comment block near the top of the feature file:

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
  "implementation_hash": "<hash>",
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

The `background_hash` covers all background steps.

The `scenario_hash` covers the scenario name, scenario steps, example headers,
and example values.

The `implementation_hash` identifies the parser, generator, mutator, filters,
runner adapter, and runtime components whose behavior can affect mutation
generation, filtering, execution, or classification.

### Differential Levels

```text
full  ignore stamps and manifests; execute every mutation
hard  reuse only when feature identity, scenario content, background content,
      and implementation hash all match
soft  reuse when feature identity, scenario content, and background content
      match, even if the implementation hash changed
```

`hard` is the default.

### Scenario Skip Rules

When a scenario manifest is accepted, a scenario may be skipped only when all
of these are true:

1. The manifest version is supported.
2. The manifest feature name and feature path match the current feature.
3. The manifest background hash matches the current background hash.
4. The manifest implementation hash is valid for the selected differential
   level.
5. The manifest has an entry for the same scenario index.
6. The entry scenario name and scenario hash match the current scenario.
7. The entry has zero survived mutations and zero errors.

Skipped scenarios keep their previous manifest entries, including their
previous `tested_at` values.

Executed scenarios receive new result summaries and timestamps.

Deleted scenarios must be removed from the next manifest.

After a successful mutation run, the mutator should write a fresh scenario
manifest and a fresh feature mutation stamp to the feature file.

## Reports

### Text Report

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

For `survived` and `error` results, include available details:

```text
  error: <error text>
  output:
<runner output>
```

Example:

```text
total=2 killed=1 survived=1 errors=0
killed   $.scenarios[0].examples[0].count: 20 -> 27
survived $.scenarios[1].examples[0].status: accepted -> accfpted
  output:
<test runner output>
```

### JSON Report

When `--json` is supplied, the report must be a JSON object written to standard
output:

```json
{
  "summary": {
    "Total": 2,
    "Killed": 1,
    "Survived": 1,
    "Errors": 0
  },
  "results": [
    {
      "Mutation": {
        "ID": "m1",
        "Path": "$.scenarios[0].examples[0].count",
        "Description": "$.scenarios[0].examples[0].count: 20 -> 27",
        "Original": "20",
        "Mutated": "27"
      },
      "Status": "killed",
      "Output": "<test runner output>",
      "Error": "",
      "Duration": 125000000
    }
  ]
}
```

Portable field requirements:

```text
summary.Total             number
summary.Killed            number
summary.Survived          number
summary.Errors            number
summary.SkippedScenarios  number, when differential mutation skipped scenarios
summary.SkippedMutations  number, when differential mutation skipped scenarios
results                   array
```

Each result object must include:

```text
Mutation.ID           string
Mutation.Path         string
Mutation.Description  string
Mutation.Original     string
Mutation.Mutated      string
Status                "killed", "survived", or "error"
Output                string
Error                 string
Duration              implementation-defined duration value
```

Implementations may choose idiomatic JSON key casing, but they should document
it and keep it stable.

## Conformance Checklist

A conforming parser and mutator can be validated with these cases:

1. Parser accepts `Feature:`, `Background:`, `Scenario:`, `Scenario Outline:`,
   supported steps, placeholders, and examples tables.
2. Parser writes the JSON IR shape defined in this document.
3. Parser rejects a file with no feature declaration.
4. Parser rejects examples outside a scenario.
5. Parser rejects an examples data row whose cell count differs from the
   header.
6. Parser preserves scenario, step, and example row order.
7. Parser records parameters from step text in appearance order.
8. Mutator generates mutations only for example cell values.
9. Mutator produces stable mutation IDs, paths, and descriptions.
10. Mutator applies the portable value mutation rules.
11. Mutator deep-copies the IR before applying each mutation.
12. Mutator generates or prepares executable test entry points once for a
    value-only mutation run.
13. Mutator runs the same generated test entry points against each mutated JSON
    IR.
14. Mutator supports simple runner mode.
15. Mutator supports or allows persistent worker mode for efficient mutation
    execution.
16. Mutator classifies failing generated tests as `killed`.
17. Mutator classifies passing generated tests as `survived`.
18. Mutator classifies parsing, IR writing, timeout, runner, protocol, and
    infrastructure failures as `error`.
19. Mutator exits with `1` when any mutation survives or errors.
20. Mutator emits text and JSON reports in stable order.
21. Mutator emits periodic status lines to standard error without corrupting
    report output on standard output.
22. Mutator supports differential levels `full`, `hard`, and `soft`, with
    `hard` as the default.
23. Mutator ignores stamps and manifests at `full` level.
24. Mutator at `hard` level skips only clean manifest scenarios whose feature
    identity, background hash, scenario hash, and implementation hash match.
25. Mutator at `soft` level skips clean manifest scenarios whose feature
    identity, background hash, and scenario hash match, even when the
    implementation hash differs.
26. Mutator rejects stale manifests when the background hash changes, and
    reruns changed scenarios when their scenario hash changes.
27. Mutator writes a fresh scenario manifest and feature mutation stamp after a
    successful mutation run.

