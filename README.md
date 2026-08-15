# BedrockMovementFix 2.1.2

Paper 1.21.11 / Java 21.

2.1.2 changes the watchdog from "no PlayerMoveEvent" stall detection to a
CLIPPED_INTO_BLOCK burst + restricted movement pattern detector.

Correction requires:
- Bedrock/Floodgate player
- near configured special block
- >= fail-move-threshold CLIPPED_INTO_BLOCK failures in fail-move-window-ms
- recent activity
- cooldown ready
- several recent movement samples that are below movement-low-delta
- no meaningful movement progress for movement-desync-window-ms

It never calls PlayerFailMoveEvent#setAllowed(true), never loops teleport,
and has no ProtocolLib/PacketEvents dependency.
