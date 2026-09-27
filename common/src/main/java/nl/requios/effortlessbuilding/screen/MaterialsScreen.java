package nl.requios.effortlessbuilding.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import nl.requios.effortlessbuilding.compat.ae2.AE2Integration;
import nl.requios.effortlessbuilding.network.PacketHandler;
import nl.requios.effortlessbuilding.network.QueryAE2CountC2SPacket;
import nl.requios.effortlessbuilding.utilities.InventoryHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a build needs: each block, how many, how many the player carries, how many their AE2 network
 * holds (with a linked wireless terminal), and what is still missing.
 */
public class MaterialsScreen extends Screen {

    private static final int ROW_H = 20;

    private final Screen parent;
    private final List<Map.Entry<Item, Integer>> rows;
    private final int total;
    private int panelW, panelH, scroll = 0;

    /** @param required block item → count; blocks without a known item are passed as null keys and skipped */
    public MaterialsScreen(Screen parent, Map<Item, Integer> required) {
        super(Component.translatable("effortlessbuilding.screen.materials"));
        this.parent = parent;
        this.rows = new ArrayList<>(required.entrySet());
        this.rows.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        this.total = required.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 16, 520);
        panelH = Math.min(height - 16, 400);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(panelX() + panelW - 76, panelY() + panelH - 20, 70, 16).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = BlockGrid.scrollStep(visibleRows());
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows()), scroll + (scrollY > 0 ? -step : step)));
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
        boolean creative = minecraft != null && minecraft.player != null && minecraft.player.isCreative();
        boolean ae2 = minecraft != null && minecraft.player != null && AE2Integration.hasLinkedTerminal(minecraft.player);

        g.drawString(font, title, px + 6, py + 7, 0xFFFFFF);
        g.drawString(font, I18n.get("effortlessbuilding.screen.materials_total", total, rows.size()), px + 6, py + 19, 0xAAAAAA);
        if (creative) g.drawString(font, I18n.get("effortlessbuilding.screen.materials_creative"), px + 200, py + 19, 0x88DD88);

        // Columns: block | needed | carried | AE2 | missing
        int cNeed = px + panelW - 250, cHave = cNeed + 60, cNet = cHave + 60, cMiss = cNet + 60;
        int hy = py + 34;
        g.drawString(font, I18n.get("effortlessbuilding.screen.materials_block"), px + 6, hy, 0xCCCCCC);
        g.drawString(font, I18n.get("effortlessbuilding.screen.materials_needed"), cNeed, hy, 0xCCCCCC);
        g.drawString(font, I18n.get("effortlessbuilding.screen.materials_have"), cHave, hy, 0xCCCCCC);
        if (ae2) g.drawString(font, "AE2", cNet, hy, 0xCCCCCC);
        g.drawString(font, I18n.get("effortlessbuilding.screen.materials_missing"), cMiss, hy, 0xCCCCCC);

        ItemStack hovered = null;
        for (int r = 0; r < visibleRows(); r++) {
            int i = r + scroll;
            if (i >= rows.size()) break;
            Item item = rows.get(i).getKey();
            int needed = rows.get(i).getValue();
            int y = listTop() + r * ROW_H;
            if (r % 2 == 1) g.fill(px + 4, y - 2, px + panelW - 4, y + ROW_H - 2, 0x18FFFFFF);
            ItemStack stack = new ItemStack(item);
            g.renderItem(stack, px + 6, y);
            g.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), cNeed - px - 34), px + 26, y + 4, 0xEEEEEE);
            if (mouseX >= px + 6 && mouseX < px + 22 && mouseY >= y && mouseY < y + 16) hovered = stack;

            int have = minecraft != null && minecraft.player != null
                    ? InventoryHelper.findTotalItemsInInventory(minecraft.player, item) : 0;
            int net = 0;
            if (ae2) {
                if (AE2Integration.shouldQueryCount(item)) PacketHandler.sendToServer(new QueryAE2CountC2SPacket(item));
                net = Math.max(0, AE2Integration.getCachedCount(item));
            }
            int missing = creative ? 0 : Math.max(0, needed - have - net);
            g.drawString(font, amount(needed), cNeed, y + 4, 0xFFFFFF);
            g.drawString(font, amount(have), cHave, y + 4, have >= needed ? 0x88DD88 : 0xDDDDDD);
            if (ae2) g.drawString(font, amount(net), cNet, y + 4, 0xAACCFF);
            g.drawString(font, missing == 0 ? "✔" : amount(missing), cMiss, y + 4, missing == 0 ? 0x88DD88 : 0xFF7777);
        }
        if (rows.size() > visibleRows()) {
            g.drawString(font, (scroll + 1) + "–" + Math.min(rows.size(), scroll + visibleRows()) + " / " + rows.size()
                    + "  " + I18n.get("effortlessbuilding.screen.scroll_for_more"), px + 6, py + panelH - 16, 0x777777);
        }
        if (hovered != null) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    /** "130" or "2 st + 2" once it is at least a stack. */
    private static String amount(int n) {
        if (n < 64) return String.valueOf(n);
        int stacks = n / 64, rest = n % 64;
        return rest == 0 ? stacks + " st" : stacks + " st + " + rest;
    }

    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }
    private int listTop() { return panelY() + 48; }
    private int visibleRows() { return Math.max(1, (panelH - 48 - 24) / ROW_H); }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
