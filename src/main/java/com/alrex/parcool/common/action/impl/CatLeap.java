package com.alrex.parcool.common.action.impl;

import com.alrex.parcool.api.SoundEvents;
import com.alrex.parcool.client.animation.impl.CatLeapAnimator;
import com.alrex.parcool.client.input.KeyRecorder;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.StaminaConsumeTiming;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.config.ParCoolConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.ByteBuffer;

public class CatLeap extends Action {
	private int coolTimeTick = 0;
	private boolean ready = false;
	private int readyTick = 0;
    // キャットリープのクールタイム。上流は 30（1.5秒）。
    // 走りながら SHIFT を押して離すと出る技で、跳んだあと次が出るまでが長く感じたので 20（1秒）にした。
    // ⚠ 抑止は主にスタミナ側（消費200＝全27アクションで最大・満腹度1あたり3回）が担っている。
    //    ここを縮めるぶん、連発したときの空腹の減りは目立つようになる。
    private static final int MAX_COOL_TIME_TICK = 20;

    // ── マイクラ部（eruto）のパッチ: 入ってきた速さを跳躍に乗せる（2026-09-04）──────
    //
    // ⚠ 上流は canStart で movement を normalize() し、跳ぶ瞬間に
    //   setDeltaMovement(方向.x, …, 方向.z) と**単位ベクトルをそのまま速度にしていた**。
    //   ＝ 水平速度は入り方に関係なく**常に 1.0 ブロック/tick（20 m/s）固定**。
    //
    // ⚠⚠ **速度を失ってはいない**（1.0 は地上で出せるどの速さより速い）。
    //   問題は**どう入っても同じ**こと——歩いて入っても、坂を 0.8 で滑ってから入っても、
    //   跳ぶ距離が1ブロックも変わらない。⚠ **速さを積む意味が無い。**
    //
    // ⚠ これは当部が Slide で直したのと同じ型（README「上流は毎ティック水平速度を
    //   固定値で書き直す」）。⚠⚠ **上流自身、WallJump では
    //   `motion.x() + jumpMotion.x()` と足している**ので、そちらへ揃える。
    //
    //     跳ぶ速さ = clamp(BASE + (入りの速さ − REF) × GAIN, BASE, CAP)
    //
    //   | 入り方 | 水平速度 | 跳ぶ速さ | おおよその飛距離 |
    //   | - | - | - | - |
    //   | 歩き・ダッシュ | 〜0.30 | 1.00（上流と同じ） | 約7ブロック |
    //   | 平地のスライディング | 0.45 | 1.15 | 約8 |
    //   | 45度の坂を滑って | 0.65 | 1.35 | 約9.5 |
    //   | 坂の上限まで乗せて | 0.80 | 1.50 | 約10.5 |
    //
    // ⚠ REF を 0.30（ダッシュの速さ）に置いたので、**普通に走って跳ぶぶんは上流のまま**。
    //   ⚠ 伸びるのはスライディングを噛ませたときだけ。
    // ⚠ 飛距離は「空中の水平減衰 0.91 を滞空 12 tick ぶん足した」概算。実測ではない。
    private static final double LEAP_BASE_SPEED = 1.0;
    private static final double LEAP_REF_SPEED = 0.30;
    private static final double LEAP_SPEED_GAIN = 1.0;
    private static final double LEAP_MAX_SPEED = 1.6;

	@Override
	public void onTick(Player player, Parkourability parkourability, IStamina stamina) {
		if (coolTimeTick > 0) {
			coolTimeTick--;
		}
	}

	@Override
	public void onClientTick(Player player, Parkourability parkourability, IStamina stamina) {
		if (player.isLocalPlayer()) {
			if (KeyRecorder.keySneak.isPressed() && parkourability.get(FastRun.class).getNotDashTick(parkourability.getAdditionalProperties()) < 10) {
				ready = true;
			}
			if (ready) {
				readyTick++;
			}
			if (readyTick > 10) {
				ready = false;
				readyTick = 0;
			}
		}
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public boolean canStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		Vec3 movement = player.getDeltaMovement().multiply(1, 0, 1);
		if (movement.lengthSqr() < 0.001) return false;
		// マイクラ部（eruto）のパッチ: 向きと**速さ**を分けて渡す。
		// ⚠ 上流は normalize() した向きだけを渡していたので、速さは復元できなかった。
		double entrySpeed = movement.length();
		movement = movement.scale(1 / entrySpeed);
		startInfo.putDouble(movement.x()).putDouble(movement.z()).putDouble(entrySpeed);
		return (player.onGround()
				&& !player.isInWater()
				&& !stamina.isExhausted()
				&& coolTimeTick <= 0
				&& readyTick > 0
				&& parkourability.get(ChargeJump.class).getChargingTick() < ChargeJump.JUMP_MAX_CHARGE_TICK / 2
                && !parkourability.get(HideInBlock.class).isDoing()
				&& !parkourability.get(Roll.class).isDoing()
				&& !parkourability.get(Tap.class).isDoing()
				&& KeyRecorder.keySneak.isReleased()
		);
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public boolean canContinue(Player player, Parkourability parkourability, IStamina stamina) {
		return !((getDoingTick() > 1 && player.onGround())
				|| player.isFallFlying()
				|| player.isInWaterOrBubble()
				|| player.isInLava()
		);
	}

	@Override
	public void onStartInLocalClient(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startData) {
        Vec3 jumpDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
        double entrySpeed = startData.getDouble();
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
            player.playSound(SoundEvents.CATLEAP.get(), 1, 1);
		coolTimeTick = MAX_COOL_TIME_TICK;
        spawnJumpEffect(player, jumpDirection);
        player.jumpFromGround();
        Vec3 motionVec = player.getDeltaMovement();
        // マイクラ部（eruto）のパッチ: 速く入ったぶんだけ跳躍を伸ばす（上の表）。
        double leapSpeed = Mth.clamp(
                LEAP_BASE_SPEED + (entrySpeed - LEAP_REF_SPEED) * LEAP_SPEED_GAIN,
                LEAP_BASE_SPEED, LEAP_MAX_SPEED
        );
        player.setDeltaMovement(
                jumpDirection.x() * leapSpeed,
                motionVec.y() * 1.16667,
                jumpDirection.z() * leapSpeed
        );
		Animation animation = Animation.get(player);
		if (animation != null) animation.setAnimator(new CatLeapAnimator());
	}

	@Override
	public void onStartInOtherClient(Player player, Parkourability parkourability, ByteBuffer startData) {
		Vec3 jumpDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
			player.playSound(SoundEvents.CATLEAP.get(), 1, 1);
		spawnJumpEffect(player, jumpDirection);
		Animation animation = Animation.get(player);
		if (animation != null) animation.setAnimator(new CatLeapAnimator());
	}

	@Override
	public boolean wantsToShowStatusBar(LocalPlayer player, Parkourability parkourability) {
		return coolTimeTick > 0;
	}

	@Override
	public float getStatusValue(LocalPlayer player, Parkourability parkourability) {
		return coolTimeTick / (float) MAX_COOL_TIME_TICK;
	}

	@Override
	public StaminaConsumeTiming getStaminaConsumeTiming() {
		return StaminaConsumeTiming.OnStart;
	}

	@OnlyIn(Dist.CLIENT)
	private void spawnJumpEffect(Player player, Vec3 jumpDirection) {
		if (!ParCoolConfig.Client.Booleans.EnableActionParticles.get()) return;
		Level level = player.level();
		Vec3 pos = player.position();
		var blockPosVec = pos.add(0, -0.2, 0);
		BlockPos blockpos = new BlockPos(
				Mth.floor(blockPosVec.x()),
				Mth.floor(blockPosVec.y()),
				Mth.floor(blockPosVec.z())
		);
		if (!level.isLoaded(blockpos)) return;
		float width = player.getBbWidth();
		BlockState blockstate = level.getBlockState(blockpos);
		if (blockstate.getRenderShape() != RenderShape.INVISIBLE) {
			for (int i = 0; i < 20; i++) {
				Vec3 particlePos = new Vec3(
						pos.x() + (jumpDirection.x() * -0.5 + player.getRandom().nextDouble() - 0.5D) * width,
						pos.y() + 0.1D,
						pos.z() + (jumpDirection.z() * -0.5 + player.getRandom().nextDouble() - 0.5D) * width
				);
				Vec3 particleSpeed = particlePos.subtract(pos).normalize().scale(2.5 + 8 * player.getRandom().nextDouble()).add(0, 1.5, 0);
				level.addParticle(
						new BlockParticleOption(ParticleTypes.BLOCK, blockstate).setPos(blockpos),
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
}
