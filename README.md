# Acceptance Pipeline Specification

## Purpose

This repository specifies a portable acceptance-test pipeline that agents can
install in a project. The pipeline turns Gherkin feature files into JSON IR,
generates executable acceptance test entry points, runs those tests, and uses
acceptance mutation to check whether example data is actually connected to the
application under test.

Some pipeline tools are pre-supplied Go commands from this repository. Other
components are project-dependent and are written by the agents responsible for
installing and maintaining acceptance testing in the target project.

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

Portable tools in this repository:

1. `gherkin-parser`: reads the supported Gherkin subset and writes JSON IR.
2. JSON IR reader/writer support: loads and stores the canonical feature
   representation.
3. `gherkin-ir-dry-checker`: reads one JSON IR file and reports duplicate,
   near-duplicate, and possible-synonym step text for agent review.
4. `gherkin-mutator`: builds deterministic example-value mutations, runs them
   through a persistent runner worker, and reports killed, survived, and error
   results.

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
2. [ir-dry-checker-spec.md](ir-dry-checker-spec.md): report-only duplicate,
   near-duplicate, and possible-synonym step analysis for one JSON IR file.
3. [acceptance-generator.md](acceptance-generator.md): entrypoint generator
   command, generated test requirements, runtime contract, step handler
   contract, generated metadata, and implementation hash rules.
4. [mutator-spec.md](mutator-spec.md): mutator command, mutation rules,
   persistent runner-worker protocol, differential manifests, status reporting,
   result classification, and report formats.

## Command Entry Points

A conforming setup exposes these command shapes:

```text
gherkin-parser <feature-file> <json-output>
gherkin-ir-dry-checker <json-ir> <report-output>
acceptance-entrypoint-generator <json-ir> <generated-test-output>
gherkin-mutator [options]
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

## Typical Installation Flow

When installing the pipeline in a project, an agent usually:

1. Creates one or more feature files that exercise real project behavior.
2. Parses each feature into JSON IR with `gherkin-parser`.
3. Optionally runs `gherkin-ir-dry-checker` on each IR and uses the report to
   simplify project step handlers.
4. Creates project-specific generated entry points from each IR.
5. Implements the runtime and step handlers needed by those generated tests.
6. Adds a normal acceptance script that parses, generates, and runs the
   generated tests.
7. Adds a runner adapter that can stay hot and accept mutation jobs over
   stdin/stdout.
8. Runs `gherkin-mutator` and improves scenarios or handlers until important
   mutations are killed.
9. Adds parser, generator, runtime, handler, adapter, and mutator coverage at
   the project-appropriate level.

Normal acceptance should be part of regular verification. Acceptance mutation is
usually a deliberate quality workflow because it may be slower than normal test
runs.
