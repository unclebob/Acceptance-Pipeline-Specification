# Acceptance Pipeline Specification

## Purpose

This document defines a portable acceptance-test pipeline that agents can install in a new project. The pipeline turns a small Gherkin feature file into a JSON intermediate representation, generates executable acceptance tests from that IR, runs those tests, and runs acceptance mutation against the Gherkin examples to measure whether those tests are fully connected to the application they test.

In this specification, acceptance mutation means mutating Gherkin example values in the specification-derived JSON IR. It does not mean conventional mutation testing of application source code.

This specification is intentionally implementation-language and project neutral. The strategy it implements should work for any language and any project.

## Pipeline Overview

The pipeline has two operating modes.

Normal acceptance run:

```text
feature file
  -> gherkin parser
  -> JSON IR
  -> acceptance generator
  -> generated acceptance tests
  -> project test runner
```

Acceptance mutation run:

```text
feature file
  -> gherkin parser
  -> base JSON IR
  -> mutator builds one changed IR per mutation
  -> acceptance generator creates tests for each changed IR
  -> project test runner evaluates each mutation
  -> mutation report
```

The normal run proves that the project satisfies the feature file. The acceptance mutation run probes whether the generated acceptance tests are strong enough to fail when example data is changed.

## Required Project Layout

A conforming setup should create these paths or their project-specific equivalents:

```text
features/a-feature.feature
build/acceptance/a-feature.json
build/acceptance-mutation/
acceptance/generated/
```

Recommended command entry points:

```text
gherkin-parser <feature-file> <json-output>
acceptance-generator <json-ir> <generated-test-output>
gherkin-mutator [options]
```

### Generated Tests

The exact executable format of the generated tests is project-specific. Generated tests typically run in the same environment as the project's unit tests, but they should be kept in a separate generated-test location.

## Required Components

An implementation must provide:

1. Gherkin parser: Reads the supported Gherkin subset and writes the JSON IR.
2. JSON IR reader/writer: Loads and stores the canonical feature representation.
3. Acceptance generator: Converts JSON IR into executable acceptance tests for the project.
4. Acceptance runtime: Expands the IR into scenario executions and dispatches steps to project handlers.
5. Project step handlers: Bind exact step text to project behavior and assertions.
6. Test runner adapter: Runs generated tests and captures status, output, and error text.
7. Mutator: Builds and applies deterministic example-value mutations.
8. Acceptance mutation reporter: Emits text or JSON reports and returns the correct exit code.
9. Convenience scripts: Provide a stable one-command normal run and acceptance mutation run.

## Normal Acceptance Script

Agents should install a script equivalent to this POSIX shell example:

```sh
#!/bin/sh
set -eu

mkdir -p build/acceptance acceptance/generated

gherkin-parser \
  features/a-feature.feature \
  build/acceptance/a-feature.json

acceptance-generator \
  build/acceptance/a-feature.json \
  acceptance/generated/a-feature_acceptance_test.<test-extension>

<project-test-command> acceptance/generated
```

Script requirements:

1. Stop on the first failed command.
2. Create required output directories before writing files.
3. Treat parser, generator, and test failures as script failures.
4. Never run generated tests against the source feature file directly; generated tests must be created from the JSON IR every time.

## Acceptance Mutation Script

Agents should install a script equivalent to this POSIX shell example:

```sh
#!/bin/sh
set -eu

gherkin-mutator --feature features/a-feature.feature "$@"
```

The mutator command owns parsing, Gherkin example mutation, generation, test execution, and reporting.

## Gherkin Parser Command

The parser command accepts exactly two positional arguments:

```text
gherkin-parser <feature-file> <json-output>
```

Exit codes:

```text
0  parse succeeded and JSON IR was written
1  input/output/parsing error
2  wrong command usage
```

The parser reads the feature file, parses the supported Gherkin subset, and writes pretty-printed JSON IR.

## Supported Gherkin Syntax

The parser accepts a small, deterministic subset of Gherkin.

### General Rules

Blank lines are ignored.

Lines whose first non-whitespace character is `#` are ignored.

Leading and trailing whitespace are ignored before parsing each line.

Free-form lines that do not match supported syntax are ignored. This allows brief feature descriptions, but they are not preserved in the IR.

The parser must preserve the order of background steps, scenarios, scenario steps, and example rows. Example columns become object keys in the JSON IR; consumers that need deterministic key traversal must sort keys explicitly.

### Feature

A feature file must contain a feature declaration:

```gherkin
Feature: <feature name>
```

The feature name is the trimmed text after `Feature:`.

A missing feature declaration is an error.

If multiple feature declarations appear, a conforming feature should treat that as invalid or use the last declaration consistently. New projects should use exactly one.

### Background

A feature may contain one background:

```gherkin
Background:
  Given <step text>
  And <step text>
```

Background steps are prepended to every scenario execution by the acceptance runtime.

If multiple background sections are present, portable behavior is undefined. New projects should use at most one.

### Scenarios

The parser accepts both `Scenario:` and `Scenario Outline:`:

```gherkin
Scenario: <scenario name>
  Given <step text>
  When <step text>
  Then <step text>

Scenario Outline: <scenario name>
  Given <step text containing <parameter_name>>

  Examples:
    | parameter_name |
    | value          |
```

Both forms produce the same JSON IR shape. Scenarios with examples can be mutated.

A scenario without examples is valid and executes once with an empty example object. Such scenarios cannot be mutated.

### Steps

Supported step keywords:

```text
Given
When
Then
And
```

A step line must be one of:

```gherkin
Given <step text>
When <step text>
Then <step text>
And <step text>
```

The keyword is stored separately from the step text. The step text is the trimmed text after the keyword.

A step outside a background or scenario is an error.

### Parameters

Parameters are placeholders inside step text:

```text
<parameter_name>
```

Parameter names must match:

```text
[A-Za-z0-9_]+
```

The parser records parameter names in the order they appear in each step's text. Repeated parameter names should be preserved as repeated entries.

Parameters are not expanded by the parser. They remain in the step text and are resolved by the acceptance runtime using the current example object.

### Examples Tables

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

1. A row is recognized only when the trimmed line starts with `|`.
2. Leading and trailing `|` characters are removed.
3. The remaining text is split on `|`.
4. Each cell is trimmed.
5. The first row after `Examples:` is the header row.
6. Every data row must have the same number of cells as the header row.
7. Header names become JSON object keys.
8. Cell values are stored as strings.

Unsupported syntax:

```text
tags
rules
localized keywords
escaped pipes
quoted table cells
multiline cells
doc strings
data tables attached to steps
step-level comments with semantic meaning
```

## JSON Intermediate Representation

The JSON IR is the canonical data structure consumed by the generator, runtime, and mutator.

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

If `examples` is empty, the runtime must execute the scenario once with an empty example object.

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
keyword  one of "Given", "When", "Then", "And"
text     string
```

Optional fields:

```text
parameters  array of strings; omit or use [] when no placeholders are present
```

The `parameters` field is derived from `text`. Generators and runtimes should treat `text` as authoritative and may validate that `parameters` agrees with the placeholders found in `text`.

### Example Object

An example object maps parameter or column names to string values:

```json
{
  "input": "42",
  "command": "calculate total",
  "expected_status": "accepted"
}
```

All values must be strings, even when they represent numbers, lists, commands, booleans, messages, or enums.

## Acceptance Generator Command

The generator command accepts exactly two positional arguments:

```text
acceptance-generator <json-ir> <generated-test-output>
```

Exit codes:

```text
0  generation succeeded
1  input/output/generation error
2  wrong command usage
```

The generator reads JSON IR and writes executable tests that execute the scenarios and examples represented by that IR.

Generator requirements:

1. Generated tests must embed or load the JSON IR supplied to the generator.
2. Generated tests must not parse the source Gherkin file.
3. Generated tests must run every scenario execution represented by the IR.
4. Generated tests must fail when the runtime reports an unsupported step, invalid example value, or failed assertion.
5. The generated output must be deterministic for a fixed IR.

The generated test format is implementation-specific.

## Acceptance Runtime

The runtime is the shared execution engine used by generated tests.

Runtime responsibilities:

1. Load or receive the JSON IR.
2. Expand each scenario into scenario executions.
3. For scenarios with examples, create one execution per example row.
4. For scenarios without examples, create one execution with an empty example object.
5. Prepend background steps to each execution.
6. Execute steps in order.
7. Resolve placeholder values from the current example object.
8. Route each step to a project step handler.
9. Report any unsupported step, missing value, invalid conversion, or failed assertion as a test failure.

Suggested execution naming:

```text
<scenario name>/example_<one-based-index>
```

For scenarios without examples, use `example_1` or another stable name.

## Step Handler Contract

Step handlers are the project-specific adapter layer. They connect exact Gherkin step text to project behavior.

The portable baseline matches handlers by exact `text` value, not by keyword:

```text
"the result is <result>"
```

Handler inputs:

```text
world/state object for the current scenario execution
example values for the current scenario execution
```

Handler outputs:

```text
success
failure with diagnostic text
```

Handler requirements:

1. A scenario execution must get a fresh world/state object.
2. Background and scenario steps within the same execution share the same world/state object.
3. Handlers must fetch placeholder values by name from the current example object.
4. Handlers must parse string values into project types as needed.
5. Missing, malformed, or semantically invalid values must fail the current test.
6. Unsupported step text must fail the current test.

A project may add regex or expression matching, but exact text matching is the portable baseline.

## Test Runner Adapter

The test runner adapter runs generated acceptance tests.

Inputs:

```text
generated test path or directory
timeout or cancellation signal
```

Outputs:

```text
passed?       boolean
output        combined standard output/error or equivalent diagnostic text
error text    infrastructure error, command failure text, or empty string
duration      elapsed time
```

The adapter must distinguish:

```text
test failure         generated tests ran and failed
test success         generated tests ran and passed
infrastructure error tests could not be generated, started, completed, or evaluated
```

The mutator uses that distinction for result classification.

## Gherkin Mutator Command

The mutator command should expose these options:

```text
gherkin-mutator [options]

Options:
  --feature <path>    Gherkin feature file to parse and mutate.
                      Default: features/a-feature.feature

  --work-dir <path>   Directory where mutation work files are written.
                      Default: build/acceptance-mutation

  --workers <count>   Maximum number of mutation workers to run in parallel.
                      Values less than 1 must be treated as 1.

  --timeout <duration>
                      Timeout for the full acceptance mutation run.
                      Duration syntax is implementation-defined but should support seconds.

  --status-interval <duration>
                      Interval for periodic status lines while mutations are running.
                      Default: 30s. A value of 0 disables periodic status.

  --level <level>     Differential mutation level: full, hard, or soft.
                      Default: hard.

  --json              Emit JSON report instead of text report.
```

Exit codes:

```text
0  all mutations were killed and no errors occurred
1  at least one mutation survived, or at least one mutation produced a setup/tool error
2  command-line usage or option parsing error
```

## Gherkin Example Mutation Model

The mutator creates candidate mutations from scenario example values. It does not mutate feature names, scenario names, step text, step keywords, background steps, or example headers.

For each scenario, for each example row, for each example key in lexicographic order:

1. Read the original string value.
2. Compute the mutated value using the value mutation rules.
3. If the mutated value is identical to the original value, skip it.
4. Create one mutation that changes only that single example cell.

The original JSON IR must not be modified in place. Each mutation is applied to a deep copy of the base IR.

### Mutation Identity

Mutation IDs must be stable and deterministic for a fixed input IR:

```text
m1
m2
m3
...
```

Mutation paths must use this format:

```text
$.scenarios[<scenario_index>].examples[<example_index>].<key>
```

Indexes are zero-based. Keys are the literal example object keys.

Mutation descriptions should use:

```text
<path>: <original> -> <mutated>
```

### Value Mutation Rules

Values are strings. The mutator infers a portable value type from the
string content, then changes the value without using project-specific
semantics. Random choices must be pseudo-random and deterministic for a
fixed mutation path and original value, so repeated runs over the same IR
produce the same mutation set.

Before selecting a mutation rule, compute:

```text
trimmed = value with leading and trailing whitespace removed
```

Rules are applied in this order:

1. If `trimmed` contains a comma, treat it as a comma-delimited list. Split on commas, trim each item, mutate one selected item recursively using these same rules, and join the list with `, `. The selected item must be chosen pseudo-randomly and deterministically.
2. If lowercase `trimmed` is `true` or `false`, mutate it to the opposite lowercase boolean value.
3. If lowercase `trimmed` is `null`, `nil`, or `none`, mutate it to a non-empty dithered string.
4. If `trimmed` is a base-10 integer, mutate it to the decimal representation of the integer plus a pseudo-random nonzero integer delta.
5. If `trimmed` is a finite base-10 floating point number, mutate it to the decimal representation of the number plus a pseudo-random nonzero floating point delta.
6. If `trimmed` is an ISO-8601 date, time, or date-time value, mutate it by a pseudo-random nonzero amount appropriate to the represented precision.
7. If `trimmed` is a recognized duration value, mutate it by a pseudo-random nonzero amount while preserving valid duration syntax.
8. Otherwise, dither the original untrimmed string.

String dithering must produce a different string by applying one small
edit, such as inserting a character, deleting a character, replacing a
character, swapping adjacent characters, or changing character case. Empty
strings are dithered by inserting a character.

The portable mutator must not define command, enum, or domain-specific
swaps. Project-specific semantic mutations belong in the project adapter
or in a project-specific mutator extension.

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

Projects may define filters that skip semantically equivalent mutations. Filters are project-specific and belong in the project adapter, not in the portable mutator core.

Filter requirements:

1. Filters must be deterministic.
2. Filters must run before creating a mutation entry.
3. The report's `total` count must include only mutations that were executed.
4. Filtered mutations should not appear in the result list unless the project explicitly adds a separate skipped report.

### Differential Mutation

Acceptance mutation may be run differentially. A differential run reuses previous successful mutation results when it can prove that the relevant feature content and mutation implementation have not changed. Differential mutation is an optimization only; it must not change the meaning of killed, survived, or error results.

There are two reuse mechanisms:

1. A feature mutation stamp may be used as a whole-file shortcut when the feature has no scenario manifest and the selected level is not `full`. The stamp records a hash of the feature content excluding the stamp line itself. If the stamp is present and valid, the mutator may skip the entire feature and exit successfully.
2. A scenario manifest may be used for scenario-level reuse. The manifest records enough information to decide which scenarios can be skipped and which scenarios must be rerun.

A feature mutation stamp should use this comment form:

```text
# mutation-stamp: sha256=<feature-content-hash>
```

The feature content hash must be computed over the feature file after removing the first mutation-stamp line. A stale, missing, malformed, or mismatched stamp must not be trusted.

A scenario manifest should be stored as a comment block near the top of the feature file:

```text
# acceptance-mutation-manifest-begin
# { ... JSON manifest ... }
# acceptance-mutation-manifest-end
```

The JSON manifest must contain:

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

The `background_hash` must cover all background steps, because a background change can affect every scenario. The `scenario_hash` must cover the scenario name, scenario steps, example headers, and example values. Header order must be deterministic. The `implementation_hash` must identify the acceptance mutation implementation and every project adapter component whose behavior can affect mutation generation, filtering, execution, or classification.

When a scenario manifest is accepted, a scenario may be skipped only when all of these are true:

1. The manifest version is supported.
2. The manifest feature name and feature path match the current feature.
3. The manifest background hash matches the current background hash.
4. The manifest implementation hash is valid for the selected differential level.
5. The manifest has an entry for the same scenario index.
6. The entry scenario name and scenario hash match the current scenario.
7. The entry has zero survived mutations and zero errors.

Skipped scenarios keep their previous manifest entries, including their previous `tested_at` values. Executed scenarios receive new result summaries and timestamps. Deleted scenarios must be removed from the next manifest. A successful acceptance mutation run should write a fresh scenario manifest and a fresh feature mutation stamp.

Differential levels:

```text
full  ignore stamps and manifests; execute every mutation
hard  reuse only when feature identity, scenario content, background content,
      and implementation hash all match
soft  reuse when feature identity, scenario content, and background content
      match, even if the implementation hash changed
```

`hard` is the default because it avoids reusing results after changes to the parser, generator, mutator, filters, runner adapter, or runtime. `soft` is useful when implementation changes are known not to affect acceptance mutation behavior. `full` is useful for scheduled verification, baseline refreshes, and debugging stale-manifest suspicion.

## Acceptance Mutation Execution

For each mutation:

1. Create a mutation work directory:

   ```text
   <work-dir>/<mutation-id>/
   ```

2. Write the mutated JSON IR to:

   ```text
   <work-dir>/<mutation-id>/feature.json
   ```

3. Ask the acceptance generator to generate executable tests from that IR.

4. Place generated tests under:

   ```text
   <work-dir>/<mutation-id>/generated/
   ```

5. Run the generated tests using the test runner adapter.

6. Classify the result.

Parallel workers may execute different mutations concurrently. Each mutation must write only inside its own mutation work directory.

The timeout applies to the full acceptance mutation run. When the timeout expires, unfinished mutations should be reported as `error` with useful timeout text.

If differential mutation skips scenarios, the report should include the skipped scenario count and skipped mutation count separately from the executed mutation totals.

## Acceptance Mutation Status

The mutator should emit periodic status lines while an acceptance mutation run is active, so agents and continuous-integration logs can distinguish a long-running run from a hung process.

Status lines must be written to standard error. Standard output is reserved for the final text or JSON report, and `--json` output must remain valid JSON without progress records mixed into it.

The mutator should emit:

1. One status line after mutation discovery and before executing the first mutation.
2. One status line at least every `--status-interval` while at least one mutation is still running.
3. One status line when execution finishes, before the final report is emitted.

Status lines should be single-line, stable, human-readable records using this form:

```text
status elapsed=<duration> total=<total> completed=<completed> running=<running> killed=<killed> survived=<survived> errors=<errors> skipped_scenarios=<count> skipped_mutations=<count>
```

`skipped_scenarios` and `skipped_mutations` may be omitted when no differential mutation skip occurred. `completed` counts only executed mutations that have reached `killed`, `survived`, or `error`. `running` counts mutations currently assigned to workers. The final status line should have `running=0` and `completed` equal to the executed mutation total.

The status interval is best-effort: implementations may delay a status line while synchronously parsing, generating, or waiting for a test runner, but long-running worker orchestration should continue to report progress.

## Result Classification

Each mutation has one of these statuses:

```text
killed
survived
error
```

Classification rules:

```text
killed   generated tests failed after the mutation was applied
survived generated tests passed after the mutation was applied
error    parsing, IR writing, generation, timeout, runner startup, or infrastructure failed
```

A killed mutation means the acceptance tests detected the changed specification value.

A survived mutation means the acceptance tests did not detect the changed specification value and should be investigated.

An error is not a test-quality result; it means the mutation could not be evaluated reliably.

## Text Acceptance Mutation Report

The default text report starts with one summary line:

```text
total=<total> killed=<killed> survived=<survived> errors=<errors>
```

When differential mutation skips scenarios, the report should also include:

```text
skipped_scenarios=<count> skipped_mutations=<count>
```

It then prints one line per result:

```text
<status> <path>: <original> -> <mutated>
```

Status should be left-aligned to 8 characters for readability.

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

## JSON Acceptance Mutation Report

When `--json` is supplied, the report must be a JSON object:

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
summary.Total     number
summary.Killed    number
summary.Survived  number
summary.Errors    number
summary.SkippedScenarios  number, when differential mutation skipped scenarios
summary.SkippedMutations  number, when differential mutation skipped scenarios
results           array
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

Implementations may choose idiomatic JSON key casing, but they should document it and keep it stable.

## Agent Setup Checklist

When installing this pipeline in a new project, an agent should:

1. Create `features/a-feature.feature` with at least one scenario that exercises real project behavior.
2. Implement the Gherkin parser command.
3. Implement JSON IR reader/writer support.
4. Implement the acceptance runtime that expands scenarios, applies backgrounds, and dispatches steps.
5. Implement project step handlers for every step text in the feature file.
6. Implement the acceptance generator command.
7. Add the normal acceptance script.
8. Run the normal acceptance script and confirm generated tests pass.
9. Implement the mutator command using the same parser, IR, generator, and test runner adapter.
10. Add the acceptance mutation script.
11. Run the acceptance mutation script and inspect survived mutations.
12. Add or improve acceptance scenarios until important mutations are killed.
13. Add parser, generator, runtime, and mutator unit tests.
14. Add the normal acceptance script to the project's regular verification workflow.
15. Add the acceptance mutation script to an explicit quality workflow, because acceptance mutation may be slower than normal verification.

## Conformance Checklist

A conforming implementation can be validated with these cases:

1. Parser accepts `Feature:`, `Background:`, `Scenario:`, `Scenario Outline:`, supported steps, parameter placeholders, and examples tables.
2. Parser writes the JSON IR shape defined in this document.
3. Parser rejects a file with no feature declaration.
4. Parser rejects examples outside a scenario.
5. Parser rejects an examples data row whose cell count differs from the header.
6. Generator creates deterministic executable tests from JSON IR.
7. Generated tests execute the IR they were generated from.
8. Runtime applies background steps before every scenario execution.
9. Runtime executes scenarios without examples once.
10. Runtime fails unsupported step text.
11. Runtime fails invalid or missing example values.
12. Normal acceptance script fails if parsing, generation, or generated tests fail.
13. Mutator generates mutations only for example cell values.
14. Mutator produces stable mutation IDs, paths, and descriptions.
15. Mutator applies comma-list, boolean, null-like, integer, floating point, date/time, duration, and string-dithering value mutation rules.
16. Mutator deep-copies the IR before applying each mutation.
17. Mutator classifies failing generated tests as `killed`.
18. Mutator classifies passing generated tests as `survived`.
19. Mutator classifies parsing, generation, timeout, and infrastructure failures as `error`.
20. Mutator exits with `1` when any mutation survives or errors.
21. Mutator emits text and JSON reports in stable order.
22. Mutator emits periodic status lines to standard error without corrupting text or JSON reports on standard output.
23. Mutator supports differential levels `full`, `hard`, and `soft`, with `hard` as the default.
24. Mutator ignores stamps and manifests at `full` level.
25. Mutator at `hard` level skips only clean manifest scenarios whose feature identity, background hash, scenario hash, and implementation hash match.
26. Mutator at `soft` level skips clean manifest scenarios whose feature identity, background hash, and scenario hash match, even when the implementation hash differs.
27. Mutator rejects stale manifests when the background hash changes, and reruns changed scenarios when their scenario hash changes.
28. Mutator writes a fresh scenario manifest and feature mutation stamp after a successful acceptance mutation run.
