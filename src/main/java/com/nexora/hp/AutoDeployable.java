package com.nexora.hp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Auto deployable: when a slayer quest's kill bar fills, the sidebar's quest line flips from
 * "x/y Kills" to "Slay the boss!" right as the (slayer-agnostic) spawn animation starts --
 * confirmed via /dumpscoreboard captures of both phases. On that rising edge, place whatever orb
 * or flare is in the hotbar: switch to it, look straight down, right-click, then restore view
 * and slot. One-shot per boss: the latch only rearms once the line disappears ("Boss slain!" /
 * quest over), so a missing deployable doesn't retrigger every tick either.
 */
final class AutoDeployable {

    private static final String BOSS_SPAWN_LINE = "Slay the boss!";

    private static boolean bossSpawnLatched = false;
    private static boolean releasePending = false;
    private static int originalSlot = -1;
    private static float originalPitch = 0f;

    private AutoDeployable() {
    }

    /**
     * Must run every tick regardless of which subsystem owns it (see the flush block in
     * NexoraHpMod.onClientTick): releases the click and puts the player's view and hotbar slot
     * back no matter who claims the tick.
     */
    static void flushRelease(Minecraft client, LocalPlayer player) {
        if (releasePending) {
            releasePending = false;
            Input.releaseUse(client);
            View.setPitch(player, originalPitch);
            if (originalSlot >= 0) {
                Input.clickSlot(client, originalSlot);
                originalSlot = -1;
            }
        }
    }

    static void tick(Minecraft client, LocalPlayer player) {
        boolean bossSpawning = Sidebar.hasLine(client, BOSS_SPAWN_LINE);
        boolean spawnEdge = bossSpawning && !bossSpawnLatched;
        bossSpawnLatched = bossSpawning;

        if (!spawnEdge || !NexoraHpConfig.autoDeployableEnabled || NexoraHpMod.panicActive || releasePending) {
            return;
        }

        int deploySlot = SkyblockItems.findDeployableSlot(player.getInventory());
        if (deploySlot < 0) {
            return;
        }

        int selectedSlot = player.getInventory().getSelectedSlot();
        originalSlot = selectedSlot == deploySlot ? -1 : selectedSlot;
        if (originalSlot >= 0) {
            Input.clickSlot(client, deploySlot);
        }
        originalPitch = View.lookDown(player);
        Input.pressUse(client);
        releasePending = true;
    }
}
