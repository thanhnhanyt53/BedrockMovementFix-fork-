# BedrockMovementFix 2.1.3

Paper 1.21.11 / Java 21.

2.1.3 only allows correction after BOTH signals are present:
1. A CLIPPED_INTO_BLOCK failure burst.
2. Genuine loss of server-side positional progress in the recent movement direction.

Hysteresis:
NORMAL -> SUSPECTED -> CONFIRMED -> one-shot CORRECTION.

Ordinary clipped movement with continued positional progress is ignored.
The plugin never calls PlayerFailMoveEvent#setAllowed(true), never runs a
repeating teleport task, and has no ProtocolLib/PacketEvents dependency.
