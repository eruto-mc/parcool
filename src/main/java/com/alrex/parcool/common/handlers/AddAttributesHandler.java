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
        //
        // Registering an attribute only puts it in the registry; a player does not
        // carry it until it is added here. Datapacks hit this first: Origins reads
        // the modifier's attribute off the player and reports "Can't find attribute
        // parcool:<name>", which disconnects the client on world join - not at
        // startup, and not during the handshake, so nothing earlier catches it.
        // Every attribute declared in api/Attributes needs a line here.
        event.add(EntityType.PLAYER, Attributes.WALL_CLIMB.get());
        event.add(EntityType.PLAYER, Attributes.WALL_CLIMB_CHAIN.get());
        event.add(EntityType.PLAYER, Attributes.PARKOUR.get());
        event.add(EntityType.PLAYER, Attributes.DODGE_DISTANCE.get());
        event.add(EntityType.PLAYER, Attributes.DODGE_RECOVERY.get());
    }
}
