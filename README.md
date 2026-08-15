# BedrockMovementFix 2.1.5

Paper 1.21.11 / Java 21.

## Detection state machine

`NORMAL -> SUSPECTED -> STALLING -> CONFIRMED -> one-shot correction`

- `CLIPPED_INTO_BLOCK` burst opens `SUSPECTED`.
- Micro-stalls do not correct.
- `STALLING` requires a hard zero-distance stall and a held movement direction.
- Continued hard stall is required before `CONFIRMED`.
- Recovery immediately cancels `SUSPECTED`, `STALLING`, or `CONFIRMED`.
- Correction is one-shot and cooldown-protected.
- No `PlayerFailMoveEvent#setAllowed(true)`.
- No repeating teleport.
- No ProtocolLib/PacketEvents dependency.

## Diagnostic file

The plugin now writes asynchronous diagnostics to:

`plugins/BedrockMovementFix/bedrock-movement-fix.log`

with one rotated `.1` backup when the configured size is exceeded.

Relevant config:

```yaml
log-file-enabled: true
log-file-name: bedrock-movement-fix.log
log-file-max-bytes: 2097152
```
