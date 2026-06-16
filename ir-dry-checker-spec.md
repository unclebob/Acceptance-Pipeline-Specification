# IR-DRY Checker Specification

## Purpose

This document specifies `bb gherkin-ir-dry-checker`, the portable report-only
command that reads one parser-produced JSON IR file and reports repeated,
near-duplicated, and possible-synonym step text.

The checker does not rewrite the JSON IR, generated entry points, runtime, or
project implementation files. Its output is advisory. Agents or developers
decide whether any reported finding should be addressed by normalizing or
pruning Gherkin in the source feature file.

The parser and JSON IR are specified in [parser-spec.md](parser-spec.md).

## Command

```text
bb gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>
```

The command accepts exactly two positional arguments:

1. `<json-ir>`: path to the parser-produced JSON IR file.
2. `<report-output>`: path where the JSON DRY report is written.

Exit codes:

```text
0  report generation succeeded
1  input/output/report generation error
2  wrong command usage
```

Options:

```text
--include-exact
    Include ordinary exact duplicate step text across the whole IR. By default,
    the checker reports only exact repeats within one background or scenario.
```

## Scope

The checker analyzes exactly one JSON IR file per invocation.

It must not read other feature files, source files, generated entry points,
runtime files, or project implementation files.

The checker reports findings across background and scenario steps represented
by the supplied IR. Step keywords are retained in finding locations but are not
part of step-text equivalence.

## Finding Categories

The checker may report these finding kinds:

```text
duplicate-in-scenario
exact-duplicate
placeholder-variant
near-duplicate
possible-synonym
```

### Duplicate In Scenario

A `duplicate-in-scenario` finding means the same step `text` appears more than
once in the same background or scenario.

This is the default exact-repeat finding because repeated identical steps
inside one execution are more likely to be accidental than repeated use of a
common step across several scenarios.

### Exact Duplicate

An `exact-duplicate` finding means the same step `text` appears more than once
in the IR. These findings are emitted only when `--include-exact` is supplied.

Repeated use of a step across scenarios is often normal. The finding exists to
help agents audit vocabulary reuse across the IR when `--include-exact` is
explicitly requested.

### Placeholder Variant

A `placeholder-variant` finding means two or more step texts become identical
after replacing placeholder names with generic ordered slots.

Example:

```text
the player is in room <destination_room>
the player is in room <expected_player_room>
```

Both normalize to:

```text
the player is in room <_1>
```

This is a high-confidence sign of possible feature-wording drift. Prefer
normalizing the Gherkin source when the different placeholder names do not add
meaning for the reader. If the different names do add meaning, the recommended
project runtime default is regex or expression matching that captures the
placeholder name and reads that key from the current example object, as
specified in [acceptance-generator.md](acceptance-generator.md).

### Near Duplicate

A `near-duplicate` finding means two step texts have high token similarity
after placeholder normalization.

Near-duplicate findings are advisory. They must not be treated as proof that
the two steps have identical behavior.

### Possible Synonym

A `possible-synonym` finding means two step texts have moderate token
similarity after placeholder normalization.

Possible-synonym findings are review prompts. They are expected to include
false positives and must be inspected by an agent or developer before any step
text is changed.

## Portable Similarity Rules

The portable baseline may use token-set similarity:

1. Replace placeholders with generic ordered slots.
2. Remove placeholders for token comparison.
3. Lowercase text.
4. Split text into alphanumeric tokens.
5. Ignore small function words.
6. Compute Jaccard similarity:

```text
shared_tokens / total_distinct_tokens
```

The baseline thresholds are:

```text
>= 0.72  near-duplicate
>= 0.45  possible-synonym
<  0.45  no finding
```

Implementations may add better language-neutral heuristics, but they must keep
findings advisory and report confidence clearly.

## JSON Report

The report is pretty-printed JSON.

Required top-level fields:

```json
{
  "schema_version": 1,
  "feature_name": "Feature name",
  "summary": {
    "step_occurrences": 0,
    "unique_steps": 0,
    "findings": 0
  },
  "findings": []
}
```

Each finding object includes:

```json
{
  "kind": "placeholder-variant",
  "confidence": "high",
  "canonical_candidate": "the player is in room <value>",
  "pattern_candidate": "^the player is in room (.+)$",
  "members": [
    {
      "text": "the player is in room <expected_player_room>",
      "locations": [
        {
          "section": "scenario",
          "scenario_index": 0,
          "scenario_name": "Scenario name",
          "step_index": 2,
          "keyword": "Then"
        }
      ]
    }
  ],
  "reason": "step text is identical after replacing placeholder names with generic slots",
  "suggested_action": "Review the feature wording and normalize the Gherkin if the different forms do not add meaning."
}
```

`score` may be included for similarity-based findings.

## Non-Goals

The checker must not:

1. Rewrite feature files.
2. Rewrite JSON IR.
3. Rewrite generated entry points.
4. Rewrite project implementation files.
5. Require knowledge of a project implementation language.
6. Treat possible synonyms as semantically equivalent without review.
7. Recommend implementation changes as a substitute for clear feature wording.

## Conformance Checklist

1. The command accepts exactly `<json-ir> <report-output>`.
2. The command exits with `0`, `1`, or `2` using the meanings specified above.
3. The command reads one parser JSON IR file.
4. The command writes a JSON report.
5. The report uses `schema_version`.
6. The report includes step occurrence, unique step, and finding counts.
7. The default report identifies duplicate step text within one background or
   scenario.
8. With `--include-exact`, the report identifies exact duplicate step text
   across the IR.
9. The report identifies placeholder variants.
10. Similarity-based findings are advisory and include confidence.
11. Finding locations identify background or scenario step positions.
12. The command does not modify input IR, feature files, generated files, or
    project implementation files.
