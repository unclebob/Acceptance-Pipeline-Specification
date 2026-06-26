# Acceptance Entrypoint Generator Specification

## Purpose

This document specifies `acceptance-entrypoint-generator`, the project-specific
command that turns parser JSON IR into executable acceptance test entry points.

The generator creates thin test entry points only. It must not generate
application bindings or semantically meaningful step handlers. Agents or
developers create project step handlers as needed to connect step text to real
application behavior and assertions.

The parser and JSON IR are specified in [parser-spec.md](parser-spec.md).

## Command

```text
acceptance-entrypoint-generator <json-ir> <generated-test-output>
```

The command accepts exactly two positional arguments:

1. `<json-ir>`: path to the parser-produced JSON IR file.
2. `<generated-test-output>`: directory or file path where generated test entry
   points are written, according to the project generator's convention.

Exit codes:

```text
0  entrypoint generation succeeded
1  input/output/entrypoint generation error
2  wrong command usage
```

## Generated Entry Points

The entrypoint generator reads JSON IR and writes executable test entry points
that select scenarios and examples represented by that IR.

Entrypoint generator requirements:

1. Generated entry points must embed or load the JSON IR supplied to the
   entrypoint generator.
2. Generated entry points must not parse the source Gherkin file.
3. Generated entry points must run every scenario execution represented by the
   IR.
4. Generated entry points must delegate all step behavior to the acceptance
   runtime and project step handlers.
5. Generated entry points must fail when the runtime reports an unsupported
   step, invalid example value, or failed assertion.
6. Generated output must be deterministic for a fixed IR.
7. Generated metadata must include an `implementation_hash` computed only from
   generated acceptance files.

The generated test format is implementation-specific.

## Acceptance Runtime Contract

The runtime is the shared execution engine used by generated tests.

Runtime responsibilities:

1. Load or receive the JSON IR.
2. Expand each scenario into scenario executions.
3. For scenarios with examples, create one execution per example row.
4. For scenarios without examples, create one execution with an empty example
   object.
5. Prepend background steps to each execution.
6. Execute steps in order.
7. Resolve placeholder values from the current example object.
8. Route each step to a project step handler.
9. Report any unsupported step, missing value, invalid conversion, or failed
   assertion as a test failure.

Suggested execution naming:

```text
<scenario name>/example_<one-based-index>
```

For scenarios without examples, use `example_1` or another stable name.

## Step Handler Contract

Step handlers are the project-specific adapter layer. They connect Gherkin step
text to project behavior.

Handlers match JSON IR step text, not the original source feature file. After
parameter inference, IR step text contains placeholders such as `<p1>` for
inferred literals and `<amount>` for explicit example columns that vary per row.
See [parser-spec.md](parser-spec.md#feature-authoring).

Recommended project runtimes use regex or expression matching that extracts
placeholder names from the matched step text. The handler then uses each
captured placeholder name as the key into the current example object.

The portable minimum remains exact `text` matching, not keyword matching, so a
minimal conforming runtime can match this step text directly:

```text
"the result is <p1>"
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
2. Background and scenario steps within the same execution share the same
   world/state object.
3. Handlers must fetch placeholder values by name from the current example
   object.
4. Handlers must parse string values into project types as needed.
5. Missing, malformed, or semantically invalid values must fail the current
   test.
6. Unsupported step text must fail the current test.

For normal project use, regex or expression matching with placeholder-name
capture should be the default style for step handlers. This lets one handler
support a consistent step vocabulary in IR while values come from the current
example object.

For example, these IR step texts:

```text
the player is in room <p1>
the player is in room <p2>
```

may be handled by a single project-specific pattern equivalent to:

```text
^the player is in room <([A-Za-z0-9_]+)>$
```

The handler receives the captured placeholder name, such as `p1` or `p2`, and
fetches that key from the example values. Regex or expression matching must
still fail ambiguous or unsupported step text, and projects should keep
patterns narrow enough that unrelated steps do not match the same handler.

Authors may still write varying example columns with explicit names such as
`<amount>`. Handlers then fetch those author names from the example object.

## Generator Metadata

The generator should write one metadata file per feature under:

```text
<generated-test-output-dir>/metadata/<feature-metadata-name>.json
```

The metadata filename is derived from the feature path by converting it to
lowercase, replacing every run of non-alphanumeric characters with one hyphen,
trimming leading and trailing hyphens, and appending `.json`.

Examples:

```text
features/Hunt The Wumpus.feature     -> metadata/features-hunt-the-wumpus-feature.json
features/orders/Cancel Order.feature -> metadata/features-orders-cancel-order-feature.json
Features/API v2/Happy Path.feature   -> metadata/features-api-v2-happy-path-feature.json
```

The metadata object should include:

```json
{
  "schema_version": 1,
  "feature_path": "features/Hunt The Wumpus.feature",
  "ir_path": "build/acceptance/hunt-the-wumpus.json",
  "implementation_hash": "sha256:<hash>",
  "hash_scope": "generated_files",
  "generated_files": [
    "acceptance/generated/a-feature_acceptance_test.<test-extension>"
  ]
}
```

Field meanings:

1. `schema_version`: metadata schema version. Current value is `1`.
2. `feature_path`: source feature path represented by this metadata file.
3. `ir_path`: JSON IR path used to generate the entry points.
4. `implementation_hash`: deterministic hash of generated acceptance files.
5. `hash_scope`: must be `generated_files`.
6. `generated_files`: generated acceptance files included in the hash.

## Implementation Hash

The implementation hash must cover generated acceptance files only.

It must not include:

1. Step handler files.
2. Acceptance runtime files.
3. Runner adapter files.
4. Application source files.
5. Parser files.
6. Mutator files.
7. Project-specific filters.

The mutator uses this hash to decide whether existing mutation manifest results
are still valid for the generated test entry points for a feature.

## Conformance Checklist

1. The command accepts exactly `<json-ir> <generated-test-output>`.
2. The command exits with `0`, `1`, or `2` using the meanings specified above.
3. Generated tests execute all scenario/example executions represented by the
   IR.
4. Generated tests delegate step behavior to runtime and step handlers.
5. Runtime applies background steps before every scenario execution.
6. Runtime executes scenarios without examples once.
7. Runtime fails unsupported step text.
8. Runtime fails invalid or missing example values.
9. Step handlers receive a fresh world/state object for each scenario
   execution.
10. Step handlers fail missing, malformed, semantically invalid, or unsupported
   steps.
11. Generated tests are deterministic for a fixed IR.
12. Metadata is written per feature under `metadata/`.
13. Metadata filenames follow the strict lowercase-and-hyphen mapping.
14. Metadata uses `schema_version`.
15. `implementation_hash` covers only generated acceptance files.
