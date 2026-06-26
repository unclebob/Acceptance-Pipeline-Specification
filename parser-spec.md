# Parser Specification

## Purpose

This document specifies `bb gherkin-parser`, the portable command that converts
a small deterministic Gherkin subset into the JSON intermediate representation
used by the acceptance generator, runtime, and mutator.

## Parser Command

The parser task accepts exactly two positional arguments:

```text
bb gherkin-parser [--do-not-infer] <feature-file> <json-output>
```

Options:

```text
--do-not-infer
    Disable parameter inference. When omitted, inference is enabled by default.
```

Exit codes:

```text
0  parse succeeded and JSON IR was written
1  input/output/parsing error
2  command-line usage error
```

The parser reads the source feature file and writes pretty-printed JSON.

## Supported Gherkin Subset

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

## General Parsing Rules

1. Blank lines are ignored.
2. Lines whose first non-whitespace character is `#` are ignored.
3. Leading and trailing whitespace are removed before classifying each line.
4. Free-form lines that do not match supported syntax are ignored.
5. Order must be preserved for background steps, scenarios, scenario steps, and
   example rows.
6. Example object key traversal is not guaranteed by JSON object order; any
   consumer that needs stable key order must sort keys explicitly.

## Feature Authoring

Authors should write constant values as literals in step and background text.
The parser infers parameters from those literals when producing JSON IR.

Write fixed values directly in steps:

```gherkin
Given balance is 100
When the customer withdraws 20
Then the remaining balance is 80
```

The parser emits IR with inferred placeholders such as `<p1>`, `<p2>`, and
`<p3>` plus matching example columns. See [Parameter Inference](#parameter-inference).

Use an `Examples:` table for values that change from row to row. Reference those
varying values with explicit `<column_name>` placeholders in step text. If every
row repeats the same value for a column, remove that column and write the value
as a literal in a step or in `Background:` instead.

Prefer this shape:

```gherkin
Background:
  Given balance is 100

Scenario Outline: Withdraw cash
  When the customer withdraws <amount>
  Then the remaining balance is <remaining>

Examples:
  | amount | remaining |
  | 20     | 80        |
  | 5      | 45        |
```

over repeating a constant column:

```gherkin
Examples:
  | balance | amount | remaining |
  | 100     | 20     | 80        |
  | 100     | 5      | 45        |
```

The second form is valid, but the constant `balance` column is better expressed
once in `Background:` or in a `Given` step. After parsing, the IR is the same
either way.

Use explicit `<placeholders>` only where example values vary per row. The parser
does not rewrite source feature files.

## Feature Rules

A valid feature file must contain:

```gherkin
Feature: <feature name>
```

The feature name is the trimmed text after `Feature:`.

A missing feature declaration is a parsing error.

New feature files should contain exactly one feature declaration. If multiple
feature declarations are encountered, an implementation must either reject the
file or consistently use one documented behavior.

## Background Rules

A feature may contain one background:

```gherkin
Background:
  Given a configured project state
```

Background steps are stored separately in the JSON IR. The runtime prepends
them to every scenario execution.

Portable behavior is undefined for multiple background sections. New feature
files should use at most one.

## Scenario Rules

The parser accepts:

```gherkin
Scenario: <scenario name>
Scenario Outline: <scenario name>
```

Both forms produce the same JSON shape.

A scenario without examples is valid. Before parameter inference, it executes
once with an empty example object. After inference, it may gain synthesized
example rows and become mutable.

A scenario with examples executes once per example row and can be mutated.

## Step Rules

A step line must start with one of:

```text
Given
When
Then
And
```

The keyword and text are stored separately.

For this source input:

```gherkin
Then the result is accepted
```

the keyword is:

```text
Then
```

and the text is:

```text
the result is accepted
```

After parameter inference, the IR step text becomes `the result is <p1>` with
example value `p1 = accepted`.

A step outside a background or scenario is a parsing error.

## Parameter Rules

Parameters are placeholders in JSON IR step text:

```text
<parameter_name>
```

They are usually produced by parameter inference from literals in source
Gherkin. Authors may still write explicit placeholders in feature files; the
parser records them as written.

Parameter names must match:

```text
[A-Za-z0-9_]+
```

Inferred parameters use generated names `p1`, `p2`, and so on. Explicit author
placeholders keep their written names.

The parser records parameter names in the order they appear in step text.
Repeated parameter names must be preserved as repeated entries.

The parser does not expand parameters. Parameter expansion is a runtime
responsibility.

## Examples Table Rules

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
          "text": "the result is <p1>",
          "parameters": ["p1"]
        }
      ],
      "examples": [
        {
          "p1": "accepted"
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
  "text": "the input is <p1>",
  "parameters": ["p1"]
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
  "p1": "42",
  "p2": "calculate total",
  "p3": "accepted"
}
```

All values must be strings, even when they represent numbers, booleans, lists,
dates, commands, messages, or enums.

## Parameter Inference

Parameter inference is enabled by default. It does not modify the source Gherkin
file. It rewrites the JSON IR so literal values in step text are represented as
placeholders with matching example columns.

Use `--do-not-infer` to emit the IR without this pass.

### Purpose

Turn literal step values into example-backed parameters so the runtime can
resolve them and the mutator can change them. This is the normal path for
feature files written with literals in steps and background, as recommended in
[Feature Authoring](#feature-authoring).

For this source Gherkin:

```gherkin
Given A is 1
```

the inferred IR is:

```json
{
  "keyword": "Given",
  "text": "A is <p1>",
  "parameters": ["p1"]
}
```

with:

```json
"examples": [{ "p1": "1" }]
```

### When Inference Runs

1. Run after normal line parsing and before JSON output.
2. Skip entirely when `--do-not-infer` is supplied.
3. Scan background steps and scenario steps in order.
4. Do not infer spans already written as explicit `<parameter_name>`
   placeholders.

### Promotable Literal Patterns

A step-text span is promotable when it matches one of these patterns. Apply
patterns without a stopword denylist. Each matching span is promoted at most
once.

1. **Quoted string** — a double-quoted span in step text. The promoted value is
   the trimmed content inside the quotes, without the quote characters.
2. **Comma-separated value list** — text containing a comma that splits into two
   or more trimmed non-empty parts, each of which is itself promotable by these
   rules or is a typed literal. The whole span is one parameter.
3. **Typed literal** — base-10 integer, base-10 finite floating-point number,
   boolean `true` or `false`, null-like `null`, `nil`, or `none`, or ISO-8601
   date, time, or date-time value.
4. **Copula identifier** — the single whitespace-delimited token immediately
   after `is`, `equals`, or `has`, when that token is not itself an explicit
   `<parameter_name>` placeholder.

Examples:

```text
Given A is 1                     -> A is <p1>                     p1 = 1
Then status is accepted          -> status is <p1>                 p1 = accepted
Given message is "hello world"   -> message is <p1>                p1 = hello world
Then totals are 2, 5, 8          -> totals are <p1>                p1 = 2, 5, 8
```

Spans that do not match a promotable pattern remain literal in step text.

### Parameter Naming

Generated parameter names must match:

```text
[A-Za-z0-9_]+
```

Assign names in occurrence order:

1. Walk background steps top to bottom.
2. Walk the scenario steps top to bottom.
3. Within each step, scan step text left to right.
4. Assign the next name `p1`, `p2`, `p3`, ... for each promoted span.
5. Skip any generated name already used as an example column in the current
   scenario.

There is no deduplication by value within a scenario. The same literal in two
places becomes two parameters.

```gherkin
Given A is 1
And B is 1
```

becomes `p1` and `p2`, both with value `"1"`.

Explicit author placeholders keep their names and are independent from generated
`pN` names.

### Merging Into Example Tables

Inference must merge generated parameters into the scenario example table.

1. Rewrite promoted spans in step `text` to `<pN>`.
2. Record `pN` in step `parameters` in left-to-right appearance order, mixing
   explicit and inferred placeholders as they appear.
3. If the scenario already has example rows, append each `pN` column to every row.
4. If the scenario has no example rows, synthesize one row containing all
   inferred columns for that scenario.
5. Never remove, rename, or overwrite existing example columns or cell values.

Static literals in step text produce the same inferred value on every example
row.

For this source Gherkin:

```gherkin
Scenario Outline: Withdraw cash
  Given balance is 100
  When the customer withdraws <amount>
  Then the remaining balance is <remaining>

Examples:
  | amount | remaining |
  | 20     | 80        |
  | 5      | 45        |
```

the inferred IR is:

```json
{
  "steps": [
    {
      "keyword": "Given",
      "text": "balance is <p1>",
      "parameters": ["p1"]
    },
    {
      "keyword": "When",
      "text": "the customer withdraws <amount>",
      "parameters": ["amount"]
    },
    {
      "keyword": "Then",
      "text": "the remaining balance is <remaining>",
      "parameters": ["remaining"]
    }
  ],
  "examples": [
    { "amount": "20", "remaining": "80", "p1": "100" },
    { "amount": "5", "remaining": "45", "p1": "100" }
  ]
}
```

### Background Inference

Infer literals from background steps the same way as scenario steps.

Merge background-generated columns into every scenario example table in the
feature. If a scenario has no example rows, synthesize a row containing the
background-generated columns for that scenario.

### Example Parameter Validation

After inference, the parser must reject the feature when any explicit
`<parameter_name>` placeholder in background or scenario step text does not
have a matching example column in that scenario's example rows.

This validation applies to author-written placeholders only. Generated `pN`
columns do not satisfy missing explicit placeholders.

For background placeholders, the matching column must be present in each
scenario's example rows.

## Parser Conformance Checklist

1. Parser accepts `Feature:`, `Background:`, `Scenario:`, `Scenario Outline:`,
   supported steps, placeholders, and examples tables.
2. Parser writes the JSON IR shape defined in this document.
3. Parser rejects a file with no feature declaration.
4. Parser rejects examples outside a scenario.
5. Parser rejects an examples data row whose cell count differs from the
   header.
6. Parser preserves scenario, step, and example row order.
7. Parser records parameters from step text in appearance order.
8. Parser infers parameters by default, supports `--do-not-infer`, merges
   inferred columns into existing example tables, and rejects missing explicit
   placeholder columns after inference.
