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

    /** owo turns vanilla widgets into UI components at runtime; this gives them that type for the compiler. */
    public static io.wispforest.owo.ui.core.Component w(Object widget) {
        return (io.wispforest.owo.ui.core.Component) widget;
    }
}
