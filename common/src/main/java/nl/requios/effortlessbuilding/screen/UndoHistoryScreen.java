package nl.requios.effortlessbuilding.screen;

import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import nl.requios.effortlessbuilding.network.PacketHandler;
import nl.requios.effortlessbuilding.network.RedoPacket;
import nl.requios.effortlessbuilding.network.UndoHistoryC2SPacket;
import nl.requios.effortlessbuilding.network.UndoHistoryS2CPacket;
import nl.requios.effortlessbuilding.network.UndoPacket;
import nl.requios.effortlessbuilding.utilities.UndoManager.Summary;

import java.util.ArrayList;
import java.util.List;

import static nl.requios.effortlessbuilding.screen.Ui.*;

/**
 * The undo history: undone operations (greyed, redo-able) above, done ones below, newest nearest
 * the line between them. Clicking a done operation undoes it and everything after it; clicking an
 * undone one redoes up to it. Hovering shows everything a click would change. The lists come from
 * the server and refresh after every step.
 */
public class UndoHistoryScreen extends BaseOwoScreen<FlowLayout> {

    private static final int ROW_H = 20;

    /** One line: the operation and how many steps undo or redo takes to reach it. */
    private record Row(Summary summary, boolean undone, int steps) {}

    private static UndoHistoryS2CPacket latest;

    private final List<Row> rows = new ArrayList<>();
    private final List<FlowLayout> rowLayouts = new ArrayList<>();
    private boolean loaded = false;
    private int hovered = -1;
    private FlowLayout list;

    public UndoHistoryScreen() {
        super(Component.translatable("effortlessbuilding.screen.undo_history"));
    }

    /** Called when the server sends the lists; refreshes the open screen. */
    public static void receive(UndoHistoryS2CPacket packet) {
        latest = packet;
        if (Minecraft.getInstance().screen instanceof UndoHistoryScreen screen) screen.refresh();
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.flat(BACKGROUND));
        root.horizontalAlignment(HorizontalAlignment.CENTER).verticalAlignment(VerticalAlignment.CENTER);

        FlowLayout box = panel(Containers.verticalFlow(Sizing.fixed(Math.min(width - 16, 440)), Sizing.fixed(Math.min(height - 16, 380))));
        box.gap(4);
        box.child(label(title.copy().withStyle(s -> s.withBold(true)), 0xFFFFFF));
        box.child(label(Component.translatable("effortlessbuilding.screen.undo_history.hint"), MUTED).maxWidth(Math.min(width - 16, 440) - 12));

        list = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        list.gap(1);
        box.child(Containers.verticalScroll(Sizing.fill(100), Sizing.expand(), list).scrollbarThiccness(3));

        FlowLayout bottom = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        bottom.child(hspace());
        bottom.child(w(Components.button(Component.translatable("gui.done"), b -> onClose())).horizontalSizing(Sizing.fixed(70)));
        box.child(bottom);
        root.child(box);

        fillList();
        if (!loaded && minecraft != null && minecraft.getConnection() != null) PacketHandler.sendToServer(new UndoHistoryC2SPacket());
    }

    private void refresh() {
        loaded = true;
        rows.clear();
        if (latest != null) {
            // Furthest redo on top, down to the next redo, then the latest done operation and older ones
            for (int j = latest.redo().size() - 1; j >= 0; j--) rows.add(new Row(latest.redo().get(j), true, j + 1));
            for (int i = 0; i < latest.undo().size(); i++) rows.add(new Row(latest.undo().get(i), false, i + 1));
        }
        if (list != null) fillList();
    }

    private void fillList() {
        list.clearChildren();
        rowLayouts.clear();
        hovered = -1;
        if (!loaded || rows.isEmpty()) {
            list.child(label(Component.translatable(loaded ? "effortlessbuilding.screen.undo_history.empty"
                    : "effortlessbuilding.screen.undo_history.loading"), MUTED).margins(Insets.of(12, 0, 4, 0)));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            // The line between undone and done operations
            if (i > 0 && rows.get(i - 1).undone() && !rows.get(i).undone()) {
                list.child(Components.box(Sizing.fill(100), Sizing.fixed(1)).color(io.wispforest.owo.ui.core.Color.ofArgb(0xFFFFFFFF)).fill(true));
            }
            list.child(row(i));
        }
    }

    private FlowLayout row(int index) {
        Row row = rows.get(index);
        Summary s = row.summary();
        FlowLayout line = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(ROW_H));
        line.gap(6).verticalAlignment(VerticalAlignment.CENTER).padding(Insets.horizontal(4));
        line.child(Components.item(new ItemStack(s.item())));
        String what = I18n.get("effortlessbuilding.screen.undo_history." + s.kind().name().toLowerCase(),
                s.blocks(), new ItemStack(s.item()).getHoverName().getString());
        line.child(label(Component.literal(what), row.undone() ? 0x888888 : 0xFFFFFF));
        line.child(hspace());
        String when = age(s.ageSeconds()) + (s.free() ? " · " + I18n.get("effortlessbuilding.screen.undo_history.free") : "");
        line.child(label(Component.literal(when), 0xAAAAAA));
        line.tooltip(Component.translatable(row.undone() ? "effortlessbuilding.screen.undo_history.redo_to"
                : "effortlessbuilding.screen.undo_history.undo_to", row.steps()));

        line.mouseEnter().subscribe(() -> { hovered = index; paintRows(); });
        line.mouseLeave().subscribe(() -> { if (hovered == index) { hovered = -1; paintRows(); } });
        line.mouseDown().subscribe((x, y, button) -> {
            if (button != 0) return false;
            if (row.undone()) PacketHandler.sendToServer(new RedoPacket(row.steps()));
            else PacketHandler.sendToServer(new UndoPacket(row.steps()));
            // Handled in order on the server, so this answer already shows the change
            PacketHandler.sendToServer(new UndoHistoryC2SPacket());
            return true;
        });
        rowLayouts.add(line);
        paintRow(index);
        return line;
    }

    /** Colors every row: those a click on the hovered row would change stand out (red undo, green redo). */
    private void paintRows() {
        for (int i = 0; i < rowLayouts.size(); i++) paintRow(i);
    }

    private void paintRow(int i) {
        Row row = rows.get(i);
        boolean affected = hovered >= 0 && rows.get(hovered).undone() == row.undone()
                && (row.undone() ? i >= hovered : i <= hovered);
        int color = affected ? (row.undone() ? 0x5533AA33 : 0x55AA3333) : (i % 2 == 0 ? 0x22FFFFFF : 0x11FFFFFF);
        rowLayouts.get(i).surface(Surface.flat(color));
    }

    private static String age(long seconds) {
        if (seconds < 60) return I18n.get("effortlessbuilding.screen.undo_history.seconds", seconds);
        if (seconds < 3600) return I18n.get("effortlessbuilding.screen.undo_history.minutes", seconds / 60);
        return I18n.get("effortlessbuilding.screen.undo_history.hours", seconds / 3600);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
