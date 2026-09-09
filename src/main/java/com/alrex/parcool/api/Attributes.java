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

    /**
     * Minecraft-bu (eruto) patch: whether this player can restart a Vertical Wall
     * Run in mid-air.
     *
     * <p>Upstream gates the action on a real ground jump 5-12 ticks earlier
     * ({@code LivingJumpEvent} is the only thing that resets that counter, and
     * Wall Jump sets velocity directly rather than jumping), so a wall run can
     * never be chained while airborne - you get one push and then fall. That is
     * the right default, but our wall-climbing races want to keep going.
     *
     * <p>At 1.0 a fresh press of the jump key while airborne also opens the
     * window. The existing {@code |vertical speed| <= height/5} check stays, so
     * the press only lands near the apex of the previous climb - holding jump
     * does nothing. Wall Slide is already excluded by {@code canStart}, so
     * holding the wall-slide key still slides instead of climbing.
     *
     * <p>0.0 (default) keeps upstream behaviour.
     */
    public static final RegistryObject<Attribute> WALL_CLIMB_CHAIN = ATTRIBUTES.register("wall_climb_chain", () -> new RangedAttribute("parcool.wall_climb_chain", 0.0, 0.0, 1.0).setSyncable(true));

    /**
     * Minecraft-bu (eruto) patch: whether this player may use ParCool at all.
     *
     * <p>Upstream decides this per server ({@code permit_*}) or per client
     * ({@code can_*}); there is no per-player switch, so a race that should not
     * vault and wall-run (our Golem) had no way to be told apart.
     *
     * <p>{@code ActionProcessor.checkAndChangeActionState} is the single gate every
     * action passes through, start and continue alike, so one check there covers
     * all of them. 1.0 = normal, 0.0 = no ParCool.
     */
    public static final RegistryObject<Attribute> PARKOUR = ATTRIBUTES.register("parkour", () -> new RangedAttribute("parcool.parkour", 1.0, 0.0, 1.0).setSyncable(true));

    /**
     * Minecraft-bu (eruto) patch: how far this player's Dodge carries them.
     *
     * <p>Dodge sets one flat horizontal velocity ({@code 0.9 * speedModifier}) and
     * then coasts on vanilla ground friction, so that single impulse decides the
     * whole distance - the 11-tick length never truncates it (what is left after
     * 11 ticks is 0.13% of the initial speed).
     *
     * <p>Upstream exposes the modifier only as a client config capped by a server
     * limitation, and limitations can merely tighten ({@code Math.min}), so there
     * was no way to give one race a longer dodge without handing everyone the
     * higher cap first.
     *
     * <p>This multiplies on top of the config, so the server cap keeps its meaning
     * and Origins can set it per race with a plain {@code origins:attribute}.
     * 1.0 keeps vanilla ParCool behaviour.
     */
    public static final RegistryObject<Attribute> DODGE_DISTANCE = ATTRIBUTES.register("dodge_distance", () -> new RangedAttribute("parcool.dodge_distance", 1.0, 0.1, 5.0).setSyncable(true));

    /**
     * Minecraft-bu (eruto) patch: how soon this player may dodge again.
     *
     * <p>The base cooldown is not the limit and cannot become one: its config floor
     * equals {@code Dodge.MAX_TICK}, so it always expires exactly as the dodge
     * ends. What actually spaces dodges out is the successive-dodge gate - after
     * {@code successive_dodge_count} dodges you are locked out for
     * {@code successive_dodge_cool_time} ticks.
     *
     * <p>This divides that lockout, so a nimble race recovers sooner. 1.0 keeps
     * vanilla ParCool behaviour.
     */
    public static final RegistryObject<Attribute> DODGE_RECOVERY = ATTRIBUTES.register("dodge_recovery", () -> new RangedAttribute("parcool.dodge_recovery", 1.0, 0.1, 10.0).setSyncable(true));

    /**
     * Minecraft-bu (eruto) patch: whether this player's breakfall always counts as
     * just-timed.
     *
     * <p>Upstream decides "just" by how recently the breakfall was readied
     * ({@code BreakfallReady.getDoingTick() < JustTimeBreakfallTick}), which is a
     * timing check the player either hits or misses. A just-timed breakfall removes
     * fall damage up to 1.34x the normal height and multiplies the rest by 0.66.
     *
     * <p>At 1.0 the timing check is skipped and the breakfall is always just-timed.
     * <b>Whether a breakfall happens at all is untouched</b> - the readiness check
     * ({@code BreakfallReady.isReadyInput}) still decides that, so a player who does
     * not press (or, in Auto control, does not hold a movement key) still takes the
     * fall normally.
     *
     * <p>0.0 (default) keeps upstream behaviour.
     */
    public static final RegistryObject<Attribute> BREAKFALL_JUST = ATTRIBUTES.register("breakfall_just", () -> new RangedAttribute("parcool.breakfall_just", 0.0, 0.0, 1.0).setSyncable(true));

    public static void registerAll(IEventBus bus) {
        ATTRIBUTES.register(bus);
    }
}
