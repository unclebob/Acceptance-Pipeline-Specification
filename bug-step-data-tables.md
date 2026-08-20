# Bug: Step Data Tables Are Dropped and Never Mutated

## Summary

`bb gherkin-parser` recognizes `|` table rows, then silently discards them unless
the current section is `Examples:`. Data tables attached to steps never appear
in the JSON IR. `bb gherkin-mutator` only discovers cells under
`$.scenarios[].examples[]`, so those tables would be invisible to mutation even
if they survived parsing.

That is a pipeline defect, not an authoring preference. Values written in a
step table are part of the specification. Changing a cell in that table must
be able to fail the acceptance suite. Today it cannot.

Do not work around this by teaching Gherkin authors to use only `Examples:`
tables. Fix the parser and the mutator here.

## Reproduction

Source:

```gherkin
Feature: Replay the same set-up

Scenario: Same set-up restores the hunt
  Given a hunt started with this setup:
    | piece  | room |
    | hunter | 2    |
    | wumpus | 8    |
    | pit    | 4    |
  When the hunter answers Y to SAME SET-UP
  Then the new hunt setup is:
    | piece  | room |
    | hunter | 2    |
    | wumpus | 8    |
    | pit    | 4    |
```

Command:

```text
bb gherkin-parser --do-not-infer <feature-file> <json-output>
```

Actual JSON IR (parser exit 0):

```json
{
  "name" : "Replay the same set-up",
  "scenarios" : [ {
    "name" : "Same set-up restores the hunt",
    "steps" : [ {
      "keyword" : "Given",
      "text" : "a hunt started with this setup:"
    }, {
      "keyword" : "When",
      "text" : "the hunter answers Y to SAME SET-UP"
    }, {
      "keyword" : "Then",
      "text" : "the new hunt setup is:"
    } ],
    "examples" : [ ]
  } ]
}
```

The `|` rows are gone. The step text remains. The scenario has no examples, so
the mutator discovers no candidates.

## Observed Failure

A SwarmForge story used this Gherkin shape (`Given a hunt started with this
setup:` plus a `| piece | room |` table, and a matching `Then the new hunt
setup is:` table). The implementer hardcoded the same numbers in application
code. Editing the `.feature` table cannot fail the suite, because those cells
never reach JSON IR, generated tests, step handlers, or the mutator.

The hardener therefore cannot detect that the specification values are not
connected to the application.

## Parser

### Current behavior

`bb/src/aps/gherkin.clj` classifies a trimmed line starting with `|` as
`:table` and routes it to `apply-table-line`:

```clojure
(defn- apply-table-line [state line line-no]
  (if (or (not= (:section state) :examples) (nil? (:current state)))
    state
    (let [cells (parse-table-row line)]
      (if (nil? (:headers state))
        (assoc state :headers cells)
        (add-example-row state cells line-no)))))
```

When the section is a scenario, background, or step — not `:examples` — the
function returns `state` unchanged. The line is not an error. It is not stored
on the preceding step. It is dropped.

This is not the same as parser-spec general rule 4 ("free-form lines that do
not match supported syntax are ignored"). Table rows *do* match supported
syntax: they are classified as tables. The parser then throws them away.

### Spec currently documents the wrong policy

[parser-spec.md](parser-spec.md) lists this under unsupported syntax:

```text
data tables attached to steps
```

The JSON IR step object has only `keyword`, `text`, and optional `parameters`.
There is no table field.

That listing is the bug, not a reason to keep the drop. A silent drop lets a
feature file look complete while the pipeline tests a weaker spec.

### Required parser behavior

1. A `|` row immediately after a step, or after another `|` row already
   attached to that step, is a **step data table**.
2. Attach the table to the preceding step. Preserve header order and row
   order. Parse cells with the same pipe rules already used for `Examples:`.
3. A `|` row that is not under `Examples:` and has no preceding step in the
   current background or scenario is a **parsing error**.
4. Do **not** silently ignore `|` rows.
5. Keep `Examples:` tables as scenario example rows. They remain distinct from
   step data tables.

Suggested step object (field name may vary; the table must be on the step):

```json
{
  "keyword": "Then",
  "text": "the new hunt setup is:",
  "table": {
    "headers": ["piece", "room"],
    "rows": [
      ["hunter", "2"],
      ["wumpus", "8"],
      ["pit", "4"]
    ]
  }
}
```

Background steps must accept the same table attachment.

If attaching tables to steps is rejected as an IR change, the only acceptable
alternative is to **fail the parse** when a step data table is present. Quiet
success with missing cells is not acceptable.

## Mutator

### Current behavior

[mutator-spec.md](mutator-spec.md) Mutation Scope:

> The mutator creates candidate mutations only from scenario example values.

Discovery in `bb/src/aps/mutation.clj` walks only example objects:

```text
$.scenarios[<scenario_index>].examples[<example_index>].<key>
```

Step data tables are outside that walk. After the parser drop they are also
absent from the IR, so mutation coverage of those values is zero.

### Required mutator behavior

Those table cells are the same kind of specification value as example cells.
If hunter room `2` becomes `9`, the suite should die.

Either:

1. **Mutate step table cells.** Discover each cell, change exactly one per
   mutation, apply the existing value-mutation rules, and use a stable path
   such as:

   ```text
   $.scenarios[<i>].steps[<j>].table.rows[<r>][<c>]
   ```

   Background tables need a matching path under `$.background`.

2. **Or fold step tables into example columns** during parse or mutation
   discovery so the existing `$.scenarios[].examples[]` walk already covers
   them. Folding must not drop rows or collapse a Then table into a single
   unused placeholder.

Do not leave a quiet hole. Feature names, scenario names, step text, step
keywords, and example headers stay immutable. Table *cells* do not.

## Downstream

Once the IR carries the table, generated tests and the project runtime must
pass it to the step handler. Handlers that only see step `text` and the current
example object cannot assert against a Then table.

This bug is still in APS. The parser and mutator specs, JSON IR, parser, and
mutator are the first fix. Generator and runtime contracts should follow the
IR so attached tables are executable, not merely stored.

## Files

- `bb/src/aps/gherkin.clj` — `apply-table-line`
- `parser-spec.md` — unsupported list, step object, table rules
- `bb/src/aps/mutation.clj` — `discover` / `apply-mutation`
- `mutator-spec.md` — mutation scope and discovery paths
- `acceptance-generator.md` — runtime/handler inputs, after the IR change
