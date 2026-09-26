package nl.requios.effortlessbuilding.palette;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipeline;
import nl.requios.effortlessbuilding.buildpipeline.IBuildSystem;
import nl.requios.effortlessbuilding.item.RandomizerToolItem;
import nl.requios.effortlessbuilding.utilities.BlockEntry;
import nl.requios.effortlessbuilding.utilities.BlockSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pipeline stage that gives every placed block an item from the active {@link BlockPalette},
 * laid out by its pattern relative to the build's start point. Runs after modifiers, so mirrored
 * and arrayed copies follow the pattern too. The Randomizer tool, when held, wins.
 */
public final class PaletteSystem implements IBuildSystem {

    /** Reads the player's palette setting ({@link PaletteClientState}). */
    public static final PaletteSystem CLIENT = new PaletteSystem(true);
    /** Reads the palette sent with the build packet (see {@link #setServerPalette}). */
    public static final PaletteSystem SERVER = new PaletteSystem(false);

    private static final ThreadLocal<BlockPalette> SERVER_PALETTE = new ThreadLocal<>();

    private final boolean client;

    private PaletteSystem(boolean client) {
        this.client = client;
    }

    public static void setServerPalette(@Nullable BlockPalette palette) {
        if (palette == null) SERVER_PALETTE.remove();
        else SERVER_PALETTE.set(palette);
    }

    @Override
    public void processBlocks(BlockSet blocks, Player player, BuildPipeline.BuildState action) {
        if (action != BuildPipeline.BuildState.PLACING || blocks.isEmpty()) return;
        if (player.getMainHandItem().getItem() instanceof RandomizerToolItem) return;
        BlockPalette palette = client ? PaletteClientState.getActive() : SERVER_PALETTE.get();
        if (palette == null) return;
        List<Item> items = palette.resolve(player);
        if (items.isEmpty()) return;

        BlockPos origin = blocks.firstPos != null ? blocks.firstPos : blocks.keySet().iterator().next();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
        }
        for (BlockEntry entry : blocks.values()) {
            BlockPos p = entry.blockPos;
            int index = palette.pattern().index(items.size(), palette.band(),
                    p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ(),
                    minY - origin.getY(), maxY - origin.getY(), p.asLong());
            Item item = items.get(index);
            entry.item = item;
            entry.blockState = ((BlockItem) item).getBlock().defaultBlockState();
        }
    }
}
