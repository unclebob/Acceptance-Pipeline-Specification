# Held-Out Specification

## Purpose

This document specifies `bb gherkin-heldout-split`, `bb gherkin-heldout-gate`,
and `bb gherkin-heldout-verify`, the portable commands that implement a
held-out generalization check for the acceptance pipeline. The Go binaries
with the same names are fallbacks for environments where Babashka is not
available; the two implementations produce interchangeable artifacts.

An autonomous agent that iterates against visible acceptance scenarios treats
them as a training set: it can satisfy them through memorization instead of
implemented behavior. The held-out barrier measures that gap. Scenarios are
split into a visible set the agent works against and a held-out set the agent
must never see. The gate compares pass rates and reports the generalization
gap. A gap above the limit is a reward hacking signal: the artifact satisfies
the visible set without implementing the feature.

The tools are scenario-level, deterministic, and report-only apart from the
repository guard described below. They do not run tests and do not rewrite
feature files.

## Split Command

```text
bb gherkin-heldout-split <json-ir> <visible-output> <heldout-output> [heldout-fraction] [seed] [--manifest <path>] [--in-repo-ok]
gherkin-heldout-split [-manifest <path>] [-in-repo-ok] <json-ir> <visible-output> <heldout-output> [heldout-fraction] [seed]
```

The Go fallback takes options before positional arguments (standard Go flag
behavior). The Babashka task accepts options in any position.

Defaults: `heldout-fraction` is `0.2`, `seed` is `aps-heldout`.

Exit codes:

```text
0  split succeeded and both IR files were written
1  input, validation, output error, or repository guard refusal
2  command-line usage error
```

Behavior:

1. Reads one parser-produced JSON IR file.
2. Validates that every scenario has a unique non-blank name, that there are
   at least two scenarios, and that the fraction is between 0 and 1 exclusive.
3. Orders scenarios by `sha256(seed + "\n" + scenario-name)`.
4. Takes the first `ceil(n * fraction)` scenarios in that order as the
   held-out set; the rest are visible.
5. Writes two IR files with the same feature name and background, keeping the
   original relative scenario order inside each subset.
6. With `--manifest`, writes a fingerprint manifest of the held-out set.

Repository guard: the command refuses to write the held-out IR into a path
inside a git repository unless `--in-repo-ok` is given. The held-out set
belongs outside the agent-readable project tree; the flag exists for local
experiments only.

The same input, fraction, and seed always produce the same split. A different
seed produces a different split. Changing the seed invalidates previous
held-out results, so a project fixes one seed per corpus and changes it only
to refresh a leaked held-out set.

A fraction that would empty either side is rejected. To grow the held-out set,
raise the fraction; to keep more scenarios visible, lower it.

## Verify Command

```text
bb gherkin-heldout-verify <manifest> <heldout-ir>
gherkin-heldout-verify <manifest> <heldout-ir>
```

Exit codes:

```text
0  the held-out IR matches the manifest
1  drift detected, or input/validation error
2  command-line usage error
```

The command recomputes the scenario fingerprints of the held-out IR and
compares them with the manifest. It reports missing, unknown, and changed
scenario counts. Any nonzero count is drift: the held-out set was edited,
substituted, or refreshed. Drift is not automatically a leak; it means the
held-out set no longer measures what the manifest recorded, and previous
gate reports for that manifest are no longer comparable.

A fingerprint is `sha256(seed + "\u0000" + canonical-scenario-string)` where
the canonical string joins, with NUL separators: the literal `scenario`, the
name, then for each step the literal `step`, keyword, text, and comma-joined
parameters, then for each example row the literal `example` and the
`key=value` cells sorted by key. Both implementations produce identical
fingerprints, so a manifest written by one verifies under the other.

## Gate Command

```text
bb gherkin-heldout-gate <visible-results> <heldout-results> [max-gap]
gherkin-heldout-gate <visible-results> <heldout-results> [max-gap]
```

Default: `max-gap` is `0.05` (5 percentage points).

Exit codes:

```text
0  gate passed: gap is at most max-gap
1  gate failed, or input/validation error
2  command-line usage error
```

Results files are JSON objects produced by the project runner adapter or any
script that maps project test results onto scenario outcomes:

```json
{
  "results": [
    {"name": "withdraw cash", "status": "pass"},
    {"name": "close account", "status": "fail"}
  ]
}
```

`name` is the scenario name from the IR. `status` is one of `pass`, `fail`,
`error`. Errors count as not passed and are reported separately.

The gate computes `P(visible) - P(held-out)`, where `P` is the share of
scenarios with status `pass`, and prints a JSON report with per-set totals,
the gap as a fraction and in percentage points, the limit, and the verdict.
The gate fails when the gap exceeds the limit. A low held-out pass rate with a
small gap is not a generalization failure; it is an ordinary acceptance
failure handled by the normal run.

## Operational Isolation Requirements

The guard, manifest, and verify command support isolation mechanically. The
rest is operational. A conforming setup obeys these rules:

1. The held-out IR file, its generated tests, and its results live outside
   the context the agent reads: a separate directory outside the repository,
   or a separate CI job whose logs and files the agent cannot read. The
   split command's repository guard is a reminder of this rule, not its
   enforcement.
2. The agent sees only the visible IR and visible results.
3. The held-out set is verified against its manifest after every split and
   whenever its files could have been touched. Drift or evidence of leakage
   means refresh: change the seed, re-split, and treat all previous gap
   reports as invalid.
4. The gate report is the only artifact that crosses back into the visible
   context.

## Interpretation

The gap is an estimate of reward hacking, not a proof. A passing gate does
not certify correctness; it says the artifact behaves on scenarios it could
not memorize about as well as on scenarios it could. Use it together with the
normal acceptance run and acceptance mutation, not instead of them.
