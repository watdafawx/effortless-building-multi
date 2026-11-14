package nl.requios.effortlessbuilding.screen;

import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Size;
import io.wispforest.owo.ui.core.Sizing;

/**
 * A vertical scroll that can put its position back after its content was cleared and refilled.
 * owo clamps the offset to 0 when it lays out the emptied content, so plain scrolls jump to the top
 * on every refresh. Read {@link #offset()} before the clear and {@link #restore(double)} after the refill.
 */
final class KeepScroll extends ScrollContainer<FlowLayout> {
    private double pending = -1;

    KeepScroll(Sizing horizontal, Sizing vertical, FlowLayout child) {
        super(ScrollDirection.VERTICAL, horizontal, vertical, child);
        scrollbarThiccness(3);
    }

    double offset() {
        return pending >= 0 ? pending : scrollOffset;
    }

    void restore(double offset) {
        pending = offset;
        apply();
    }

    private void apply() {
        if (pending < 0) return;
        scrollOffset = currentScrollPosition = Math.max(0, Math.min(pending, maxScroll));
    }

    @Override
    public void layout(Size space) {
        super.layout(space);
        apply();
        // Keep waiting while the content is not laid out yet (nothing to scroll)
        if (maxScroll > 0 || pending == 0) pending = -1;
    }
}
