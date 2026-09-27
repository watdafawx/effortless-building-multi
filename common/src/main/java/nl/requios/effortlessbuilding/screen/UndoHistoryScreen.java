package nl.requios.effortlessbuilding.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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

/**
 * The undo history: undone operations (greyed, redo-able) above, done ones below, newest nearest
 * the middle. Clicking a done operation undoes it and everything after it; clicking an undone one
 * redoes up to it. The lists come from the server and refresh after every step.
 */
public class UndoHistoryScreen extends Screen {

    private static final int ROW_H = 20;

    /** One line: the operation and how many steps undo or redo takes to reach it. */
    private record Row(Summary summary, boolean undone, int steps) {}

    private static UndoHistoryS2CPacket latest;

    private final List<Row> rows = new ArrayList<>();
    private int panelW, panelH, scroll = 0;
    private boolean loaded = false;

    public UndoHistoryScreen() {
        super(Component.translatable("effortlessbuilding.screen.undo_history"));
    }

    /** Called when the server sends the lists; refreshes the open screen. */
    public static void receive(UndoHistoryS2CPacket packet) {
        latest = packet;
        if (Minecraft.getInstance().screen instanceof UndoHistoryScreen screen) screen.refresh();
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 16, 420);
        panelH = Math.min(height - 16, 360);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(panelX() + panelW - 76, panelY() + panelH - 20, 70, 16).build());
        if (!loaded) PacketHandler.sendToServer(new UndoHistoryC2SPacket());
    }

    private void refresh() {
        loaded = true;
        rows.clear();
        if (latest == null) return;
        // Furthest redo on top, down to the next redo, then the latest done operation and older ones
        for (int j = latest.redo().size() - 1; j >= 0; j--) rows.add(new Row(latest.redo().get(j), true, j + 1));
        for (int i = 0; i < latest.undo().size(); i++) rows.add(new Row(latest.undo().get(i), false, i + 1));
        // Keep the boundary between undone and done in view
        scroll = Math.max(0, Math.min(maxScroll(), latest.redo().size() - visibleRows() / 2));
    }

    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }
    private int listY() { return panelY() + 34; }
    private int visibleRows() { return Math.max(1, (panelH - 34 - 26) / ROW_H); }
    private int maxScroll() { return Math.max(0, rows.size() - visibleRows()); }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < panelX() + 4 || mouseX > panelX() + panelW - 4 || mouseY < listY()) return -1;
        int i = (int) ((mouseY - listY()) / ROW_H);
        if (i >= visibleRows()) return -1;
        i += scroll;
        return i < rows.size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        int i = rowAt(mouseX, mouseY);
        if (button != 0 || i < 0) return false;
        Row row = rows.get(i);
        if (row.undone()) PacketHandler.sendToServer(new RedoPacket(row.steps()));
        else PacketHandler.sendToServer(new UndoPacket(row.steps()));
        // Handled in order on the server, so this answer already shows the change
        PacketHandler.sendToServer(new UndoHistoryC2SPacket());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = BlockGrid.scrollStep(visibleRows());
        scroll = Math.max(0, Math.min(maxScroll(), scroll + (scrollY > 0 ? -step : step)));
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 170 << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int px = panelX(), py = panelY();
        g.drawString(font, title, px + 6, py + 7, 0xFFFFFF);
        g.drawString(font, I18n.get("effortlessbuilding.screen.undo_history.hint"), px + 6, py + 19, 0xAAAAAA);

        if (!loaded) {
            g.drawCenteredString(font, I18n.get("effortlessbuilding.screen.undo_history.loading"), px + panelW / 2, listY() + 20, 0xAAAAAA);
            return;
        }
        if (rows.isEmpty()) {
            g.drawCenteredString(font, I18n.get("effortlessbuilding.screen.undo_history.empty"), px + panelW / 2, listY() + 20, 0xAAAAAA);
            return;
        }

        int hovered = rowAt(mouseX, mouseY);
        int end = Math.min(rows.size(), scroll + visibleRows());
        for (int i = scroll; i < end; i++) {
            Row row = rows.get(i);
            Summary s = row.summary();
            int y = listY() + (i - scroll) * ROW_H;
            // What clicking here would change: every row from this one to the boundary
            boolean affected = hovered >= 0 && rows.get(hovered).undone() == row.undone()
                    && (row.undone() ? i >= hovered : i <= hovered);
            int bg = affected ? (row.undone() ? 0x5533AA33 : 0x55AA3333) : (i % 2 == 0 ? 0x22FFFFFF : 0x11FFFFFF);
            g.fill(px + 4, y, px + panelW - 4, y + ROW_H - 1, bg);
            // The boundary between undone and done operations
            if (i > 0 && rows.get(i - 1).undone() && !row.undone()) g.fill(px + 4, y - 1, px + panelW - 4, y, 0xFFFFFFFF);

            g.renderItem(new ItemStack(s.item()), px + 8, y + 1);
            int color = row.undone() ? 0x888888 : 0xFFFFFF;
            String what = I18n.get("effortlessbuilding.screen.undo_history." + s.kind().name().toLowerCase(),
                    s.blocks(), new ItemStack(s.item()).getHoverName().getString());
            g.drawString(font, what, px + 30, y + 6, color);
            String when = age(s.ageSeconds()) + (s.free() ? " · " + I18n.get("effortlessbuilding.screen.undo_history.free") : "");
            g.drawString(font, when, px + panelW - 10 - font.width(when), y + 6, 0xAAAAAA);
        }

        if (hovered >= 0) {
            Row row = rows.get(hovered);
            String key = row.undone() ? "effortlessbuilding.screen.undo_history.redo_to" : "effortlessbuilding.screen.undo_history.undo_to";
            g.renderTooltip(font, Component.translatable(key, row.steps()), mouseX, mouseY);
        }
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
