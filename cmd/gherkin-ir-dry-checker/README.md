# Gherkin IR DRY Checker

`gherkin-ir-dry-checker` analyzes one APS Gherkin JSON IR file and writes a
JSON report describing duplicated or similar step text.

```sh
gherkin-ir-dry-checker <json-ir> <report-output>
```

Example:

```sh
gherkin-parser features/checkout.feature build/acceptance/ir/checkout.json
gherkin-ir-dry-checker build/acceptance/ir/checkout.json build/acceptance/dry/checkout.json
```

## What It Reports

The report may include:

- `exact-duplicate`: the same step text appears multiple times.
- `placeholder-variant`: steps differ mainly by placeholder names, such as
  `<room>` vs `<expected_room>`.
- `near-duplicate`: steps are textually similar enough to deserve review.
- `possible-synonym`: steps may express the same idea with different words.

## Intended Use

Use this report to reduce duplication in project-owned acceptance assets. The
most common cleanup is consolidating repeated literal step handlers into a
single parameterized or regex-based handler.

For example, several handlers like:

```text
the output contains line <message>
the output contains line <error_message>
the output contains line <success_message>
```

can often become one handler pattern:

```text
the output contains line <...>
```

or the equivalent regex in the project's acceptance runtime.

## Important Limits

This tool is advisory. It does not modify files.

It does not know whether two similar steps have identical domain meaning.
Review each finding before merging handlers. Similar text may still need
separate behavior when one step performs setup and another performs an
assertion.

A safe cleanup must preserve scenario behavior and should be verified by
regenerating and running the acceptance tests after the step-handler changes.
