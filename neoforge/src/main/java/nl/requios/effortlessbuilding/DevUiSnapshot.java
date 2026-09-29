package nl.requios.effortlessbuilding;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import nl.requios.effortlessbuilding.screen.PaletteScreen;
import nl.requios.effortlessbuilding.screen.ShapeGeneratorScreen;
import nl.requios.effortlessbuilding.screen.UndoHistoryScreen;

import java.io.File;
import java.util.function.Supplier;

/**
 * Development aid, off unless the {@code effortlessbuilding.uitest} system property names a screen
 * (the {@code runUiTest} Gradle task sets it): opens that screen from the title screen, saves a
 * screenshot to {@code screenshots/ui-<name>.png} in the run folder, then closes the game.
 * Lets the mod's screens be checked without playing.
 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class DevUiSnapshot {

    private static final String SCREEN = System.getProperty("effortlessbuilding.uitest", "");
    private static int ticks = -1;

    private DevUiSnapshot() {}

    private static io.wispforest.owo.ui.core.OwoUIAdapter<?> adapter(io.wispforest.owo.ui.base.BaseOwoScreen<?> screen) {
        try {
            var field = io.wispforest.owo.ui.base.BaseOwoScreen.class.getDeclaredField("uiAdapter");
            field.setAccessible(true);
            return (io.wispforest.owo.ui.core.OwoUIAdapter<?>) field.get(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Buttons that open a dropdown (their text ends with ▼). */
    private static void collectMenus(io.wispforest.owo.ui.core.Component c, java.util.List<net.minecraft.client.gui.components.Button> out) {
        if (c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().endsWith("▼")) out.add(b);
        if (c instanceof io.wispforest.owo.ui.core.ParentComponent parent) {
            for (var child : parent.children()) collectMenus(child, out);
        }
    }

    private static void collectButtons(io.wispforest.owo.ui.core.Component c, String text, java.util.List<net.minecraft.client.gui.components.Button> out) {
        if (c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(text)) out.add(b);
        if (c instanceof io.wispforest.owo.ui.core.ParentComponent parent) {
            for (var child : parent.children()) collectButtons(child, text, out);
        }
    }

    /** Logs every component's type, position and size, indented by depth. */
    private static void dump(io.wispforest.owo.ui.core.Component c, int depth) {
        Constants.LOG.info("[UI] {}{} at {},{} size {}x{} sizing {} x {}", "  ".repeat(depth), c.getClass().getSimpleName(),
                c.x(), c.y(), c.width(), c.height(), c.horizontalSizing().get(), c.verticalSizing().get());
        if (c instanceof io.wispforest.owo.ui.container.ScrollContainer<?> scroll) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String name : new String[]{"maxScroll", "scrollOffset", "childSize"}) {
                    var f = io.wispforest.owo.ui.container.ScrollContainer.class.getDeclaredField(name);
                    f.setAccessible(true);
                    sb.append(name).append('=').append(f.get(scroll)).append(' ');
                }
                Constants.LOG.info("[UI] {}  scroll: {}", "  ".repeat(depth), sb);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        if (depth < 6 && c instanceof io.wispforest.owo.ui.core.ParentComponent parent) {
            for (var child : parent.children()) dump(child, depth + 1);
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (SCREEN.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (ticks < 0) {
            if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
            Supplier<Screen> screen = switch (SCREEN) {
                case "palette" -> () -> new PaletteScreen(null);
                case "undo" -> UndoHistoryScreen::new;
                default -> ShapeGeneratorScreen::new;
            };
            String type = System.getProperty("effortlessbuilding.uitest.type", "");
            if (!type.isEmpty()) {
                nl.requios.effortlessbuilding.shape.ShapeClientState.setActive(
                        nl.requios.effortlessbuilding.shape.ShapeParams.defaults(nl.requios.effortlessbuilding.shape.ShapeType.valueOf(type)));
            }
            mc.setScreen(screen.get());
            ticks = 0;
            return;
        }
        if (++ticks == 20 && Boolean.getBoolean("effortlessbuilding.uitest.menu") && mc.screen instanceof io.wispforest.owo.ui.base.BaseOwoScreen<?> owo) {
            // Open the lowest dropdown on the screen, to check how menus are placed and layered
            java.util.List<net.minecraft.client.gui.components.Button> menus = new java.util.ArrayList<>();
            collectMenus(adapter(owo).rootComponent, menus);
            menus.stream().max(java.util.Comparator.comparingInt(net.minecraft.client.gui.components.Button::getY)).ifPresent(b -> b.onPress());
        }
        if (ticks == 25 && Boolean.getBoolean("effortlessbuilding.uitest.menu") && mc.screen instanceof io.wispforest.owo.ui.base.BaseOwoScreen<?> owo) {
            // Click the second entry of the open menu, as a player would
            for (var c : adapter(owo).rootComponent.children()) {
                if (c instanceof io.wispforest.owo.ui.component.DropdownComponent menu
                        && menu.children().getFirst() instanceof io.wispforest.owo.ui.core.ParentComponent entries
                        && entries.children().size() > 1) {
                    var entry = entries.children().get(1);
                    mc.screen.mouseClicked(entry.x() + 3, entry.y() + 3, 0);
                }
            }
        }
        if (ticks == 10 && Boolean.getBoolean("effortlessbuilding.uitest.addpart") && mc.screen instanceof io.wispforest.owo.ui.base.BaseOwoScreen<?> owo) {
            // Press "+" (add part), then pick the second shape in its menu
            java.util.List<net.minecraft.client.gui.components.Button> plus = new java.util.ArrayList<>();
            collectButtons(adapter(owo).rootComponent, "+", plus);
            plus.stream().min(java.util.Comparator.comparingInt(net.minecraft.client.gui.components.Button::getY)).ifPresent(b -> b.onPress());
        }
        if (ticks == 15 && Boolean.getBoolean("effortlessbuilding.uitest.addpart") && mc.screen instanceof io.wispforest.owo.ui.base.BaseOwoScreen<?> owo) {
            for (var c : adapter(owo).rootComponent.children()) {
                if (c instanceof io.wispforest.owo.ui.component.DropdownComponent menu
                        && menu.children().getFirst() instanceof io.wispforest.owo.ui.core.ParentComponent entries) {
                    var entry = entries.children().get(1);
                    mc.screen.mouseClicked(entry.x() + 3, entry.y() + 3, 0);
                }
            }
        }
        if (Boolean.getBoolean("effortlessbuilding.uitest.drag") && mc.screen instanceof io.wispforest.owo.ui.base.BaseOwoScreen<?> owo) {
            // After adding a part: switch the preview to the top view, then drag in it
            if (ticks == 18) {
                java.util.List<net.minecraft.client.gui.components.Button> view = new java.util.ArrayList<>();
                collectButtons(adapter(owo).rootComponent, "3D", view);
                view.forEach(b -> b.onPress());
            }
            if (ticks == 22) {
                double x = mc.screen.width * 0.82, y = mc.screen.height * 0.4;
                mc.screen.mouseClicked(x, y, 0);
                for (int i = 1; i <= 6; i++) mc.screen.mouseDragged(x + i * 10, y, 0, 10, 0);
                mc.screen.mouseReleased(x + 60, y, 0);
            }
            // Optionally undo the drag with Ctrl+Z
            if (ticks == 32 && Boolean.getBoolean("effortlessbuilding.uitest.undo")) {
                mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_Z, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);
            }
        }
        if (ticks == 30 && Boolean.getBoolean("effortlessbuilding.uitest.save") && mc.screen instanceof io.wispforest.owo.ui.base.BaseOwoScreen<?> owo) {
            java.util.List<net.minecraft.client.gui.components.Button> save = new java.util.ArrayList<>();
            collectButtons(adapter(owo).rootComponent, "Save", save);
            save.forEach(b -> b.onPress());
        }
        if (ticks == 3 && SCREEN.equals("undo")) {
            // No server here: show made-up history
            var kind = nl.requios.effortlessbuilding.utilities.UndoManager.Summary.Kind.class;
            java.util.List<nl.requios.effortlessbuilding.utilities.UndoManager.Summary> done = java.util.List.of(
                    new nl.requios.effortlessbuilding.utilities.UndoManager.Summary(net.minecraft.world.item.Items.COBBLESTONE, 340, kind.getEnumConstants()[0], 25, false),
                    new nl.requios.effortlessbuilding.utilities.UndoManager.Summary(net.minecraft.world.item.Items.OAK_PLANKS, 96, kind.getEnumConstants()[0], 190, false),
                    new nl.requios.effortlessbuilding.utilities.UndoManager.Summary(net.minecraft.world.item.Items.STONE, 12, kind.getEnumConstants()[1], 4000, true));
            java.util.List<nl.requios.effortlessbuilding.utilities.UndoManager.Summary> undone = java.util.List.of(
                    new nl.requios.effortlessbuilding.utilities.UndoManager.Summary(net.minecraft.world.item.Items.GLASS, 48, kind.getEnumConstants()[0], 10, false));
            nl.requios.effortlessbuilding.screen.UndoHistoryScreen.receive(new nl.requios.effortlessbuilding.network.UndoHistoryS2CPacket(done, undone));
        }
        if (ticks == 5 && Boolean.getBoolean("effortlessbuilding.uitest.scroll") && mc.screen != null) {
            // Scroll down over the middle of the screen (the settings on the Shape Generator)
            mc.screen.mouseScrolled(mc.screen.width * 0.25, mc.screen.height * 0.5, 0, -100); // over the labels
        }
        if (ticks == 39 && mc.screen instanceof io.wispforest.owo.ui.base.BaseOwoScreen<?> owo) {
            dump(adapter(owo).rootComponent, 0);
        }
        if (ticks == 40) {
            File dir = mc.gameDirectory;
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                File out = new File(dir, "screenshots/ui-" + SCREEN + ".png");
                out.getParentFile().mkdirs();
                image.writeToFile(out);
                Constants.LOG.info("[EffortlessBuilding] UI snapshot saved to {}", out.getAbsolutePath());
            } catch (Exception e) {
                Constants.LOG.error("[EffortlessBuilding] UI snapshot failed", e);
            }
            mc.stop();
        }
    }
}
