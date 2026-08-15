# BedrockMovementFix 2.0.0

Conservative Bedrock movement correction for Paper servers from the 1.21.x API line through 26.2.

## Build target

This project is compiled against:

- Paper API: `1.21.11-R0.1-SNAPSHOT`
- Java: 21
- Maven compiler release: 21
- plugin.yml `api-version: '1.21'`

The code uses `PlayerFailMoveEvent`, which is present in the Paper 1.21.11 API and remains available in later Paper API versions. Paper 1.21.11 documents the event and its four fail reasons: MOVED_TOO_QUICKLY, MOVED_WRONG, CLIPPED_INTO_BLOCK and MOVED_INTO_UNLOADED_CHUNK.

`api-version: 1.21` allows the plugin to be loaded on later compatible Paper releases, including the 26.x line. It does not mean one binary can use every API change introduced between 1.21 and 26.2; the implementation deliberately uses stable APIs available in the 1.21.11 baseline.

## Important design

- Never calls `PlayerFailMoveEvent#setAllowed(true)`.
- Paper remains authoritative over movement validation.
- Tracks the last accepted movement.
- Observes repeated failures.
- Applies only conservative, small corrections.
- Uses join/teleport/world-change grace periods.
- Does not touch death, respawn, inventory or block interaction.
- `bedrockmovementfix.bypass` completely bypasses the plugin.

## Build

Requires JDK 21 and Maven:

```bash
mvn -B clean package
```

Output:

```text
target/BedrockMovementFix-2.0.0.jar
```
