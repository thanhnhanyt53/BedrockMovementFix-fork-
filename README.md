# BedrockMovementFix 2.1.6

Paper 1.21.11 / Java 21.

## Bidirectional correlation

Within `correlation-window-ms` (default 1500 ms), either:
- `CLIPPED_INTO_BLOCK` may arrive before a hard stall, or
- a hard stall may begin before `CLIPPED_INTO_BLOCK`.

`CONFIRMED` requires all of:
- correlated CLIPPED signal;
- hard stall (consecutive near-zero server movement);
- a stable movement direction;
- the player is still producing movement events / attempting movement;
- no recovery;
- special-block proximity.

Recovery cancels detection immediately.

## Diagnostics

Every MOVE, FAIL_MOVE, STATE transition, and CORRECTION is written asynchronously to:

`plugins/BedrockMovementFix/bedrock-movement-fix.log`

The logger uses a single background writer and rotates the file to `.1` at the configured size.

No ProtocolLib/PacketEvents dependency.
No `PlayerFailMoveEvent#setAllowed(true)`.
No repeating teleport.
