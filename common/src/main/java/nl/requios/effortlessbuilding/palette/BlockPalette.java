package nl.requios.effortlessbuilding.palette;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipeline;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Several blocks mixed over a build in a pattern, instead of only the held block.
 *
 * @param hotbarSlots bit i set = hotbar slot i is part of the palette (HOTBAR source)
 * @param custom      the chosen blocks (CUSTOM source); repeats weigh a block more
 * @param band        blocks per checker cell, layer or ring
 */
public record BlockPalette(Source source, int hotbarSlots, List<Item> custom, PalettePattern pattern, int band) {

    public static final int MAX_CUSTOM = 16;

    /** Where the palette's blocks come from. Sent by ordinal: only append. */
    public enum Source {
        /** The blocks in the chosen hotbar slots, whatever they hold at build time. */
        HOTBAR,
        /** A fixed list picked in the palette screen. */
        CUSTOM;

        public String getNameKey() {
            return "effortlessbuilding.palette.source." + name().toLowerCase();
        }
    }

    public BlockPalette {
        hotbarSlots &= (1 << Inventory.getSelectionSize()) - 1;
        List<Item> clean = new ArrayList<>();
        for (Item item : custom) {
            if (clean.size() < MAX_CUSTOM && item instanceof BlockItem) clean.add(item);
        }
        custom = List.copyOf(clean);
        band = Math.max(1, Math.min(64, band));
    }

    public static BlockPalette defaults() {
        return new BlockPalette(Source.HOTBAR, 0b1110, List.of(), PalettePattern.RANDOM, 1);
    }

    public BlockPalette withSource(Source source) { return new BlockPalette(source, hotbarSlots, custom, pattern, band); }
    public BlockPalette withHotbarSlots(int slots) { return new BlockPalette(source, slots, custom, pattern, band); }
    public BlockPalette withCustom(List<Item> custom) { return new BlockPalette(source, hotbarSlots, custom, pattern, band); }
    public BlockPalette withPattern(PalettePattern pattern) { return new BlockPalette(source, hotbarSlots, custom, pattern, band); }
    public BlockPalette withBand(int band) { return new BlockPalette(source, hotbarSlots, custom, pattern, band); }

    public boolean hasHotbarSlot(int slot) {
        return (hotbarSlots & (1 << slot)) != 0;
    }

    /**
     * The blocks to build with, in order. For the hotbar source this reads the player's own
     * inventory, so on the server it is always what the player really holds.
     */
    public List<Item> resolve(Player player) {
        if (source == Source.CUSTOM) return custom;
        List<Item> items = new ArrayList<>();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (!hasHotbarSlot(slot)) continue;
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof BlockItem && BuildPipeline.isBuildTriggerItem(stack)) items.add(stack.getItem());
        }
        return items;
    }

    // ---- network ------------------------------------------------------------

    public static void write(FriendlyByteBuf buf, @Nullable BlockPalette p) {
        buf.writeBoolean(p != null);
        if (p == null) return;
        buf.writeVarInt(p.source.ordinal());
        buf.writeVarInt(p.hotbarSlots);
        buf.writeVarInt(p.custom.size());
        for (Item item : p.custom) buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
        buf.writeVarInt(p.pattern.ordinal());
        buf.writeVarInt(p.band);
    }

    /** Untrusted input: ordinals are bounds-checked, unknown or non-block items dropped. */
    public static @Nullable BlockPalette read(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) return null;
        Source source = Source.values()[checkOrdinal(buf.readVarInt(), Source.values().length)];
        int slots = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CUSTOM) throw new IllegalArgumentException("Too many palette blocks: " + count);
        List<Item> custom = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(custom::add);
        }
        PalettePattern pattern = PalettePattern.values()[checkOrdinal(buf.readVarInt(), PalettePattern.values().length)];
        return new BlockPalette(source, slots, custom, pattern, buf.readVarInt());
    }

    private static int checkOrdinal(int ordinal, int size) {
        if (ordinal < 0 || ordinal >= size) throw new IllegalArgumentException("Bad ordinal " + ordinal);
        return ordinal;
    }
}
