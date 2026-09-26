package nl.requios.effortlessbuilding.utilities;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.Constants;

import java.util.*;

/**
 * Server-side per-player undo/redo stacks.
 * Each entry records the block positions with their old and new states.
 * <p>
 * Item accounting follows the game mode the operation was made in, not the current one:
 * a survival placement is refunded on undo and paid again on redo; a creative one is free both ways,
 * as are tool interactions and fluids, which never charged block items.
 * Items that came from the AE2 network go back to it; the rest go to the inventory.
 */
public class UndoManager {

    private static final int MAX_STACK_SIZE = 50;

    private static final Map<UUID, Deque<UndoEntry>> undoStacks = new HashMap<>();
    private static final Map<UUID, Deque<UndoEntry>> redoStacks = new HashMap<>();

    public record BlockChange(BlockState oldState, BlockState newState) {}

    /**
     * A single undo-able operation: a set of block changes in a specific dimension.
     *
     * @param free         no item accounting: made in creative, or not paid with block items
     * @param networkDebit per item, how many of the placed blocks were paid from the AE2 network;
     *                     updated as undo refunds them and redo pays again
     */
    public record UndoEntry(ResourceKey<Level> dimension, Map<BlockPos, BlockChange> changes,
                            boolean free, Map<Item, Integer> networkDebit) {}

    /** How a change is reverted or re-applied, and which items that costs or returns. */
    private enum Kind {
        /** Air/replaceable → block. */
        PLACE,
        /** Block → air. */
        BREAK,
        /** Solid block → other block (replace mode). */
        REPLACE,
        /** Anything else (tool interactions, fluids): restored without items. */
        OTHER;

        static Kind of(BlockChange change) {
            BlockState oldState = change.oldState(), newState = change.newState();
            if (newState.isAir()) return oldState.isAir() ? OTHER : BREAK;
            if (oldState.canBeReplaced()) return PLACE;
            return itemOf(newState) != Items.AIR && itemOf(oldState) != Items.AIR ? REPLACE : OTHER;
        }
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /**
     * Record a block placement or break paid in items (unless in creative). Clears the redo stack.
     *
     * @param networkDebit per item, how many placed blocks were paid from the AE2 network
     */
    public static void recordOperation(ServerPlayer player, ResourceKey<Level> dimension,
                                       Map<BlockPos, BlockChange> changes, Map<Item, Integer> networkDebit) {
        record(player, new UndoEntry(dimension, changes, player.isCreative(), new HashMap<>(networkDebit)));
    }

    /**
     * Record an operation that undo/redo reverts without moving items (tool interactions, fluids).
     * Clears the redo stack.
     */
    public static void recordFreeOperation(ServerPlayer player, ResourceKey<Level> dimension,
                                           Map<BlockPos, BlockChange> changes) {
        record(player, new UndoEntry(dimension, changes, true, new HashMap<>()));
    }

    private static void record(ServerPlayer player, UndoEntry entry) {
        if (entry.changes().isEmpty()) return;

        UUID id = player.getUUID();
        push(undoStacks.computeIfAbsent(id, k -> new ArrayDeque<>()), entry);

        // New operation invalidates redo history
        redoStacks.computeIfAbsent(id, k -> new ArrayDeque<>()).clear();
    }

    // -------------------------------------------------------------------------
    // Undo / Redo
    // -------------------------------------------------------------------------

    /**
     * Undo the most recent operation. Returns the number of blocks restored, or -1 if nothing to undo.
     * Positions changed by someone else since are skipped. For survival operations, undoing a placement
     * refunds the item; restoring a broken or replaced block costs its item (inventory, then AE2), and
     * is skipped if the player can't pay (a replaced position is left as air).
     */
    public static int undo(ServerPlayer player) {
        UUID id = player.getUUID();
        Deque<UndoEntry> undoStack = undoStacks.get(id);
        if (undoStack == null || undoStack.isEmpty()) return -1;

        UndoEntry entry = undoStack.pop();
        ServerLevel level = player.server.getLevel(entry.dimension());
        if (level == null) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot undo: dimension {} no longer loaded", entry.dimension());
            return -1;
        }

        // Keep positions that are still as we left them
        List<Map.Entry<BlockPos, BlockChange>> todo = new ArrayList<>();
        for (var e : entry.changes().entrySet()) {
            BlockState current = level.getBlockState(e.getKey());
            BlockChange change = e.getValue();
            if (entry.free() || Kind.of(change) == Kind.OTHER || current.equals(change.newState())) {
                todo.add(e);
            }
        }

        int restored = 0;
        if (entry.free()) {
            for (var e : todo) {
                level.setBlock(e.getKey(), e.getValue().oldState(), 3);
                restored++;
            }
        } else {
            Budget budget = Budget.take(player, todo, change -> switch (Kind.of(change)) {
                case BREAK, REPLACE -> itemOf(change.oldState());
                default -> Items.AIR;
            });
            Map<Item, Integer> refunds = new HashMap<>();
            List<BlockPos> restoredPlacements = new ArrayList<>();
            for (var e : todo) {
                BlockPos pos = e.getKey();
                BlockChange change = e.getValue();
                switch (Kind.of(change)) {
                    case PLACE -> {
                        level.setBlock(pos, change.oldState(), 3);
                        refunds.merge(itemOf(change.newState()), 1, Integer::sum);
                    }
                    case BREAK -> {
                        if (!budget.spend(itemOf(change.oldState()))) continue;
                        level.setBlock(pos, change.oldState(), 3);
                        restoredPlacements.add(pos);
                    }
                    case REPLACE -> {
                        refunds.merge(itemOf(change.newState()), 1, Integer::sum);
                        boolean paid = budget.spend(itemOf(change.oldState()));
                        level.setBlock(pos, paid ? change.oldState() : Blocks.AIR.defaultBlockState(), 3);
                        if (paid) restoredPlacements.add(pos);
                    }
                    case OTHER -> level.setBlock(pos, change.oldState(), 3);
                }
                restored++;
            }
            refund(player, refunds, entry.networkDebit());
            budget.refundUnspent(player);
            PlacedBlockTracker.trackAll(player.getUUID(), entry.dimension(), restoredPlacements);
        }

        push(redoStacks.computeIfAbsent(id, k -> new ArrayDeque<>()), entry);
        return restored;
    }

    /**
     * Redo the most recently undone operation. Returns the number of blocks re-applied, or -1 if nothing to redo.
     * For survival operations, re-placing costs the item (inventory, then AE2) and is skipped if the player
     * can't pay; re-breaking gives the item back.
     */
    public static int redo(ServerPlayer player) {
        UUID id = player.getUUID();
        Deque<UndoEntry> redoStack = redoStacks.get(id);
        if (redoStack == null || redoStack.isEmpty()) return -1;

        UndoEntry entry = redoStack.pop();
        ServerLevel level = player.server.getLevel(entry.dimension());
        if (level == null) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot redo: dimension {} no longer loaded", entry.dimension());
            return -1;
        }

        List<Map.Entry<BlockPos, BlockChange>> todo = new ArrayList<>();
        for (var e : entry.changes().entrySet()) {
            BlockState current = level.getBlockState(e.getKey());
            BlockChange change = e.getValue();
            if (entry.free() || Kind.of(change) == Kind.OTHER || current.equals(change.oldState())) {
                todo.add(e);
            }
        }

        int reapplied = 0;
        if (entry.free()) {
            for (var e : todo) {
                level.setBlock(e.getKey(), e.getValue().newState(), 3);
                reapplied++;
            }
        } else {
            Budget budget = Budget.take(player, todo, change -> switch (Kind.of(change)) {
                case PLACE, REPLACE -> itemOf(change.newState());
                default -> Items.AIR;
            });
            Map<Item, Integer> returned = new HashMap<>();
            List<BlockPos> placedAgain = new ArrayList<>();
            for (var e : todo) {
                BlockPos pos = e.getKey();
                BlockChange change = e.getValue();
                Kind kind = Kind.of(change);
                if ((kind == Kind.PLACE || kind == Kind.REPLACE) && !budget.spend(itemOf(change.newState()))) continue;
                if (kind == Kind.BREAK || kind == Kind.REPLACE) returned.merge(itemOf(change.oldState()), 1, Integer::sum);
                if (kind == Kind.PLACE || kind == Kind.REPLACE) placedAgain.add(pos);
                level.setBlock(pos, change.newState(), 3);
                reapplied++;
            }
            for (var r : returned.entrySet()) {
                InventoryHelper.giveOrDropItems(player, r.getKey(), r.getValue());
            }
            budget.refundUnspent(player);
            // Whatever redo kept from the network is refundable again by the next undo
            budget.fromNetwork.forEach((item, n) -> entry.networkDebit().merge(item, n, Integer::sum));
            PlacedBlockTracker.trackAll(player.getUUID(), entry.dimension(), placedAgain);
        }

        push(undoStacks.computeIfAbsent(id, k -> new ArrayDeque<>()), entry);
        return reapplied;
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /**
     * Clear undo/redo stacks for a player (call on disconnect).
     */
    public static void clearPlayer(UUID playerId) {
        undoStacks.remove(playerId);
        redoStacks.remove(playerId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void push(Deque<UndoEntry> stack, UndoEntry entry) {
        stack.push(entry);
        if (stack.size() > MAX_STACK_SIZE) stack.removeLast();
    }

    private static Item itemOf(BlockState state) {
        return state.getBlock().asItem();
    }

    /**
     * Refunds items per type: the part that was paid from the network goes back to it
     * (and is taken off the entry's debit), the rest goes to the inventory.
     */
    private static void refund(ServerPlayer player, Map<Item, Integer> refunds, Map<Item, Integer> networkDebit) {
        for (var r : refunds.entrySet()) {
            Item item = r.getKey();
            if (item == Items.AIR) continue;
            int toNetwork = Math.min(r.getValue(), networkDebit.getOrDefault(item, 0));
            InventoryHelper.returnItems(player, item, r.getValue(), toNetwork);
            if (toNetwork > 0) networkDebit.merge(item, -toNetwork, Integer::sum);
        }
    }

    /**
     * Items taken up front (inventory first, then AE2) for the positions that need paying,
     * so each item type costs one inventory scan and one network call instead of one per block.
     */
    private static final class Budget {
        final Map<Item, Integer> available = new HashMap<>();
        final Map<Item, Integer> fromNetwork = new HashMap<>();

        static Budget take(ServerPlayer player, List<Map.Entry<BlockPos, BlockChange>> changes,
                           java.util.function.Function<BlockChange, Item> cost) {
            Map<Item, Integer> needed = new HashMap<>();
            for (var e : changes) {
                Item item = cost.apply(e.getValue());
                if (item != Items.AIR) needed.merge(item, 1, Integer::sum);
            }
            Budget budget = new Budget();
            for (var n : needed.entrySet()) {
                InventoryHelper.Taken taken = InventoryHelper.takeItems(player, n.getKey(), n.getValue());
                budget.available.put(n.getKey(), taken.total());
                if (taken.fromNetwork() > 0) budget.fromNetwork.put(n.getKey(), taken.fromNetwork());
            }
            return budget;
        }

        boolean spend(Item item) {
            if (item == Items.AIR) return false;
            int left = available.getOrDefault(item, 0);
            if (left <= 0) return false;
            available.put(item, left - 1);
            return true;
        }

        /** Returns anything taken but not spent (positions skipped after the budget was taken). */
        void refundUnspent(ServerPlayer player) {
            for (var a : available.entrySet()) {
                int left = a.getValue();
                if (left <= 0) continue;
                int toNetwork = Math.min(left, fromNetwork.getOrDefault(a.getKey(), 0));
                InventoryHelper.returnItems(player, a.getKey(), left, toNetwork);
                fromNetwork.merge(a.getKey(), -toNetwork, Integer::sum);
            }
        }
    }
}
