package io.github.watdafawx.shapewright.screen;

import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Size;
import io.wispforest.owo.ui.core.Sizing;

/**
 * A vertical scroll that can put its position back after its content was cleared and refilled.
 * owo clamps the offset to 0 every time it lays out, and clearing the content lays out an empty list (and
 * every added row lays out again), so plain scrolls jump to the top on every refresh. Read {@link #offset()}
 * before the clear and {@link #restore(double)} after the refill.
 * <p>
 * The wanted offset is kept for a short while and put back on every layout in that time, not just the
 * first one: other panels refilled in the same click (the settings) lay the whole screen out again, and
 * any of those passes can clamp the offset when the content is momentarily shorter. The player scrolling
 * ends the wait at once.
 */
final class KeepScroll extends ScrollContainer<FlowLayout> {
    /** How long a restored position is defended against layout passes, in milliseconds. */
    private static final long HOLD_MS = 400;

    private double pending = -1;
    private long holdUntil;

    KeepScroll(Sizing horizontal, Sizing vertical, FlowLayout child) {
        super(ScrollDirection.VERTICAL, horizontal, vertical, child);
        scrollbarThiccness(3);
    }

    double offset() {
        return pending >= 0 ? pending : scrollOffset;
    }

    void restore(double offset) {
        pending = offset;
        holdUntil = System.currentTimeMillis() + HOLD_MS;
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
        // Let go once the content has been laid out for a while (or there was nothing to restore)
        if (pending == 0 || (maxScroll > 0 && System.currentTimeMillis() > holdUntil)) pending = -1;
    }

    /** Mouse wheel, scrollbar drag and keys all end up here: the player now decides where the list is. */
    @Override
    protected void scrollBy(double offset, boolean instant, boolean showScrollbar) {
        pending = -1;
        super.scrollBy(offset, instant, showScrollbar);
    }
}
