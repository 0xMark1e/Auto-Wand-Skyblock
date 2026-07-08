package com.nexora.hp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class NexoraHpConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 22;
    private static final int SECTION_GAP = 14;
    private static final int SECTION_HEADER_HEIGHT = 14;
    private static final int TITLE_HEIGHT = 28;
    private static final int TAB_BAR_HEIGHT = 24;
    // Clearance between the tab bar's own decorations (active-tab underline, divider line) and
    // the first section header -- without this, the header's text landed right on top of them.
    private static final int TAB_CONTENT_GAP = 10;
    private static final int FOOTER_HEIGHT = 22;
    private static final int ACCENT_COLOR = 0xFF5CE6C7;
    private static final int TAB_INACTIVE_COLOR = 0xFF6E6E7A;

    // Row counts per section, used to compute layout height up front instead of hand-tuned offsets.
    private static final int GENERAL_ROWS = 4;
    private static final int PANIC_ROWS = 2;
    private static final int DISPLAY_ROWS = 2;
    private static final int ATTUNEMENT_ROWS = 3;

    private enum Tab {
        HEALING("HEALING"),
        BLAZE_SLAYER("BLAZE SLAYER");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private final Screen parent;

    private final List<SectionHeader> sectionHeaders = new ArrayList<>();
    private Tab currentTab = Tab.HEALING;
    private int panelTop;
    private int panelBottom;
    private int contentTop;
    private int maxContentHeight;
    private int tabBarY;

    public NexoraHpConfigScreen(Screen parent) {
        super(Component.literal("Nexora-Heal"));
        this.parent = parent;
    }

    private record SectionHeader(String text, int y) {
    }

    /** A draggable slider snapped to a fixed step, over a [min, max] range, with a unit suffix. */
    private static final class ValueSlider extends AbstractSliderButton {
        private final String label;
        private final String suffix;
        private final int min;
        private final int max;
        private final int step;
        private final IntConsumer onChange;

        ValueSlider(int x, int y, int width, int height, String label, String suffix, int min, int max, int step,
                int initialValue, IntConsumer onChange) {
            super(x, y, width, height, Component.empty(), fraction(initialValue, min, max));
            this.label = label;
            this.suffix = suffix;
            this.min = min;
            this.max = max;
            this.step = step;
            this.onChange = onChange;
            this.updateMessage();
        }

        private static double fraction(int value, int min, int max) {
            return (value - min) / (double) (max - min);
        }

        private int currentValue() {
            int raw = min + (int) Math.round(this.value * (max - min));
            return Math.round(raw / (float) step) * step;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(label + ": " + currentValue() + suffix));
        }

        @Override
        protected void applyValue() {
            onChange.accept(currentValue());
        }
    }

    private static int sectionHeight(int rows) {
        return SECTION_HEADER_HEIGHT + rows * SPACING;
    }

    private static int healingContentHeight() {
        return sectionHeight(GENERAL_ROWS) + SECTION_GAP + sectionHeight(PANIC_ROWS) + SECTION_GAP
                + sectionHeight(DISPLAY_ROWS);
    }

    private static int blazeSlayerContentHeight() {
        return sectionHeight(ATTUNEMENT_ROWS);
    }

    @Override
    protected void init() {
        this.sectionHeaders.clear();
        this.clearWidgets();

        this.maxContentHeight = Math.max(healingContentHeight(), blazeSlayerContentHeight());
        int panelHeight = TITLE_HEIGHT + TAB_BAR_HEIGHT + TAB_CONTENT_GAP + this.maxContentHeight + SPACING + 12
                + BUTTON_HEIGHT + FOOTER_HEIGHT;

        int centerX = this.width / 2;
        this.panelTop = Math.max(4, this.height / 2 - panelHeight / 2);
        this.panelBottom = this.panelTop + panelHeight;
        this.tabBarY = this.panelTop + TITLE_HEIGHT;
        this.contentTop = this.tabBarY + TAB_BAR_HEIGHT + TAB_CONTENT_GAP;

        int thisTabHeight = this.currentTab == Tab.HEALING ? healingContentHeight() : blazeSlayerContentHeight();
        // Center whichever tab's content is shorter than the taller tab, so switching tabs never
        // resizes the panel or moves the Done button, but a short tab doesn't look stuck at the top.
        int y = this.contentTop + (this.maxContentHeight - thisTabHeight) / 2;

        if (this.currentTab == Tab.HEALING) {
            y = buildHealingTab(centerX, y);
        } else {
            y = buildBlazeSlayerTab(centerX, y);
        }

        int doneY = this.contentTop + this.maxContentHeight + SPACING + 12;
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(centerX - BUTTON_WIDTH / 2, doneY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private int buildHealingTab(int centerX, int y) {
        y = section(centerX, y, "GENERAL");

        Button enabledButton = Button.builder(enabledLabel(), b -> {
            NexoraHpConfig.enabled = !NexoraHpConfig.enabled;
            b.setMessage(enabledLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(enabledButton);
        y += SPACING;

        this.addRenderableWidget(new ValueSlider(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                "Heal Below", "%", 10, 95, 5, NexoraHpConfig.healThresholdPercent,
                percent -> NexoraHpConfig.healThresholdPercent = percent));
        y += SPACING;

        Button cooldownButton = Button.builder(cooldownLabel(), b -> {
            NexoraHpConfig.cooldownSeconds = NexoraHpConfig.cooldownSeconds % 60 + 1;
            b.setMessage(cooldownLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(cooldownButton);
        y += SPACING;

        Button avoidRagnarockButton = Button.builder(avoidRagnarockLabel(), b -> {
            NexoraHpConfig.avoidRagnarock = !NexoraHpConfig.avoidRagnarock;
            b.setMessage(avoidRagnarockLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(avoidRagnarockButton);
        y += SPACING + SECTION_GAP;

        y = section(centerX, y, "PANIC HEAL");

        Button panicEnabledButton = Button.builder(panicEnabledLabel(), b -> {
            NexoraHpConfig.panicEnabled = !NexoraHpConfig.panicEnabled;
            b.setMessage(panicEnabledLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(panicEnabledButton);
        y += SPACING;

        this.addRenderableWidget(new ValueSlider(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                "Panic Below", "%", 5, 90, 5, NexoraHpConfig.panicThresholdPercent,
                percent -> NexoraHpConfig.panicThresholdPercent = percent));
        y += SPACING + SECTION_GAP;

        y = section(centerX, y, "DISPLAY");

        Button soundButton = Button.builder(soundLabel(), b -> {
            NexoraHpConfig.soundEnabled = !NexoraHpConfig.soundEnabled;
            b.setMessage(soundLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(soundButton);
        y += SPACING;

        Button hudPositionButton = Button.builder(hudPositionLabel(), b -> {
            NexoraHpConfig.hudPosition = NexoraHpConfig.hudPosition.next();
            b.setMessage(hudPositionLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(hudPositionButton);
        y += SPACING;

        return y;
    }

    private int buildBlazeSlayerTab(int centerX, int y) {
        y = section(centerX, y, "ATTUNEMENT");

        Button autoAttunementButton = Button.builder(autoAttunementLabel(), b -> {
            NexoraHpConfig.autoAttunementEnabled = !NexoraHpConfig.autoAttunementEnabled;
            b.setMessage(autoAttunementLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(autoAttunementButton);
        y += SPACING;

        this.addRenderableWidget(new ValueSlider(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                "Swap Delay", "ms", AttunementController.MIN_CONFIRM_WINDOW_MILLIS,
                AttunementController.MAX_CONFIRM_WINDOW_MILLIS, 50, NexoraHpConfig.attunementSwitchDelayMillis,
                millis -> NexoraHpConfig.attunementSwitchDelayMillis = millis));
        y += SPACING;

        Button showAttunementButton = Button.builder(showAttunementLabel(), b -> {
            NexoraHpConfig.showAttunement = !NexoraHpConfig.showAttunement;
            b.setMessage(showAttunementLabel());
        }).bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(showAttunementButton);
        y += SPACING;

        return y;
    }

    /** Draws a small caps section label with a divider line, and returns the y for the first widget in it. */
    private int section(int centerX, int y, String text) {
        this.sectionHeaders.add(new SectionHeader(text, y));
        return y + SECTION_HEADER_HEIGHT;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0 && event.y() >= this.tabBarY && event.y() < this.tabBarY + TAB_BAR_HEIGHT) {
            int centerX = this.width / 2;
            int panelX1 = centerX - PANEL_WIDTH / 2;
            int panelX2 = centerX + PANEL_WIDTH / 2;
            if (event.x() >= panelX1 && event.x() < panelX2) {
                Tab clicked = event.x() < centerX ? Tab.HEALING : Tab.BLAZE_SLAYER;
                if (clicked != this.currentTab) {
                    this.currentTab = clicked;
                    this.init();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x99050507);

        int centerX = this.width / 2;
        int panelX1 = centerX - PANEL_WIDTH / 2;
        int panelX2 = centerX + PANEL_WIDTH / 2;
        int panelHeight = this.panelBottom - this.panelTop;

        graphics.fillGradient(panelX1, this.panelTop, panelX2, this.panelBottom, 0xF0181822, 0xF00E0E16);
        graphics.outline(panelX1, this.panelTop, PANEL_WIDTH, panelHeight, 0x22FFFFFF);
        drawCornerBrackets(graphics, panelX1, this.panelTop, panelX2, this.panelBottom, 10, ACCENT_COLOR);
        graphics.fill(panelX1 + 1, this.panelTop + 1, panelX2 - 1, this.panelTop + 3, ACCENT_COLOR);

        graphics.centeredText(this.font, this.title, centerX, this.panelTop + 9, 0xFFFFFFFF);

        drawTabBar(graphics, panelX1, panelX2, centerX);

        for (SectionHeader header : this.sectionHeaders) {
            // Draw near the top of this section's own reserved SECTION_HEADER_HEIGHT block,
            // rather than backing up from the first widget below it -- that backward offset was
            // tuned for the old layout and landed on top of the tab bar's own decorations once
            // the tab bar was inserted above it.
            int textY = header.y() + 3;
            graphics.text(this.font, header.text(), panelX1 + 20, textY, ACCENT_COLOR);
            int lineX1 = panelX1 + 20 + this.font.width(header.text()) + 6;
            graphics.horizontalLine(lineX1, panelX2 - 20, textY + this.font.lineHeight / 2, 0x40FFFFFF);
        }

        graphics.centeredText(this.font, "Nexora-Heal • v1.0.0", centerX, this.panelBottom - 12, 0xFF55555F);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawTabBar(GuiGraphicsExtractor graphics, int panelX1, int panelX2, int centerX) {
        int textY = this.tabBarY + 6;
        for (Tab tab : Tab.values()) {
            boolean active = tab == this.currentTab;
            int zoneX1 = tab == Tab.HEALING ? panelX1 : centerX;
            int zoneX2 = tab == Tab.HEALING ? centerX : panelX2;
            int zoneCenterX = (zoneX1 + zoneX2) / 2;
            int color = active ? 0xFFFFFFFF : TAB_INACTIVE_COLOR;
            graphics.centeredText(this.font, tab.label, zoneCenterX, textY, color);
            if (active) {
                int textWidth = this.font.width(tab.label);
                graphics.fill(zoneCenterX - textWidth / 2 - 2, this.tabBarY + TAB_BAR_HEIGHT - 5,
                        zoneCenterX + textWidth / 2 + 2, this.tabBarY + TAB_BAR_HEIGHT - 3, ACCENT_COLOR);
            }
        }
        graphics.horizontalLine(panelX1 + 12, panelX2 - 12, this.tabBarY + TAB_BAR_HEIGHT - 1, 0x30FFFFFF);
    }

    /** Four accent-colored corner brackets instead of a full border -- matches the HUD's look. */
    private static void drawCornerBrackets(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int len,
            int color) {
        int t = 1;
        graphics.fill(x1, y1, x1 + len, y1 + t, color);
        graphics.fill(x1, y1, x1 + t, y1 + len, color);
        graphics.fill(x2 - len, y1, x2, y1 + t, color);
        graphics.fill(x2 - t, y1, x2, y1 + len, color);
        graphics.fill(x1, y2 - t, x1 + len, y2, color);
        graphics.fill(x1, y2 - len, x1 + t, y2, color);
        graphics.fill(x2 - len, y2 - t, x2, y2, color);
        graphics.fill(x2 - t, y2 - len, x2, y2, color);
    }

    @Override
    public void onClose() {
        NexoraHpConfig.save();
        this.minecraft.setScreen(parent);
    }

    private static Component enabledLabel() {
        return Component.literal("Auto-Heal: " + (NexoraHpConfig.enabled ? "ON" : "OFF"));
    }

    private static Component cooldownLabel() {
        return Component.literal("Cooldown: " + NexoraHpConfig.cooldownSeconds + "s");
    }

    private static Component soundLabel() {
        return Component.literal("Heal Sound: " + (NexoraHpConfig.soundEnabled ? "ON" : "OFF"));
    }

    private static Component hudPositionLabel() {
        return Component.literal("HUD Position: " + NexoraHpConfig.hudPosition.name().replace('_', ' '));
    }

    private static Component avoidRagnarockLabel() {
        return Component.literal("Avoid Ragnarock: " + (NexoraHpConfig.avoidRagnarock ? "ON" : "OFF"));
    }

    private static Component panicEnabledLabel() {
        return Component.literal("Panic Heal: " + (NexoraHpConfig.panicEnabled ? "ON" : "OFF"));
    }

    private static Component autoAttunementLabel() {
        return Component.literal("Auto Attunement: " + (NexoraHpConfig.autoAttunementEnabled ? "ON" : "OFF"));
    }

    private static Component showAttunementLabel() {
        return Component.literal("Show Attunement: " + (NexoraHpConfig.showAttunement ? "ON" : "OFF"));
    }
}
