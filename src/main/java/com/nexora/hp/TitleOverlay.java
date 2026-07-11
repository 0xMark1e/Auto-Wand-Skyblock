package com.nexora.hp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

/**
 * A big centered announcement title (the "PERSONAL BEST!" style): scaled-up bold text, centered
 * horizontally at the upper quarter of the screen, drawn in neon pink with darker offset copies
 * behind it for a glow, fading out at the end of its display time. /testtext drives it manually;
 * features can call {@link #show} for their own announcements.
 */
final class TitleOverlay {

    private static final int DISPLAY_MILLIS = 3000;
    private static final int FADE_MILLIS = 600;
    private static final float SCALE = 3.0f;
    private static final int NEON_PINK = 0x00FF4FDE;
    private static final int NEON_PINK_GLOW = 0x00A3128F;

    private static String text = null;
    private static long showUntil = 0L;

    private TitleOverlay() {
    }

    static void show(String message) {
        text = message;
        showUntil = System.currentTimeMillis() + DISPLAY_MILLIS;
    }

    static void render(GuiGraphicsExtractor graphics) {
        if (text == null) {
            return;
        }
        long remaining = showUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            text = null;
            return;
        }

        // Fade out over the last FADE_MILLIS. The font renderer treats near-zero alpha as fully
        // opaque (a vanilla quirk), so bail out before the fade reaches that range.
        float fade = remaining >= FADE_MILLIS ? 1f : remaining / (float) FADE_MILLIS;
        int alpha = Math.round(fade * 255);
        if (alpha < 8) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        String display = "§l" + text;
        int halfWidth = font.width(display) / 2;
        int core = (alpha << 24) | NEON_PINK;
        int glow = (Math.round(alpha * 0.55f) << 24) | NEON_PINK_GLOW;

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(graphics.guiWidth() / 2f, graphics.guiHeight() * 0.25f);
        pose.scale(SCALE);
        // Offset glow copies around the core give the neon halo; the core goes on top last.
        graphics.text(font, display, -halfWidth - 1, -1, glow);
        graphics.text(font, display, -halfWidth + 1, -1, glow);
        graphics.text(font, display, -halfWidth - 1, 1, glow);
        graphics.text(font, display, -halfWidth + 1, 1, glow);
        graphics.text(font, display, -halfWidth, 0, core);
        pose.popMatrix();
    }
}
