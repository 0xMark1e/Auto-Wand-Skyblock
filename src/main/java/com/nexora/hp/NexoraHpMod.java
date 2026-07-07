package com.nexora.hp;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.context.CommandContext;
import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

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
    private static int wandSlot = -1;

    // Panic heal: spams the "Instant Heal" sword's charges until the server tells us we're out,
    // then waits until the reported recharge time before it's willing to try again.
    private static final String SWORD_ID = "FLORID_ZOMBIE_SWORD";
    private static final int PANIC_RETRY_DELAY_TICKS = 3;
    private static final Pattern NO_CHARGES_PATTERN = Pattern.compile("next one in ([0-9.]+)s");

    private static int swordSlot = -1;
    private static boolean panicActive = false;
    private static boolean panicReleasePending = false;
    private static boolean panicRestorePending = false;
    private static int panicRetryDelayTicks = 0;
    private static int panicOriginalSlot = -1;
    private static boolean panicCanHeal = true;
    private static long panicCooldownEndsAt = 0L;
    private static long panicCooldownDurationMillis = 0L;
    private static volatile Double panicDepletedCooldownSeconds = null;

    @Override
    public void onInitializeClient() {
        NexoraHpConfig.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext) -> {
            dispatcher.register(ClientCommands.literal("showhp")
                    .executes(NexoraHpMod::showHp));
            dispatcher.register(ClientCommands.literal("nexora")
                    .executes(NexoraHpMod::openConfigScreen));
            dispatcher.register(ClientCommands.literal("getid")
                    .executes(NexoraHpMod::getId));
        });

        ClientTickEvents.END_CLIENT_TICK.register(NexoraHpMod::onClientTick);

        // The item only tells us "no charges left" via a chat message (there's no NBT/component
        // field for remaining charges), so that's what drives the panic-heal cooldown.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, type, receptionTimestamp) ->
                onChatMessage(message.getString()));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nexora-heal", "heal_indicator"),
                (graphics, deltaTracker) -> renderHealIndicator(graphics));
    }

    private static void onChatMessage(String text) {
        if (!panicActive || !text.contains("No more charges")) {
            return;
        }
        Matcher matcher = NO_CHARGES_PATTERN.matcher(text);
        panicDepletedCooldownSeconds = matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    private static void onClientTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }

        // Vanilla only consumes queued hotbar-slot clicks and the use-item key while no screen is
        // open (Minecraft.tick() gates its call to handleKeybinds() on screen == null). Our own
        // per-tick logic isn't gated like that by default, so if we kept advancing our state
        // machine while e.g. the player's inventory or a menu is open, we'd queue a restore click
        // that sits unconsumed until the screen closes. Pausing everything here keeps our
        // synthetic input in lockstep with when the game will actually consume it.
        if (client.screen != null || client.getOverlay() != null) {
            return;
        }

        // Release the simulated right-click, then switch back to whatever slot we were on before healing.
        if (releasePending) {
            releasePending = false;
            releaseUseAndRestoreSlot(client, originalSlot);
            originalSlot = -1;
        }

        tickPanic(client, player);

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

        wandSlot = findWandSlot(player.getInventory());

        // Never interrupt a held Ragnarock -- wait until the player switches away from it
        // themselves before switching to the heal item. Also don't fight the panic sequence
        // over which hotbar slot is selected while it's mid-flight.
        if (needHeal && canHeal && wandSlot >= 0 && !panicActive
                && !(NexoraHpConfig.avoidRagnarock && isHoldingRagnarock(player))) {
            triggerHeal(client, player, now, wandSlot);
        }
    }

    /**
     * Panic heal: below a (lower) HP%, switch to the "Instant Heal" sword and spam its use key
     * once every few ticks -- exactly like a player mashing right-click -- until the server's own
     * "No more charges, next one in Xs!" message tells us to stop. That message is also the only
     * place the recharge timer is exposed (there's no charges field in the item's data), so it
     * drives the cooldown directly instead of us guessing a duration.
     */
    private static void tickPanic(Minecraft client, LocalPlayer player) {
        long now = System.currentTimeMillis();

        if (panicReleasePending) {
            panicReleasePending = false;
            KeyMapping.set(boundKey(client.options.keyUse), false);
            panicRetryDelayTicks = PANIC_RETRY_DELAY_TICKS;
        }

        if (panicDepletedCooldownSeconds != null) {
            double seconds = panicDepletedCooldownSeconds;
            panicDepletedCooldownSeconds = null;
            panicActive = false;
            panicCanHeal = false;
            panicCooldownDurationMillis = Math.round(seconds * 1000) + 500L;
            panicCooldownEndsAt = now + panicCooldownDurationMillis;
            panicRestorePending = true;
        }

        if (panicRestorePending) {
            panicRestorePending = false;
            if (panicOriginalSlot >= 0) {
                KeyMapping.click(boundKey(client.options.keyHotbarSlots[panicOriginalSlot]));
            }
            panicOriginalSlot = -1;
        }

        if (!panicCanHeal && now >= panicCooldownEndsAt) {
            panicCanHeal = true;
        }

        swordSlot = findSwordSlot(player.getInventory());

        if (!NexoraHpConfig.enabled || !NexoraHpConfig.panicEnabled) {
            return;
        }

        float maxHp = player.getMaxHealth();
        boolean needPanic = maxHp > 0f && player.getHealth() < maxHp * (NexoraHpConfig.panicThresholdPercent / 100f);

        if (!panicActive && needPanic && panicCanHeal && swordSlot >= 0) {
            int selectedSlot = player.getInventory().getSelectedSlot();
            if (selectedSlot != swordSlot) {
                panicOriginalSlot = selectedSlot;
                KeyMapping.click(boundKey(client.options.keyHotbarSlots[swordSlot]));
            }
            panicActive = true;
            KeyMapping.set(boundKey(client.options.keyUse), true);
            panicReleasePending = true;

            if (NexoraHpConfig.soundEnabled) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6F));
            }
        } else if (panicActive && !panicReleasePending) {
            if (panicRetryDelayTicks > 0) {
                panicRetryDelayTicks--;
            } else {
                KeyMapping.set(boundKey(client.options.keyUse), true);
                panicReleasePending = true;
            }
        }
    }

    /** Exact-ID match: unlike the wand, there's only the one "Instant Heal" sword variant so far. */
    private static int findSwordSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (SWORD_ID.equals(extraAttributesId(inventory.getItem(slot)))) {
                return slot;
            }
        }
        return -1;
    }

    private static final String RAGNAROCK_ID = "RAGNAROCK_AXE";
    private static final String WAND_ID_PREFIX = "WAND_OF_";

    private static boolean isHoldingRagnarock(LocalPlayer player) {
        return RAGNAROCK_ID.equals(extraAttributesId(player.getInventory().getSelectedItem()));
    }

    /**
     * Scans the hotbar (slots 0-8) for an item whose internal ID starts with "WAND_OF_" --
     * there are several wand variants (Wand of Restoration, Wand of Mending, ...) that all
     * share this prefix, so it's a prefix match rather than an exact one. This replaces the
     * old fixed-slot config: the wand can now sit in whatever hotbar slot the player likes.
     */
    private static int findWandSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            String id = extraAttributesId(inventory.getItem(slot));
            if (id.startsWith(WAND_ID_PREFIX)) {
                return slot;
            }
        }
        return -1;
    }

    /** Releases the simulated right-click and, if we switched slots to heal, switches back. */
    private static void releaseUseAndRestoreSlot(Minecraft client, int originalSlot) {
        KeyMapping.set(boundKey(client.options.keyUse), false);
        if (originalSlot >= 0) {
            KeyMapping.click(boundKey(client.options.keyHotbarSlots[originalSlot]));
        }
    }

    private static void triggerHeal(Minecraft client, LocalPlayer player, long now, int healSlotIndex) {
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

    private static final int COLOR_HEADER = 0xFF8A8A9A;
    private static final int COLOR_GREEN = 0xFF5CE65C;
    private static final int COLOR_RED = 0xFFE65C5C;
    private static final int COLOR_GRAY = 0xFF9A9AA5;
    private static final int COLOR_AMBER = 0xFFF2B33D;

    private static void renderHealIndicator(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.options.hideGui) {
            return;
        }

        boolean enabled = NexoraHpConfig.enabled;
        String label;
        int barColor;
        float progress;
        if (enabled && wandSlot < 0) {
            label = "NO WAND";
            barColor = COLOR_AMBER;
            progress = 0f;
        } else {
            label = statusLabel(enabled, canHeal, "HEAL", cooldownEndsAt);
            barColor = statusColor(enabled, canHeal, cooldownEndsAt, cooldownDurationMillis);
            progress = progressFor(enabled, canHeal, cooldownEndsAt, cooldownDurationMillis);
        }

        boolean showPanicRow = NexoraHpConfig.panicEnabled;
        String panicLabel = null;
        int panicColor = 0;
        float panicProgress = 0f;
        if (showPanicRow) {
            if (enabled && swordSlot < 0) {
                panicLabel = "NO SWORD";
                panicColor = COLOR_AMBER;
            } else if (enabled && panicActive) {
                panicLabel = "PANIC USING";
                panicColor = COLOR_GREEN;
                panicProgress = 1f;
            } else {
                panicLabel = statusLabel(enabled, panicCanHeal, "PANIC", panicCooldownEndsAt);
                panicColor = statusColor(enabled, panicCanHeal, panicCooldownEndsAt, panicCooldownDurationMillis);
                panicProgress = progressFor(enabled, panicCanHeal, panicCooldownEndsAt, panicCooldownDurationMillis);
            }
        }

        float hpRatio = player.getMaxHealth() > 0f ? player.getHealth() / player.getMaxHealth() : 0f;
        int hpColor = lerpColor(COLOR_RED, COLOR_GREEN, Math.max(0f, Math.min(1f, hpRatio)));
        String hpText = Math.round(hpRatio * 100f) + "%";

        Font font = client.font;
        int padding = 5;
        int barHeight = 3;
        int margin = 8;
        int rowGap = 2;
        int headerText = font.width("NEXORA-HEAL");

        int contentWidth = Math.max(headerText + font.width(hpText) + 10, Math.max(font.width(label), 96));
        if (showPanicRow) {
            contentWidth = Math.max(contentWidth, font.width(panicLabel));
        }
        int boxWidth = contentWidth + padding * 2;

        int boxHeight = padding * 2 + font.lineHeight * (showPanicRow ? 3 : 2) + rowGap * (showPanicRow ? 4 : 2)
                + barHeight * (showPanicRow ? 2 : 1);

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

        int barX1 = x1 + padding;
        int barX2 = x2 - padding;

        int rowY = y1 + padding;
        graphics.text(font, "NEXORA-HEAL", x1 + padding, rowY, COLOR_HEADER);
        graphics.text(font, hpText, x2 - padding - font.width(hpText), rowY, hpColor);
        rowY += font.lineHeight + rowGap;

        graphics.text(font, label, x1 + padding, rowY, barColor);
        rowY += font.lineHeight + rowGap;
        rowY = drawBar(graphics, barX1, barX2, rowY, barHeight, barColor, progress);

        if (showPanicRow) {
            rowY += rowGap;
            graphics.text(font, panicLabel, x1 + padding, rowY, panicColor);
            rowY += font.lineHeight + rowGap;
            drawBar(graphics, barX1, barX2, rowY, barHeight, panicColor, panicProgress);
        }
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

    private static int drawBar(GuiGraphicsExtractor graphics, int x1, int x2, int y, int height, int color, float progress) {
        graphics.fill(x1, y, x2, y + height, 0xFF25252F);
        int filledWidth = Math.round((x2 - x1) * Math.max(0f, Math.min(1f, progress)));
        if (filledWidth > 0) {
            graphics.fillGradient(x1, y, x1 + filledWidth, y + height, color, dim(color));
        }
        return y + height;
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

    private static int getId(CommandContext<FabricClientCommandSource> context) {
        LocalPlayer player = context.getSource().getPlayer();
        ItemStack held = player.getInventory().getSelectedItem();
        String id = extraAttributesId(held);

        if (id.isEmpty()) {
            context.getSource().sendFeedback(
                    Component.literal("[NEXORA] No internal ID found on held item")
                            .withStyle(ChatFormatting.RED));
        } else {
            context.getSource().sendFeedback(
                    Component.literal("[NEXORA] Item ID -> " + id)
                            .withStyle(ChatFormatting.GREEN));
        }

        return 1;
    }

    /** Reads the Skyblock internal item ID (ExtraAttributes.id NBT tag) from an item stack. */
    private static String extraAttributesId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }

        CustomData customData = stack.getComponents().get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return "";
        }

        CompoundTag tag = customData.copyTag();

        // Newer Hypixel data stores "id" directly on the custom-data root; older items nest it
        // under "ExtraAttributes" instead. Try the direct key first, then fall back.
        String directId = tag.getStringOr("id", "");
        if (!directId.isEmpty()) {
            return directId;
        }
        return tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
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
