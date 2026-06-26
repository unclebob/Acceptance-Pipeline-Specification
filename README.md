# Acceptance Pipeline Specification

## Purpose

This repository specifies a portable acceptance-test pipeline that agents can
install in a project. The pipeline turns Gherkin feature files into JSON IR,
generates executable acceptance test entry points, runs those tests, and uses
acceptance mutation to check whether example data is actually connected to the
application under test.

Some pipeline tools are pre-supplied Babashka tasks from this repository.
Agents should get the latest versions directly from the canonical repository
and install them in the target project for convenient execution as `bb ...`
commands. Other components are project-dependent and are written by the agents
responsible for installing and maintaining acceptance testing in the target
project.

Acceptance mutation means mutating Gherkin example values in the
specification-derived JSON IR. It does not mean conventional mutation testing of
application source code.

The specification is intentionally implementation-language and project neutral.

## Pipeline

Normal acceptance run:

```text
feature file
  -> gherkin parser
  -> JSON IR
  -> optional IR-DRY checker
  -> acceptance entrypoint generator
  -> generated test entry points
  -> project test runner
```

Acceptance mutation run:

```text
feature file
  -> gherkin parser
  -> base JSON IR
  -> acceptance entrypoint generator
  -> reusable generated test entry points
  -> gherkin mutator
  -> runner adapter evaluates mutated IR
  -> mutation report
```

The normal run proves that the project satisfies the feature. The mutation run
checks whether the acceptance tests fail when important example values change.

## Component Map

Portable tools in this repository are exposed as Babashka tasks:

1. `bb gherkin-parser`: reads the supported Gherkin subset and writes JSON IR.
2. JSON IR reader/writer support: loads and stores the canonical feature
   representation.
3. `bb gherkin-ir-dry-checker`: reads one JSON IR file and reports repeated,
   near-duplicate, and possible-synonym step text so agents can normalize and
   prune feature-file Gherkin.
4. `bb gherkin-mutator`: builds deterministic example-value mutations, runs
   them through a persistent runner worker, and reports killed, survived, and
   error results.

```sh
bb gherkin-parser <feature-file> <json-output>
bb gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>
bb gherkin-mutator --runner-worker "<command>" [options]
```

Project-specific components created by agents as needed:

1. Acceptance entrypoint generator: creates executable test entry points from
   JSON IR.
2. Acceptance runtime: expands scenario executions and dispatches steps.
3. Project step handlers: connect step text to project behavior and assertions.
4. Runner adapter: runs generated tests against a supplied JSON IR and reports
   test success, test failure, or infrastructure error.
5. Convenience scripts: provide stable normal acceptance and mutation commands.

## Specifications

Read the specs in this order:

1. [parser-spec.md](parser-spec.md): supported Gherkin syntax, parser behavior,
   and canonical JSON IR.
2. [ir-dry-checker-spec.md](ir-dry-checker-spec.md): report-only repeated,
   near-duplicate, and possible-synonym step analysis used to normalize and
   prune feature-file Gherkin.
3. [acceptance-generator.md](acceptance-generator.md): entrypoint generator
   command, generated test requirements, runtime contract, step handler
   contract, generated metadata, and implementation hash rules.
4. [mutator-spec.md](mutator-spec.md): mutator command, mutation rules,
   persistent runner-worker protocol, differential manifests, status reporting,
   result classification, and report formats.

## Command Entry Points

A conforming setup should use these Babashka command shapes:

```text
bb gherkin-parser <feature-file> <json-output>
bb gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>
acceptance-entrypoint-generator <json-ir> <generated-test-output>
bb gherkin-mutator [options]
```

Common generated paths are:

```text
features/
build/acceptance/
build/acceptance-mutation/
acceptance/generated/
```

The exact project test command, generated test extension, runtime, handlers, and
adapter are project-specific.

## Tool Source and Installation

Agents should fetch the latest portable APS tools directly from:

```text
git@github.com:unclebob/Acceptance-Pipeline-Specification.git
```

Install the repository's Babashka task definitions and supporting `bb/src`
sources into the target project, or install a project-local wrapper that invokes
those files from a checked-out copy of the repository. The installed setup must
make these commands convenient to run from the target project:

```text
bb gherkin-parser <feature-file> <json-output>
bb gherkin-ir-dry-checker [--include-exact] <json-ir> <report-output>
bb gherkin-mutator [options]
```

Agents should update the installed portable tools from the canonical repository
when installing the pipeline and when maintaining an existing installation,
unless the target project deliberately pins a known revision for reproducible
builds.

## Writing Feature Files

Authors should write constant values as literals in steps and `Background:`.
The parser infers parameters from those literals when it builds JSON IR, so
feature files do not need `<placeholders>` for values that stay the same on
every example row.

Keep `Examples:` tables for values that change from row to row. Reference those
columns with explicit `<column_name>` placeholders in step text. When every row
repeats the same value for a column, remove that column and move the value into
a literal step or into `Background:` instead.

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

See [parser-spec.md](parser-spec.md#feature-authoring) for the full authoring
rules and [parser-spec.md](parser-spec.md#parameter-inference) for inference
behavior.

## Gherkin IR DRY Checker

APS includes `bb gherkin-ir-dry-checker`, a report-only tool that analyzes
parser-produced JSON IR for duplicated or similar step text. Its purpose is to
help agents normalize and prune the Gherkin in feature files before generated
tests are created.

Run it after parsing newly written or changed feature files and before
generating acceptance tests:

```sh
bb gherkin-parser features/example.feature build/acceptance/ir/example.json
bb gherkin-ir-dry-checker build/acceptance/ir/example.json build/acceptance/dry/example.json
```

The checker does not rewrite feature files, IR, generated tests, runtimes, or
project implementation files. It produces an advisory JSON report. Use that
report to edit feature files when the same idea is expressed unnecessarily in
different ways or when repeated steps inside one scenario should be pruned.

Typical workflow:

1. Parse each `.feature` file into JSON IR with `bb gherkin-parser`.
2. Run `bb gherkin-ir-dry-checker` on each IR file.
3. Review findings such as `duplicate-in-scenario`, `placeholder-variant`,
   `near-duplicate`, and `possible-synonym`.
4. Normalize Gherkin wording where the different forms are accidental drift.
5. Prune accidental repeated steps inside a background or scenario.
6. Parse, check, generate, and run the acceptance tests.

Do not blindly merge steps only because they look similar. Some step texts have
the same shape but different setup or assertion semantics.

## Typical Installation Flow

When installing the pipeline in a project, an agent usually:

1. Fetches the latest portable APS tools directly from the canonical repository.
2. Installs those tools in the project so the `bb ...` commands are convenient
   to run.
3. Creates one or more feature files that exercise real project behavior.
4. Parses each feature into JSON IR with `bb gherkin-parser`.
5. Optionally runs `bb gherkin-ir-dry-checker` on each IR and uses the report
   to normalize and prune feature-file Gherkin.
6. Creates project-specific generated entry points from each IR.
7. Implements the runtime and step handlers needed by those generated tests.
8. Adds a normal acceptance script that parses, generates, and runs the
   generated tests.
9. Adds a runner adapter that can stay hot and accept mutation jobs over
   stdin/stdout.
10. Runs `bb gherkin-mutator` and improves scenarios or handlers until
   important mutations are killed.
11. Adds parser, generator, runtime, handler, adapter, and mutator coverage at
   the project-appropriate level.

Normal acceptance should be part of regular verification. Acceptance mutation is
usually a deliberate quality workflow because it may be slower than normal test
runs.
