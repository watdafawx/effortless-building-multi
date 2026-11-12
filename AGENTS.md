# AGENTS.md

## Quick Orientation
- Target: Minecraft `1.21.1`, Java `21`, primary loaders are `fabric` and `neoforge`.
- Put shared gameplay logic in `common/`; loader projects should only contain bootstrap/events/platform glue.
- Read first: `BuildPipeline.java`, `BuildPipelineClient.java`, `PacketHandler.java`.

## Project Structure
- `common/` — shared code compiled into all loaders
- `neoforge/` — NeoForge-specific entry points and event handlers
- `fabric/` — Fabric-specific entry points

## How It Works

**Build modes** are shapes the player selects (wall, floor, line, cube, etc.) and builds with a few clicks. For example, a wall mode lets you click two corners and fills the rectangle between them.

**Modifiers** transform the resulting block set — mirrors duplicate blocks across an axis, arrays repeat them in a grid, radials copy them around a center point.

### Client Flow
1. Player clicks → `BuildPipelineClient` receives the click via mixin/loader hooks.
2. The **build mode** processes the click (multi-click sequence: first click sets one corner, second click completes the shape). On the final click, the build mode populates a `BlockSet` with all positions in the shape.
3. The `BlockSet` runs through the **client pipeline** (`ModifierSystem` → `ConstraintSystem`), which multiplies positions via modifiers and marks invalid ones.
4. A packet is sent to the server containing the build mode, click positions, and all context needed to reproduce the result.

### Server Flow
1. `PacketHandler` receives the packet and calls `BuildPipeline.SERVER.runServerPipeline(...)`.
2. The **server pipeline** (`BuildModeSystem` → `ModifierSystemServer` → `ConstraintSystem`) regenerates the same `BlockSet` from scratch using the packet data — the server never trusts the client's block positions.
3. `PacketHandler` iterates `blockSet.validEntries()` and applies the actual world changes.

### Preview
While the player is mid-sequence (between clicks), `BuildPipelineClient.getPreviewBlocks()` runs each frame to show what would be built. The preview also runs through the pipeline so modifiers and constraints are visualized live.

## Pipeline Stages
- `BuildModeSystem` — generates initial positions from the build mode (server only; client populates BlockSet before pipeline)
- `ModifierSystem` / `ModifierSystemServer` — mirrors/arrays/radials the block set
- `ConstraintSystem` — marks entries with `BlockStatus` rejection reasons (not removal). Checks: max blocks, only-placed-blocks, max-hardness, require-tools, breaking-disabled. Runs for both breaking and replacing existing blocks during placement.

## Anchor toolbar (preview lock)
While a preview is locked, `RenderHandler.renderAnchorBar` draws a Create-style tool bar in the world (no screen). `MixinMouseHandler`: Ctrl+scroll picks a tool, Shift+scroll uses it (Shift+Ctrl = 5 blocks). Tools (`BuildPipelineClient.AnchorTool`): forward/back, left/right, up/down, rotate 90° (generator shapes only, via `ROTATE_Y`), overview screen. Moves go through `reanchor`, which recomputes the preview like the server.

## BlockEntry Status
- `BlockEntry.status` (default `VALID`) carries rejection reasons from `ConstraintSystem`.
- `BlockSet.validEntries()` / `validPositions()` filter to valid entries.
- Server skips non-valid entries; client renderer shows rejection visuals (red tint + outline via `BlockPreviewRenderer`, item stacks via `BreakDisplayTracker`).

## Gradle Commands
```powershell
.\gradlew.bat :common:compileJava
.\gradlew.bat :fabric:compileJava :neoforge:compileJava
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
```

## In-game selftest (NeoForge)
`neoforge/.../SelfTest.java` (copied from the `neoforge-ingame-selftest` skill, same as hungry-loop) runs unattended in a scratch world: `.\gradlew.bat :neoforge:runSelftest` (JDK 21 `JAVA_HOME`), then read `neoforge/run/eb-selftest.txt` and `neoforge/run/screenshots/eb-selftest-*.png`. Scenario `floor-anchor` locks a checkerboard FLOOR, uses the lift/move/rotate tools, builds it and checks the server world equals the client preview. Prove new scenarios by breaking the fix first (`MUTATION-TEST`).
