package io.github.watdafawx.shapewright;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import io.github.watdafawx.shapewright.compat.GhostCompat;
import io.github.watdafawx.shapewright.shape.ShapeGenerator.Cell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Only runs when the optional mc-mcp mod is in the dev run: sends a build to it as a ghost and checks it arrived. */
final class GhostBridgeTest {

    private static final String ID = "eb-selftest-ghost";

    private GhostBridgeTest() {}

    static boolean present() {
        return net.neoforged.fml.ModList.get().isLoaded(GhostCompat.MOD_ID);
    }

    /** How many blocks mc-mcp holds for the ghost, or -1 when there is no such ghost (read by reflection: no dependency). */
    private static int storedBlocks() {
        try {
            Class<?> store = Class.forName("dev.watdafawx.mcmcp.ghost.GhostStore");
            Object ghost = store.getMethod("get", String.class).invoke(null, ID);
            if (ghost == null) return -1;
            Object blocks = ghost.getClass().getField("blocks").get(ghost);
            return (int) blocks.getClass().getMethod("size").invoke(blocks);
        } catch (ReflectiveOperationException e) {
            return -2;
        }
    }

    static SelfTest.Seq scenario() {
        Map<Cell, BlockState> cube = new HashMap<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++)
            cube.put(new Cell(x, y, z), Blocks.STONE_BRICKS.defaultBlockState());
        return new SelfTest.Seq("ghost-bridge")
                .onServerRun(p -> {
                    SelfTest.clearPlayer(p);
                    p.setGameMode(GameType.CREATIVE);
                    p.teleportTo(600.5, -60, 600.5);
                })
                .waitTicks(30)
                .run(() -> Minecraft.getInstance().player.setXRot(10))
                .check("bridge-available", () -> GhostCompat.isAvailable() ? List.of() : List.of("GhostCompat.isAvailable() is false with mc-mcp loaded"))
                .run(() -> GhostCompat.send(ID, Minecraft.getInstance().player.blockPosition().offset(-1, 0, 4), cube))
                .waitTicks(20)
                .check("ghost-arrived", () -> {
                    List<String> f = new ArrayList<>();
                    int n = storedBlocks();
                    if (n != 27) f.add("mc-mcp holds " + n + " blocks for the ghost, expected 27");
                    return f;
                });
    }
}
