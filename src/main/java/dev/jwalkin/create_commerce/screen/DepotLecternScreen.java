package dev.jwalkin.create_commerce.screen;

import dev.jwalkin.create_commerce.config.CoinConverter;
import dev.jwalkin.create_commerce.menu.DepotLecternMenu;
import dev.jwalkin.create_commerce.network.AddTerminalNamePacket;
import dev.jwalkin.create_commerce.network.RemoveTerminalNamePacket;
import dev.jwalkin.create_commerce.network.SetVillageNamePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class DepotLecternScreen extends AbstractContainerScreen<DepotLecternMenu> {

    private enum Tab { DEPOSIT, ACCEPTS, TERMINALS, INFO }

    // -- Layout constants --
    private static final int W = 240;
    // Panel height includes space for 5 visible Accepts rows.
    private static final int H = 246;

    // Title bar header.
    private static final int TITLE_H = 14;

    // Tab strip.
    private static final int TAB_Y = 18;
    private static final int TAB_H = 14;
    private static final int TAB_COUNT = 4;
    private static final int TAB_W = 54;
    private static final int TAB_GAP = 2;
    private static final int TABS_START_X = 8;

    // Tab content area
    private static final int CONTENT_TOP = 36;
    private static final int CONTENT_BOTTOM = 148;
    private static final int CONTENT_X = 8;
    private static final int CONTENT_RIGHT = W - 8;

    // Player inventory position.
    private static final int INV_LABEL_Y = 150;
    private static final int INV_X = 39;
    private static final int INV_Y = 162;
    private static final int HOTBAR_Y = 220;

    private static final int INPUT_SLOTS = 9;

    // Palette
    private static final int PANEL_OUTER = 0xFF2A2A2A;
    private static final int PANEL_BG = 0xFF3D3D3D;
    private static final int TITLE_BG = 0xFF1A0F05;
    private static final int TITLE_ACCENT = 0xFF6B4A1E;
    private static final int CONTENT_BG = 0xFF2F2F2F;
    private static final int SLOT_WELL_DARK = 0xFF111111;
    private static final int SLOT_WELL_LIGHT = 0xFF373737;
    private static final int TEXT_PRIMARY = 0xFFFFE9C7;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int TEXT_LABEL = 0xFFFFD700;

    private Tab currentTab = Tab.DEPOSIT;

    private EditBox renameInput;
    private Button renameButton;
    private EditBox terminalInput;
    private Button addTerminalButton;
    private EditBox searchInput;

    private int namesScroll = 0;
    private int acceptsScroll = 0;

    private boolean draggingScrollbar = false;
    private Tab dragTab = null;

    private List<AcceptedItem> resolvedAcceptsCache;
    private String lastSearchQuery = "";
    private List<AcceptedItem> filteredAccepts;

    private record AcceptedItem(ItemStack stack, int inputAmount, int payoutAmount) {}

    public DepotLecternScreen(DepotLecternMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = W;
        this.imageHeight = H;
        this.titleLabelX = CONTENT_X;
        this.titleLabelY = -100;
        this.inventoryLabelX = INV_X;
        this.inventoryLabelY = INV_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();

        renameInput = new EditBox(font, leftPos + CONTENT_X, topPos + CONTENT_TOP + 28,
                160, 14, Component.literal("Village name"));
        renameInput.setMaxLength(32);
        renameInput.setBordered(true);
        renameInput.setValue(menu.getCustomName() == null ? "" : menu.getCustomName());
        addRenderableWidget(renameInput);

        renameButton = Button.builder(Component.literal("Save"), btn -> trySetVillageName())
                .bounds(leftPos + CONTENT_X + 164, topPos + CONTENT_TOP + 28, 32, 14)
                .build();
        addRenderableWidget(renameButton);

        terminalInput = new EditBox(font, leftPos + CONTENT_X, topPos + CONTENT_TOP + 14,
                160, 14, Component.literal("Terminal name"));
        terminalInput.setMaxLength(32);
        terminalInput.setBordered(true);
        addRenderableWidget(terminalInput);

        addTerminalButton = Button.builder(Component.literal("Add"), btn -> tryAddTerminal())
                .bounds(leftPos + CONTENT_X + 164, topPos + CONTENT_TOP + 14, 32, 14)
                .build();
        addRenderableWidget(addTerminalButton);

        searchInput = new EditBox(font, leftPos + CONTENT_X, topPos + CONTENT_TOP,
                W - 16, 14, Component.literal("Search…"));
        searchInput.setMaxLength(32);
        searchInput.setBordered(true);
        searchInput.setHint(Component.literal("Search…"));
        searchInput.setResponder(this::onSearchChanged);
        addRenderableWidget(searchInput);

        cacheResolvedItems();
        applyTabVisibility();
    }

    private void cacheResolvedItems() {
        resolvedAcceptsCache = new ArrayList<>();
        List<String> acceptIds = menu.getAcceptedItems();
        List<Integer> inputAmounts = menu.getAcceptedInputAmounts();
        List<Integer> payouts = menu.getAcceptedPayouts();
        for (int i = 0; i < acceptIds.size(); i++) {
            ItemStack stack = idToStack(acceptIds.get(i));
            if (stack.isEmpty()) continue;
            int input = i < inputAmounts.size() ? inputAmounts.get(i) : 1;
            int payout = i < payouts.size() ? payouts.get(i) : 1;
            resolvedAcceptsCache.add(new AcceptedItem(stack, input, payout));
        }
        filteredAccepts = new ArrayList<>(resolvedAcceptsCache);
    }

    private static ItemStack idToStack(String id) {
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item == Items.AIR) return ItemStack.EMPTY;
            return new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private void onSearchChanged(String query) {
        lastSearchQuery = query == null ? "" : query.trim().toLowerCase();
        if (lastSearchQuery.isEmpty()) {
            filteredAccepts = new ArrayList<>(resolvedAcceptsCache);
        } else {
            filteredAccepts = new ArrayList<>();
            for (AcceptedItem a : resolvedAcceptsCache) {
                String displayName = a.stack().getHoverName().getString().toLowerCase();
                if (displayName.contains(lastSearchQuery)) filteredAccepts.add(a);
            }
        }
        acceptsScroll = 0;
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        applyTabVisibility();
    }

    private void applyTabVisibility() {
        boolean info = currentTab == Tab.INFO;
        boolean terminals = currentTab == Tab.TERMINALS;
        boolean accepts = currentTab == Tab.ACCEPTS;
        boolean deposit = currentTab == Tab.DEPOSIT;
        renameInput.setVisible(info);
        renameButton.visible = info;
        renameButton.active = info;
        terminalInput.setVisible(terminals);
        addTerminalButton.visible = terminals;
        addTerminalButton.active = terminals;
        searchInput.setVisible(accepts);
        if (!info) renameInput.setFocused(false);
        if (!terminals) terminalInput.setFocused(false);
        if (!accepts) searchInput.setFocused(false);

        // Toggle deposit slot visibility for non-Deposit tabs.
        for (Slot s : menu.slots) {
            if (s instanceof dev.jwalkin.create_commerce.menu.GatedSlot gs) {
                gs.active = deposit;
            }
        }
    }

    private void tryAddTerminal() {
        String name = terminalInput.getValue().trim();
        if (name.isEmpty() || name.length() > 32) return;
        if (menu.getTerminalNames().contains(name)) {
            terminalInput.setValue("");
            return;
        }
        menu.getTerminalNames().add(name);
        PacketDistributor.sendToServer(new AddTerminalNamePacket(menu.getBlockPos(), name));
        terminalInput.setValue("");
    }

    private void tryRemoveTerminal(String name) {
        menu.getTerminalNames().remove(name);
        PacketDistributor.sendToServer(new RemoveTerminalNamePacket(menu.getBlockPos(), name));
    }

    private void trySetVillageName() {
        String name = renameInput.getValue().trim();
        if (name.length() > 32) return;
        menu.setCustomNameLocal(name);
        PacketDistributor.sendToServer(new SetVillageNamePacket(menu.getBlockPos(), name));
    }

    // Background
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // Full outer panel + inner background fill.
        g.fill(x, y, x + W, y + H, PANEL_OUTER);
        g.fill(x + 1, y + 1, x + W - 1, y + H - 1, PANEL_BG);

        // Title bar
        g.fill(x + 1, y + 1, x + W - 1, y + TITLE_H + 1, TITLE_BG);
        g.fill(x + 1, y + TITLE_H + 1, x + W - 1, y + TITLE_H + 2, TITLE_ACCENT);

        // Tabs
        for (int i = 0; i < TAB_COUNT; i++) {
            int tx = x + TABS_START_X + i * (TAB_W + TAB_GAP);
            int ty = y + TAB_Y;
            boolean active = i == currentTab.ordinal();
            // Outer 1px border
            g.fill(tx, ty, tx + TAB_W, ty + TAB_H, PANEL_OUTER);
            // Inner fill
            int fill = active ? 0xFF6B4A1E : 0xFF3A2918;
            g.fill(tx + 1, ty + 1, tx + TAB_W - 1, ty + TAB_H - 1, fill);
            // Top highlight
            int hl = active ? 0xFFAA7A2E : 0xFF553818;
            g.fill(tx + 1, ty + 1, tx + TAB_W - 1, ty + 2, hl);
        }

        // Content panel.
        g.fill(x + 4, y + CONTENT_TOP - 2, x + W - 4, y + CONTENT_BOTTOM, CONTENT_BG);
        g.fill(x + 1, y + CONTENT_BOTTOM, x + W - 1, y + CONTENT_BOTTOM + 1, TITLE_ACCENT);

        // Deposit slot wells
        if (currentTab == Tab.DEPOSIT) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    int sx = x + 93 + col * 18 - 1;
                    int sy = y + 56 + row * 18 - 1;
                    drawCreateSlot(g, sx, sy);
                }
            }
        }

        // Inventory backdrop
        int invX = x + INV_X;
        int invY = y + INV_Y;
        int hotbarY = y + HOTBAR_Y;
        g.fill(invX - 2, invY - 2, invX + 9 * 18, invY + 3 * 18, CONTENT_BG);
        g.fill(invX - 2, hotbarY - 2, invX + 9 * 18, hotbarY + 18, CONTENT_BG);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawCreateSlot(g, invX + col * 18 - 1, invY + row * 18 - 1);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawCreateSlot(g, invX + col * 18 - 1, hotbarY - 1);
        }
    }

    private void drawCreateSlot(GuiGraphics g, int sx, int sy) {
        g.fill(sx, sy, sx + 18, sy + 18, SLOT_WELL_DARK);
        g.fill(sx + 1, sy + 1, sx + 18, sy + 18, SLOT_WELL_LIGHT);
        g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF222222);
    }

    // Foreground
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        // Title text
        g.drawString(font, "DEPOT LECTERN", leftPos + 6, topPos + 4, TEXT_LABEL, false);
        renderCloseButton(g, mouseX, mouseY);
        renderTabContent(g, mouseX, mouseY);
        renderTooltip(g, mouseX, mouseY);
    }

    // Close button
    private static final int CLOSE_BTN_SIZE = 10;
    private static final int CLOSE_BTN_X_OFFSET = W - CLOSE_BTN_SIZE - 3;
    private static final int CLOSE_BTN_Y_OFFSET = 2;

    private void renderCloseButton(GuiGraphics g, int mouseX, int mouseY) {
        int bx = leftPos + CLOSE_BTN_X_OFFSET;
        int by = topPos + CLOSE_BTN_Y_OFFSET;
        boolean hovered = mouseX >= bx && mouseX < bx + CLOSE_BTN_SIZE
                && mouseY >= by && mouseY < by + CLOSE_BTN_SIZE;
        int border = hovered ? 0xFFCC4444 : TITLE_ACCENT;
        int fill = hovered ? 0xFF5A2020 : 0xFF2A1505;
        g.fill(bx, by, bx + CLOSE_BTN_SIZE, by + CLOSE_BTN_SIZE, border);
        g.fill(bx + 1, by + 1, bx + CLOSE_BTN_SIZE - 1, by + CLOSE_BTN_SIZE - 1, fill);
        g.drawString(font, "✕", bx + 2, by + 1, hovered ? 0xFFFFFFFF : 0xFFE0B870, false);
    }

    private boolean clickedCloseButton(double mouseX, double mouseY) {
        int bx = leftPos + CLOSE_BTN_X_OFFSET;
        int by = topPos + CLOSE_BTN_Y_OFFSET;
        return mouseX >= bx && mouseX < bx + CLOSE_BTN_SIZE
                && mouseY >= by && mouseY < by + CLOSE_BTN_SIZE;
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < TAB_COUNT; i++) {
            String label = tabLabel(Tab.values()[i]);
            int tx = TABS_START_X + i * (TAB_W + TAB_GAP) + (TAB_W - font.width(label)) / 2;
            int ty = TAB_Y + 4;
            int color = i == currentTab.ordinal() ? TEXT_LABEL : TEXT_DIM;
            g.drawString(font, label, tx, ty, color, false);
        }

        g.drawString(font, playerInventoryTitle, INV_X, INV_LABEL_Y, TEXT_PRIMARY, false);
    }

    private static String tabLabel(Tab tab) {
        return switch (tab) {
            case DEPOSIT -> "Deposit";
            case ACCEPTS -> "Accepts";
            case TERMINALS -> "Terminals";
            case INFO -> "Info";
        };
    }

    private void renderTabContent(GuiGraphics g, int mouseX, int mouseY) {
        switch (currentTab) {
            case DEPOSIT -> renderDepositTab(g);
            case ACCEPTS -> renderAcceptsTab(g, mouseX, mouseY);
            case TERMINALS -> renderTerminalsTab(g, mouseX, mouseY);
            case INFO -> renderInfoTab(g);
        }
    }

    private void renderDepositTab(GuiGraphics g) {
        int x = leftPos;
        int y = topPos;
        String title = "Deposit Items";
        g.drawString(font, title, x + (W - font.width(title)) / 2,
                y + CONTENT_TOP + 6, TEXT_PRIMARY, false);
    }

    private void renderAcceptsTab(GuiGraphics g, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int listTop = y + CONTENT_TOP + 18;
        int listBottom = y + CONTENT_BOTTOM - 4;
        int rowH = 18;
        int rowsVisible = (listBottom - listTop) / rowH;

        if (filteredAccepts.isEmpty()) {
            String msg = lastSearchQuery.isEmpty() ? "" : "(no matches)";
            if (!msg.isEmpty()) {
                g.drawString(font, msg, x + CONTENT_X + 4, listTop, TEXT_DIM, false);
            }
            return;
        }

        ItemStack currency = currencyDisplayStack();
        int total = filteredAccepts.size();
        int maxScroll = Math.max(0, total - rowsVisible);
        if (acceptsScroll > maxScroll) acceptsScroll = maxScroll;

        // Right-align: value → spur icon → arrow.
        int valueRightEdge = x + CONTENT_RIGHT - 8; // 4px gap to scrollbar track
        int currencyIconX = valueRightEdge - 28;     // icon at ~36px from right
        int arrowX = currencyIconX - 10;

        for (int i = 0; i < rowsVisible && (i + acceptsScroll) < total; i++) {
            AcceptedItem entry = filteredAccepts.get(i + acceptsScroll);
            int rowY = listTop + i * rowH;
            int inputX = x + CONTENT_X + 2;
            int nameX = inputX + 20;

            // Input item icon.
            ItemStack inputStack = entry.stack().copy();
            inputStack.setCount(Math.max(1, entry.inputAmount()));
            g.renderItem(inputStack, inputX, rowY);
            g.renderItemDecorations(font, inputStack, inputX, rowY);

            // Strip "Uncraftable " from bare potion names.
            String name = entry.stack().getHoverName().getString().replace("Uncraftable ", "");
            int nameMax = arrowX - 4 - nameX;
            g.drawString(font, trimToWidth(name, Math.max(0, nameMax)), nameX, rowY + 4, TEXT_PRIMARY, false);

            // Arrow separator.
            g.drawString(font, "→", arrowX, rowY + 4, TEXT_DIM, false);

            // Payout: spur icon with count overlay.
            ItemStack payoutDisplay = currency.copy();
            payoutDisplay.setCount(entry.payoutAmount());
            g.renderItem(currency, currencyIconX, rowY);
            g.renderItemDecorations(font, payoutDisplay, currencyIconX, rowY);
        }

        if (total > rowsVisible) {
            renderScrollbar(g, x + CONTENT_RIGHT - 4, listTop, listBottom, total, rowsVisible, acceptsScroll,
                    Tab.ACCEPTS, mouseX, mouseY);
        }
    }

    private ItemStack currencyDisplayStack() {
        try {
            // Numismatics spur if available, else emerald
            ItemStack spur = CoinConverter.getSpurDisplayStack();
            if (!spur.isEmpty()) return spur;
            return new ItemStack(Items.EMERALD);
        } catch (Exception e) {
            return new ItemStack(Items.EMERALD);
        }
    }

    private void renderTerminalsTab(GuiGraphics g, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        g.drawString(font, "Linked terminals", x + CONTENT_X, y + CONTENT_TOP + 2, TEXT_PRIMARY, false);

        var names = menu.getTerminalNames();
        int listTop = y + CONTENT_TOP + 32;
        int listBottom = y + CONTENT_BOTTOM - 4;
        int rowH = 12;
        int rowsVisible = (listBottom - listTop) / rowH;

        if (names.isEmpty()) {
            return;
        }

        int total = names.size();
        int maxScroll = Math.max(0, total - rowsVisible);
        if (namesScroll > maxScroll) namesScroll = maxScroll;

        for (int i = 0; i < rowsVisible && (i + namesScroll) < total; i++) {
            String name = names.get(i + namesScroll);
            int rowY = listTop + i * rowH;
            String shown = trimToWidth(name, W - 60);
            g.drawString(font, shown, x + CONTENT_X + 4, rowY + 2, TEXT_PRIMARY, false);

            // × button past the scrollbar.
            int xBtnX = x + CONTENT_RIGHT - 22;
            int xBtnY = rowY + 1;
            int color = (mouseX >= xBtnX && mouseX < xBtnX + 10 && mouseY >= xBtnY && mouseY < xBtnY + 10)
                    ? 0xFFCC4444 : 0xFF888888;
            g.fill(xBtnX, xBtnY, xBtnX + 10, xBtnY + 10, color);
            g.drawString(font, "x", xBtnX + 3, xBtnY + 1, 0xFFFFFFFF, false);
        }

        if (total > rowsVisible) {
            renderScrollbar(g, x + CONTENT_RIGHT - 4, listTop, listBottom, total, rowsVisible, namesScroll,
                    Tab.TERMINALS, mouseX, mouseY);
        }
    }

    private void renderInfoTab(GuiGraphics g) {
        int x = leftPos;
        int y = topPos;
        String custom = menu.getCustomName();
        String profile = menu.getProfileDisplay();
        String header = (custom == null || custom.isBlank()) ? "(unnamed)" : custom;
        g.drawString(font, "Village name:", x + CONTENT_X, y + CONTENT_TOP + 2, TEXT_PRIMARY, false);
        g.drawString(font, trimToWidth(header, W - 16), x + CONTENT_X, y + CONTENT_TOP + 14, TEXT_LABEL, false);
        g.drawString(font, "Type: " + (profile == null ? "" : profile),
                x + CONTENT_X, y + CONTENT_TOP + 50, TEXT_PRIMARY, false);
    }

    // Scrollbar
    private void renderScrollbar(GuiGraphics g, int trackX, int trackTop, int trackBot,
                                  int total, int visible, int scroll,
                                  Tab tab, int mouseX, int mouseY) {
        int trackH = trackBot - trackTop;
        g.fill(trackX, trackTop, trackX + 5, trackBot, 0xFF1A1A1A);
        if (total <= visible) return;
        int thumbH = Math.max(6, Math.min(trackH, visible * trackH / total));
        int travel = trackH - thumbH;
        int maxScroll = total - visible;
        int thumbY1 = trackTop + (int) ((long) scroll * travel / maxScroll);
        int thumbY2 = thumbY1 + thumbH;
        if (scroll >= maxScroll) thumbY2 = trackBot; // hard-snap on the final tick
        boolean hovered = mouseX >= trackX && mouseX < trackX + 5 && mouseY >= thumbY1 && mouseY < thumbY2;
        int thumbColor = (draggingScrollbar && dragTab == tab) ? 0xFFFFE9C7
                : hovered ? 0xFFE0B870 : 0xFFB48C42;
        g.fill(trackX, thumbY1, trackX + 5, thumbY2, thumbColor);
    }

    // Slot rendering
    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        if (slot.index < INPUT_SLOTS && currentTab != Tab.DEPOSIT) return;
        super.renderSlot(g, slot);
    }

    private boolean isDepositSlot(Slot slot) {
        return slot.index < INPUT_SLOTS;
    }

    private boolean isOverDepositSlot(double mouseX, double mouseY) {
        for (Slot s : menu.slots) {
            if (!isDepositSlot(s)) continue;
            double sx = leftPos + s.x;
            double sy = topPos + s.y;
            if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) return true;
        }
        return false;
    }

    // Input
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && clickedCloseButton(mouseX, mouseY)) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.closeContainer();
            }
            return true;
        }
        for (int i = 0; i < TAB_COUNT; i++) {
            int tx = leftPos + TABS_START_X + i * (TAB_W + TAB_GAP);
            int ty = topPos + TAB_Y;
            if (mouseX >= tx && mouseX < tx + TAB_W && mouseY >= ty && mouseY < ty + TAB_H) {
                switchTab(Tab.values()[i]);
                return true;
            }
        }

        if (currentTab != Tab.DEPOSIT && isOverDepositSlot(mouseX, mouseY)) {
            return true;
        }

        // Scrollbar drag start
        if (button == 0) {
            int trackX = leftPos + CONTENT_RIGHT - 4;
            int trackTop, trackBot;
            if (currentTab == Tab.ACCEPTS) {
                trackTop = topPos + CONTENT_TOP + 18;
                trackBot = topPos + CONTENT_BOTTOM - 4;
                if (mouseX >= trackX && mouseX < trackX + 5 && mouseY >= trackTop && mouseY < trackBot) {
                    draggingScrollbar = true;
                    dragTab = Tab.ACCEPTS;
                    updateDragScroll(mouseY, trackTop, trackBot);
                    return true;
                }
            } else if (currentTab == Tab.TERMINALS) {
                trackTop = topPos + CONTENT_TOP + 32;
                trackBot = topPos + CONTENT_BOTTOM - 4;
                if (mouseX >= trackX && mouseX < trackX + 5 && mouseY >= trackTop && mouseY < trackBot) {
                    draggingScrollbar = true;
                    dragTab = Tab.TERMINALS;
                    updateDragScroll(mouseY, trackTop, trackBot);
                    return true;
                }
            }
        }

        if (currentTab == Tab.TERMINALS && button == 0) {
            var names = menu.getTerminalNames();
            int listTop = topPos + CONTENT_TOP + 32;
            int listBottom = topPos + CONTENT_BOTTOM - 4;
            int rowH = 12;
            int rowsVisible = (listBottom - listTop) / rowH;
            for (int i = 0; i < rowsVisible && (i + namesScroll) < names.size(); i++) {
                int rowY = listTop + i * rowH;
                int xBtnX = leftPos + CONTENT_RIGHT - 22;
                int xBtnY = rowY + 1;
                if (mouseX >= xBtnX && mouseX < xBtnX + 10 && mouseY >= xBtnY && mouseY < xBtnY + 10) {
                    String name = names.get(i + namesScroll);
                    tryRemoveTerminal(name);
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0 && dragTab != null) {
            int trackTop, trackBot;
            if (dragTab == Tab.ACCEPTS) {
                trackTop = topPos + CONTENT_TOP + 18;
                trackBot = topPos + CONTENT_BOTTOM - 4;
            } else {
                trackTop = topPos + CONTENT_TOP + 32;
                trackBot = topPos + CONTENT_BOTTOM - 4;
            }
            updateDragScroll(mouseY, trackTop, trackBot);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            dragTab = null;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateDragScroll(double mouseY, int trackTop, int trackBot) {
        int total = (dragTab == Tab.ACCEPTS) ? filteredAccepts.size() : menu.getTerminalNames().size();
        // Row heights must match the renderer (Accepts=18, Terminals=12).
        int rowH = (dragTab == Tab.ACCEPTS) ? 18 : 12;
        int rowsVisible = (trackBot - trackTop) / rowH;
        int maxScroll = Math.max(0, total - rowsVisible);
        if (maxScroll == 0) return;
        double pct = (mouseY - trackTop) / (double) (trackBot - trackTop);
        int scroll = (int) Math.round(pct * maxScroll);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        if (dragTab == Tab.ACCEPTS) acceptsScroll = scroll;
        else namesScroll = scroll;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (currentTab == Tab.ACCEPTS) {
            int listTop = topPos + CONTENT_TOP + 18;
            int listBottom = topPos + CONTENT_BOTTOM - 4;
            if (mouseX >= leftPos + CONTENT_X && mouseX < leftPos + CONTENT_RIGHT
                    && mouseY >= listTop && mouseY < listBottom) {
                int rowsVisible = (listBottom - listTop) / 18;
                int max = Math.max(0, filteredAccepts.size() - rowsVisible);
                acceptsScroll = Math.max(0, Math.min(max, acceptsScroll - (int) Math.signum(scrollY)));
                return true;
            }
        } else if (currentTab == Tab.TERMINALS) {
            int listTop = topPos + CONTENT_TOP + 32;
            int listBottom = topPos + CONTENT_BOTTOM - 4;
            if (mouseX >= leftPos + CONTENT_X && mouseX < leftPos + CONTENT_RIGHT
                    && mouseY >= listTop && mouseY < listBottom) {
                int rowsVisible = (listBottom - listTop) / 12;
                int max = Math.max(0, menu.getTerminalNames().size() - rowsVisible);
                namesScroll = Math.max(0, Math.min(max, namesScroll - (int) Math.signum(scrollY)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * Suppresses inventory close-key while an EditBox is focused.
     * ESC still closes normally.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        EditBox focused = focusedEditBox();
        if (focused != null) {
            if (keyCode == 256) { // ESC
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter
                if (focused == renameInput) trySetVillageName();
                else if (focused == terminalInput) tryAddTerminal();
                return true;
            }
            focused.keyPressed(keyCode, scanCode, modifiers);
            return true; // disable inventory key (E) closing UI while typing
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private EditBox focusedEditBox() {
        if (renameInput != null && renameInput.isVisible() && renameInput.isFocused()) return renameInput;
        if (terminalInput != null && terminalInput.isVisible() && terminalInput.isFocused()) return terminalInput;
        if (searchInput != null && searchInput.isVisible() && searchInput.isFocused()) return searchInput;
        return null;
    }

    private String trimToWidth(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "…";
        while (s.length() > 1 && font.width(s + ellipsis) > maxWidth) s = s.substring(0, s.length() - 1);
        return s + ellipsis;
    }
}
