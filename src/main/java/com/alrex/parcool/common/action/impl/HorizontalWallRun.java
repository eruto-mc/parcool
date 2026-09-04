package com.alrex.parcool.common.action.impl;

import com.alrex.parcool.api.SoundEvents;
import com.alrex.parcool.client.animation.impl.HorizontalWallRunAnimator;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.StaminaConsumeTiming;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.info.ActionInfo;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.utilities.BufferUtil;
import com.alrex.parcool.utilities.VectorUtil;
import com.alrex.parcool.utilities.WorldUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;

import java.nio.ByteBuffer;

public class HorizontalWallRun extends Action {
	public enum ControlType {
		PressKey, Auto
	}
	private int coolTime = 0;
	private float bodyYaw = 0;

	private int getMaxRunningTick(ActionInfo info) {
		Integer value = info.getClientSetting().get(ParCoolConfig.Client.Integers.WallRunContinuableTick);
		if (value == null) value = ParCoolConfig.Client.Integers.WallRunContinuableTick.DefaultValue;
		return Math.min(value, info.getServerLimitation().get(ParCoolConfig.Server.Integers.MaxWallRunContinuableTick));
	}

	private boolean wallIsRightward = false;
	private Vec3 runningWallDirection = null;
	private Vec3 runningDirection = null;

	// ── マイクラ部（eruto）のパッチ: 壁を走る速さに、入ってきた勢いを引き継ぐ（2026-09-04）──
	//
	// ⚠⚠ 上流は毎ティック `speedScale = 0.2` を書き込んでいた（移動速度の属性比だけ掛ける）。
	//   0.2 ブロック/tick ＝ **4.0 m/s** で、⚠ **バニラの歩き 4.317 m/s より遅い。**
	//   ダッシュ補正が乗っても 0.24 ＝ 4.8 m/s で、⚠ **ダッシュ 5.612 m/s に届かない。**
	//   ＝ **走って壁に飛び移ると、そこで歩きより遅くなる。**
	//
	// ⚠ しかも入ってきた速さは毎ティック上書きで消える。CatLeap と同じ「固定値で書き直す」型。
	//   ⚠⚠ **上流自身 WallJump では `motion + jumpMotion` と足している**ので、そちらへ揃える。
	//
	// いまは「上流の 0.2×属性比」と「飛び移った瞬間の水平速度」の**大きいほう**を使う。
	// ⚠ 減衰は入れていない——壁走りは `wall-run_continuable_tick`（既定 25＝1.25秒）で
	//   どのみち終わるので、その間は勢いのままにする。⚠ 速すぎたらここに減衰を足す。
	private double entrySpeed = 0;

	@Override
	public void onClientTick(Player player, Parkourability parkourability, IStamina stamina) {
		if (coolTime > 0) coolTime--;
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public void onWorkingTickInLocalClient(Player player, Parkourability parkourability, IStamina stamina) {
		Vec3 wallDirection = WorldUtil.getRunnableWall(player, player.getBbWidth() * 0.65f);
		if (wallDirection == null) return;
		if (runningWallDirection == null) return;
		if (runningDirection == null) return;
		Vec3 lookVec = VectorUtil.fromYawDegree(player.yBodyRot);
		double differenceAngle = Math.asin(
				new Vec3(
						lookVec.x() * runningDirection.x() + lookVec.z() * runningDirection.z(), 0,
						-lookVec.x() * runningDirection.z() + lookVec.z() * runningDirection.x()
				).normalize().z()
		);
		bodyYaw = (float) VectorUtil.toYawDegree(lookVec.yRot((float) (differenceAngle / 10)));
		Vec3 movement = player.getDeltaMovement();
		BlockPos leanedBlock = new BlockPos(
				Mth.floor(player.getX() + runningWallDirection.x()),
				Mth.floor(player.getBoundingBox().minY + player.getBbHeight() * 0.5),
				Mth.floor(player.getZ() + runningWallDirection.z())
		);
		if (!player.getCommandSenderWorld().isLoaded(leanedBlock)) return;
		float slipperiness = player.getCommandSenderWorld().getBlockState(leanedBlock).getFriction(player.getCommandSenderWorld(), leanedBlock, player);
		if (slipperiness <= 0.8) {
			double speedScale = 0.2;
			var attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
			if (attr != null) {
				speedScale *= attr.getValue() / attr.getBaseValue();
			}
			// マイクラ部（eruto）のパッチ: 飛び移った瞬間の速さを下回らせない（上の注記）。
			speedScale = Math.max(speedScale, entrySpeed);
			player.setDeltaMovement(
					runningDirection.x() * speedScale,
					movement.y() * (slipperiness - 0.1) * ((double) getDoingTick()) / getMaxRunningTick(parkourability.getActionInfo()),
					runningDirection.z() * speedScale
			);
		}
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public boolean canStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		Vec3 wallDirection = WorldUtil.getRunnableWall(player, player.getBbWidth() * 0.65f);
		if (wallDirection == null) return false;
		Vec3 wallVec = wallDirection.normalize();
		Vec3 lookDirection = VectorUtil.fromYawDegree(player.yBodyRot);
		lookDirection = new Vec3(lookDirection.x(), 0, lookDirection.z()).normalize();
		//doing "wallDirection/direction" as complex number(x + z i) to calculate difference of player's direction to steps
		Vec3 dividedVec =
				new Vec3(
						wallVec.x() * lookDirection.x() + wallVec.z() * lookDirection.z(), 0,
						-wallVec.x() * lookDirection.z() + wallVec.z() * lookDirection.x()
				).normalize();
		// 体の向きと壁の平行からのずれの許容。sin(θ) で比べている。
		// 0.9 ＝ **26度**しか許さず、走りながら少し壁へ寄っただけで出なくなっていた。
		// ⚠ 当部は Vault で同じ形の条件を 45°→60° に広げている（README）。
		//    こちらは 26°→ **37度**（0.8）まで緩める。
		// ⚠ 操作種別が Auto なので、緩めすぎると壁をかすめただけで暴発する。
		//    ⚠ まず 0.8 で走ってみて、まだ渋いなら 0.75（41度）まで。
		if (Math.abs(dividedVec.z()) < 0.8) {
			return false;
		}
		BufferUtil.wrap(startInfo).putBoolean(dividedVec.z() > 0/*if true, wall is in right side*/);
		Vec3 runDirection = wallVec.yRot((float) (Math.PI / 2));
		if (runDirection.dot(lookDirection) < 0) {
			runDirection = runDirection.reverse();
		}
		startInfo.putDouble(wallDirection.x())
				.putDouble(wallDirection.z())
				.putDouble(runDirection.x())
				.putDouble(runDirection.z());

		return (!parkourability.get(WallJump.class).justJumped()
				&& (
				(ParCoolConfig.Client.HWallRunControl.get() == ControlType.PressKey && KeyBindings.getKeyHorizontalWallRun().isDown())
						|| ParCoolConfig.Client.HWallRunControl.get() == ControlType.Auto
		)
				&& !parkourability.get(Crawl.class).isDoing()
				&& !parkourability.get(Dodge.class).isDoing()
				&& !parkourability.get(Vault.class).isDoing()
				&& !parkourability.get(ClingToCliff.class).isDoing()
				&& !player.isInWaterOrBubble()
				&& Math.abs(player.getDeltaMovement().y()) < 0.5
				&& coolTime == 0
				&& !player.onGround()
				&& parkourability.getAdditionalProperties().getNotLandingTick() > 5
                && (
                parkourability.get(FastRun.class).canActWithRunning(player)
                        || parkourability.get(FastRun.class).getNotDashTick(parkourability.getAdditionalProperties()) < 10
		)
				&& !stamina.isExhausted()
		);
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public boolean canContinue(Player player, Parkourability parkourability, IStamina stamina) {
		Vec3 wallDirection = WorldUtil.getRunnableWall(player, player.getBbWidth() * 0.65f);
		if (wallDirection == null) return false;
		if (!(player instanceof LocalPlayer localPlayer)) return false;
		if (localPlayer.input == null) return false;
		var moveVector = localPlayer.input.getMoveVector();
		var actualInputVector
				= new Vec3(moveVector.x, 0, moveVector.y)
				.normalize()
				.yRot((float) -Math.toRadians(player.getYRot()));
		// Input almost opposite to wall
		if (wallDirection.normalize().dot(actualInputVector) < -0.86) {
			return false;
		}
		return (getDoingTick() < getMaxRunningTick(parkourability.getActionInfo())
				&& !stamina.isExhausted()
				&& !parkourability.get(WallJump.class).justJumped()
				&& !parkourability.get(Crawl.class).isDoing()
				&& !parkourability.get(Dodge.class).isDoing()
				&& !parkourability.get(Vault.class).isDoing()
				&& ((ParCoolConfig.Client.HWallRunControl.get() == ControlType.PressKey && KeyBindings.getKeyHorizontalWallRun().isDown())
				|| ParCoolConfig.Client.HWallRunControl.get() == ControlType.Auto)
				&& !player.onGround()
		);
	}

	@Override
	public void onStop(Player player) {
		coolTime = 10;
	}

	@Override
	public void onStartInLocalClient(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startData) {
		wallIsRightward = BufferUtil.getBoolean(startData);
		runningWallDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
		runningDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
		// マイクラ部（eruto）のパッチ: 飛び移った瞬間の水平速度を控えておく。
		// ⚠ ここで採る（canStart ではない）——canStart は開始条件を判定するだけで、
		//    ⚠⚠ **通らなかった回にも呼ばれる**ので、そこで控えると古い値が残る。
		entrySpeed = player.getDeltaMovement().multiply(1, 0, 1).length();
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
            player.playSound(SoundEvents.HORIZONTAL_WALL_RUN.get(), 1f, 1f);
		Animation animation = Animation.get(player);
		if (animation != null) {
			animation.setAnimator(new HorizontalWallRunAnimator(wallIsRightward));
		}
	}

	@Override
	public void onStartInOtherClient(Player player, Parkourability parkourability, ByteBuffer startData) {
		wallIsRightward = BufferUtil.getBoolean(startData);
		runningWallDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
		runningDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
		Animation animation = Animation.get(player);
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
			player.playSound(SoundEvents.HORIZONTAL_WALL_RUN.get(), 1f, 1f);
		if (animation != null) {
			animation.setAnimator(new HorizontalWallRunAnimator(wallIsRightward));
		}
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public void onRenderTick(TickEvent.RenderTickEvent event, Player player, Parkourability parkourability) {
		if (isDoing()) {
			if (runningDirection == null) return;
			Vec3 lookVec = VectorUtil.fromYawDegree(player.getYHeadRot());
			double differenceAngle = Math.asin(
					new Vec3(
							lookVec.x() * runningDirection.x() + lookVec.z() * runningDirection.z(), 0,
							-lookVec.x() * runningDirection.z() + lookVec.z() * runningDirection.x()
					).normalize().z()
			);
			if (Math.abs(differenceAngle) > Math.PI / 4) {
				player.setYRot((float) VectorUtil.toYawDegree(
						runningDirection.yRot((float) (-Math.signum(differenceAngle) * Math.PI / 4))
				));
			}
            player.yBodyRotO = player.yBodyRot = bodyYaw;
		}
	}

    @Override
    public void onWorkingTickInClient(Player player, Parkourability parkourability, IStamina stamina) {
        spawnRunningParticle(player);
    }

	@Override
	public void saveSynchronizedState(ByteBuffer buffer) {
		buffer.putFloat(bodyYaw);
	}

	@Override
	public void restoreSynchronizedState(ByteBuffer buffer) {
		bodyYaw = buffer.getFloat();
	}

	@Override
	public StaminaConsumeTiming getStaminaConsumeTiming() {
		return StaminaConsumeTiming.OnWorking;
	}

	@OnlyIn(Dist.CLIENT)
	public void spawnRunningParticle(Player player) {
		if (!ParCoolConfig.Client.Booleans.EnableActionParticles.get()) return;
		if (runningDirection == null || runningWallDirection == null) return;
		Level level = player.level();
		Vec3 pos = player.position();
        BlockPos leanedBlock = new BlockPos(
				Mth.floor(pos.x() + runningWallDirection.x()),
				Mth.floor(pos.y() + player.getBbHeight() * 0.25),
				Mth.floor(pos.z() + runningWallDirection.z())
        );
		if (!level.isLoaded(leanedBlock)) return;
		float width = player.getBbWidth();
		BlockState blockstate = level.getBlockState(leanedBlock);

        Vec3 wallDirection = runningWallDirection.normalize();
        Vec3 orthogonalToWallVec = wallDirection.yRot((float) (Math.PI / 2));
        Vec3 particleBaseDirection = runningDirection.subtract(wallDirection);
        if (blockstate.getRenderShape() != RenderShape.INVISIBLE) {
            Vec3 particlePos = new Vec3(
                    pos.x() + (wallDirection.x() * 0.4 + orthogonalToWallVec.x() * (player.getRandom().nextDouble() - 0.5D)) * width,
                    pos.y() + 0.1D + 0.3 * player.getRandom().nextDouble(),
                    pos.z() + (wallDirection.z() * 0.4 + orthogonalToWallVec.z() * (player.getRandom().nextDouble() - 0.5D)) * width
            );
            Vec3 particleSpeed = particleBaseDirection
                    .yRot((float) (Math.PI * 0.2 * (player.getRandom().nextDouble() - 0.5)))
                    .scale(3 + 6 * player.getRandom().nextDouble())
                    .add(0, 1.5, 0);
            level.addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK, blockstate).setPos(leanedBlock),
                    particlePos.x(),
                    particlePos.y(),
                    particlePos.z(),
                    particleSpeed.x(),
                    particleSpeed.y(),
                    particleSpeed.z()
            );
        }
    }
}
