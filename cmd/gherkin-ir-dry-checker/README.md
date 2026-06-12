# Gherkin IR DRY Checker

`bb gherkin-ir-dry-checker` analyzes one APS Gherkin JSON IR file and writes a
JSON report describing duplicated or similar step text. Its purpose is to help
agents normalize and prune the Gherkin in feature files. The Go binary
`gherkin-ir-dry-checker` is available as a fallback when Babashka is not.

```sh
bb gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>
```

Fallback:

```sh
gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>
```

Example:

```sh
bb gherkin-parser features/checkout.feature build/acceptance/ir/checkout.json
bb gherkin-ir-dry-checker build/acceptance/ir/checkout.json build/acceptance/dry/checkout.json
```

## What It Reports

The report may include:

- `exact-duplicate`: the same step text appears multiple times.
- `duplicate-in-scenario`: the same step text appears multiple times in one
  background or scenario.
- `placeholder-variant`: steps differ mainly by placeholder names, such as
  `<room>` vs `<expected_room>`.
- `near-duplicate`: steps are textually similar enough to deserve review.
- `possible-synonym`: steps may express the same idea with different words.

Ordinary exact duplicates across scenarios are omitted by default because they
usually indicate consistent vocabulary. Use `--include-exact` only when you
want a vocabulary-reuse audit across the whole IR.

## Intended Use

Run this checker after parsing newly written or changed feature files and
before generating acceptance tests.

Use the report to normalize feature wording where the same idea is expressed
unnecessarily in different ways. For example, several feature steps like:

```text
the output contains line <message>
the output contains line <error_message>
the output contains line <success_message>
```

can often become one feature vocabulary form:

```text
the output contains line <message>
```

Use `duplicate-in-scenario` findings to prune accidental repeated steps within
one background or scenario.

## Important Limits

This tool is advisory. It does not modify files.

It does not know whether two similar steps have identical domain meaning.
Review each finding before editing Gherkin. Similar text may still need
separate wording when one step performs setup and another performs an
assertion.

A safe cleanup must preserve scenario behavior and should be verified by
parsing, checking, generating, and running the acceptance tests after the
feature changes.
