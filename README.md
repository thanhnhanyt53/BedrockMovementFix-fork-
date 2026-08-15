# BedrockMovementFix 2.1.1

Paper 1.21.11 / Java 21. Conservative watchdog for Bedrock movement validation around special blocks.

Key changes from 2.1.0:
- CLIPPED_INTO_BLOCK burst is the primary signal.
- Interaction/animation are diagnostic/activity signals, not mandatory prerequisites.
- Requires repeated clipped failures + movement stall + special-block proximity.
- One-shot correction with cooldown.
- Never calls PlayerFailMoveEvent#setAllowed(true).
- No ProtocolLib or PacketEvents dependency.
- No repeating teleport loop.
- Debug correlation IDs for corrections.
