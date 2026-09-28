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
        if (ticks == 5 && Boolean.getBoolean("effortlessbuilding.uitest.scroll") && mc.screen != null) {
            // Scroll down over the middle of the screen (the settings on the Shape Generator)
            mc.screen.mouseScrolled(mc.screen.width * 0.375, mc.screen.height * 0.5, 0, -100); // over a number field
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
