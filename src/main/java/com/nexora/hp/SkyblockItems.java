package com.nexora.hp;

import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Skyblock item identification. Every custom Hypixel item carries its internal ID in NBT, and
 * features decide what to do purely off those IDs -- never display names, which change with
 * reforges and formatting.
 */
final class SkyblockItems {

    static final String SWORD_ID = "FLORID_ZOMBIE_SWORD";
    static final String FIRE_DAGGER_ID = "HEARTFIRE_DAGGER";
    static final String TWILIGHT_DAGGER_ID = "HEARTMAW_DAGGER";
    static final String RAGNAROCK_ID = "RAGNAROCK_AXE";
    static final String WAND_ID_PREFIX = "WAND_OF_";
    static final Set<String> KATANA_IDS = Set.of("VOIDEDGE_KATANA", "VOIDWALKER_KATANA", "VORPAL_KATANA");

    private SkyblockItems() {
    }

    /** Reads the Skyblock internal item ID (ExtraAttributes.id NBT tag) from an item stack. */
    static String id(ItemStack stack) {
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

    /** Hotbar slot (0-8) of the item with this exact internal ID, or -1. */
    static int findSlot(Inventory inventory, String id) {
        for (int slot = 0; slot < 9; slot++) {
            if (id.equals(id(inventory.getItem(slot)))) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Scans the hotbar for an item whose internal ID starts with "WAND_OF_" -- there are several
     * wand variants (Wand of Restoration, Wand of Mending, ...) that all share this prefix, so
     * it's a prefix match rather than an exact one. This replaces the old fixed-slot config: the
     * wand can sit in whatever hotbar slot the player likes.
     */
    static int findWandSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (id(inventory.getItem(slot)).startsWith(WAND_ID_PREFIX)) {
                return slot;
            }
        }
        return -1;
    }

    /** Exact-ID match: unlike the wand, there's only the one "Instant Heal" sword variant so far. */
    static int findSwordSlot(Inventory inventory) {
        return findSlot(inventory, SWORD_ID);
    }

    /** Any orb or flare tier in the hotbar -- suffix match so every tier of both families counts. */
    static int findDeployableSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            String id = id(inventory.getItem(slot));
            if (id.endsWith("_POWER_ORB") || id.endsWith("_FLARE")) {
                return slot;
            }
        }
        return -1;
    }

    static boolean isRagnarock(ItemStack stack) {
        return RAGNAROCK_ID.equals(id(stack));
    }

    static boolean isKatana(ItemStack stack) {
        return KATANA_IDS.contains(id(stack));
    }

    /**
     * Reads a Blaze dagger's current toggle form off its "td_attune_mode" NBT tag. This used to
     * read the item's vanilla material (documented as swapping between Stone/Golden/Iron/Diamond
     * Sword), but item dumps (now /dumpitem) proved that's stale -- the material never actually
     * changes anymore, and Hypixel signals the mode through this dedicated tag instead.
     */
    static String daggerMode(ItemStack stack) {
        CustomData customData = stack.getComponents().get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return null;
        }
        CompoundTag tag = customData.copyTag();
        if (!tag.contains("td_attune_mode")) {
            return null;
        }
        return AttunementController.daggerModeAttunement(tag.getIntOr("td_attune_mode", -1));
    }
}
