package com.nexora.hp;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/**
 * The corner HUD panel: header + HP bar, then one row per active concern (heal/panic readiness
 * with cooldown bars, attunement state, Soulcry state). Reads feature state from NexoraHpMod and
 * the feature classes; draws nothing when the HUD is disabled or the GUI is hidden.
 */
final class Hud {

    private static final int COLOR_GREEN = 0xFF5CE65C;
    private static final int COLOR_RED = 0xFFE65C5C;
    private static final int COLOR_GRAY = 0xFF9A9AA5;
    private static final int COLOR_AMBER = 0xFFF2B33D;
    private static final int COLOR_ASHEN = 0xFFC2C2CC;
    private static final int COLOR_SPIRIT = 0xFFF5F5F5;
    private static final int COLOR_CRYSTAL = 0xFF5CE6E6;
    private static final int COLOR_ACCENT = 0xFF5CE6C7;

    private Hud() {
    }

    /** One row of the HUD: a colored label, and optionally a colored progress bar beneath it. */
    private record HudRow(String label, int color, boolean hasBar, float progress) {
        private static HudRow text(String label, int color) {
            return new HudRow(label, color, false, 0f);
        }

        private static HudRow bar(String label, int color, float progress) {
            return new HudRow(label, color, true, progress);
        }
    }

    static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (!NexoraHpConfig.hudEnabled || player == null || client.level == null || client.options.hideGui) {
            return;
        }

        boolean enabled = NexoraHpConfig.enabled;
        List<HudRow> rows = new ArrayList<>();

        if (enabled && NexoraHpMod.wandSlot < 0) {
            rows.add(HudRow.bar("NO WAND", COLOR_AMBER, 0f));
        } else {
            rows.add(HudRow.bar(
                    statusLabel(enabled, NexoraHpMod.canHeal, "HEAL", NexoraHpMod.cooldownEndsAt),
                    statusColor(enabled, NexoraHpMod.canHeal, NexoraHpMod.cooldownEndsAt,
                            NexoraHpMod.cooldownDurationMillis),
                    progressFor(enabled, NexoraHpMod.canHeal, NexoraHpMod.cooldownEndsAt,
                            NexoraHpMod.cooldownDurationMillis)));
        }

        if (NexoraHpConfig.panicEnabled) {
            if (enabled && NexoraHpMod.swordSlot < 0) {
                rows.add(HudRow.bar("NO SWORD", COLOR_AMBER, 0f));
            } else if (enabled && NexoraHpMod.panicActive) {
                rows.add(HudRow.bar("PANIC USING", COLOR_GREEN, 1f));
            } else {
                rows.add(HudRow.bar(
                        statusLabel(enabled, NexoraHpMod.panicCanHeal, "PANIC", NexoraHpMod.panicCooldownEndsAt),
                        statusColor(enabled, NexoraHpMod.panicCanHeal, NexoraHpMod.panicCooldownEndsAt,
                                NexoraHpMod.panicCooldownDurationMillis),
                        progressFor(enabled, NexoraHpMod.panicCanHeal, NexoraHpMod.panicCooldownEndsAt,
                                NexoraHpMod.panicCooldownDurationMillis)));
            }
        }

        String attunement = NexoraHpMod.currentAttunement;
        if (NexoraHpConfig.showAttunement && attunement != null) {
            String label = "IMMUNE".equals(attunement) ? "BOSS IMMUNE" : "ATTUNEMENT: " + attunement;
            rows.add(HudRow.text(label, attunementColor(attunement)));
        }

        if (enabled && NexoraHpConfig.autoAttunementEnabled && attunement != null && !"IMMUNE".equals(attunement)) {
            boolean fireFamily = "ASHEN".equals(attunement) || "AURIC".equals(attunement);
            if ((fireFamily ? NexoraHpMod.fireDaggerSlot : NexoraHpMod.twilightDaggerSlot) < 0) {
                rows.add(HudRow.text("NO DAGGER", COLOR_AMBER));
            }
        }

        if (enabled && NexoraHpConfig.autoSoulcryEnabled && AutoSoulcry.bossNearby) {
            rows.add(AutoSoulcry.holdingKatana
                    ? HudRow.text("SOULCRY: ACTIVE", COLOR_GREEN)
                    : HudRow.text("NO KATANA", COLOR_AMBER));
        }

        float hpRatio = player.getMaxHealth() > 0f ? player.getHealth() / player.getMaxHealth() : 0f;
        int hpColor = lerpColor(COLOR_RED, COLOR_GREEN, Math.max(0f, Math.min(1f, hpRatio)));
        String hpText = Math.round(hpRatio * 100f) + "%";

        Font font = client.font;
        int padding = 6;
        int barHeight = 3;
        int margin = 8;
        int rowGap = 4;
        int ledSize = 4;
        int ledGap = 4;
        int textIndent = ledSize + ledGap;
        // Measure the header with its actual "§l" bold prefix -- bold glyphs render wider than
        // the plain text, so measuring the plain string undersized the box and let the HP% text
        // crowd into the title.
        int headerText = font.width("§lNEXORA");

        int contentWidth = Math.max(headerText + font.width(hpText) + 10, 100);
        for (HudRow row : rows) {
            contentWidth = Math.max(contentWidth, textIndent + font.width(row.label()));
        }
        int boxWidth = contentWidth + padding * 2;

        // Sizing mirrors the exact draw sequence below step for step (same increments, same
        // order) instead of separately counting "elements" and gaps, so the two can never drift
        // out of sync -- that drift previously undercounted a gap and packed rows too tightly.
        int contentHeight = font.lineHeight + 2; // header line + clearance before the divider
        contentHeight += rowGap + barHeight; // divider-to-HP-bar gap, then the HP bar itself
        for (HudRow row : rows) {
            contentHeight += rowGap + font.lineHeight;
            if (row.hasBar()) {
                contentHeight += rowGap + barHeight;
            }
        }
        int boxHeight = padding * 2 + contentHeight;

        boolean right = NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.TOP_RIGHT
                || NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_RIGHT;
        boolean bottom = NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_LEFT
                || NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_RIGHT;

        int x1 = right ? graphics.guiWidth() - margin - boxWidth : margin;
        int x2 = x1 + boxWidth;
        int y1 = bottom ? graphics.guiHeight() - margin - boxHeight : margin;
        int y2 = y1 + boxHeight;

        // Panel: dark gradient fill, a faint full outline for structure, and pulsing accent
        // corner brackets on top for a tactical-HUD look.
        graphics.fillGradient(x1, y1, x2, y2, 0xEC12121C, 0xEC08080D);
        graphics.outline(x1, y1, boxWidth, boxHeight, 0x22FFFFFF);

        float pulse = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 260.0);
        int bracketColor = (Math.round(pulse * 0xFF) << 24) | (COLOR_ACCENT & 0x00FFFFFF);
        drawCornerBrackets(graphics, x1, y1, x2, y2, Math.min(10, boxWidth / 4), bracketColor);

        int barX1 = x1 + padding;
        int barX2 = x2 - padding;

        int rowY = y1 + padding;
        graphics.text(font, "§lNEXORA", x1 + padding, rowY, COLOR_ACCENT);
        graphics.text(font, hpText, x2 - padding - font.width(hpText), rowY, hpColor);
        rowY += font.lineHeight + 2;
        graphics.fill(barX1, rowY - 1, barX2, rowY, (COLOR_ACCENT & 0x00FFFFFF) | 0x50000000);
        rowY += rowGap;
        rowY = drawSegmentedBar(graphics, barX1, barX2, rowY, barHeight, hpColor, hpRatio);

        for (HudRow row : rows) {
            rowY += rowGap;
            drawLed(graphics, x1 + padding, rowY + 1, ledSize, row.color());
            graphics.text(font, row.label(), x1 + padding + textIndent, rowY, row.color());
            rowY += font.lineHeight;
            if (row.hasBar()) {
                rowY += rowGap;
                rowY = drawSegmentedBar(graphics, barX1, barX2, rowY, barHeight, row.color(), row.progress());
            }
        }
    }

    /** A small square status LED with a soft glow behind it, instead of relying on font glyph coverage. */
    private static void drawLed(GuiGraphicsExtractor graphics, int x, int y, int size, int color) {
        int glow = (color & 0x00FFFFFF) | 0x40000000;
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, glow);
        graphics.fill(x, y, x + size, y + size, color | 0xFF000000);
    }

    /** Four accent-colored corner brackets instead of a full border -- reads as HUD, not a dialog box. */
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

    private static int attunementColor(String attunement) {
        return switch (attunement) {
            case "ASHEN" -> COLOR_ASHEN;
            case "SPIRIT" -> COLOR_SPIRIT;
            case "CRYSTAL" -> COLOR_CRYSTAL;
            case "AURIC" -> COLOR_AMBER;
            case "IMMUNE" -> COLOR_RED;
            default -> COLOR_GRAY;
        };
    }

    private static String statusLabel(boolean enabled, boolean ready, String name, long cooldownEndsAt) {
        if (!enabled) {
            return name + " OFF";
        }
        if (ready) {
            return name + " READY";
        }
        long remainingMillis = Math.max(0L, cooldownEndsAt - System.currentTimeMillis());
        return String.format("%s IN %.1fs", name, remainingMillis / 1000f);
    }

    private static int statusColor(boolean enabled, boolean ready, long cooldownEndsAt, long cooldownDurationMillis) {
        if (!enabled) {
            return COLOR_GRAY;
        }
        if (ready) {
            return COLOR_GREEN;
        }
        return lerpColor(COLOR_RED, COLOR_GREEN, progressFor(true, false, cooldownEndsAt, cooldownDurationMillis));
    }

    private static float progressFor(boolean enabled, boolean ready, long cooldownEndsAt, long cooldownDurationMillis) {
        if (!enabled) {
            return 0f;
        }
        if (ready) {
            return 1f;
        }
        long remainingMillis = Math.max(0L, cooldownEndsAt - System.currentTimeMillis());
        return cooldownDurationMillis > 0 ? 1f - (remainingMillis / (float) cooldownDurationMillis) : 1f;
    }

    /** A "tech" tick-bar: small lit/unlit blocks instead of one solid fill, sized to the given width. */
    private static int drawSegmentedBar(GuiGraphicsExtractor graphics, int x1, int x2, int y, int height, int color,
            float progress) {
        int width = x2 - x1;
        int segments = Math.max(6, width / 7);
        int gap = 1;
        float segWidth = (width - gap * (segments - 1)) / (float) segments;
        int lit = Math.round(segments * Math.max(0f, Math.min(1f, progress)));

        for (int i = 0; i < segments; i++) {
            int sx1 = x1 + Math.round(i * (segWidth + gap));
            int sx2 = Math.round(x1 + i * (segWidth + gap) + segWidth);
            if (i < lit) {
                boolean tip = i == lit - 1;
                graphics.fill(sx1, y, sx2, y + height, tip ? brighten(color) : color);
            } else {
                graphics.fill(sx1, y, sx2, y + height, 0xFF25252F);
            }
        }
        return y + height;
    }

    private static int brighten(int color) {
        int a = (color >> 24) & 0xFF, r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (a << 24) | (Math.min(255, r + 40) << 16) | (Math.min(255, g + 40) << 8) | Math.min(255, b + 40);
    }

    private static int lerpColor(int colorA, int colorB, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aA = (colorA >> 24) & 0xFF, rA = (colorA >> 16) & 0xFF, gA = (colorA >> 8) & 0xFF, bA = colorA & 0xFF;
        int aB = (colorB >> 24) & 0xFF, rB = (colorB >> 16) & 0xFF, gB = (colorB >> 8) & 0xFF, bB = colorB & 0xFF;
        int a = Math.round(aA + (aB - aA) * t);
        int r = Math.round(rA + (rB - rA) * t);
        int g = Math.round(gA + (gB - gA) * t);
        int b = Math.round(bA + (bB - bA) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
