# BedrockMovementFix 2.0.0

Safe movement correction for Paper 26.1.x + Geyser/Floodgate + Velocity.

## Design
- Does NOT force `PlayerFailMoveEvent#setAllowed(true)`.
- Does NOT modify every movement tick.
- Only tracks repeated movement validation failures for Floodgate/Bedrock players.
- Uses a cooldown and grace periods around join, teleport and world changes.
- Large discrepancies are left to Paper.
- `bedrockmovementfix.bypass` bypasses the plugin completely.

## Important
This plugin is intentionally conservative. It is a diagnostic/safety-oriented replacement for the old implementation that forced failed movement to be accepted.

Build with Java 25:
`mvn clean package`
