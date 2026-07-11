package com.nexora.hp;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The one place that turns decisions into synthetic input. Everything the mod does goes through
 * the same KeyMapping state the game's own mouse/keyboard handlers set on a real press -- never
 * packets or direct inventory/state manipulation -- so behavior is identical to a player
 * physically pressing the keys, in singleplayer and on servers alike.
 */
final class Input {

    // KeyMapping has no public getter for its currently bound key, so we read the "key" field
    // reflectively to simulate a press of whatever key/button the player has the action bound to
    // (instead of hardcoding a keycode).
    private static final Field KEY_MAPPING_KEY_FIELD;

    static {
        try {
            KEY_MAPPING_KEY_FIELD = KeyMapping.class.getDeclaredField("key");
            KEY_MAPPING_KEY_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Input() {
    }

    private static InputConstants.Key boundKey(KeyMapping mapping) {
        try {
            return (InputConstants.Key) KEY_MAPPING_KEY_FIELD.get(mapping);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts holding right-click. Always pair with {@link #releaseUse} on a later tick -- and make
     * sure that release runs unconditionally (see the flush blocks in NexoraHpMod.onClientTick),
     * or another subsystem claiming the next tick orphans the key in the pressed state.
     */
    static void pressUse(Minecraft client) {
        KeyMapping.set(boundKey(client.options.keyUse), true);
    }

    static void releaseUse(Minecraft client) {
        KeyMapping.set(boundKey(client.options.keyUse), false);
    }

    /** One left click, exactly like a single physical attack press. */
    static void clickAttack(Minecraft client) {
        KeyMapping.click(boundKey(client.options.keyAttack));
    }

    /** One press of the given hotbar slot's key (0-8). */
    static void clickSlot(Minecraft client, int slot) {
        KeyMapping.click(boundKey(client.options.keyHotbarSlots[slot]));
    }

    /** Whether the player is physically holding the attack button right now. */
    static boolean attackKeyDown(Minecraft client) {
        return client.options.keyAttack.isDown();
    }
}
