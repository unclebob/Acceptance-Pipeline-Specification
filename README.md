# Acceptance Pipeline Specification

## Purpose

This document defines a portable acceptance-test pipeline that agents can install in a new project. The pipeline turns a small Gherkin feature file into a JSON intermediate representation, generates executable acceptance tests from that IR, runs those tests, and mutation-tests the acceptance examples to measure whether those tests are fully connected to the application they test.

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

The normal run proves that the project satisfies the feature file. The mutation run probes whether the generated acceptance tests are strong enough to fail when example data is changed.

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
8. Mutation reporter: Emits text or JSON reports and returns the correct exit code.
9. Convenience scripts: Provide a stable one-command normal run and mutation run.

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

## Mutation Script

Agents should install a script equivalent to this POSIX shell example:

```sh
#!/bin/sh
set -eu

gherkin-mutator --feature features/a-feature.feature "$@"
```

The mutator command owns parsing, mutation, generation, test execution, and reporting.

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
  "input": "M 2",
  "command": "move 2",
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
                      Timeout for the full mutation run.
                      Duration syntax is implementation-defined but should support seconds.

  --json              Emit JSON report instead of text report.
```

Exit codes:

```text
0  all mutations were killed and no errors occurred
1  at least one mutation survived, or at least one mutation produced a setup/tool error
2  command-line usage or option parsing error
```

## Mutation Model

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

Values are strings. Before selecting a mutation rule, compute:

```text
trimmed = value with leading and trailing whitespace removed
```

Rules are applied in this order:

1. If `trimmed` contains a comma, treat it as a comma-delimited list. Split on commas, trim each item, mutate the first item recursively using these same rules, and join the list with `, `.
2. If `trimmed` is a base-10 integer, mutate it to the decimal representation of `integer + 1`.
3. If lowercase `trimmed` is one of the command swaps below, mutate to the mapped value.
4. Otherwise, mutate to `MUTATED: ` followed by the original untrimmed value.

Command swaps:

```text
move     -> stay
stay     -> move
move 2   -> shoot 2
shoot 2  -> move 2
m 2      -> s 2
s 2      -> m 2
```

Examples:

```text
20                  -> 21
2, 5, 8             -> 3, 5, 8
move 2              -> shoot 2
shoot 2             -> move 2
accepted            -> MUTATED: accepted
message with spaces -> MUTATED: message with spaces
```

### Equivalent Mutation Filters

Projects may define filters that skip semantically equivalent mutations. Filters are project-specific and belong in the project adapter, not in the portable mutator core.

Filter requirements:

1. Filters must be deterministic.
2. Filters must run before creating a mutation entry.
3. The report's `total` count must include only mutations that were executed.
4. Filtered mutations should not appear in the result list unless the project explicitly adds a separate skipped report.

## Mutation Execution

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

The timeout applies to the full mutation run. When the timeout expires, unfinished mutations should be reported as `error` with useful timeout text.

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

## Text Mutation Report

The default text report starts with one summary line:

```text
total=<total> killed=<killed> survived=<survived> errors=<errors>
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
killed   $.scenarios[0].examples[0].count: 20 -> 21
survived $.scenarios[1].examples[0].status: accepted -> MUTATED: accepted
  output:
<test runner output>
```

## JSON Mutation Report

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
        "Description": "$.scenarios[0].examples[0].count: 20 -> 21",
        "Original": "20",
        "Mutated": "21"
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
10. Add the mutation script.
11. Run the mutation script and inspect survived mutations.
12. Add or improve acceptance scenarios until important mutations are killed.
13. Add parser, generator, runtime, and mutator unit tests.
14. Add the normal acceptance script to the project's regular verification workflow.
15. Add the mutation script to an explicit quality workflow, because mutation testing may be slower than normal verification.

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
15. Mutator applies integer, comma-list, command-swap, and generic-string value mutation rules.
16. Mutator deep-copies the IR before applying each mutation.
17. Mutator classifies failing generated tests as `killed`.
18. Mutator classifies passing generated tests as `survived`.
19. Mutator classifies parsing, generation, timeout, and infrastructure failures as `error`.
20. Mutator exits with `1` when any mutation survives or errors.
21. Mutator emits text and JSON reports in stable order.
