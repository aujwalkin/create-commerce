package dev.jwalkin.create_commerce.screen;

import dev.jwalkin.create_commerce.config.CoinConverter;
import dev.jwalkin.create_commerce.data.VillageSnapshot;
import dev.jwalkin.create_commerce.menu.TradeTerminalMenu;
import dev.jwalkin.create_commerce.network.RefreshTerminalPacket;
import dev.jwalkin.create_commerce.network.SetTerminalNamePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TradeTerminalScreen extends AbstractContainerScreen<TradeTerminalMenu> {

    private enum Mode { LIST, DETAIL }
    private enum DetailTab { ACCEPTS, TERMINALS }

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 206;
    private static final int TITLE_H = 14;
    private static final int NAME_ROW_TOP = TITLE_H + 6;
    private static final int NAME_ROW_H = 18;
    private static final int CONTENT_TOP = NAME_ROW_TOP + NAME_ROW_H + 6;
    private static final int CONTENT_BOTTOM = PANEL_H - 22;
    private static final int CONTENT_X = 8;
    private static final int CONTENT_RIGHT = PANEL_W - 8;
    private static final int ENTRY_H = 34;

    // Cap-bar layout.
    private static final int BAR_X_OFFSET = 110;
    private static final int BAR_W = 100;
    private static final int BAR_H = 6;

    // Detail view sub-panel split. Accepts gets the wider left side (so its
    // payout column has room for the spur icon AND the scrollbar without
    // overlap); Linked-Terminals takes the narrower right side.
    private static final int DETAIL_SPLIT_X = 156;

    // Close button (top-right).
    private static final int CLOSE_BTN_SIZE = 10;
    private static final int CLOSE_BTN_X = PANEL_W - CLOSE_BTN_SIZE - 3;
    private static final int CLOSE_BTN_Y = 2;

    // Refresh button, left of close button.
    private static final int REFRESH_BTN_SIZE = 10;
    private static final int REFRESH_BTN_X = CLOSE_BTN_X - REFRESH_BTN_SIZE - 2;
    private static final int REFRESH_BTN_Y = 2;

    // Palette
    private static final int PANEL_OUTER = 0xFF2A2A2A;
    private static final int PANEL_BG = 0xFF3D3D3D;
    private static final int TITLE_BG = 0xFF1A0F05;
    private static final int TITLE_ACCENT = 0xFF6B4A1E;
    private static final int CONTENT_BG = 0xFF2F2F2F;
    private static final int ROW_HOVER = 0xFF3A3A3A;
    private static final int ROW_DIVIDER = 0xFF555555;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int TEXT_MUTED = 0xFF999999;
    private static final int TEXT_LABEL = 0xFFFFD700;

    private Mode mode = Mode.LIST;
    private DetailTab detailTab = DetailTab.ACCEPTS;
    private UUID detailVillageId = null;

    private int listScroll = 0;
    private int acceptsScroll = 0;
    private int terminalsScroll = 0;
    private boolean draggingScrollbar = false;
    private DragTarget dragTarget = null;

    private enum DragTarget { LIST, DETAIL_ACCEPTS, DETAIL_TERMINALS }

    private EditBox nameInput;
    private Button confirmButton;
    private Button backButton;

    // Client-side cooldown tracking, synced from server.
    private static final long REFRESH_COOLDOWN_TICKS = 1200L; // 60s
    /** The game tick when the cooldown will expire. Persisted via menu sync. */
    private long cooldownExpiresAt = Long.MIN_VALUE / 2L;

    public TradeTerminalScreen(TradeTerminalMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
    }

    @Override
    protected void init() {
        super.init();

        // Initialize cooldown from server-synced menu data.
        // Ensure the timer is accurate after close/reopen.
        long remainingFromServer = menu.getCooldownRemainingTicks();
        if (remainingFromServer > 0) {
            cooldownExpiresAt = clientGameTime() + remainingFromServer;
        } else {
            cooldownExpiresAt = Long.MIN_VALUE / 2L;
        }

        // Name input fills the dark row up to the Set button.
        nameInput = new EditBox(font, leftPos + 8, topPos + NAME_ROW_TOP, PANEL_W - 44, 14,
                Component.literal("Terminal name"));
        nameInput.setMaxLength(32);
        nameInput.setBordered(true);
        nameInput.setValue(menu.getTerminalName());
        addRenderableWidget(nameInput);

        confirmButton = Button.builder(Component.literal("Set"), btn -> tryConfirm())
                .bounds(leftPos + PANEL_W - 32, topPos + NAME_ROW_TOP, 28, 14)
                .build();
        addRenderableWidget(confirmButton);

        // Back button
        backButton = Button.builder(Component.literal("← Back"), btn -> exitDetail())
                .bounds(leftPos + 8, topPos + NAME_ROW_TOP - 1, 50, 14)
                .build();
        addRenderableWidget(backButton);

        applyModeVisibility();
    }

    private void applyModeVisibility() {
        boolean list = mode == Mode.LIST;
        nameInput.setVisible(list);
        confirmButton.visible = list;
        confirmButton.active = list;
        backButton.visible = !list;
        backButton.active = !list;
        if (!list) nameInput.setFocused(false);
    }

    private void enterDetail(UUID villageId) {
        detailVillageId = villageId;
        mode = Mode.DETAIL;
        acceptsScroll = 0;
        terminalsScroll = 0;
        applyModeVisibility();
    }

    private void exitDetail() {
        detailVillageId = null;
        mode = Mode.LIST;
        applyModeVisibility();
    }

    private VillageSnapshot currentDetailSnapshot() {
        if (detailVillageId == null) return null;
        for (VillageSnapshot s : menu.getSnapshots()) {
            if (detailVillageId.equals(s.villageId())) return s;
        }
        return null;
    }

    private void tryConfirm() {
        String name = nameInput.getValue().trim();
        if (name.length() > 32) return;
        menu.setTerminalNameLocal(name);
        PacketDistributor.sendToServer(new SetTerminalNamePacket(menu.getBlockPos(), name));
        // Reopen to refresh village list.
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        g.fill(x, y, x + PANEL_W, y + PANEL_H, PANEL_OUTER);
        g.fill(x + 1, y + 1, x + PANEL_W - 1, y + PANEL_H - 1, PANEL_BG);

        // Title bar
        g.fill(x + 1, y + 1, x + PANEL_W - 1, y + TITLE_H + 1, TITLE_BG);
        g.fill(x + 1, y + TITLE_H + 1, x + PANEL_W - 1, y + TITLE_H + 2, TITLE_ACCENT);

        // Name/back-button row.
        g.fill(x + 4, y + NAME_ROW_TOP - 2, x + PANEL_W - 2, y + NAME_ROW_TOP + NAME_ROW_H - 4, CONTENT_BG);

        // Content area background
        g.fill(x + 4, y + CONTENT_TOP, x + PANEL_W - 4, y + CONTENT_BOTTOM, CONTENT_BG);

        // Footer divider
        g.fill(x + 1, y + CONTENT_BOTTOM, x + PANEL_W - 1, y + CONTENT_BOTTOM + 1, ROW_DIVIDER);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int x = leftPos;
        int y = topPos;

        g.drawString(font, "TRADE TERMINAL", x + 6, y + 4, TEXT_LABEL, false);

        if (mode == Mode.DETAIL) {
            VillageSnapshot navSnap = currentDetailSnapshot();
            if (navSnap != null) {
                String navTitle = (navSnap.customName() != null && !navSnap.customName().isBlank())
                        ? navSnap.customName()
                        : navSnap.profileDisplayName();
                // Inline title next to the [← Back] button.
                int navTitleX = x + 64;
                int navMax = PANEL_W - 8 - 64;
                g.drawString(font, trimToWidth(navTitle, navMax), navTitleX, y + NAME_ROW_TOP + 2, TEXT_LABEL, false);
            }
        }

        renderCloseButton(g, x, y, mouseX, mouseY);
        renderRefreshButton(g, x, y, mouseX, mouseY);

        if (mode == Mode.LIST) {
            List<VillageSnapshot> snapshots = menu.getSnapshots();
            if (snapshots.isEmpty()) {
                renderEmptyState(g, x, y);
                // Hint in same position as populated state.
                String hint = "Match a Depot Lectern's terminal name.";
                g.drawString(font, hint, x + (PANEL_W - font.width(hint)) / 2,
                        y + CONTENT_BOTTOM + 7, 0x999999, false);
            } else {
                renderVillageList(g, x, y, snapshots, mouseX, mouseY);
                // Footer hint.
                String hint = "Select to view details";
                g.drawString(font, hint, x + (PANEL_W - font.width(hint)) / 2,
                        y + CONTENT_BOTTOM + 7, 0x999999, false);
            }
        } else {
            VillageSnapshot snap = currentDetailSnapshot();
            if (snap == null) {
                exitDetail();
                return;
            }
            renderDetail(g, x, y, snap, mouseX, mouseY);
            // No footer hint in detail view.
        }

        // Refresh tooltip.
        if (clickedRefreshButton(mouseX, mouseY)) {
            String cdLabel = refreshCooldownLabel();
            String tip = cdLabel != null ? cdLabel : "Refresh";
            setTooltipForNextRenderPass(Component.literal(tip));
        }

        renderTooltip(g, mouseX, mouseY);
    }

    private void renderCloseButton(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int bx = x + CLOSE_BTN_X;
        int by = y + CLOSE_BTN_Y;
        boolean hovered = mouseX >= bx && mouseX < bx + CLOSE_BTN_SIZE
                && mouseY >= by && mouseY < by + CLOSE_BTN_SIZE;
        int border = hovered ? 0xFFCC4444 : TITLE_ACCENT;
        int fill = hovered ? 0xFF5A2020 : 0xFF2A1505;
        g.fill(bx, by, bx + CLOSE_BTN_SIZE, by + CLOSE_BTN_SIZE, border);
        g.fill(bx + 1, by + 1, bx + CLOSE_BTN_SIZE - 1, by + CLOSE_BTN_SIZE - 1, fill);
        g.drawString(font, "✕", bx + 2, by + 1, hovered ? 0xFFFFFFFF : 0xFFE0B870, false);
    }

    private boolean clickedCloseButton(double mouseX, double mouseY) {
        int bx = leftPos + CLOSE_BTN_X;
        int by = topPos + CLOSE_BTN_Y;
        return mouseX >= bx && mouseX < bx + CLOSE_BTN_SIZE
                && mouseY >= by && mouseY < by + CLOSE_BTN_SIZE;
    }

    private void renderRefreshButton(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int bx = x + REFRESH_BTN_X;
        int by = y + REFRESH_BTN_Y;
        boolean hovered = mouseX >= bx && mouseX < bx + REFRESH_BTN_SIZE
                && mouseY >= by && mouseY < by + REFRESH_BTN_SIZE;
        boolean cooling = refreshOnCooldown();
        // Cooldown: muted greys + dim glyph.
        int border = cooling ? 0xFF4A4036 : (hovered ? 0xFFE0B870 : TITLE_ACCENT);
        int fill = cooling ? 0xFF1F1813 : (hovered ? 0xFF4A3210 : 0xFF2A1505);
        int glyph = cooling ? 0xFF7A6A50 : (hovered ? 0xFFFFFFFF : 0xFFE0B870);
        g.fill(bx, by, bx + REFRESH_BTN_SIZE, by + REFRESH_BTN_SIZE, border);
        g.fill(bx + 1, by + 1, bx + REFRESH_BTN_SIZE - 1, by + REFRESH_BTN_SIZE - 1, fill);
        g.drawString(font, "↻", bx + 2, by + 1, glyph, false);
    }

    private boolean clickedRefreshButton(double mouseX, double mouseY) {
        int bx = leftPos + REFRESH_BTN_X;
        int by = topPos + REFRESH_BTN_Y;
        return mouseX >= bx && mouseX < bx + REFRESH_BTN_SIZE
                && mouseY >= by && mouseY < by + REFRESH_BTN_SIZE;
    }

    private long clientGameTime() {
        return (this.minecraft != null && this.minecraft.level != null)
                ? this.minecraft.level.getGameTime()
                : 0L;
    }

    private boolean refreshOnCooldown() {
        return clientGameTime() < cooldownExpiresAt;
    }

    /** Cooldown tooltip label */
    private String refreshCooldownLabel() {
        long remaining = cooldownExpiresAt - clientGameTime();
        if (remaining <= 0) return null;
        long seconds = (remaining + 19) / 20;
        return String.format("Refresh (%02d)", seconds);
    }

    /**
     * Called from UpdateTerminalSnapshotsPacket handler to sync the cooldown
     * state from the server. Keeps UI timer accurate across
     * close/reopen, terminal switches, and same-name terminal sharing.
     */
    public void syncCooldownFromServer(long remainingTicks) {
        if (remainingTicks > 0) {
            cooldownExpiresAt = clientGameTime() + remainingTicks;
        } else {
            // A successful refresh: start full 60s cooldown locally.
            cooldownExpiresAt = clientGameTime() + REFRESH_COOLDOWN_TICKS;
        }
    }

    private void triggerRefresh() {
        if (refreshOnCooldown()) return;
        // Optimistically start cooldown locally, server response will correct it.
        cooldownExpiresAt = clientGameTime() + REFRESH_COOLDOWN_TICKS;
        UUID target = (mode == Mode.DETAIL) ? detailVillageId : null;
        PacketDistributor.sendToServer(new RefreshTerminalPacket(menu.getBlockPos(), target));
    }

    // List view

    private void renderEmptyState(GuiGraphics g, int x, int y) {
        int midY = y + CONTENT_TOP + (CONTENT_BOTTOM - CONTENT_TOP) / 2 - 4;
        if (menu.getTerminalName() == null || menu.getTerminalName().isBlank()) {
            g.drawCenteredString(font, "No terminal name set.", x + PANEL_W / 2, midY, TEXT_DIM);
        } else {
            g.drawCenteredString(font, "No villages match \"" + trimToWidth(menu.getTerminalName(), 120) + "\".",
                    x + PANEL_W / 2, midY, TEXT_DIM);
            g.drawCenteredString(font, "Add this name to a Depot Lectern's list.",
                    x + PANEL_W / 2, midY + 12, 0x666666);
        }
    }

    private void renderVillageList(GuiGraphics g, int x, int y, List<VillageSnapshot> snapshots, int mouseX, int mouseY) {
        int listX = x + 8;
        int listY = y + CONTENT_TOP + 4;
        int listH = CONTENT_BOTTOM - CONTENT_TOP - 4;
        int visibleCount = listH / ENTRY_H;

        int maxScroll = Math.max(0, snapshots.size() - visibleCount);
        listScroll = Math.min(listScroll, maxScroll);

        g.enableScissor(x + 4, y + CONTENT_TOP, x + PANEL_W - 4, y + CONTENT_BOTTOM);

        for (int i = 0; i < visibleCount + 1 && (i + listScroll) < snapshots.size(); i++) {
            VillageSnapshot snap = snapshots.get(i + listScroll);
            int entryY = listY + i * ENTRY_H;
            // Hover highlight with 2 px gap between rows.
            int hoverBot = entryY + ENTRY_H - 4;
            boolean rowHover = mouseX >= listX - 2 && mouseX < x + PANEL_W - 10
                    && mouseY >= entryY - 2 && mouseY < hoverBot;
            if (rowHover) {
                g.fill(listX - 2, entryY - 2, x + PANEL_W - 10, hoverBot, ROW_HOVER);
            }
            renderListEntry(g, listX, entryY, snap);
        }

        g.disableScissor();

        if (snapshots.size() > visibleCount) {
            renderScrollbar(g, x + PANEL_W - 7, y + CONTENT_TOP, y + CONTENT_BOTTOM,
                    snapshots.size(), visibleCount, listScroll, DragTarget.LIST, mouseX, mouseY);
        }
    }

    private void renderListEntry(GuiGraphics g, int x, int y, VillageSnapshot snap) {
        String custom = snap.customName();
        String profile = snap.profileDisplayName();
        boolean hasCustom = custom != null && !custom.isBlank();

        String primary = hasCustom ? custom : profile;
        int maxWidth = PANEL_W - 24;
        g.drawString(font, trimToWidth(primary, maxWidth), x, y, TEXT_PRIMARY, false);

        // Row layout: name, optional profile, cap bar(s).
        int barStartY = y + 11;
        if (hasCustom) {
            g.drawString(font, trimToWidth(profile, maxWidth), x, y + 10, TEXT_MUTED, false);
            barStartY = y + 20;
        }

        int barY = barStartY;
        for (Map.Entry<String, Integer> entry : snap.capConsumed().entrySet()) {
            String cat = entry.getKey();
            int consumed = entry.getValue();
            int max = snap.capMax().getOrDefault(cat, 512);

            String label = displayCategory(cat) + ": " + consumed + "/" + max;
            g.drawString(font, label, x, barY, 0xBBBBBB, false);

            int barX = x + BAR_X_OFFSET;
            g.fill(barX, barY + 1, barX + BAR_W, barY + 1 + BAR_H, 0xFF111111);
            float pct = max > 0 ? (float) consumed / max : 1.0f;
            int fillW = (int) (BAR_W * Math.min(pct, 1.0f));
            if (fillW > 0) {
                int color = pct < 0.5f ? 0xFF1FA31F : pct < 0.8f ? 0xFFBFBF00 : 0xFFBF1F1F;
                g.fill(barX, barY + 1, barX + fillW, barY + 1 + BAR_H, color);
            }
            barY += 12;
        }
    }

    // Detail view

    private void renderDetail(GuiGraphics g, int x, int y, VillageSnapshot snap, int mouseX, int mouseY) {
        int contentLeft = x + CONTENT_X;
        int contentRight = x + CONTENT_RIGHT;

        long most = snap.villageId().getMostSignificantBits();
        int bellX = (int) (most >> 32);
        int bellZ = (int) (most & 0xFFFFFFFFL);
        int bellY = (int) snap.villageId().getLeastSignificantBits();

        // Header: profile type + coords. Custom name is in the nav row.
        int headerY = y + CONTENT_TOP + 3;
        String headerLine = snap.profileDisplayName() + " (" + bellX + ", " + bellY + ", " + bellZ + ")";
        g.drawString(font, trimToWidth(headerLine, PANEL_W - 24), contentLeft, headerY, TEXT_LABEL, false);

        // Stats: population + structures.
        int statsY = headerY + 11;
        StringBuilder stats = new StringBuilder();
        stats.append("Population: ").append(snap.villagerCount() < 0 ? "?" : Integer.toString(snap.villagerCount()));
        stats.append("   Structures: ").append(snap.structureCount() < 0 ? "?" : Integer.toString(snap.structureCount()));
        g.drawString(font, stats.toString(), contentLeft, statsY, TEXT_DIM, false);

        // Cap bar(s).
        int barY = statsY + 11;
        for (Map.Entry<String, Integer> entry : snap.capConsumed().entrySet()) {
            String cat = entry.getKey();
            int consumed = entry.getValue();
            int max = snap.capMax().getOrDefault(cat, 512);
            String label = displayCategory(cat) + ": " + consumed + "/" + max;
            g.drawString(font, label, contentLeft, barY, 0xBBBBBB, false);
            int barX = contentLeft + BAR_X_OFFSET;
            g.fill(barX, barY + 1, barX + BAR_W, barY + 1 + BAR_H, 0xFF111111);
            float pct = max > 0 ? (float) consumed / max : 1.0f;
            int fillW = (int) (BAR_W * Math.min(pct, 1.0f));
            if (fillW > 0) {
                int color = pct < 0.5f ? 0xFF1FA31F : pct < 0.8f ? 0xFFBFBF00 : 0xFFBF1F1F;
                g.fill(barX, barY + 1, barX + fillW, barY + 1 + BAR_H, color);
            }
            barY += 12;
        }

        // Sub-tab strip: Accepts | Terminals.
        int subTabY = barY + 2;
        int subTabContentTop = renderSubTabs(g, contentLeft, subTabY, contentRight) + 1;

        // Active sub-tab fills the remaining width AND height.
        if (detailTab == DetailTab.ACCEPTS) {
            renderAcceptsList(g, contentLeft, subTabContentTop + 3, contentRight, snap, mouseX, mouseY);
        } else {
            renderTerminalsList(g, contentLeft, subTabContentTop + 3, contentRight, snap, mouseX, mouseY);
        }
    }

    /** Renders sub-tab strip, returns y below it. */
    private int renderSubTabs(GuiGraphics g, int left, int top, int right) {
        int gap = 4;
        int tabW = (right - left - gap) / 2;
        int tabH = 12;

        DetailTab[] tabs = DetailTab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + i * (tabW + gap);
            boolean active = tabs[i] == detailTab;
            int border = active ? TITLE_ACCENT : 0xFF333333;
            int fill = active ? 0xFF6B4A1E : 0xFF2A2A2A;
            g.fill(tx, top, tx + tabW, top + tabH, border);
            g.fill(tx + 1, top + 1, tx + tabW - 1, top + tabH, fill);
            String label = tabs[i] == DetailTab.ACCEPTS ? "Accepts" : "Linked terminals";
            int labelX = tx + (tabW - font.width(label)) / 2;
            int color = active ? TEXT_LABEL : TEXT_DIM;
            g.drawString(font, label, labelX, top + 2, color, false);
        }
        // Accent underline.
        g.fill(left, top + tabH, right, top + tabH + 1, TITLE_ACCENT);
        return top + tabH;
    }

    private boolean clickedSubTab(double mouseX, double mouseY, int subTabY) {
        int left = leftPos + CONTENT_X;
        int right = leftPos + CONTENT_RIGHT;
        int gap = 4;
        int tabW = (right - left - gap) / 2;
        int tabH = 12;
        DetailTab[] tabs = DetailTab.values();
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + i * (tabW + gap);
            if (mouseX >= tx && mouseX < tx + tabW && mouseY >= subTabY && mouseY < subTabY + tabH) {
                if (detailTab != tabs[i]) {
                    detailTab = tabs[i];
                    acceptsScroll = 0;
                    terminalsScroll = 0;
                }
                return true;
            }
        }
        return false;
    }

    /** Y where the sub-tab strip starts. */
    private int currentSubTabY() {
        VillageSnapshot snap = currentDetailSnapshot();
        if (snap == null) return topPos + CONTENT_TOP;
        // Layout: header (11) → stats (11) → caps (11 each) → 3 px gap.
        int headerY = topPos + CONTENT_TOP + 3;
        int statsY = headerY + 11;
        int barY = statsY + 11;
        barY += 12 * Math.max(1, snap.capConsumed().size());
        return barY + 2;
    }

    /** Y where sub-tab content begins. */
    private int subTabContentTopY() {
        return currentSubTabY() + 12 + 1 + 3; // tabH + accent + gap
    }

    private void renderAcceptsList(GuiGraphics g, int left, int top, int right, VillageSnapshot snap, int mouseX, int mouseY) {
        int listBottom = topPos + CONTENT_BOTTOM - 4;
        // 14 px row pitch for 16-px icons with 2 px gap.
        int rowH = 14;
        int rowsVisible = (listBottom - top) / rowH;
        int total = snap.acceptedItems().size();
        if (total == 0) {
            g.drawString(font, "(nothing — village empty)", left, top, TEXT_MUTED, false);
            return;
        }
        int maxScroll = Math.max(0, total - rowsVisible);
        if (acceptsScroll > maxScroll) acceptsScroll = maxScroll;

        ItemStack currency = CoinConverter.getSpurDisplayStack();
        if (currency.isEmpty()) currency = new ItemStack(Items.EMERALD);
        int valueRightEdge = right - 6;
        int payoutIconX = valueRightEdge - 28;
        // Fixed slot for per-item cap text.
        int capTextW = 52;
        int capTextX = payoutIconX - capTextW - 2;

        for (int i = 0; i < rowsVisible && (i + acceptsScroll) < total; i++) {
            int idx = i + acceptsScroll;
            int rowY = top + i * rowH;
            ItemStack stack = idToStack(snap.acceptedItems().get(idx));
            if (stack.isEmpty()) continue;

            int input = idx < snap.acceptedInputAmounts().size() ? snap.acceptedInputAmounts().get(idx) : 1;
            int payout = idx < snap.acceptedPayouts().size() ? snap.acceptedPayouts().get(idx) : 0;
            int consumed = idx < snap.acceptedConsumed().size() ? snap.acceptedConsumed().get(idx) : 0;
            int capMax = idx < snap.acceptedCapMax().size() ? snap.acceptedCapMax().get(idx) : 0;
            ItemStack inputStack = stack.copy();
            inputStack.setCount(Math.max(1, input));
            g.renderItem(inputStack, left, rowY - 2);
            g.renderItemDecorations(font, inputStack, left, rowY - 2);

            String name = stack.getHoverName().getString().replace("Uncraftable ", "");
            int textX = left + 18;
            int nameMaxW = (capTextX - 4) - textX;
            g.drawString(font, trimToWidth(name, Math.max(0, nameMaxW)), textX, rowY, TEXT_PRIMARY, false);

            // Per-item cap text — color tracks fill level.
            if (capMax > 0) {
                String capLabel = consumed + "/" + capMax;
                float pct = (float) consumed / capMax;
                int capColor = pct >= 1.0f ? 0xFFBF1F1F
                        : pct >= 0.8f ? 0xFFBFBF00
                        : pct >= 0.5f ? 0xFF6FB04F
                        : TEXT_MUTED;
                int capLabelW = font.width(capLabel);
                int drawX = capTextX + (capTextW - capLabelW);
                g.drawString(font, trimToWidth(capLabel, capTextW), drawX, rowY, capColor, false);
            } else {
                String dash = "—";
                int dashW = font.width(dash);
                g.drawString(font, dash, capTextX + (capTextW - dashW), rowY, TEXT_MUTED, false);
            }

            // Payout icon (offset up to align with cap text).
            int payoutIconY = rowY - 4;
            g.renderItem(currency, payoutIconX, payoutIconY);
            ItemStack payoutDisplay = currency.copy();
            payoutDisplay.setCount(payout);
            g.renderItemDecorations(font, payoutDisplay, payoutIconX, payoutIconY);
        }

        if (total > rowsVisible) {
            renderScrollbar(g, right - 4, top, listBottom, total, rowsVisible, acceptsScroll,
                    DragTarget.DETAIL_ACCEPTS, mouseX, mouseY);
        }
    }

    private void renderTerminalsList(GuiGraphics g, int left, int top, int right, VillageSnapshot snap, int mouseX, int mouseY) {
        int listBottom = topPos + CONTENT_BOTTOM - 4;
        int rowH = 11;
        int rowsVisible = (listBottom - top) / rowH;
        int total = snap.terminalNames().size();
        if (total == 0) {
            g.drawString(font, "(This village isn't broadcasting under any name)", left, top, TEXT_MUTED, false);
            return;
        }
        int maxScroll = Math.max(0, total - rowsVisible);
        if (terminalsScroll > maxScroll) terminalsScroll = maxScroll;
        for (int i = 0; i < rowsVisible && (i + terminalsScroll) < total; i++) {
            int rowY = top + i * rowH;
            String name = snap.terminalNames().get(i + terminalsScroll);
            String shown = trimToWidth(name, right - left - 16);
            int color = name.equals(menu.getTerminalName()) ? TEXT_LABEL : TEXT_PRIMARY;
            g.drawString(font, "• " + shown, left, rowY, color, false);
        }
        if (total > rowsVisible) {
            renderScrollbar(g, right - 4, top, listBottom, total, rowsVisible, terminalsScroll,
                    DragTarget.DETAIL_TERMINALS, mouseX, mouseY);
        }
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

    // -- Scrollbar --
    private void renderScrollbar(GuiGraphics g, int trackX, int trackTop, int trackBot,
                                  int total, int visible, int scroll,
                                  DragTarget target, int mouseX, int mouseY) {
        int trackH = trackBot - trackTop;
        g.fill(trackX, trackTop, trackX + 4, trackBot, 0xFF1A1A1A);
        if (total <= visible) return;
        int thumbH = Math.max(6, Math.min(trackH, visible * trackH / total));
        int travel = trackH - thumbH;
        int maxScroll = total - visible;
        int thumbY1 = trackTop + (int) ((long) scroll * travel / maxScroll);
        int thumbY2 = thumbY1 + thumbH;
        if (scroll >= maxScroll) thumbY2 = trackBot;
        boolean hovered = mouseX >= trackX && mouseX < trackX + 4 && mouseY >= thumbY1 && mouseY < thumbY2;
        int thumbColor = (draggingScrollbar && dragTarget == target) ? 0xFFCCCCCC
                : hovered ? 0xFFAAAAAA : 0xFF888888;
        g.fill(trackX, thumbY1, trackX + 4, thumbY2, thumbColor);
    }

    // -- Input --

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int delta = -(int) Math.signum(scrollY);
        if (mode == Mode.LIST) {
            List<VillageSnapshot> snapshots = menu.getSnapshots();
            if (snapshots.isEmpty()) return false;
            int listH = CONTENT_BOTTOM - CONTENT_TOP - 4;
            int visibleCount = listH / ENTRY_H;
            int maxScroll = Math.max(0, snapshots.size() - visibleCount);
            listScroll = Math.max(0, Math.min(maxScroll, listScroll + delta));
            return true;
        } else {
            VillageSnapshot snap = currentDetailSnapshot();
            if (snap == null) return false;
            // Scroll active sub-tab.
            if (detailTab == DetailTab.ACCEPTS) {
                int rowsVisible = detailListRowsVisible(14);
                int max = Math.max(0, snap.acceptedItems().size() - rowsVisible);
                acceptsScroll = Math.max(0, Math.min(max, acceptsScroll + delta));
            } else {
                int rowsVisible = detailListRowsVisible(11);
                int max = Math.max(0, snap.terminalNames().size() - rowsVisible);
                terminalsScroll = Math.max(0, Math.min(max, terminalsScroll + delta));
            }
            return true;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (clickedCloseButton(mouseX, mouseY)) {
                if (this.minecraft != null && this.minecraft.player != null) {
                    this.minecraft.player.closeContainer();
                }
                return true;
            }
            if (clickedRefreshButton(mouseX, mouseY)) {
                triggerRefresh();
                return true;
            }
            // Scrollbar drag start.
            int trackX, trackTop, trackBot;
            DragTarget candidate = null;
            if (mode == Mode.LIST) {
                trackX = leftPos + PANEL_W - 7;
                trackTop = topPos + CONTENT_TOP;
                trackBot = topPos + CONTENT_BOTTOM;
                if (mouseX >= trackX && mouseX < trackX + 4 && mouseY >= trackTop && mouseY < trackBot
                        && menu.getSnapshots().size() > listVisibleRows()) {
                    candidate = DragTarget.LIST;
                }
            } else {
                VillageSnapshot snap = currentDetailSnapshot();
                if (snap != null) {
                    // First, sub-tab strip clicks (Accepts | Terminals).
                    int subTabY = currentSubTabY();
                    if (clickedSubTab(mouseX, mouseY, subTabY)) {
                        return true;
                    }
                    // Single full-width scrollbar on the active sub-tab.
                    int contentTop = subTabContentTopY();
                    int contentBot = topPos + CONTENT_BOTTOM - 4;
                    int trackXDetail = leftPos + CONTENT_RIGHT - 4;
                    int rowH = (detailTab == DetailTab.ACCEPTS) ? 14 : 11;
                    int rowsVisible = detailListRowsVisible(rowH);
                    int total = (detailTab == DetailTab.ACCEPTS)
                            ? snap.acceptedItems().size()
                            : snap.terminalNames().size();
                    if (mouseX >= trackXDetail && mouseX < trackXDetail + 4
                            && mouseY >= contentTop && mouseY < contentBot
                            && total > rowsVisible) {
                        candidate = (detailTab == DetailTab.ACCEPTS)
                                ? DragTarget.DETAIL_ACCEPTS
                                : DragTarget.DETAIL_TERMINALS;
                    }
                }
            }
            if (candidate != null) {
                draggingScrollbar = true;
                dragTarget = candidate;
                int top = (candidate == DragTarget.LIST) ? topPos + CONTENT_TOP : subTabContentTopY();
                int bot = (candidate == DragTarget.LIST) ? topPos + CONTENT_BOTTOM : topPos + CONTENT_BOTTOM - 4;
                updateDragScroll(mouseY, top, bot);
                return true;
            }

            // Row click → detail view.
            if (mode == Mode.LIST) {
                List<VillageSnapshot> snapshots = menu.getSnapshots();
                int listX = leftPos + 8;
                int listY = topPos + CONTENT_TOP + 4;
                int rowsVisible = listVisibleRows();
                int contentBot = topPos + CONTENT_BOTTOM;
                for (int i = 0; i <= rowsVisible && (i + listScroll) < snapshots.size(); i++) {
                    int entryY = listY + i * ENTRY_H;
                    int entryBot = Math.min(entryY + ENTRY_H - 4, contentBot);
                    if (entryY >= contentBot) break;
                    if (mouseX >= listX - 2 && mouseX < leftPos + PANEL_W - 10
                            && mouseY >= entryY - 2 && mouseY < entryBot) {
                        enterDetail(snapshots.get(i + listScroll).villageId());
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0 && dragTarget != null) {
            int top = (dragTarget == DragTarget.LIST) ? topPos + CONTENT_TOP : subTabContentTopY();
            int bot = (dragTarget == DragTarget.LIST) ? topPos + CONTENT_BOTTOM : topPos + CONTENT_BOTTOM - 4;
            updateDragScroll(mouseY, top, bot);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            dragTarget = null;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateDragScroll(double mouseY, int trackTop, int trackBot) {
        if (dragTarget == null) return;
        int total, visible;
        VillageSnapshot snap = currentDetailSnapshot();
        switch (dragTarget) {
            case LIST -> { total = menu.getSnapshots().size(); visible = listVisibleRows(); }
            case DETAIL_ACCEPTS -> { total = snap == null ? 0 : snap.acceptedItems().size(); visible = detailListRowsVisible(14); }
            case DETAIL_TERMINALS -> { total = snap == null ? 0 : snap.terminalNames().size(); visible = detailListRowsVisible(11); }
            default -> { return; }
        }
        int maxScroll = Math.max(0, total - visible);
        if (maxScroll == 0) return;
        double pct = (mouseY - trackTop) / (double) (trackBot - trackTop);
        int scroll = (int) Math.round(pct * maxScroll);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        switch (dragTarget) {
            case LIST -> listScroll = scroll;
            case DETAIL_ACCEPTS -> acceptsScroll = scroll;
            case DETAIL_TERMINALS -> terminalsScroll = scroll;
        }
    }

    private int listVisibleRows() {
        return (CONTENT_BOTTOM - CONTENT_TOP - 4) / ENTRY_H;
    }

    private int detailListRowsVisible(int rowH) {
        return Math.max(0, (topPos + CONTENT_BOTTOM - 4 - subTabContentTopY()) / rowH);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mode == Mode.DETAIL && keyCode == 256) { // ESC tabs out of detail before closing.
            exitDetail();
            return true;
        }
        if (nameInput != null && nameInput.isVisible() && nameInput.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter
                tryConfirm();
                return true;
            }
            return nameInput.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // We draw our own labels in render().
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String displayCategory(String cat) {
        if (cat == null) return "";
        if ("general".equals(cat)) return "Today";
        return capitalize(cat);
    }

    private String trimToWidth(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "…";
        while (s.length() > 1 && font.width(s + ellipsis) > maxWidth) s = s.substring(0, s.length() - 1);
        return s + ellipsis;
    }
}
