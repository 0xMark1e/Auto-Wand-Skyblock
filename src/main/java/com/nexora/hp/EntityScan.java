package com.nexora.hp;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * Hypixel communicates most boss/prop state through invisible named armor stands (attunement
 * requirements, gift prompts, boss nametags). This is the shared "find those stands" scan every
 * feature builds its detection on.
 */
final class EntityScan {

    private EntityScan() {
    }

    /** Named armor stands within the radius whose custom-name text passes the given test. */
    static List<ArmorStand> namedArmorStands(Minecraft client, LocalPlayer player, double radius,
            Predicate<String> nameTest) {
        AABB box = player.getBoundingBox().inflate(radius);
        return client.level.getEntities(EntityTypeTest.forClass(ArmorStand.class), box,
                stand -> stand.hasCustomName() && nameTest.test(stand.getCustomName().getString()));
    }
}
