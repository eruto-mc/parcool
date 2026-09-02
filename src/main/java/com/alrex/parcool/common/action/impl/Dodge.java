package com.alrex.parcool.common.action.impl;

import com.alrex.parcool.api.Attributes;
import com.alrex.parcool.api.SoundEvents;
import com.alrex.parcool.client.animation.impl.DodgeAnimator;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.client.input.KeyRecorder;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.BehaviorEnforcer;
import com.alrex.parcool.common.action.StaminaConsumeTiming;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.info.ActionInfo;
import com.alrex.parcool.common.registries.ParCoolPoses;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.extern.AdditionalMods;
import com.alrex.parcool.utilities.VectorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.ByteBuffer;

public class Dodge extends Action {
	public static final int MAX_TICK = 11;
	private static final BehaviorEnforcer.ID ID_JUMP_CANCEL = BehaviorEnforcer.newID();
	private static final BehaviorEnforcer.ID ID_DESCEND_EDGE = BehaviorEnforcer.newID();

	private static int getMaxCoolTime(ActionInfo info) {
		return Math.max(
				info.getClientSetting().get(ParCoolConfig.Client.Integers.DodgeCoolTime),
				info.getServerLimitation().get(ParCoolConfig.Server.Integers.DodgeCoolTime)
		);
	}

	private static int getMaxSuccessiveDodge(ActionInfo info) {
		return Math.min(
				info.getClientSetting().get(ParCoolConfig.Client.Integers.MaxSuccessiveDodgeCount),
				info.getServerLimitation().get(ParCoolConfig.Server.Integers.MaxSuccessiveDodgeCount)
		);
	}

	// Minecraft-bu (eruto) patch: divide the successive-dodge lockout by our
	// attribute. This gate - not the base cooldown - is what spaces dodges out:
	// getMaxCoolTime cannot fall below Dodge.MAX_TICK (that is the config floor),
	// so it always expires exactly as the dodge ends and never delays anything.
	private static int getSuccessiveCoolTime(Player player, ActionInfo info) {
		int base = Math.max(
				info.getClientSetting().get(ParCoolConfig.Client.Integers.SuccessiveDodgeCoolTime),
				info.getServerLimitation().get(ParCoolConfig.Server.Integers.SuccessiveDodgeCoolTime)
		);
		return (int) Math.ceil(base / player.getAttributeValue(Attributes.DODGE_RECOVERY.get()));
	}

	public enum DodgeDirection {
		Front, Back, Left, Right;

		public DodgeDirection inverse() {
			switch (this) {
				case Front:
					return Back;
				case Back:
					return Front;
				case Left:
					return Right;
				case Right:
					return Left;
			}
			return Front;
		}

		public DodgeDirection right() {
			switch (this) {
				case Front:
					return Right;
				case Right:
					return Back;
				case Back:
					return Left;
				case Left:
					return Front;
			}
			return Front;
		}

		public DodgeDirection left() {
			switch (this) {
				case Front:
					return Left;
				case Left:
					return Back;
				case Back:
					return Right;
				case Right:
					return Front;
			}
			return Front;
		}
	}

	/**
	 * Minecraft-bu (eruto) patch: the move vector for a dodge direction, built
	 * the same way {@code KeyBindings.getCurrentMoveVector()} builds it from
	 * {@code player.input} - x carries the left impulse (A is +1), z the forward
	 * one (W is +1).
	 */
	@OnlyIn(Dist.CLIENT)
	private static Vec3 toMoveVector(DodgeDirection direction) {
		return switch (direction) {
			case Front -> new Vec3(0, 0, 1);
			case Back -> new Vec3(0, 0, -1);
			case Left -> new Vec3(1, 0, 0);
			case Right -> new Vec3(-1, 0, 0);
		};
	}

	private DodgeDirection dodgeDirection = null;
	private int coolTime = 0;
	private int successivelyCount = 0;
	private int successivelyCoolTick = 0;
	// Minecraft-bu (eruto) patch: the lockout length actually used at the last
	// start. The HUD needs it to draw the bar, and it is per-player now, so it
	// cannot be recomputed from the config alone. Only read while
	// isInSuccessiveCoolDown() is true, which cannot happen before the first
	// dodge has set it - so it is never divided by zero.
	private int successivelyCoolTickMax = 0;

	@OnlyIn(Dist.CLIENT)
	@Override
	public void onClientTick(Player player, Parkourability parkourability, IStamina stamina) {
		if (coolTime > 0) coolTime--;
		if (successivelyCoolTick > 0) {
			successivelyCoolTick--;
		} else {
			successivelyCount = 0;
		}
	}

	@Override
	public StaminaConsumeTiming getStaminaConsumeTiming() {
		return StaminaConsumeTiming.OnStart;
	}

	public double getSpeedModifier(ActionInfo info) {
		return Math.min(
				info.getClientSetting().get(ParCoolConfig.Client.Doubles.DodgeSpeedModifier),
				info.getServerLimitation().get(ParCoolConfig.Server.Doubles.MaxDodgeSpeedModifier)
		);
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public boolean canStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		boolean enabledDoubleTap = ParCoolConfig.Client.Booleans.EnableDoubleTappingForDodge.get();
		DodgeDirection direction = null;
		var dodgeVec = KeyRecorder.getLastMoveVector();
		if (enabledDoubleTap) {
			if (KeyRecorder.keyBack.isDoubleTapped()) direction = DodgeDirection.Back;
			if (KeyRecorder.keyLeft.isDoubleTapped()) direction = DodgeDirection.Left;
			if (KeyRecorder.keyRight.isDoubleTapped()) direction = DodgeDirection.Right;
		}
		if (direction == null && KeyRecorder.keyDodge.isPressed()) {
			if (KeyBindings.isKeyBackDown()) direction = DodgeDirection.Back;
			if (KeyBindings.isKeyForwardDown()) direction = DodgeDirection.Front;
			if (KeyBindings.isKeyLeftDown()) direction = DodgeDirection.Left;
			if (KeyBindings.isKeyRightDown()) direction = DodgeDirection.Right;
			if (direction != null) dodgeVec = KeyBindings.getCurrentMoveVector();
		}
		if (direction == null || dodgeVec == null) return false;
		// マイクラ部（eruto）のパッチ: 飛ぶ向きは押したキーから組み直す。
		// KeyBindings 側でキーの押下を先に見るようにしたので direction は正しいが、
		// dodgeVec は player.input 由来のままで、Better Third Person が書き換えた
		// 途中の値（半円を描くために回している最中の向き）が入っている。
		// ⚠ 下の handleCustomCameraRotationForDodge が direction を Front へ潰すので、
		//    組み直すのはその手前でなければならない。
		dodgeVec = toMoveVector(direction);
		direction = AdditionalMods.betterThirdPerson().handleCustomCameraRotationForDodge(direction);
		direction = AdditionalMods.shoulderSurfing().handleCustomCameraRotationForDodge(direction);
		startInfo.putInt(direction.ordinal());
		startInfo.putDouble(dodgeVec.x);
		startInfo.putDouble(dodgeVec.z);
		return ((parkourability.getAdditionalProperties().getLandingTick() > 5 || parkourability.getAdditionalProperties().getPreviousNotLandingTick() < 2)
				&& player.onGround()
				&& !isInSuccessiveCoolDown(parkourability.getActionInfo())
				&& coolTime <= 0
				&& !player.isInWaterOrBubble()
				&& player.onGround()
                && !player.isInWaterOrBubble()
				&& !player.isShiftKeyDown()
				&& !stamina.isExhausted()
				&& !parkourability.get(Crawl.class).isDoing()
				&& !parkourability.get(Roll.class).isDoing()
				&& !parkourability.get(Tap.class).isDoing()
		);
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public boolean canContinue(Player player, Parkourability parkourability, IStamina stamina) {
		return !(parkourability.get(Roll.class).isDoing()
				|| parkourability.get(ClingToCliff.class).isDoing()
				|| getDoingTick() >= MAX_TICK
				|| player.isInWaterOrBubble()
				|| player.isFallFlying()
				|| player.getAbilities().flying
		);
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public void onStartInLocalClient(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startData) {
		dodgeDirection = DodgeDirection.values()[startData.getInt()];
		var dodgeVec = new Vec3(startData.getDouble(), 0, startData.getDouble());
		coolTime = getMaxCoolTime(parkourability.getActionInfo());
		if (successivelyCount < getMaxSuccessiveDodge(parkourability.getActionInfo())) {
			successivelyCount++;
		}
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get()) {
			player.playSound(SoundEvents.DODGE.get(), 1f, 1f);
		}
		successivelyCoolTick = getSuccessiveCoolTime(player, parkourability.getActionInfo());
		successivelyCoolTickMax = successivelyCoolTick;

		if (!player.onGround()) return;
		var cameraEntity = Minecraft.getInstance().getCameraEntity();
		var cameraYRot = cameraEntity != null ? cameraEntity.getYRot() : 0;
		dodgeVec = VectorUtil.rotateYDegrees(dodgeVec, cameraYRot);
		// Minecraft-bu (eruto) patch: scale the one impulse that decides the
		// distance. Multiplied on top of the config so the server cap still means
		// what it says; see api/Attributes#DODGE_DISTANCE.
		dodgeVec = dodgeVec.scale(0.9 * getSpeedModifier(parkourability.getActionInfo())
				* player.getAttributeValue(Attributes.DODGE_DISTANCE.get()));
		if (AdditionalMods.isCameraDecoupled()) player.setYRot(VectorUtil.toYaw(dodgeVec));
		player.setDeltaMovement(dodgeVec);

		Animation animation = Animation.get(player);
		if (animation != null) animation.setAnimator(new DodgeAnimator(dodgeDirection));
		parkourability.getBehaviorEnforcer().addMarkerCancellingJump(ID_JUMP_CANCEL, this::isDoing);
		if (!parkourability.getClientInfo().get(ParCoolConfig.Client.Booleans.CanGetOffStepsWhileDodge)) {
			parkourability.getBehaviorEnforcer().addMarkerCancellingDescendFromEdge(ID_DESCEND_EDGE, this::isDoing);
		}
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public void onStartInOtherClient(Player player, Parkourability parkourability, ByteBuffer startData) {
		dodgeDirection = DodgeDirection.values()[startData.getInt()];
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
			player.playSound(SoundEvents.DODGE.get(), 1f, 1f);
		Animation animation = Animation.get(player);
		if (animation != null) animation.setAnimator(new DodgeAnimator(dodgeDirection));
	}

	public int getCoolTime() {
		return coolTime;
	}

	public int getSuccessivelyCoolTick() {
		return successivelyCoolTick;
	}

	public boolean isInSuccessiveCoolDown(ActionInfo info) {
		return successivelyCount >= getMaxSuccessiveDodge(info);
	}

	public float getCoolDownPhase(ActionInfo info) {
		int maxCoolTime = getMaxCoolTime(info);
		int successiveMaxCoolTime = successivelyCoolTickMax;
		return Math.min(
				(float) (maxCoolTime - getCoolTime()) / maxCoolTime,
				isInSuccessiveCoolDown(info) ? (float) (successiveMaxCoolTime - getSuccessivelyCoolTick()) / (successiveMaxCoolTime) : 1
		);
	}

	@Override
	public boolean wantsToShowStatusBar(LocalPlayer player, Parkourability parkourability) {
		return coolTime > 0 || isInSuccessiveCoolDown(parkourability.getActionInfo());
	}

	@Override
	public float getStatusValue(LocalPlayer player, Parkourability parkourability) {
		ActionInfo info = parkourability.getActionInfo();
		int maxCoolTime = getMaxCoolTime(info);
		int successiveMaxCoolTime = successivelyCoolTickMax;
		return Math.max(
				(float) getCoolTime() / maxCoolTime,
				isInSuccessiveCoolDown(info) ? (float) (getSuccessivelyCoolTick()) / (successiveMaxCoolTime) : 0
		);
	}

	@Override
	public void onWorkingTick(Player player, Parkourability parkourability, IStamina stamina) {
		Pose pose = ParCoolPoses.ROLLING.get();
		player.setPose(pose);
	}
}
