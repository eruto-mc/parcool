package com.alrex.parcool.common.handlers;


import com.alrex.parcool.api.Attributes;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class AddAttributesHandler {
    @SubscribeEvent
    public static void onAddAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, Attributes.MAX_STAMINA.get());
        event.add(EntityType.PLAYER, Attributes.STAMINA_RECOVERY.get());
        // Minecraft-bu (eruto) patch: must be added here too, or
        // getAttributeValue(WALL_CLIMB) throws on every player.
        event.add(EntityType.PLAYER, Attributes.WALL_CLIMB.get());
    }
}
