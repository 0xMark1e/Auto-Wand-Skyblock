package com.nexora.hp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Auto Soulcry: the three Voidgloom katanas share a right-click ability ("Soulcry", 4s duration,
 * 4s cooldown) that should stay up for the whole boss fight. The item's data does not change at
 * all while it's active (confirmed via /dumpitem component dumps taken in both states), so there
 * is no state to read -- instead, while a Voidgloom Seraph nametag is nearby and a katana is
 * held, tap right-click once a second: taps during the active window are rejected by the server
 * at no cost, and the first tap after expiry re-activates it.
 */
final class AutoSoulcry {

    private static final String VOIDGLOOM_BOSS_NAME = "Voidgloom Seraph";
    private static final double SCAN_RADIUS = 30.0;
    private static final int RETRY_DELAY_TICKS = 20;

    // Read by Hud for the SOULCRY / NO KATANA rows.
    static boolean bossNearby = false;
    static boolean holdingKatana = false;

    private static int delayTicks = 0;
    private static boolean releasePending = false;

    private AutoSoulcry() {
    }

    /**
     * Must run every tick regardless of which subsystem owns it (see the flush block in
     * NexoraHpMod.onClientTick) -- an orphaned press corrupts whoever uses the key next.
     */
    static void flushRelease(Minecraft client) {
        if (releasePending) {
            releasePending = false;
            Input.releaseUse(client);
        }
    }

    static void tick(Minecraft client, LocalPlayer player) {
        bossNearby = false;
        holdingKatana = SkyblockItems.isKatana(player.getInventory().getSelectedItem());

        if (!NexoraHpConfig.autoSoulcryEnabled || NexoraHpMod.panicActive) {
            delayTicks = 0;
            return;
        }

        // The boss scan runs before the held-item gate (not after, which would be cheaper) so
        // the HUD can show "you're at the boss but not holding a katana" as its own state.
        bossNearby = !EntityScan.namedArmorStands(client, player, SCAN_RADIUS,
                name -> name.contains(VOIDGLOOM_BOSS_NAME)).isEmpty();
        if (!bossNearby || !holdingKatana) {
            delayTicks = 0;
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        Input.pressUse(client);
        releasePending = true;
        delayTicks = RETRY_DELAY_TICKS;
    }
}
