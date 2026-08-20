Feature: Step data tables

Scenario: A table after a step is kept on that step
  Given this feature:
    """
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
    """
  When the feature is parsed without inference
  Then parsing succeeds
  And scenario 'Same set-up restores the hunt' has no examples
  And the step 'a hunt started with this setup:' has this table:
    | piece  | room |
    | hunter | 2    |
    | wumpus | 8    |
    | pit    | 4    |
  And the step 'the hunter answers Y to SAME SET-UP' has no table
  And the step 'the new hunt setup is:' has this table:
    | piece  | room |
    | hunter | 2    |
    | wumpus | 8    |
    | pit    | 4    |

Scenario: A table after a background step is kept on that step
  Given this feature:
    """
    Feature: Shared setup

    Background:
      Given a hunt started with this setup:
        | piece  | room |
        | hunter | 2    |
        | wumpus | 8    |

    Scenario: Hunt begins
      Then the hunt is ready
    """
  When the feature is parsed without inference
  Then parsing succeeds
  And the step 'a hunt started with this setup:' has this table:
    | piece  | room |
    | hunter | 2    |
    | wumpus | 8    |
  And the step 'the hunt is ready' has no table

Scenario: Examples tables stay on the scenario, not on a step
  Given this feature:
    """
    Feature: Withdrawals

    Scenario Outline: Withdraw cash
      When the customer withdraws <amount>
      Then the remaining balance is <remaining>

    Examples:
      | amount | remaining |
      | 20     | 80        |
      | 5      | 45        |
    """
  When the feature is parsed without inference
  Then parsing succeeds
  And the step 'the customer withdraws <amount>' has no table
  And scenario 'Withdraw cash' has these examples:
    | amount | remaining |
    | 20     | 80        |
    | 5      | 45        |

Scenario: A scenario can have both a step table and examples
  Given this feature:
    """
    Feature: Both

    Scenario Outline: Hunt
      Given a hunt started with this setup:
        | piece  | room |
        | hunter | 2    |
      When the hunter moves to <room>
      Then the hunter is in <room>

    Examples:
      | room |
      | 3    |
      | 4    |
    """
  When the feature is parsed without inference
  Then parsing succeeds
  And the step 'a hunt started with this setup:' has this table:
    | piece  | room |
    | hunter | 2    |
  And scenario 'Hunt' has these examples:
    | room |
    | 3    |
    | 4    |

Scenario: A table with only a header is kept with no data rows
  Given this feature:
    """
    Feature: Header only

    Scenario: Empty placement
      Given no pieces are placed:
        | piece | room |
    """
  When the feature is parsed without inference
  Then parsing succeeds
  And the step 'no pieces are placed:' has this table:
    | piece | room |

Scenario: Inference keeps step tables and does not fold their cells into examples
  Given this feature:
    """
    Feature: Replay

    Scenario: Same set-up
      Given a hunt started with this setup:
        | piece  | room |
        | hunter | 2    |
    """
  When the feature is parsed
  Then parsing succeeds
  And the step 'a hunt started with this setup:' has this table:
    | piece  | room |
    | hunter | 2    |
  And scenario 'Same set-up' has no examples

Scenario: Inference rewrites step text and still keeps the attached table
  Given this feature:
    """
    Feature: Placement

    Scenario: Place pits
      Given pit count is 2
        | room |
        | 4    |
        | 7    |
    """
  When the feature is parsed
  Then parsing succeeds
  And the step 'pit count is <p1>' has this table:
    | room |
    | 4    |
    | 7    |
  And scenario 'Place pits' has these examples:
    | p1 |
    | 2  |

Scenario: Writing JSON preserves step tables
  Given this feature:
    """
    Feature: Replay

    Scenario: Same set-up
      Then the new hunt setup is:
        | piece  | room |
        | hunter | 2    |
    """
  When the feature is parsed without inference
  And the JSON IR is written and read back
  Then parsing succeeds
  And the step 'the new hunt setup is:' has this table:
    | piece  | room |
    | hunter | 2    |

Scenario: A table with no preceding step is a parsing error
  Given this feature:
    """
    Feature: Bad table

    Scenario: Missing step
      | piece | room |
      | hunter | 2 |
    """
  When the feature is parsed without inference
  Then parsing fails

Scenario: A table directly under the feature is a parsing error
  Given this feature:
    """
    Feature: Bad table
      | piece | room |
      | hunter | 2 |
    """
  When the feature is parsed without inference
  Then parsing fails

Scenario: A table under Background with no step is a parsing error
  Given this feature:
    """
    Feature: Bad table

    Background:
      | piece | room |
      | hunter | 2 |

    Scenario: Hunt
      Then ready
    """
  When the feature is parsed without inference
  Then parsing fails

Scenario: A step table row with the wrong number of cells is a parsing error
  Given this feature:
    """
    Feature: Bad table

    Scenario: Mismatch
      Given setup:
        | piece | room |
        | hunter |
    """
  When the feature is parsed without inference
  Then parsing fails
