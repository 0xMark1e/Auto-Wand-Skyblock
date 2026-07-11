package com.nexora.hp;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Auto-cake: gifted cakes show up as an invisible armor stand wearing the cake slice as its head
 * equipment, stacked with a "From: <sender>" nametag and, for whichever cake is actually
 * addressed to you, a "CLICK TO EAT" nametag in place of the usual "To: <name>" one (confirmed
 * via entity dumps, now /dumpentities -- other players' pending cakes show "To: <name>" instead,
 * so Hypixel already filters recipiency server-side and no username matching is needed).
 *
 * While one is in range, turns the view towards it and taps attack (left click) every few ticks
 * -- exactly what a player would do by hand -- until it's gone (collected) or out of range. The
 * click only lands if the crosshair is actually on the stand, hence the turn. Skipped during
 * panic heal so the two don't fight over the keys on the same tick.
 */
final class AutoCake {

    private static final String CLICK_TO_EAT_TEXT = "CLICK TO EAT";
    private static final double SCAN_RADIUS = 4.0;
    private static final int RETRY_DELAY_TICKS = 5;
    private static final float TURN_SPEED_DEGREES = 12.0f;
    private static final float AIM_TOLERANCE_DEGREES = 4.0f;

    private static int retryDelayTicks = 0;

    private AutoCake() {
    }

    static void tick(Minecraft client, LocalPlayer player) {
        if (!NexoraHpConfig.autoCakeEnabled || NexoraHpMod.panicActive) {
            retryDelayTicks = 0;
            return;
        }

        List<ArmorStand> stands = EntityScan.namedArmorStands(client, player, SCAN_RADIUS,
                CLICK_TO_EAT_TEXT::equals);
        if (stands.isEmpty()) {
            retryDelayTicks = 0;
            return;
        }

        if (!View.turnTowards(player, stands.get(0).getEyePosition(), TURN_SPEED_DEGREES,
                AIM_TOLERANCE_DEGREES)) {
            return;
        }

        if (retryDelayTicks > 0) {
            retryDelayTicks--;
            return;
        }

        Input.clickAttack(client);
        retryDelayTicks = RETRY_DELAY_TICKS;
    }
}
