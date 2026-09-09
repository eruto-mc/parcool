package com.alrex.parcool.common.handlers;

import com.alrex.parcool.api.unstable.action.ParCoolActionEvent;
import com.alrex.parcool.common.action.impl.*;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.network.StartBreakfallMessage;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.utilities.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class PlayerDamageHandler {
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player player) {
            Parkourability parkourability = Parkourability.get(player);
            if (parkourability == null) return;
            Dodge dodge = parkourability.get(Dodge.class);
            if (dodge.isDoing()) {
                if (!parkourability.getServerLimitation().get(ParCoolConfig.Server.Booleans.DodgeProvideInvulnerableFrame))
                    return;
                if (event.getSource().is(DamageTypeTags.BYPASSES_ARMOR)) return;
                if (dodge.getDoingTick() <= 10) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {

            Parkourability parkourability = Parkourability.get(player);
            if (parkourability == null) return;

			if (parkourability.get(BreakfallReady.class).isDoing()
					&& (parkourability.getActionInfo().can(Tap.class)
					|| parkourability.getActionInfo().can(Roll.class))
			) {
				// Minecraft-bu (eruto) patch: parcool.breakfall_just makes every breakfall
				// count as just-timed. Only the timing check is skipped; whether a
				// breakfall happens at all is still BreakfallReady.isReadyInput's call,
				// so a player who does not ready one still takes the fall normally.
				boolean erutoAlwaysJust = player.getAttributeValue(
						com.alrex.parcool.api.Attributes.BREAKFALL_JUST.get()) >= 0.5;
				boolean justTime = erutoAlwaysJust
						|| parkourability.get(BreakfallReady.class).getDoingTick() < parkourability.getLimitedValue(
						ParCoolConfig.Client.Integers.JustTimeBreakfallTick,
						ParCoolConfig.Server.Integers.MaxJustTimeBreakfallTick
				);
				float distance = event.getDistance();
				if (distance > parkourability.getLimitedValue(
						ParCoolConfig.Client.Doubles.LowestFallDistanceForBreakfall,
						ParCoolConfig.Server.Doubles.MinLowestFallDistanceForBreakfall
				)) {
					StartBreakfallMessage.send(player, justTime);
				} else {
					return;
				}
				double damageRemoveHeight = parkourability.getLimitedValue(
						ParCoolConfig.Client.Doubles.DamageCompleteRemovableHeightBreakfall,
						ParCoolConfig.Server.Doubles.MaxDamageCompleteRemovableHeightBreakfall
				);
				if (distance < damageRemoveHeight || (justTime && distance < damageRemoveHeight * 1.34)) {
					event.setCanceled(true);
				} else {
					float damageReductionRate = (float) parkourability.getLimitedValue(
							ParCoolConfig.Client.Doubles.DamageReductionRateBreakfall,
							ParCoolConfig.Server.Doubles.MaxDamageReductionRateBreakfall
					);
					event.setDamageMultiplier(event.getDamageMultiplier() * (justTime ? 0.66f * damageReductionRate : damageReductionRate));
				}
			} else {
				HideInBlock hideInBlock = parkourability.get(HideInBlock.class);
				if (hideInBlock.isStandbyInAir(parkourability)
						&& parkourability.getActionInfo().can(HideInBlock.class)
						&& !MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.TryToStartEvent(player, hideInBlock))
						&& !MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.TryToStart(player, hideInBlock))
				) {
					Tuple<BlockPos, BlockPos> area = WorldUtil.getHideAbleSpace(player, new BlockPos(player.blockPosition().below()));
					if (area != null) {
						boolean stand = player.getBbHeight() < (Math.abs(area.getB().getY() - area.getA().getY()) + 1);
						if (!stand) {
							if (event.getDistance() < 10) {
								event.setCanceled(true);
							} else {
								event.setDamageMultiplier(event.getDamageMultiplier() * 0.4f);
							}
						}
					}
				}
			}
		} else if (event.getEntity() instanceof Player player) {
			if (!player.isLocalPlayer()) {
				return;
			}
			Parkourability parkourability = Parkourability.get(player);
			if (parkourability == null) return;
			if (parkourability.getAdditionalProperties().getNotLandingTick() > 5 && event.getDistance() < 0.4f) {
				parkourability.get(ChargeJump.class).onLand(player, parkourability);
			}
		}
	}
}
