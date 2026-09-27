package nl.requios.effortlessbuilding.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Pick one block from every scanned block (sorted by color, searchable), the held block, or none.
 * Returns to the parent screen with the choice.
 */
public class BlockPickerScreen extends Screen {

    private final Screen parent;
    private final Consumer<@Nullable Item> onPick;
    private final BlockGrid grid = new BlockGrid();
    private int panelW, panelH;

    public BlockPickerScreen(Screen parent, Component title, Consumer<@Nullable Item> onPick) {
        super(title);
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        BlockColorCache.ensureReady();
        panelW = Math.min(width - 16, 700);
        panelH = Math.min(height - 16, 460);
        int px = panelX(), py = panelY();

        EditBox search = new EditBox(font, px + 6, py + 20, 160, 16, Component.translatable("effortlessbuilding.screen.palette_search"));
        search.setHint(Component.translatable("effortlessbuilding.screen.palette_search"));
        search.setValue(grid.search());
        search.setResponder(grid::setSearch);
        addRenderableWidget(search);
        setInitialFocus(search);
        addRenderableWidget(Button.builder(Component.literal((grid.hideCopies() ? "☑ " : "☐ ")
                                + I18n.get("effortlessbuilding.screen.palette_hide_copies")),
                        b -> { grid.toggleHideCopies(); rebuildWidgets(); })
                .bounds(px + 172, py + 20, 90, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.picker_held"), b -> {
                    if (minecraft != null && minecraft.player != null
                            && minecraft.player.getMainHandItem().getItem() instanceof BlockItem held) pick(held);
                })
                .bounds(px + 268, py + 20, 90, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.picker_none"), b -> pick(null))
                .bounds(px + 362, py + 20, 50, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(px + panelW - 76, py + panelH - 20, 70, 16).build());
    }

    private void pick(@Nullable Item item) {
        onPick.accept(item);
        onClose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        Item item = grid.itemAt(mouseX, mouseY, gridX(), gridY(), gridW(), gridH());
        if (item != null) {
            pick(item);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        grid.scroll(scrollY, gridW(), gridH());
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 170 << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, panelX() + 6, panelY() + 7, 0xFFFFFF);
        g.drawString(font, I18n.get("effortlessbuilding.screen.picker_hint"), panelX() + 6, panelY() + panelH - 16, 0x888888);
        Item hovered = grid.render(g, font, gridX(), gridY(), gridW(), gridH(), mouseX, mouseY,
                I18n.get(BlockColorCache.isScanning() ? "effortlessbuilding.screen.palette_scanning"
                        : "effortlessbuilding.screen.palette_no_match"));
        if (hovered != null) g.renderTooltip(font, new ItemStack(hovered), mouseX, mouseY);
    }

    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }
    private int gridX() { return panelX() + 6; }
    private int gridY() { return panelY() + 42; }
    private int gridW() { return panelW - 12; }
    private int gridH() { return panelH - 42 - 24; }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
