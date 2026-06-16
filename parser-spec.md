# Parser Specification

## Purpose

This document specifies `bb gherkin-parser`, the portable command that converts
a small deterministic Gherkin subset into the JSON intermediate representation
used by the acceptance generator, runtime, and mutator.

## Parser Command

The parser task accepts exactly two positional arguments:

```text
bb gherkin-parser <feature-file> <json-output>
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

A scenario without examples is valid. It executes once with an empty example
object and cannot be mutated.

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

## Parameter Rules

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
