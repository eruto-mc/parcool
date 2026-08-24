package com.alrex.parcool.api;

import com.alrex.parcool.ParCool;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class Attributes {
    private static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(ForgeRegistries.ATTRIBUTES, ParCool.MOD_ID);
    public static final RegistryObject<Attribute> MAX_STAMINA = ATTRIBUTES.register("max_stamina", () -> new RangedAttribute("parcool.max_stamina", 2000, 10, 10000).setSyncable(true));
    public static final RegistryObject<Attribute> STAMINA_RECOVERY = ATTRIBUTES.register("stamina_recovery", () -> new RangedAttribute("parcool.stamina_recovery", 20, 0, 10000).setSyncable(true));

    /**
     * Minecraft-bu (eruto) patch: how strongly this player runs up walls.
     *
     * <p>Vertical Wall Run gives one upward push at the start
     * ({@code 0.32 * sqrt(wallHeight)}) and then coasts; the height you reach is
     * decided by that single push. Cliff-climbing races (Arachnae, Feline) need a
     * bigger push and a shorter cooldown, which upstream has as hardcoded numbers.
     *
     * <p>This attribute scales both, so Origins can set it per race with a plain
     * {@code origins:attribute} power. 1.0 keeps vanilla ParCool behaviour.
     */
    public static final RegistryObject<Attribute> WALL_CLIMB = ATTRIBUTES.register("wall_climb", () -> new RangedAttribute("parcool.wall_climb", 1.0, 0.1, 10.0).setSyncable(true));

    public static void registerAll(IEventBus bus) {
        ATTRIBUTES.register(bus);
    }
}
