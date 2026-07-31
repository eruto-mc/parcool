package com.alrex.parcool.common.action.impl;

import com.alrex.parcool.client.animation.impl.FastRunningAnimator;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.client.input.KeyRecorder;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.AdditionalProperties;
import com.alrex.parcool.common.action.BehaviorEnforcer;
import com.alrex.parcool.common.action.StaminaConsumeTiming;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.info.ActionInfo;
import com.alrex.parcool.config.ParCoolConfig;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.ByteBuffer;
import java.util.UUID;

public class FastRun extends Action {
	public enum ControlType {
		PressKey, Toggle, Auto
	}

	private static final BehaviorEnforcer.ID ID_SPRINT_CANCEL = BehaviorEnforcer.newID();

	private static final String FAST_RUNNING_MODIFIER_NAME = "parcool.modifier.fast_run";
	private static final UUID FAST_RUNNING_MODIFIER_UUID = UUID.randomUUID();
	private double speedModifier = 0;
	private boolean toggleStatus = false;
	private int lastDashTick = 0;

	public double getSpeedModifier(ActionInfo info) {
		return Math.min(
				info.getClientSetting().get(ParCoolConfig.Client.Doubles.FastRunSpeedModifier),
				info.getServerLimitation().get(ParCoolConfig.Server.Doubles.MaxFastRunSpeedModifier)
		);
	}

	@Override
	public void onServerTick(Player player, Parkourability parkourability, IStamina stamina) {
		var attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (attr == null) return;
		if (attr.getModifier(FAST_RUNNING_MODIFIER_UUID) != null) attr.removeModifier(FAST_RUNNING_MODIFIER_UUID);
		if (isDoing()) {
			player.setSprinting(true);
			attr.addTransientModifier(new AttributeModifier(
					FAST_RUNNING_MODIFIER_UUID,
					FAST_RUNNING_MODIFIER_NAME,
					speedModifier / 100d,
					AttributeModifier.Operation.ADDITION
			));
		}
	}

	@Override
	public void onClientTick(Player player, Parkourability parkourability, IStamina stamina) {
		if (player.isLocalPlayer()) {
			if (ParCoolConfig.Client.FastRunControl.get() == ControlType.Toggle
					&& parkourability.getAdditionalProperties().getSprintingTick() > 3
			) {
				if (KeyRecorder.keyFastRunning.isPressed())
					toggleStatus = !toggleStatus;
			} else {
				toggleStatus = false;
			}
		}
	}

	@Override
	public StaminaConsumeTiming getStaminaConsumeTiming() {
		return StaminaConsumeTiming.OnWorking;
	}

	@Override
	public boolean canStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		return canContinue(player, parkourability, stamina);
	}

	@Override
	public boolean canContinue(Player player, Parkourability parkourability, IStamina stamina) {
		return (!stamina.isExhausted()
                && !player.isInWaterOrBubble()
				&& player.getVehicle() == null
				&& !player.isFallFlying()
				&& player.isSprinting()
				&& !player.isVisuallyCrawling()
				&& !player.isSwimming()
				&& !player.isShiftKeyDown()
				&& !parkourability.get(Crawl.class).isDoing()
				&& !parkourability.get(ClingToCliff.class).isDoing()
				&& !parkourability.get(HangDown.class).isDoing()
                && !parkourability.get(RideZipline.class).isDoing()
                && !parkourability.get(HideInBlock.class).isDoing()
				&& ((ParCoolConfig.Client.FastRunControl.get() == ControlType.PressKey && KeyBindings.getKeyFastRunning().isDown())
				|| (ParCoolConfig.Client.FastRunControl.get() == ControlType.Toggle && toggleStatus)
				|| ParCoolConfig.Client.FastRunControl.get() == ControlType.Auto)
		);
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public void onStartInLocalClient(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startData) {
		// バニラは水平方向の衝突でスプリントを解除する（LocalPlayer.aiStep の horizontalCollision 判定）。
		// canContinue() が player.isSprinting() を要求しているため、壁に軽く当たった・段差を乗り越えた
		// だけで高速走行が途切れ、Vault の前提（canActWithRunning）まで連鎖して崩れていた。
		// 「前進を押し続けている限りは解除させない」マーカーを張ってこれを止める。
		//
		// なお解除を止めるのは isDoing() が続く間だけなので、
		//   スタミナ切れ  → canContinue() が false → isDoing() false → マーカーが外れる
		//   前進を離す    → 条件が false → マーカーが外れる（バニラ同様その場で走りが終わる）
		// と、止めるべき場面ではバニラの解除がそのまま働く。
		parkourability.getBehaviorEnforcer().addMarkerCancellingSprint(
				ID_SPRINT_CANCEL,
				() -> this.isDoing()
						&& KeyBindings.isKeyForwardDown()
						&& !player.isShiftKeyDown()
						&& !player.isInWaterOrBubble()
		);
	}

	@Override
	public void onWorkingTickInClient(Player player, Parkourability parkourability, IStamina stamina) {
		Animation animation = Animation.get(player);
		if (animation != null && !animation.hasAnimator()) {
			animation.setAnimator(new FastRunningAnimator());
		}
	}

	@Override
	public void onStartInServer(Player player, Parkourability parkourability, ByteBuffer startData) {
		speedModifier = getSpeedModifier(parkourability.getActionInfo());
	}

	@Override
	public void onStopInLocalClient(Player player) {
		Parkourability parkourability = Parkourability.get(player);
		if (parkourability == null) return;
		lastDashTick = getDashTick(parkourability.getAdditionalProperties());
	}

	@OnlyIn(Dist.CLIENT)
	public boolean canActWithRunning(Player player) {
		return ParCoolConfig.Client.Booleans.SubstituteSprintForFastRun.get() ? player.isSprinting() : this.isDoing();
	}

	//return sprinting tick if substitute sprint is on
	@OnlyIn(Dist.CLIENT)
	public int getDashTick(AdditionalProperties properties) {
		return ParCoolConfig.Client.Booleans.SubstituteSprintForFastRun.get() ? properties.getSprintingTick() : this.getDoingTick();
	}

	@OnlyIn(Dist.CLIENT)
	public int getNotDashTick(AdditionalProperties properties) {
		return ParCoolConfig.Client.Booleans.SubstituteSprintForFastRun.get() ? properties.getNotSprintingTick() : this.getNotDoingTick();
	}

	public int getLastDashTick() {
		return lastDashTick;
	}
}
