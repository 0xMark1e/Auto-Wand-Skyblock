package com.nexora.hp;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.context.CommandContext;
import java.lang.reflect.Field;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

public class NexoraHpMod implements ClientModInitializer {

    // KeyMapping has no public getter for its currently bound key, so we read
    // the "key" field reflectively to simulate a press of whatever key/button
    // the player has it bound to (instead of hardcoding a keycode).
    private static final Field KEY_MAPPING_KEY_FIELD;

    static {
        try {
            KEY_MAPPING_KEY_FIELD = KeyMapping.class.getDeclaredField("key");
            KEY_MAPPING_KEY_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static InputConstants.Key boundKey(KeyMapping mapping) {
        try {
            return (InputConstants.Key) KEY_MAPPING_KEY_FIELD.get(mapping);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean needHeal = false;
    private static boolean canHeal = true;
    private static long cooldownEndsAt = 0L;
    private static long cooldownDurationMillis = 0L;
    private static boolean releasePending = false;
    private static int originalSlot = -1;

    @Override
    public void onInitializeClient() {
        NexoraHpConfig.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext) -> {
            dispatcher.register(ClientCommands.literal("showhp")
                    .executes(NexoraHpMod::showHp));
            dispatcher.register(ClientCommands.literal("nexora")
                    .executes(NexoraHpMod::openConfigScreen));
        });

        ClientTickEvents.END_CLIENT_TICK.register(NexoraHpMod::onClientTick);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nexora-hp", "heal_indicator"),
                (graphics, deltaTracker) -> renderHealIndicator(graphics));
    }

    private static void onClientTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }

        // Release the simulated right-click, then switch back to whatever slot we were on before healing.
        if (releasePending) {
            KeyMapping.set(boundKey(client.options.keyUse), false);
            releasePending = false;

            if (originalSlot >= 0) {
                KeyMapping.click(boundKey(client.options.keyHotbarSlots[originalSlot]));
            }
            originalSlot = -1;
        }

        if (!NexoraHpConfig.enabled) {
            needHeal = false;
            return;
        }

        long now = System.currentTimeMillis();
        if (!canHeal && now >= cooldownEndsAt) {
            canHeal = true;
        }

        float currentHp = player.getHealth();
        float maxHp = player.getMaxHealth();
        needHeal = maxHp > 0f && currentHp < maxHp * (NexoraHpConfig.healThresholdPercent / 100f);

        if (needHeal && canHeal) {
            int healSlotIndex = NexoraHpConfig.hotbarSlot - 1;
            int selectedSlot = player.getInventory().getSelectedSlot();

            // Simulate pressing the heal-item hotbar key, then the use-item (right click) key.
            if (selectedSlot != healSlotIndex) {
                originalSlot = selectedSlot;
                KeyMapping.click(boundKey(client.options.keyHotbarSlots[healSlotIndex]));
            }
            KeyMapping.set(boundKey(client.options.keyUse), true);
            releasePending = true;

            canHeal = false;
            cooldownDurationMillis = NexoraHpConfig.cooldownSeconds * 1000L + 500L;
            cooldownEndsAt = now + cooldownDurationMillis;

            if (NexoraHpConfig.soundEnabled) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F));
            }
        }
    }

    private static final int COLOR_HEADER = 0xFF8A8A9A;
    private static final int COLOR_GREEN = 0xFF5CE65C;
    private static final int COLOR_RED = 0xFFE65C5C;
    private static final int COLOR_GRAY = 0xFF9A9AA5;

    private static void renderHealIndicator(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.options.hideGui) {
            return;
        }

        String label;
        int statusColor;
        int barColor;
        float progress;

        if (!NexoraHpConfig.enabled) {
            label = "AUTO-HEAL OFF";
            statusColor = COLOR_GRAY;
            barColor = COLOR_GRAY;
            progress = 0f;
        } else if (canHeal) {
            label = "HEAL READY";
            statusColor = COLOR_GREEN;
            barColor = COLOR_GREEN;
            progress = 1f;
        } else {
            long remainingMillis = Math.max(0L, cooldownEndsAt - System.currentTimeMillis());
            label = String.format("HEAL IN %.1fs", remainingMillis / 1000f);
            progress = cooldownDurationMillis > 0
                    ? 1f - (remainingMillis / (float) cooldownDurationMillis)
                    : 1f;
            barColor = lerpColor(COLOR_RED, COLOR_GREEN, progress);
            statusColor = barColor;
        }

        float hpRatio = player.getMaxHealth() > 0f ? player.getHealth() / player.getMaxHealth() : 0f;
        int hpColor = lerpColor(COLOR_RED, COLOR_GREEN, Math.max(0f, Math.min(1f, hpRatio)));
        String hpText = Math.round(hpRatio * 100f) + "%";

        Font font = client.font;
        int padding = 5;
        int barHeight = 3;
        int margin = 8;
        int rowGap = 2;
        int headerText = font.width("NEXORA-WAND");
        int contentWidth = Math.max(headerText + font.width(hpText) + 10, Math.max(font.width(label), 96));
        int boxWidth = contentWidth + padding * 2;
        int boxHeight = padding * 2 + font.lineHeight * 2 + rowGap * 2 + barHeight;

        boolean right = NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.TOP_RIGHT
                || NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_RIGHT;
        boolean bottom = NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_LEFT
                || NexoraHpConfig.hudPosition == NexoraHpConfig.HudPosition.BOTTOM_RIGHT;

        int x1 = right ? graphics.guiWidth() - margin - boxWidth : margin;
        int x2 = x1 + boxWidth;
        int y1 = bottom ? graphics.guiHeight() - margin - boxHeight : margin;
        int y2 = y1 + boxHeight;

        // Panel: soft vertical gradient fill with a status-colored border.
        // NOTE: outline() takes (x, y, width, height, color), not a second x/y corner.
        graphics.fillGradient(x1, y1, x2, y2, 0xE0141420, 0xE00A0A12);
        graphics.outline(x1, y1, boxWidth, boxHeight, (barColor & 0x00FFFFFF) | 0x60000000);
        graphics.outline(x1 + 1, y1 + 1, boxWidth - 2, boxHeight - 2, 0x30FFFFFF);

        int rowY = y1 + padding;
        graphics.text(font, "NEXORA-WAND", x1 + padding, rowY, COLOR_HEADER);
        graphics.text(font, hpText, x2 - padding - font.width(hpText), rowY, hpColor);
        rowY += font.lineHeight + rowGap;

        graphics.text(font, label, x1 + padding, rowY, statusColor);
        rowY += font.lineHeight + rowGap;

        int barX1 = x1 + padding;
        int barX2 = x2 - padding;
        graphics.fill(barX1, rowY, barX2, rowY + barHeight, 0xFF25252F);
        int filledWidth = Math.round((barX2 - barX1) * Math.max(0f, Math.min(1f, progress)));
        if (filledWidth > 0) {
            graphics.fillGradient(barX1, rowY, barX1 + filledWidth, rowY + barHeight, barColor, dim(barColor));
        }
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

    private static int dim(int color) {
        int a = (color >> 24) & 0xFF, r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (a << 24) | (r * 3 / 4 << 16) | (g * 3 / 4 << 8) | (b * 3 / 4);
    }

    private static int showHp(CommandContext<FabricClientCommandSource> context) {
        LocalPlayer player = context.getSource().getPlayer();

        float currentHp = player.getHealth();
        float maxHp = player.getMaxHealth();

        context.getSource().sendFeedback(
                Component.literal("[NEXORA] Current HP -> " + formatHp(currentHp))
                        .withStyle(ChatFormatting.RED));
        context.getSource().sendFeedback(
                Component.literal("[NEXORA] Max HP -> " + formatHp(maxHp))
                        .withStyle(ChatFormatting.GREEN));

        return 1;
    }

    private static int openConfigScreen(CommandContext<FabricClientCommandSource> context) {
        // The chat screen closes itself (setScreen(null)) right after a command is sent,
        // which would immediately wipe out our screen if we set it synchronously here.
        // Deferring to the next client tick lets that close happen first.
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreen(new NexoraHpConfigScreen(null)));
        return 1;
    }

    private static String formatHp(float value) {
        if (value == Math.floor(value)) {
            return String.valueOf((int) value);
        }
        return String.format("%.1f", value);
    }
}
