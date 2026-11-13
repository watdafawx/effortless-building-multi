package nl.requios.effortlessbuilding;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import nl.requios.effortlessbuilding.buildmode.BuildModeEnum;
import nl.requios.effortlessbuilding.buildmode.BuildModes;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.shape.ShapeClientState;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.shape.ShapeType;
import nl.requios.effortlessbuilding.utilities.BlockSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Dev-only in-game selftest harness (NeoForge 1.21.1, singleplayer).
 *
 * <p>The integrated server shares the client's JVM, so after each step the harness snapshots the
 * client <em>and</em> the server and diffs them. A desync ("ghost item") is a client value that
 * disagrees with the server and <em>stays</em> that way - checks re-run once after a short wait so
 * packets in flight don't count. Every check also screenshots, for what only eyes can judge.
 *
 * <p>Modes:
 * <ul>
 *   <li>Unattended: gradle run with {@code -Deb.selftest=auto} (see the skill's gradle
 *   snippet). Deletes and recreates a flat scratch world "{@value #WORLD}", runs {@link #suite},
 *   writes {@code run/eb-selftest.txt}, screenshots to
 *   {@code run/screenshots/eb-selftest-NN-name.png}, quits.</li>
 *   <li>Manual: {@code /ebselftest} in any world runs {@link #manualSuite} - keep that one
 *   non-destructive, it runs in a real save.</li>
 * </ul>
 * Never registered in production ({@link FMLEnvironment#production}).
 */
@EventBusSubscriber(modid = "effortlessbuilding", value = Dist.CLIENT)
public final class SelfTest {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String PREFIX = "eb";
    private static final String WORLD = "eb-selftest";
    private static final boolean AUTO = !System.getProperty(PREFIX + ".selftest", "").isEmpty();

    /** Ticks to let a change settle before checking, and extra ticks before the one re-check. */
    static final int SETTLE = 40;
    static final int RECHECK = 20;
    /** Ticks before a screenshot, so the render has caught up with the state. */
    static final int SHOT_DELAY = 8;

    private SelfTest() {}

    // =============================================================================================
    // SCENARIOS - edit these. Each scenario is a Seq of steps; steps run one per tick at most.
    // =============================================================================================

    /** Runs in the throwaway scratch world: free to wipe and provision the player. */
    private static void suite(List<Seq> out) {
        out.add(floorAnchorScenario());
        out.add(scrollGestureScenario());
        if (nl.requios.effortlessbuilding.compat.FramedCompat.isAvailable()) {
            out.add(framedCamoScenario());
            out.add(framedSlopeScenario());
        }
    }

    /** Runs in the player's real world via the command: never destroy anything here. */
    private static void manualSuite(List<Seq> out) {
        out.add(new Seq("manual-ghost-scan").check("client-matches-server", SelfTest::ghostDiff));
    }

    // ---- FLOOR shape + anchor toolbar -------------------------------------------------------------

    private static BlockSet anchorBefore;

    private static int[] bounds(BlockSet set) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (BlockPos p : set.validPositions()) {
            b[0] = Math.min(b[0], p.getX()); b[1] = Math.min(b[1], p.getY()); b[2] = Math.min(b[2], p.getZ());
            b[3] = Math.max(b[3], p.getX()); b[4] = Math.max(b[4], p.getY()); b[5] = Math.max(b[5], p.getZ());
        }
        return b;
    }

    private static String spans(BlockSet set) {
        int[] b = bounds(set);
        return (b[3] - b[0] + 1) + "x" + (b[4] - b[1] + 1) + "x" + (b[5] - b[2] + 1);
    }

    /** The mouse-wheel gestures (the mixin only forwards the held keys and the wheel to this). */
    private static Seq scrollGestureScenario() {
        ShapeParams floor = ShapeParams.defaults(ShapeType.FLOOR).withSize(2).with("length", 2);
        boolean[] taken = new boolean[3];
        return new Seq("scroll-gestures")
                .onServerRun(p -> p.teleportTo(40.5, -60, 40.5))
                .waitTicks(20)
                .run(() -> {
                    Minecraft.getInstance().player.setXRot(90);
                    holdStone();
                    BuildModes.CLIENT.setBuildMode(BuildModeEnum.SHAPE);
                    ShapeClientState.setActive(floor);
                })
                .waitTicks(SETTLE)
                .run(BuildPipelineClient::togglePreviewLock)
                .run(() -> {
                    anchorBefore = BuildPipelineClient.getAnchorBlocks();
                    taken[0] = BuildPipelineClient.handleAnchorScroll(false, false, 1);   // plain scroll: hotbar's
                    taken[1] = BuildPipelineClient.handleAnchorScroll(true, false, -1);   // Ctrl: next tool (SLIDE)
                })
                .check("plain-scroll-left-alone-ctrl-picks-tool", () -> {
                    List<String> f = new ArrayList<>();
                    if (taken[0]) f.add("a plain scroll was taken");
                    if (!taken[1]) f.add("Ctrl+scroll was not taken");
                    if (BuildPipelineClient.getAnchorTool() != BuildPipelineClient.AnchorTool.SLIDE) f.add("tool is " + BuildPipelineClient.getAnchorTool());
                    if (BuildPipelineClient.getAnchorBlocks() != anchorBefore) f.add("a tool pick moved the anchor");
                    return f;
                })
                .run(() -> taken[2] = BuildPipelineClient.handleAnchorScroll(false, true, 1)) // Shift: use SLIDE one block
                .check("shift-scroll-uses-tool", () -> {
                    List<String> f = new ArrayList<>();
                    int[] a = bounds(anchorBefore), b = bounds(BuildPipelineClient.getAnchorBlocks());
                    int shifted = Math.abs(b[0] - a[0]) + Math.abs(b[2] - a[2]);
                    if (!taken[2]) f.add("Shift+scroll was not taken");
                    if (shifted != 1) f.add("moved " + shifted + " blocks, expected 1");
                    return f;
                })
                .run(() -> {
                    anchorBefore = BuildPipelineClient.getAnchorBlocks();
                    BuildPipelineClient.handleAnchorScroll(true, true, 1);                // Shift+Ctrl: 5 blocks
                })
                .check("shift-ctrl-scroll-moves-five", () -> {
                    int[] a = bounds(anchorBefore), b = bounds(BuildPipelineClient.getAnchorBlocks());
                    int shifted = Math.abs(b[0] - a[0]) + Math.abs(b[2] - a[2]);
                    return shifted == 5 ? List.of() : List.of("moved " + shifted + " blocks, expected 5");
                })
                .run(BuildPipelineClient::clearAnchor);
    }

    // ---- Framed Blocks camo (needs the FramedBlocks mod in run/mods) ---------------------------------

    private static final net.minecraft.core.BlockPos CAMO_POS = new net.minecraft.core.BlockPos(60, -60, 60);

    private static net.minecraft.server.level.ServerLevel serverLevel() {
        return Minecraft.getInstance().getSingleplayerServer().overworld();
    }

    /** The camo block ids stored in the framed block at {@code pos}, read from its saved data. */
    private static String camoOf(net.minecraft.core.BlockPos pos) {
        // Block entities can only be read on the server thread
        return Minecraft.getInstance().getSingleplayerServer().submit(() -> camoOnServer(pos)).join();
    }

    private static String camoOnServer(net.minecraft.core.BlockPos pos) {
        var level = serverLevel();
        var be = level.getBlockEntity(pos);
        if (be == null) return "no block entity; state=" + level.getBlockState(pos) + " loaded=" + level.hasChunkAt(pos);
        return be.saveWithFullMetadata(level.registryAccess()).toString();
    }

    /**
     * A framed cube gets a camo through the compat helper, the undo snapshot keeps it, and restoring brings
     * it back after the block was wiped. Oracle: the block entity's own saved data names the camo block.
     */
    private static Seq framedCamoScenario() {
        var cube = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.parse("framedblocks:framed_cube"));
        net.minecraft.nbt.CompoundTag[] saved = new net.minecraft.nbt.CompoundTag[1];
        return new Seq("framed-camo")
                .onServerRun(p -> {
                    p.setGameMode(GameType.CREATIVE);
                    p.teleportTo(58.5, -60, 58.5);
                })
                .waitTicks(SETTLE) // the chunk has to load before a block can go there
                .onServerRun(p -> serverLevel().setBlock(CAMO_POS, cube.defaultBlockState(), 3))
                .waitTicks(10)
                .onServerRun(p -> nl.requios.effortlessbuilding.compat.FramedCompat.applyCamo(serverLevel(), CAMO_POS, "minecraft:oak_planks"))
                .waitTicks(10)
                .check("camo-applied", () -> camoOf(CAMO_POS).contains("minecraft:oak_planks")
                        ? List.of() : List.of("block entity data has no oak camo: " + camoOf(CAMO_POS)))
                .waitTicks(5)
                .onServerRun(p -> saved[0] = nl.requios.effortlessbuilding.utilities.UndoManager.snapshot(serverLevel(), CAMO_POS))
                .onServerRun(p -> serverLevel().setBlock(CAMO_POS, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3))
                .waitTicks(5)
                .onServerRun(p -> {
                    serverLevel().setBlock(CAMO_POS, cube.defaultBlockState(), 3);
                    var be = serverLevel().getBlockEntity(CAMO_POS);
                    if (be != null && saved[0] != null) be.loadWithComponents(saved[0], serverLevel().registryAccess());
                })
                .waitTicks(10)
                .check("camo-restored-from-snapshot", () -> {
                    List<String> f = new ArrayList<>();
                    if (saved[0] == null) f.add("snapshot was null");
                    if (!camoOf(CAMO_POS).contains("minecraft:oak_planks")) f.add("camo lost: " + camoOf(CAMO_POS));
                    return f;
                })
                .onServerRun(p -> nl.requios.effortlessbuilding.compat.FramedCompat.applyCamo(serverLevel(), CAMO_POS, "minecraft:chest"))
                .waitTicks(10)
                .check("block-entity-camo-refused", () -> camoOf(CAMO_POS).contains("minecraft:chest")
                        ? List.of("a chest was accepted as camo") : List.of())
                .onServerRun(p -> serverLevel().setBlock(CAMO_POS, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3));
    }

    private static BlockSet slopeBlocks;

    /** How tall a shape is in the 0.1 thick strip along its side facing {@code side}. */
    private static double edgeTop(net.minecraft.world.phys.shapes.VoxelShape shape, net.minecraft.core.Direction side) {
        double t = 0.1;
        double x0 = side.getAxis() == net.minecraft.core.Direction.Axis.X ? (side.getStepX() > 0 ? 1 - t : 0) : 0;
        double x1 = side.getAxis() == net.minecraft.core.Direction.Axis.X ? (side.getStepX() > 0 ? 1 : t) : 1;
        double z0 = side.getAxis() == net.minecraft.core.Direction.Axis.Z ? (side.getStepZ() > 0 ? 1 - t : 0) : 0;
        double z1 = side.getAxis() == net.minecraft.core.Direction.Axis.Z ? (side.getStepZ() > 0 ? 1 : t) : 1;
        var strip = net.minecraft.world.phys.shapes.Shapes.join(shape, net.minecraft.world.phys.shapes.Shapes.box(x0, 0, z0, x1, 1, z1),
                net.minecraft.world.phys.shapes.BooleanOp.AND);
        return strip.isEmpty() ? 0 : strip.max(net.minecraft.core.Direction.Axis.Y);
    }

    /**
     * A dome with "Smooth top: Framed slopes": the preview must hold framed slopes camouflaged as stone, and
     * the built world must match it: same blocks, same facing, the camo in the block entity, and every slope's
     * low side must face the way the state says (checked on the collision shape).
     */
    private static Seq framedSlopeScenario() {
        ShapeParams dome = ShapeParams.defaults(ShapeType.DOME).withSize(4).with(ShapeType.SMOOTH, 3);
        return new Seq("framed-slope-smoothing")
                .onServerRun(p -> {
                    clearPlayer(p);
                    p.setGameMode(GameType.CREATIVE);
                    p.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
                    p.teleportTo(120.5, -60, 120.5);
                })
                .waitTicks(SETTLE)
                .run(() -> {
                    Minecraft.getInstance().player.setXRot(90);
                    holdStone();
                    BuildModes.CLIENT.setBuildMode(BuildModeEnum.SHAPE);
                    ShapeClientState.setActive(dome);
                })
                .waitTicks(SETTLE)
                .run(BuildPipelineClient::togglePreviewLock)
                .check("preview-has-framed-slopes", () -> {
                    List<String> f = new ArrayList<>();
                    BlockSet blocks = BuildPipelineClient.getAnchorBlocks();
                    if (blocks == null) return List.of("preview did not lock");
                    slopeBlocks = blocks;
                    int slopes = 0, slabs = 0, plain = 0, noCamo = 0;
                    for (var e : blocks.validEntries()) {
                        var entry = e.getValue();
                        String id = entry.blockState == null ? "" : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(entry.blockState.getBlock()).toString();
                        if (id.equals("framedblocks:framed_slope")) slopes++;
                        else if (id.equals("framedblocks:framed_slab")) slabs++;
                        else plain++;
                        if (id.startsWith("framedblocks:") && !"minecraft:stone".equals(entry.camo)) noCamo++;
                    }
                    log("framed-slope-smoothing: " + slopes + " slopes, " + slabs + " slabs, " + plain + " other");
                    if (slopes == 0) f.add("no framed slopes in the preview");
                    if (noCamo > 0) f.add(noCamo + " framed blocks without the stone camo");
                    return f;
                })
                .run(BuildPipelineClient::buildAnchor)
                .waitTicks(SETTLE)
                .check("server-has-slopes-with-camo", () -> {
                    List<String> f = new ArrayList<>();
                    int[] checked = {0};
                    onServer(p -> {
                        var level = p.serverLevel();
                        for (var e : slopeBlocks.validEntries()) {
                            var entry = e.getValue();
                            if (entry.blockState == null) continue;
                            var want = entry.blockState;
                            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(want.getBlock()).toString();
                            if (!id.equals("framedblocks:framed_slope")) continue;
                            BlockPos pos = e.getKey();
                            var got = level.getBlockState(pos);
                            // "solid" is set by the block entity once a camo is applied, so it differs from the preview
                            boolean same = got.getBlock() == want.getBlock();
                            for (var prop : want.getProperties()) {
                                if (prop.getName().equals("solid")) continue;
                                if (!got.getValue(prop).equals(want.getValue(prop))) same = false;
                            }
                            if (!same) { f.add(pos + " is " + got + ", preview said " + want); continue; }
                            var be = level.getBlockEntity(pos);
                            if (be == null || !be.saveWithFullMetadata(level.registryAccess()).toString().contains("minecraft:stone")) {
                                f.add(pos + " has no stone camo"); continue;
                            }
                            // The slope's facing is its high side; the low side must be lower, and open to the air:
                            // that is where the dome drops away
                            net.minecraft.core.Direction high = got.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
                            var shape = got.getShape(level, pos);
                            double highTop = edgeTop(shape, high), lowTop = edgeTop(shape, high.getOpposite());
                            if (!(lowTop < highTop)) f.add(pos + " facing " + high + " but low=" + lowTop + " high=" + highTop);
                            BlockPos beyond = pos.relative(high.getOpposite());
                            if (!level.getBlockState(beyond).canBeReplaced()) f.add(pos + ": the low side leads into " + level.getBlockState(beyond));
                            checked[0]++;
                        }
                        return null;
                    });
                    if (checked[0] == 0) f.add("no slope was checked on the server");
                    return f;
                });
    }

    private static void holdStone() {
        Minecraft mc = Minecraft.getInstance();
        mc.player.getInventory().selected = 0;
        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0));
    }

    /**
     * A checkerboard FLOOR (7 x 5): lock the preview, use every toolbar tool, build the anchor.
     * Oracle: the SERVER world must hold exactly the blocks the client preview showed.
     */
    private static Seq floorAnchorScenario() {
        ShapeParams floor = ShapeParams.defaults(ShapeType.FLOOR).withSize(3)
                .with("length", 2).with("pattern", 1).with("tile", 1);
        return new Seq("floor-anchor")
                .onServerRun(p -> {
                    clearPlayer(p);
                    p.setGameMode(GameType.CREATIVE);
                    p.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
                    p.teleportTo(0.5, -60, 0.5);
                })
                .waitTicks(20)
                .run(() -> {
                    Minecraft.getInstance().player.setXRot(90); // straight down at the grass
                    holdStone();
                    BuildModes.CLIENT.setBuildMode(BuildModeEnum.SHAPE);
                    ShapeClientState.setActive(floor);
                })
                .waitTicks(SETTLE) // frames render, so the preview is computed
                .run(BuildPipelineClient::togglePreviewLock)
                .check("locked-floor-shape", () -> {
                    List<String> f = new ArrayList<>();
                    BlockSet blocks = BuildPipelineClient.getAnchorBlocks();
                    if (!BuildPipelineClient.previewLocked || blocks == null) return List.of("preview did not lock");
                    anchorBefore = blocks;
                    if (blocks.validCount() != 18) f.add("checker 7x5 should keep 18 blocks, got " + blocks.validCount());
                    if (!spans(blocks).equals("7x1x5") && !spans(blocks).equals("5x1x7")) f.add("wrong footprint " + spans(blocks));
                    return f;
                })
                .run(() -> {
                    BuildPipelineClient.cycleAnchorTool(-1);          // MOVE -> SLIDE
                    BuildPipelineClient.cycleAnchorTool(-1);          // SLIDE -> LIFT
                    BuildPipelineClient.useAnchorTool(1, false);      // up one
                })
                .check("lift-tool-moves-up", () -> {
                    BlockSet now = BuildPipelineClient.getAnchorBlocks();
                    if (now == null || now == anchorBefore) return List.of("anchor not recomputed");
                    int dy = bounds(now)[1] - bounds(anchorBefore)[1];
                    List<String> f = new ArrayList<>();
                    if (dy != 1) f.add("lift moved " + dy + " blocks, expected 1");
                    if (now.validCount() != anchorBefore.validCount()) f.add("block count changed " + now.validCount());
                    if (BuildPipelineClient.getAnchorTool() != BuildPipelineClient.AnchorTool.LIFT) f.add("tool is " + BuildPipelineClient.getAnchorTool());
                    return f;
                })
                .run(() -> {
                    anchorBefore = BuildPipelineClient.getAnchorBlocks();
                    BuildPipelineClient.cycleAnchorTool(1);           // LIFT -> SLIDE
                    BuildPipelineClient.cycleAnchorTool(1);           // SLIDE -> MOVE
                    BuildPipelineClient.useAnchorTool(1, true);       // forward five
                })
                .check("move-tool-big-step", () -> {
                    BlockSet now = BuildPipelineClient.getAnchorBlocks();
                    int[] a = bounds(anchorBefore), b = bounds(now);
                    int shifted = Math.abs(b[0] - a[0]) + Math.abs(b[2] - a[2]);
                    List<String> f = new ArrayList<>();
                    if (shifted != 5 || b[1] != a[1]) f.add("forward x5 shifted " + shifted + " horizontally, dy " + (b[1] - a[1]));
                    return f;
                })
                .run(() -> {
                    anchorBefore = BuildPipelineClient.getAnchorBlocks();
                    // ROTATE is the tool before MOVE in the list (VIEW, ROTATE are at the end): cycle up twice
                    BuildPipelineClient.cycleAnchorTool(1);           // MOVE -> VIEW
                    BuildPipelineClient.cycleAnchorTool(1);           // VIEW -> ROTATE
                    BuildPipelineClient.useAnchorTool(1, false);
                })
                .check("rotate-tool-quarter-turn", () -> {
                    BlockSet now = BuildPipelineClient.getAnchorBlocks();
                    List<String> f = new ArrayList<>();
                    if (BuildPipelineClient.getAnchorTool() != BuildPipelineClient.AnchorTool.ROTATE) f.add("tool is " + BuildPipelineClient.getAnchorTool());
                    if (now.validCount() != anchorBefore.validCount()) f.add("count " + anchorBefore.validCount() + " -> " + now.validCount());
                    String before = spans(anchorBefore), after = spans(now);
                    String[] s = before.split("x");
                    if (!after.equals(s[2] + "x" + s[1] + "x" + s[0])) f.add("footprint " + before + " -> " + after + ", expected the x/z spans swapped");
                    return f;
                })
                .run(() -> {
                    anchorBefore = BuildPipelineClient.getAnchorBlocks();
                    BuildPipelineClient.buildAnchor();
                })
                .waitTicks(SETTLE)
                .check("server-world-matches-preview", () -> {
                    List<String> f = new ArrayList<>();
                    Set<BlockPos> want = new HashSet<>(anchorBefore.validPositions());
                    int[] b = bounds(anchorBefore);
                    int[] stray = {0};
                    Set<BlockPos> missing = new HashSet<>(want);
                    onServer(p -> {
                        var level = p.serverLevel();
                        for (int x = b[0] - 2; x <= b[3] + 2; x++)
                            for (int z = b[2] - 2; z <= b[5] + 2; z++) {
                                BlockPos pos = new BlockPos(x, b[1], z);
                                boolean stone = level.getBlockState(pos).is(Blocks.STONE);
                                if (stone && want.contains(pos)) missing.remove(pos);
                                else if (stone) stray[0]++;
                            }
                        return null;
                    });
                    if (!missing.isEmpty()) f.add(missing.size() + " previewed blocks missing on the server, e.g. " + missing.iterator().next());
                    if (stray[0] > 0) f.add(stray[0] + " stone blocks the preview never showed");
                    if (BuildPipelineClient.previewLocked) f.add("anchor should release after building");
                    return f;
                });
    }

    // =============================================================================================
    // Generic checks and helpers
    // =============================================================================================

    /** Client vs server: the open menu's slots, the cursor, and the player inventory. */
    static List<String> ghostDiff() {
        Snap client = Snap.of(Minecraft.getInstance().player);
        Snap server = onServer(Snap::of);
        List<String> fails = new ArrayList<>();
        diffLists("menu slot", client.menu, server.menu, fails);
        diffLists("inventory slot", client.inventory, server.inventory, fails);
        if (!client.carried.equals(server.carried)) fails.add("carried client=" + client.carried + " server=" + server.carried);
        return fails;
    }

    static void diffLists(String what, List<String> client, List<String> server, List<String> fails) {
        if (client.size() != server.size()) {
            fails.add(what + " count client=" + client.size() + " server=" + server.size());
            return;
        }
        for (int i = 0; i < server.size(); i++) {
            if (!client.get(i).equals(server.get(i))) {
                fails.add("GHOST " + what + " " + i + " client=" + client.get(i) + " server=" + server.get(i));
            }
        }
    }

    record Snap(List<String> menu, List<String> inventory, String carried) {
        static Snap of(Player p) {
            List<String> menu = new ArrayList<>();
            for (Slot slot : p.containerMenu.slots) menu.add(key(slot.getItem()));
            List<String> inv = new ArrayList<>();
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) inv.add(key(p.getInventory().getItem(i)));
            return new Snap(menu, inv, key(p.containerMenu.getCarried()));
        }
    }

    /** Stable identity for comparing stacks across sides: item id + count. Components deliberately excluded
     * (cooldowns/energy tick independently on each side and would false-positive). */
    static String key(ItemStack s) {
        if (s.isEmpty()) return "-";
        return BuiltInRegistries.ITEM.getKey(s.getItem()) + (s.getCount() > 1 ? "x" + s.getCount() : "");
    }

    /** Every attribute the server player has, by id. Use for parity oracles. */
    static Map<String, Double> attributes(ServerPlayer p) {
        Map<String, Double> values = new LinkedHashMap<>();
        BuiltInRegistries.ATTRIBUTE.holders().forEach(h -> {
            if (p.getAttributes().hasAttribute(h)) values.put(h.key().location().toString(), p.getAttributeValue(h));
        });
        return values;
    }

    /** Differences between two attribute snapshots, beyond float noise. */
    static List<String> diffAttributes(String labelA, Map<String, Double> a, String labelB, Map<String, Double> b) {
        List<String> fails = new ArrayList<>();
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            double x = a.getOrDefault(k, Double.NaN);
            double y = b.getOrDefault(k, Double.NaN);
            if (Double.isNaN(x) != Double.isNaN(y) || Math.abs(x - y) > 1e-6) {
                fails.add("attribute " + k + " " + labelA + "=" + x + " " + labelB + "=" + y);
            }
        }
        return fails;
    }

    static void clearPlayer(ServerPlayer p) {
        p.containerMenu.setCarried(ItemStack.EMPTY);
        p.getInventory().clearContent();
    }

    /** Run on the server thread and wait for the result. Blocks the client tick briefly - fine for tests. */
    static <T> T onServer(Function<ServerPlayer, T> fn) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    /** A left-click on a slot of the open menu, through the real client->server path. */
    static void click(Slot slot) {
        Minecraft mc = Minecraft.getInstance();
        if (slot == null) {
            fail("click", "target slot not found in open menu");
            return;
        }
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot.index, 0, ClickType.PICKUP, mc.player);
    }

    /** Select a hotbar slot on both sides, then right-click with it (the real use-item path). */
    static void useHotbar(int hotbarSlot) {
        Minecraft mc = Minecraft.getInstance();
        mc.player.getInventory().selected = hotbarSlot;
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
    }

    static Slot firstEmptyPlayerSlot() {
        Player player = Minecraft.getInstance().player;
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container == player.getInventory() && !slot.hasItem()) return slot;
        }
        return null;
    }

    // =============================================================================================
    // Seq: a scenario's steps. A step returns true when done, false to be called again next tick.
    // =============================================================================================

    static final class Seq {
        final String name;
        final List<BooleanSupplier> steps = new ArrayList<>();

        Seq(String name) {
            this.name = name;
            steps.add(() -> {
                log("-- scenario: " + name);
                return true;
            });
        }

        Seq run(Runnable action) {
            steps.add(() -> {
                action.run();
                return true;
            });
            return this;
        }

        Seq onServerRun(java.util.function.Consumer<ServerPlayer> action) {
            return run(() -> onServer(p -> {
                action.accept(p);
                return null;
            }));
        }

        Seq waitTicks(int n) {
            int[] left = {n};
            steps.add(() -> --left[0] <= 0);
            return this;
        }

        Seq waitUntil(BooleanSupplier condition, int timeoutTicks, String failMessage) {
            int[] left = {timeoutTicks};
            steps.add(() -> {
                if (condition.getAsBoolean()) return true;
                if (--left[0] > 0) return false;
                fail(name, failMessage);
                return true;
            });
            return this;
        }

        /** Run checks; if anything fails, wait {@link #RECHECK} ticks and record the re-check instead. */
        Seq check(String checkName, Supplier<List<String>> checks) {
            steps.add(() -> {
                if (checks.get().isEmpty()) {
                    record(checkName, List.of());
                    // Let a few frames render the new state first, or the picture shows the previous one
                    int[] frames = {SHOT_DELAY};
                    queueFront(List.of(() -> --frames[0] <= 0, () -> {
                        screenshot(checkName);
                        return true;
                    }));
                    return true;
                }
                int[] left = {RECHECK};
                queueFront(List.of(() -> --left[0] <= 0, () -> {
                    record(checkName, checks.get());
                    screenshot(checkName);
                    return true;
                }));
                return true;
            });
            return this;
        }

        /** Build more steps at run time (e.g. after reading state) and run them next. */
        Seq then(Supplier<Seq> later) {
            steps.add(() -> {
                queueFront(later.get().steps);
                return true;
            });
            return this;
        }

        Seq screenshotStep(String shotName) {
            return run(() -> screenshot(shotName));
        }
    }

    // =============================================================================================
    // Engine
    // =============================================================================================

    private static final Deque<BooleanSupplier> STEPS = new ArrayDeque<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static boolean running;
    private static boolean worldRequested;
    private static boolean autoStarted;
    private static int failures;
    private static int shot;

    @SubscribeEvent
    static void onRegisterCommands(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.production) return;
        event.getDispatcher().register(Commands.literal(PREFIX + "selftest").executes(ctx -> {
            start("manual", SelfTest::manualSuite, false);
            return 1;
        }));
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (FMLEnvironment.production) return;
        Minecraft mc = Minecraft.getInstance();

        if (AUTO) {
            mc.options.pauseOnLostFocus = false; // unattended: the window is rarely focused
            // A fresh game directory (no options.txt) opens the accessibility onboarding screen instead
            // of the title screen on first launch - accept either, or an unattended run waits forever.
            if (!worldRequested && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
                worldRequested = true;
                createScratchWorld(mc);
            }
            if (!autoStarted && mc.player != null && mc.getSingleplayerServer() != null) {
                autoStarted = true;
                int[] warmup = {60}; // let the fresh world finish joining
                STEPS.add(() -> --warmup[0] <= 0);
                start("suite in scratch world " + WORLD, SelfTest::suite, true);
            }
        }

        if (running && mc.player == null) {
            fail("session", "left the world mid-run (disconnect/kick/crash) - see latest.log");
            running = false;
            writeReport();
            if (AUTO) mc.stop();
            return;
        }

        while (running && !STEPS.isEmpty()) {
            // Poll BEFORE running: steps may push follow-ups to the front, and removing "the first
            // element" afterwards would drop the follow-up instead of the step itself.
            BooleanSupplier step = STEPS.pollFirst();
            if (!step.getAsBoolean()) {
                STEPS.addFirst(step);
                return;
            }
        }
    }

    private static void start(String mode, java.util.function.Consumer<List<Seq>> build, boolean quitAfter) {
        if (running) return;
        running = true;
        failures = 0;
        shot = 0;
        REPORT.clear();
        List<Seq> seqs = new ArrayList<>();
        build.accept(seqs);
        log("start: " + mode + " (" + seqs.size() + " scenario(s))");
        for (Seq s : seqs) STEPS.addAll(s.steps);
        STEPS.add(() -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            writeReport();
            if (quitAfter) {
                int[] left = {60}; // screenshots finish writing on the io pool
                STEPS.addLast(() -> {
                    if (--left[0] > 0) return false;
                    Minecraft.getInstance().stop();
                    return true;
                });
            } else {
                STEPS.addLast(() -> {
                    running = false;
                    return true;
                });
            }
            return true;
        });
    }

    private static void createScratchWorld(Minecraft mc) {
        // QuickPlay refuses a world that doesn't exist, so create it ourselves - fresh every run.
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                LOG.error("selftest: couldn't delete old scratch world", e);
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    static void queueFront(List<BooleanSupplier> seq) {
        for (int i = seq.size() - 1; i >= 0; i--) STEPS.addFirst(seq.get(i));
    }

    static void screenshot(String name) {
        Minecraft mc = Minecraft.getInstance();
        // Flat filename: Screenshot.grab creates run/screenshots/ but not subdirectories.
        String file = String.format("%s-selftest-%02d-%s.png", PREFIX, shot++, name);
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), msg -> {});
    }

    static void record(String name, List<String> fails) {
        if (fails.isEmpty()) {
            log("PASS " + name);
        } else {
            failures++;
            log("FAIL " + name);
            new LinkedHashSet<>(fails).forEach(f -> log("    " + f));
        }
    }

    static void fail(String name, String why) {
        record(name, List.of(why));
    }

    static void log(String line) {
        REPORT.add(line);
        LOG.info("selftest: {}", line);
    }

    private static void writeReport() {
        try {
            Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve(PREFIX + "-selftest.txt"), REPORT);
        } catch (IOException e) {
            LOG.error("selftest: couldn't write report", e);
        }
    }
}
