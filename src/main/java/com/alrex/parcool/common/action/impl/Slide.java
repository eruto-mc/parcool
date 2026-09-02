package com.alrex.parcool.common.action.impl;

import com.alrex.parcool.api.SoundEvents;
import com.alrex.parcool.client.animation.impl.CrawlAnimator;
import com.alrex.parcool.client.animation.impl.SlidingAnimator;
import com.alrex.parcool.client.input.KeyRecorder;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.BehaviorEnforcer;
import com.alrex.parcool.common.action.StaminaConsumeTiming;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.config.ParCoolConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

public class Slide extends Action {
    private static final BehaviorEnforcer.ID ID_JUMP_CANCEL = BehaviorEnforcer.newID();
	private Vec3 slidingVec = null;

	// ── マイクラ部（eruto）のパッチ: 坂を滑ると速くなる（2026-09-02） ──────────
	//
	// 上流は毎ティック水平速度を固定値で書き直すので、坂でも平地でも同じ速さになる。
	// しかも接地していないティックは 0.6 倍にしており、段差は1つ落ちるのに4ティックほど
	// 浮くので、⚠ 階段を下ると速くなるどころか実際には遅くなっていた。
	//
	// ⚠ 上乗せの対象は「足がすぐ地面へ戻る坂」だけ——真下 SLOPE_GROUND_DEPTH 以内に
	//    当たり判定が在ることを条件にする。崖から飛び出すと真下が空くので上乗せが止まり、
	//    坂でもなくなるので持ち時間も減り始める。
	//
	// ⚠⚠ 深さだけでは足りない。バニラの階段は1マス進んで1マス下がるので、
	//    水平 0.9/tick まで速くなると 5 ティックで水平 4.5・自由落下 1.0 ＝ 3.5 ブロック浮く。
	//    どんな深さにしても「速くなるほど坂から離れて判定が切れる」ことになるので、
	//    下の SLOPE_STICK_ACCELERATION で坂へ引き寄せる。深さはその上での余裕。
	private static final double SLOPE_GROUND_DEPTH = 2.0;
	// 坂を滑っている間、宙に浮いているティックだけ下向きに足す加速。
	// バニラの重力（0.08/tick）の約2倍で、上の計算で言う「浮き」を打ち消す。
	private static final double SLOPE_STICK_ACCELERATION = 0.15;
	// 1ブロック下るごとに何割上乗せするか。上限まで5ブロック。
	private static final double SLOPE_BOOST_PER_BLOCK = 0.2;
	// 上乗せの上限。1.0 = 素の2倍（0.45 → 0.9 /tick ＝ 9 → 18 m/s）。
	private static final double MAX_SLOPE_BOOST = 1.0;
	// ⚠ 「坂が続く限り滑れる」の安全弁。30秒。地形では届かないが、
	//    終わらない形を残さないために置く。
	private static final int MAX_SLOPE_EXTENSION_TICK = 600;

	private double slopeBoost = 0;
	private double lastY = Double.NaN;
	private int slopeTick = 0;

	@Override
	public boolean canStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		Vec3 lookingVec = player.getLookAngle().multiply(1, 0, 1).normalize();
		startInfo.putDouble(lookingVec.x()).putDouble(lookingVec.z());
		return (!stamina.isExhausted()
				&& KeyRecorder.keyCrawlState.isPressed()
				&& player.onGround()
				&& !parkourability.get(Roll.class).isDoing()
				&& !parkourability.get(Tap.class).isDoing()
				&& parkourability.get(Crawl.class).isDoing()
				&& !player.isInWaterOrBubble()
				&& parkourability.get(FastRun.class).getDashTick(parkourability.getAdditionalProperties()) > 5
		);
	}

	@Override
	public boolean canContinue(Player player, Parkourability parkourability, IStamina stamina) {
		int maxSlidingTick = Math.min(
				parkourability.getActionInfo().getClientSetting().get(ParCoolConfig.Client.Integers.SlidingContinuableTick),
				parkourability.getActionInfo().getServerLimitation().get(ParCoolConfig.Server.Integers.MaxSlidingContinuableTick)
		);
		// マイクラ部（eruto）のパッチ: 坂を滑っていたティックは持ち時間から差し引く。
		// 平らになった瞬間から差し引きが止まるので、そこから通常の持ち時間で終わる。
		return getDoingTick() - slopeTick < maxSlidingTick
				&& parkourability.get(Crawl.class).isDoing();
	}

	@Override
	public void onStartInLocalClient(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startData) {
		slidingVec = new Vec3(startData.getDouble(), 0, startData.getDouble());
		// マイクラ部（eruto）のパッチ: 坂の上乗せを初期化する。
		slopeBoost = 0;
		lastY = player.getY();
		slopeTick = 0;
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
            player.playSound(SoundEvents.SLIDE.get(), 1f, 1f);
		Animation animation = Animation.get(player);
		if (animation != null) {
			animation.setAnimator(new SlidingAnimator());
		}
        parkourability.getBehaviorEnforcer().addMarkerCancellingJump(ID_JUMP_CANCEL, this::isDoing);
	}

	@Override
	public void onStartInOtherClient(Player player, Parkourability parkourability, ByteBuffer startData) {
		slidingVec = new Vec3(startData.getDouble(), 0, startData.getDouble());
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
			player.playSound(SoundEvents.SLIDE.get(), 1f, 1f);
		Animation animation = Animation.get(player);
		if (animation != null) {
			animation.setAnimator(new SlidingAnimator());
		}
	}

	@Override
	public void onWorkingTickInLocalClient(Player player, Parkourability parkourability, IStamina stamina) {
		if (slidingVec != null) {
			// マイクラ部（eruto）のパッチ: 坂を下った高さのぶんだけ速さを上乗せする。
			double y = player.getY();
			double drop = Double.isNaN(lastY) ? 0 : lastY - y;
			lastY = y;
			boolean onSlope = drop > 0.01 && hasGroundBelow(player);
			if (onSlope) {
				slopeBoost = Math.min(MAX_SLOPE_BOOST, slopeBoost + drop * SLOPE_BOOST_PER_BLOCK);
				if (slopeTick < MAX_SLOPE_EXTENSION_TICK) slopeTick++;
			}

            AttributeInstance attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
            double speedScale = 0.45;
            if (attr != null) {
                speedScale = attr.getValue() * 4.5;
            }
            speedScale *= 1 + slopeBoost;
            Vec3 vec = slidingVec.scale(speedScale);
			// ⚠ 段差を落ちている最中も坂の一部なので、そこでは 0.6 倍を掛けない。
			//    これを掛けていたのが「階段を下ると遅くなる」の正体だった。
			boolean keepingSpeed = player.onGround() || onSlope;
			double vy = player.getDeltaMovement().y();
			// ⚠ 坂へ引き寄せる。速くなるほど段を飛び越えて宙を飛ぶので、これが無いと
			//    自分の加速で坂から離れ、判定が切れて減速する——という堂々巡りになる。
			if (onSlope && !player.onGround()) {
				vy -= SLOPE_STICK_ACCELERATION;
			}
			player.setDeltaMovement((keepingSpeed ? vec : vec.scale(0.6)).add(0, vy, 0));
		}
	}

	/**
	 * Minecraft-bu (eruto) patch: is there anything solid within
	 * {@link #SLOPE_GROUND_DEPTH} blocks under the player's feet?
	 *
	 * <p>This is what tells "sliding down stairs" from "sliding off a cliff".
	 * Stairs and one-block steps leave the ground for a few ticks at a time but
	 * always have floor right below; a cliff does not, so the speed-up stops
	 * there and the slide runs out of time like it used to.
	 */
	private static boolean hasGroundBelow(Player player) {
		AABB box = player.getBoundingBox();
		AABB probe = new AABB(box.minX, box.minY - SLOPE_GROUND_DEPTH, box.minZ, box.maxX, box.minY, box.maxZ);
		return !player.level().noCollision(player, probe);
	}

	@Override
	public void onWorkingTickInClient(Player player, Parkourability parkourability, IStamina stamina) {
		spawnSlidingParticle(player);
	}

	@Override
	public void onStopInLocalClient(Player player) {
		Animation animation = Animation.get(player);
		if (animation != null && !animation.hasAnimator()) {
			animation.setAnimator(new CrawlAnimator());
		}
	}

	@Override
	public void onStopInOtherClient(Player player) {
		Animation animation = Animation.get(player);
		if (animation != null && !animation.hasAnimator()) {
			animation.setAnimator(new CrawlAnimator());
		}
	}

	@Nullable
	public Vec3 getSlidingVector() {
		return slidingVec;
	}

	@Override
	public StaminaConsumeTiming getStaminaConsumeTiming() {
		return StaminaConsumeTiming.None;
	}

	@OnlyIn(Dist.CLIENT)
	private void spawnSlidingParticle(Player player) {
		if (!ParCoolConfig.Client.Booleans.EnableActionParticles.get()) return;
		var level = player.level();
		var pos = player.position();
		var feetBlock = player.level().getBlockState(player.blockPosition().below());
		float width = player.getBbWidth();
		var direction = getSlidingVector();
		if (direction == null) return;

		if (feetBlock.getRenderShape() != RenderShape.INVISIBLE) {
			var particlePos = new Vec3(
					pos.x() + (player.getRandom().nextDouble() - 0.5D) * width,
					pos.y() + 0.01D + 0.2 * player.getRandom().nextDouble(),
					pos.z() + (player.getRandom().nextDouble() - 0.5D) * width
			);
			var particleSpeed = direction
					.reverse()
					.scale(2.5 + 5 * player.getRandom().nextDouble())
					.add(0, 1.5, 0);
			var blockPos = player.position().add(0, -0.5, 0);
			level.addParticle(
					new BlockParticleOption(ParticleTypes.BLOCK, feetBlock).setPos(
							new BlockPos(
									Mth.floor(blockPos.x()),
									Mth.floor(blockPos.y()),
									Mth.floor(blockPos.z())
							)
					),
					particlePos.x(),
					particlePos.y(),
					particlePos.z(),
					particleSpeed.x(),
					particleSpeed.y(),
					particleSpeed.z()
			);
		}
	}


	@Override
	public void onWorkingTick(Player player, Parkourability parkourability, IStamina stamina) {
		Pose pose = Pose.SWIMMING;
		player.setSprinting(false);
		player.setPose(pose);
	}
}
