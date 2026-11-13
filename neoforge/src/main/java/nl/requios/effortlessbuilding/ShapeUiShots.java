package nl.requios.effortlessbuilding;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.ParentComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.screen.ShapeGeneratorScreen;
import nl.requios.effortlessbuilding.shape.SchematicLibrary;
import nl.requios.effortlessbuilding.shape.SchematicWriter;
import nl.requios.effortlessbuilding.shape.ShapeClientState;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.shape.ShapeParams.Operation;
import nl.requios.effortlessbuilding.shape.ShapeParams.Part;
import nl.requios.effortlessbuilding.shape.ShapeType;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shape Generator screenshots at several window sizes and GUI scales, with simple and complex designs,
 * plus a layout check: nothing off screen, no button whose text does not fit, no label running out of its panel.
 */
final class ShapeUiShots {

    private ShapeUiShots() {}

    private record Design(String name, ShapeParams params, int editing, Consumer<ShapeGeneratorScreen> then) {
        Design(String name, ShapeParams params, int editing) {
            this(name, params, editing, screen -> {});
        }
    }

    private record Window(int width, int height) {}

    private static final String LONG_TEMPLATE = "Grand cathedral rose window with flying buttresses";
    private static final String SCHEMATIC = "eb-selftest-ui";

    static SelfTest.Seq scenario() {
        ShapeParams gear = ShapeParams.defaults(ShapeType.GEAR).withSize(24).withParts(List.of(
                new Part(ShapeParams.defaults(ShapeType.TORUS).withSize(12), Operation.SUBTRACT, 0, 2, 0),
                new Part(ShapeParams.defaults(ShapeType.PRISM).withSize(4), Operation.UNITE, 3, 0, -3, "minecraft:polished_blackstone_bricks", 4, ""),
                new Part(ShapeParams.defaults(ShapeType.STAR).withSize(18), Operation.INTERSECT, 0, 0, 0),
                new Part(ShapeParams.defaults(ShapeType.TEXT).withSchematic("EB"), Operation.UNITE, 0, 6, 0)));
        List<Design> designs = List.of(
                new Design("gear", gear, -1),
                new Design("gear-part", gear, 1),
                new Design("floor", ShapeParams.defaults(ShapeType.FLOOR), -1),
                new Design("text", ShapeParams.defaults(ShapeType.TEXT).withSchematic("Hello builders|Second line"), -1),
                new Design("schematic", ShapeParams.defaults(ShapeType.SCHEMATIC).withSchematic(SCHEMATIC), -1),
                // Ctrl+F opens the library (its tab on narrow screens) and focuses the search box
                new Design("search", gear, -1, screen -> {
                    screen.keyPressed(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_CONTROL);
                    if (adapter(screen).rootComponent.focusHandler().focused() instanceof TextBoxComponent box) box.text("o");
                }));
        List<Window> windows = List.of(new Window(1920, 1080), new Window(1280, 720), new Window(854, 480));

        SelfTest.Seq seq = new SelfTest.Seq("shape-ui").run(ShapeUiShots::provision);
        int[] savedScale = {0};
        seq.run(() -> savedScale[0] = Minecraft.getInstance().options.guiScale().get());
        // The complex design everywhere; the others at a common size
        for (Window window : windows) {
            for (int scale : new int[]{2, 3, 4, 0}) shot(seq, designs.getFirst(), window, scale);
        }
        for (Design design : designs.subList(1, designs.size())) {
            shot(seq, design, windows.get(0), 3);
            shot(seq, design, windows.get(1), 0);
        }
        // The radial menu shows the selected shape's name and a preview in Shape mode
        seq.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.getWindow().setWindowed(1280, 720);
            mc.options.guiScale().set(0);
            mc.resizeDisplay();
            ShapeClientState.setActive(gear);
            nl.requios.effortlessbuilding.buildmode.BuildModes.CLIENT.setBuildMode(nl.requios.effortlessbuilding.buildmode.BuildModeEnum.SHAPE);
            nl.requios.effortlessbuilding.screen.RadialMenu.keepOpen = true;
            mc.setScreen(nl.requios.effortlessbuilding.screen.RadialMenu.instance);
        }).waitTicks(8).check("shape-radial-1280x720", () ->
                Minecraft.getInstance().screen instanceof nl.requios.effortlessbuilding.screen.RadialMenu ? List.of() : List.of("radial menu closed"));
        return seq.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            nl.requios.effortlessbuilding.screen.RadialMenu.keepOpen = false;
            mc.setScreen(null);
            ShapeClientState.deleteTemplate(LONG_TEMPLATE);
            ShapeClientState.deleteTemplate("Tower");
            mc.options.guiScale().set(savedScale[0]);
            mc.resizeDisplay();
        });
    }

    private static void shot(SelfTest.Seq seq, Design design, Window window, int scale) {
        String name = "shape-ui-" + design.name() + "-" + window.width() + "x" + window.height() + "-s" + (scale == 0 ? "auto" : scale);
        seq.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.getWindow().setWindowed(window.width(), window.height());
            mc.options.guiScale().set(scale);
            mc.resizeDisplay();
            ShapeClientState.setActive(design.params());
            ShapeGeneratorScreen screen = new ShapeGeneratorScreen();
            mc.setScreen(screen);
            if (design.editing() >= 0) screen.editPart(design.editing());
            design.then().accept(screen);
        }).waitTicks(6).check(name, () -> layoutIssues(design));
    }

    /** A schematic to show, and templates (one with a long name) for the sidebar. */
    private static void provision() {
        Map<Cell, BlockState> blocks = new HashMap<>();
        for (int x = 0; x < 6; x++) for (int y = 0; y < 4; y++) blocks.put(new Cell(x, y, 0), Blocks.STONE_BRICKS.defaultBlockState());
        try {
            Path file = SchematicLibrary.FOLDERS.getFirst().resolve(SCHEMATIC + ".schem");
            SchematicWriter.write(file, blocks);
        } catch (java.io.IOException e) {
            SelfTest.fail("shape-ui", "couldn't write the test schematic: " + e);
        }
        ShapeClientState.saveTemplate(new ShapeClientState.Template(LONG_TEMPLATE, ShapeParams.defaults(ShapeType.ARCH)));
        ShapeClientState.saveTemplate(new ShapeClientState.Template("Tower", ShapeParams.defaults(ShapeType.TOWER)));
    }

    // ---- layout check ----

    private static List<String> layoutIssues(Design design) {
        Minecraft mc = Minecraft.getInstance();
        List<String> issues = new ArrayList<>();
        if (!(mc.screen instanceof ShapeGeneratorScreen screen)) return List.of("screen not open");
        walk(adapter(screen).rootComponent, null, mc, screen.width, screen.height, issues);
        if (design.name().equals("search") && !(adapter(screen).rootComponent.focusHandler().focused() instanceof TextBoxComponent)) {
            issues.add("Ctrl+F did not focus the search box");
        }
        return issues;
    }

    /** {@code clip}: the nearest scroll area, whose content may run past the screen. */
    private static void walk(io.wispforest.owo.ui.core.Component c, ScrollContainer<?> clip, Minecraft mc, int sw, int sh, List<String> issues) {
        if (c.width() <= 0 || c.height() <= 0) return;
        String what = describe(c);
        if (clip == null && (c.x() < 0 || c.y() < 0 || c.x() + c.width() > sw || c.y() + c.height() > sh)) {
            issues.add("off screen: " + what);
        }
        if (clip != null && (c.x() < clip.x() || c.x() + c.width() > clip.x() + clip.width())) {
            issues.add("wider than its scroll area: " + what);
        }
        if (c instanceof AbstractButton b && mc.font.width(b.getMessage()) + 6 > b.getWidth()) {
            issues.add("button text does not fit: " + what);
        }
        if (c instanceof LabelComponent && c.parent() != null) {
            var p = c.parent();
            if (c.x() + c.width() > p.x() + p.width() + 1) issues.add("label runs out of its row: " + what);
        }
        if (c instanceof ParentComponent parent) {
            ScrollContainer<?> inner = c instanceof ScrollContainer<?> s ? s : clip;
            for (var child : parent.children()) walk(child, inner, mc, sw, sh, issues);
        }
    }

    private static String describe(io.wispforest.owo.ui.core.Component c) {
        String text = c instanceof AbstractButton b ? " \"" + b.getMessage().getString() + "\""
                : c instanceof LabelComponent l ? " \"" + l.text().getString() + "\"" : "";
        return c.getClass().getSimpleName() + text + " at " + c.x() + "," + c.y() + " " + c.width() + "x" + c.height();
    }

    private static OwoUIAdapter<?> adapter(BaseOwoScreen<?> screen) {
        try {
            var field = BaseOwoScreen.class.getDeclaredField("uiAdapter");
            field.setAccessible(true);
            return (OwoUIAdapter<?>) field.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
