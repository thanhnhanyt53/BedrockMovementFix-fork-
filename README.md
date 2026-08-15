# BedrockMovementFix 2.1.4

Paper 1.21.11 / Java 21.

## Detection model

2.1.4 correlates two separate signals within a 1500 ms window:

1. `CLIPPED_INTO_BLOCK` burst opens `SUSPECTED`.
2. Consecutive near-zero server movement samples (`0.000`-like hard stall)
   can move the player to `CONFIRMED`.
3. If normal movement resumes, `SUSPECTED` or `CONFIRMED` is immediately
   cancelled.
4. Only a confirmed, still-stalled case receives one correction.
5. No repeating teleport and no `PlayerFailMoveEvent#setAllowed(true)`.

The correction is deliberately conservative and does not depend on
ProtocolLib or PacketEvents.
