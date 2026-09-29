package nl.requios.effortlessbuilding.screen;

import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.ParentComponent;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.minecraft.network.chat.Component;

/** Shared look and helpers for the mod's owo-ui screens. */
public final class Ui {

    public static final int TEXT = 0xE0E0E0, MUTED = 0x9A9A9A, ACCENT = 0xFFD37F, SECTION = 0xE8A33D;
    /** Screen background, and panels on it. */
    public static final int BACKGROUND = 0xB0101010;

    private Ui() {}

    /**
     * A label whose text clicks do nothing. owo passes clicks on plain text to the screen with no style,
     * and a mod in the pack (an EMI add-on) crashes on that; none of our labels have links anyway.
     */
    public static LabelComponent label(Component text) {
        return Components.label(text).textClickHandler(style -> false);
    }

    public static LabelComponent label(Component text, int color) {
        return label(text).color(Color.ofRgb(color));
    }

    /** A bold orange heading. */
    public static LabelComponent heading(String text) {
        return (LabelComponent) label(Component.literal(text).withStyle(s -> s.withBold(true)), SECTION).margins(Insets.bottom(2));
    }

    /** Applies the {@link #label} guard to every label under a component (e.g. dropdown entries). */
    public static void ignoreTextClicks(io.wispforest.owo.ui.core.Component c) {
        if (c instanceof LabelComponent l) l.textClickHandler(style -> false);
        if (c instanceof ParentComponent parent) parent.children().forEach(Ui::ignoreTextClicks);
    }

    /** Pushes what follows to the right edge (owo's own spacer also grows vertically, stretching the row). */
    public static io.wispforest.owo.ui.core.Component hspace() {
        return Components.spacer().verticalSizing(Sizing.fixed(0));
    }

    /** A dark panel with an outline and padding. */
    public static FlowLayout panel(FlowLayout flow) {
        flow.surface(Surface.flat(0xC0202020).and(Surface.outline(0xFF3C3C3C)));
        flow.padding(Insets.of(5));
        return flow;
    }

    /** Height of one entry in a dropdown menu, for placing the menu before it is laid out. */
    private static final int MENU_ENTRY_H = 12;

    /**
     * A button showing the current choice with a ▼; clicking opens a menu of every option (current one
     * marked). Picking applies it, then runs {@code after}.
     */
    public static <T> io.wispforest.owo.ui.component.ButtonComponent choice(net.minecraft.client.gui.screens.Screen screen,
            FlowLayout root, String shown, java.util.List<T> options, java.util.function.Function<T, String> name,
            java.util.function.Consumer<T> pick, Runnable after) {
        return Components.button(Component.literal(shown + "  ▼"), b -> openMenu(screen, root, b, shown, options, name, pick, after));
    }

    /** Opens a menu under the button (above it when there is no room below); entries are guarded like {@link #label}. */
    public static <T> void openMenu(net.minecraft.client.gui.screens.Screen screen, FlowLayout root,
            net.minecraft.client.gui.components.AbstractWidget b, String current, java.util.List<T> options,
            java.util.function.Function<T, String> name, java.util.function.Consumer<T> pick, Runnable after) {
        int menuH = options.size() * MENU_ENTRY_H + 8;
        int menuY = b.getY() + b.getHeight() + menuH <= screen.height - 4 ? b.getY() + b.getHeight() : Math.max(4, b.getY() - menuH);
        var opened = io.wispforest.owo.ui.component.DropdownComponent.openContextMenu(screen, root, FlowLayout::child, b.getX(), menuY, menu -> {
            menu.surface(Surface.flat(0xF0181818).and(Surface.outline(0xFF555555)));
            for (T option : options) {
                String text = name.apply(option);
                menu.button(Component.literal(text.equals(current) ? "» " + text : "   " + text), m -> {
                    pick.accept(option);
                    m.remove();
                    after.run();
                });
            }
        });
        ignoreTextClicks(opened);
        opened.zIndex(300); // above fields (drawn batched, so depth decides) and below tooltips
    }

    /** owo turns vanilla widgets into UI components at runtime; this gives them that type for the compiler. */
    public static io.wispforest.owo.ui.core.Component w(Object widget) {
        return (io.wispforest.owo.ui.core.Component) widget;
    }
}
