Feature: Mutating step data table cells

Scenario: Each data cell in a step table becomes one mutation
  Given this feature:
    """
    Feature: Replay

    Scenario: Same set-up
      Then the new hunt setup is:
        | piece  | room |
        | hunter | 2    |
        | wumpus | 8    |
    """
  When the feature is parsed without inference
  And mutations are discovered
  Then 4 mutations are discovered
  And a mutation exists for path '$.scenarios[0].steps[0].table.rows[0][0]' with original 'hunter'
  And a mutation exists for path '$.scenarios[0].steps[0].table.rows[0][1]' with original '2'
  And a mutation exists for path '$.scenarios[0].steps[0].table.rows[1][0]' with original 'wumpus'
  And a mutation exists for path '$.scenarios[0].steps[0].table.rows[1][1]' with original '8'
  And no mutation has original 'piece'
  And no mutation has original 'room'

Scenario: Background table cells are mutated
  Given this feature:
    """
    Feature: Shared setup

    Background:
      Given a hunt started with this setup:
        | piece  | room |
        | hunter | 2    |

    Scenario: Hunt begins
      Then the hunt is ready
    """
  When the feature is parsed without inference
  And mutations are discovered
  Then a mutation exists for path '$.background[0].table.rows[0][0]' with original 'hunter'
  And a mutation exists for path '$.background[0].table.rows[0][1]' with original '2'
  And no mutation has original 'piece'

Scenario: A scenario with only a step table and no examples is still mutated
  Given this feature:
    """
    Feature: Replay

    Scenario: Same set-up
      Given a hunt started with this setup:
        | piece  | room |
        | hunter | 2    |
    """
  When the feature is parsed without inference
  And mutations are discovered
  Then 2 mutations are discovered
  And no example mutations are discovered

Scenario: Example cells and step table cells are both mutated
  Given this feature:
    """
    Feature: Both

    Scenario Outline: Hunt
      Given a hunt started with this setup:
        | piece  | room |
        | hunter | 2    |
      When the hunter moves to <room>

    Examples:
      | room |
      | 3    |
    """
  When the feature is parsed without inference
  And mutations are discovered
  Then a mutation exists for path '$.scenarios[0].examples[0].room' with original '3'
  And a mutation exists for path '$.scenarios[0].steps[0].table.rows[0][1]' with original '2'

Scenario: Applying a table mutation changes only that cell
  Given this feature:
    """
    Feature: Replay

    Scenario: Same set-up
      Then the new hunt setup is:
        | piece  | room |
        | hunter | 2    |
        | wumpus | 8    |
    """
  When the feature is parsed without inference
  And mutations are discovered
  And the mutation at path '$.scenarios[0].steps[0].table.rows[0][1]' is applied
  Then the table cell at row 0 column 1 of 'the new hunt setup is:' is the mutated value
  And the table cell at row 0 column 0 of 'the new hunt setup is:' is still 'hunter'
  And the table cell at row 1 column 1 of 'the new hunt setup is:' is still '8'
  And the original table cell at row 0 column 1 of 'the new hunt setup is:' is still '2'

Scenario: Table cell integers are mutated with the same value rules as examples
  Given this feature:
    """
    Feature: Replay

    Scenario: Same set-up
      Then the hunter is in:
        | room |
        | 2    |
    """
  When the feature is parsed without inference
  And mutations are discovered
  Then the mutation at path '$.scenarios[0].steps[0].table.rows[0][0]' has a different integer value

Scenario: Two step tables produce mutations in step order
  Given this feature:
    """
    Feature: Replay

    Scenario: Same set-up
      Given a hunt started with this setup:
        | piece  | room |
        | hunter | 2    |
      Then the new hunt setup is:
        | piece  | room |
        | hunter | 2    |
    """
  When the feature is parsed without inference
  And mutations are discovered
  Then the first mutation path is '$.scenarios[0].steps[0].table.rows[0][0]'
  And a later mutation path is '$.scenarios[0].steps[1].table.rows[0][0]'
