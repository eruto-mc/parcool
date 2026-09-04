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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeMod;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.UUID;

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
	// ── 速さの決め方（2026-09-03 に2度目の作り直し） ────────────────
	//
	// 初めは「下った高さに比例して上乗せ」（線形）、次に「その2乗」にしたが、
	// ⚠ どちらも当部が考えた式で、⚠ **坂の急さを見ていなかった**。
	// 緩い坂も急な坂も、同じ高さを下れば同じだけ速くなる形だった。
	//
	// いまは物理そのまま——斜面に沿った重力の成分から、摩擦と速さに応じた抵抗を引く:
	//
	//     加速度 = SLIDE_GRAVITY × (sinθ − SLIDE_FRICTION × cosθ) − SLIDE_DRAG × v²
	//
	// バニラの階段は1マス進んで1マス下がる＝45度なので、sinθ・cosθ とも 0.707。
	private static final double SLIDE_GRAVITY = 0.08;
	private static final double SLIDE_FRICTION = 0.1;
	// ⚠⚠ 速さに応じた抵抗（2026-09-04 に足した）。⚠ **落ち着く速さを決めているのはこれ。**
	//
	//    摩擦だけの形には穴があった——⚠ **傾きが SLIDE_FRICTION（＝tanθ 0.1・約5.7度）を
	//    超える坂は、加速度が正のままなので必ず上限まで行く**。バニラの階段（45度）では
	//    加速度 0.051 なので **10 tick 弱で上限に張り付く**。
	//    ⚠⚠ **つまり坂の急さが速さに出ておらず、どの坂でも同じ速さだった**
	//    （2026-09-04・あなたの「最大速度が速すぎる」の正体）。
	//
	//    v² に比例する抵抗を引くと、加速度が 0 になる速さ＝落ち着く速さが坂ごとに決まる:
	//
	//        v終 = √( SLIDE_GRAVITY × (sinθ − SLIDE_FRICTION × cosθ) ÷ SLIDE_DRAG )
	//
	//    | 坂 | 落ち着く速さ |
	//    | - | - |
	//    | 26.6度（2マス進んで1マス下がる） | 0.49 ＝ 9.8 m/s |
	//    | 45度（バニラの階段） | 0.65 ＝ 13.0 m/s |
	//    | 63.4度（1マス進んで2マス下がる） | 0.75 ＝ 15.1 m/s |
	private static final double SLIDE_DRAG = 0.12;
	// 速さの上限（ブロック/tick）。⚠ **いまは安全弁**——上の表のいちばん急な坂より上に置いて
	// あるので、普通の地形では当たらない。0.8 ＝ 16 m/s ＝ 素の滑り 0.45 の 1.8 倍。
	private static final double MAX_SLIDE_SPEED = 0.8;

	// ⚠⚠ 滑っている間だけ、段差を登る高さを削る（2026-09-03）。
	//    バニラは 0.6 ブロックまで自動で登る。階段ブロックの段は 0.5 なので、
	//    横から当たるたびに体が持ち上げられ、⚠ **速いほど跳ねて見えていた**。
	//    ⚠ この持ち上げは Entity#move の中で位置を直に動かすもので、
	//    deltaMovement を通らない——だから速度をいじっても止められない。
	//    ⚠ 0.6 を**全部**削る（2026-09-03 に 0.5 から上げた）。0.1 残していたが、
	//    跳ねが残った。滑っている間は段を1つも登らせないほうが、
	//    「滑りが上へは行かない」という見た目と揃う。
	private static final double SLIDE_STEP_HEIGHT_CUT = 0.6;

	// ⚠⚠ 地面への吸着は 2026-09-03 に**外した**。経緯を残す（同じ道をもう一度作らないため）:
	//
	//    足元のすぐ下に地面が在るならそこへ位置を置く、という作りだった。
	//    ⚠ 段差登りを止めた今、持ち上げ自体が起きないので**戻す相手が居ない**。
	//    ⚠⚠ しかも害があった——地面を**中心から下へ1本レイを撃つ**だけで探していたので、
	//    段の縁では中心の真下が「次の段」を指す一方、体（幅 0.6）はまだ手前の段に乗っている。
	//    そこで位置を下げると**体が手前の段へめり込み**、次の移動処理で押し出される。
	//    ⚠ **ハーフブロックの階段を下るときの引っかかり**がこれだった。
	//
	//    もう一度やるなら、中心1本ではなく**当たり判定の箱を下へ動かして**距離を測ること。
	private static final UUID STEP_HEIGHT_MODIFIER_UUID = UUID.fromString("8f7c2e14-6b3a-4d51-9e0f-2a6d5c81b4e7");
	private static final String STEP_HEIGHT_MODIFIER_NAME = "parcool.modifier.slide_step_height";
	// ⚠ 「坂が続く限り滑れる」の安全弁。30秒。地形では届かないが、
	//    終わらない形を残さないために置く。
	private static final int MAX_SLOPE_EXTENSION_TICK = 600;

	// いま滑っている速さ（ブロック/tick）。毎ティック加速度を足して育てる。
	private double slideSpeed = 0;
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
		// マイクラ部（eruto）のパッチ: 滑りの速さを初期化する。
		// 0 を入れておくと、最初のティックで素の速さから始まる。
		slideSpeed = 0;
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
			double y = player.getY();
			double drop = Double.isNaN(lastY) ? 0 : lastY - y;
			lastY = y;
			boolean onSlope = drop > 0.01 && hasGroundBelow(player);

			AttributeInstance attr = player.getAttribute(Attributes.MOVEMENT_SPEED);
			double baseSpeed = (attr != null ? attr.getValue() : 0.1) * 4.5;
			if (slideSpeed <= 0) slideSpeed = baseSpeed;

			if (onSlope) {
				// 坂の急さは、このティックで「下がった高さ ÷ 進んだ水平距離」＝ tanθ。
				double grade = slideSpeed > 1e-4 ? drop / slideSpeed : 0;
				double inv = 1 / Math.sqrt(1 + grade * grade);   // = cosθ
				double sin = grade * inv;
				double accel = SLIDE_GRAVITY * (sin - SLIDE_FRICTION * inv)
						- SLIDE_DRAG * slideSpeed * slideSpeed;
				// ⚠ 素の滑りより遅くはしない。⚠⚠ **抵抗を足したぶん、ほとんど傾いていない坂
				//    （下がった高さが 0.01 をわずかに超えるだけ）では減速が勝つ**ので、
				//    これが無いと「坂に入った瞬間だけ遅くなる」が起きる。
				slideSpeed = Math.max(baseSpeed,
						Math.min(MAX_SLIDE_SPEED, slideSpeed + accel));
				if (slopeTick < MAX_SLOPE_EXTENSION_TICK) slopeTick++;
			} else {
				// 平らでは摩擦だけが残る。⚠ 素の滑りの速さより下へは落とさない。
				slideSpeed = Math.max(baseSpeed, slideSpeed - SLIDE_GRAVITY * SLIDE_FRICTION);
			}

			Vec3 vec = slidingVec.scale(slideSpeed);
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
	/**
	 * Minecraft-bu (eruto) patch: scale a height written for a normal-sized
	 * player to whoever is actually sliding.
	 *
	 * <p>Our races differ in size (Pehkui), so a flat 0.5 blocks reads as "one
	 * stair step" for a human and "most of the body" for a small one. 1.8 is
	 * vanilla's standing height, the basis {@code Vault} already uses.
	 *
	 * <p>⚠ Asks for the STANDING dimensions on purpose. A slide sets the pose to
	 * SWIMMING, so {@code getBbHeight()} would return the crawling height and we
	 * would mistake posture for body size.
	 */
	private static double scaled(Player player, double height) {
		return height * (player.getDimensions(Pose.STANDING).height / 1.8);
	}

	private static boolean hasGroundBelow(Player player) {
		AABB box = player.getBoundingBox();
		double depth = scaled(player, SLOPE_GROUND_DEPTH);
		AABB probe = new AABB(box.minX, box.minY - depth, box.minZ, box.maxX, box.minY, box.maxZ);
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

	@Override
	public void onTick(Player player, Parkourability parkourability, IStamina stamina) {
		// マイクラ部（eruto）のパッチ: 段差の削りをここで付け外しする。
		// ⚠⚠ onStop は ActionProcessor から**一度も呼ばれていない**（2026-09-03 に grep で確認）。
		//    そちらに戻す処理を置くと、⚠ **一度滑っただけで以後ずっと段差を登れなくなる**。
		//    onTick は isDoing() でなくても毎ティック呼ばれるので、ここなら必ず戻る。
		//
		// ⚠⚠ 一度「下っている間だけ」に絞ったが、**それが振動を作っていた**（2026-09-03）。
		//    段差登りで上がったティックは「下っていない」ので削りが外れ、
		//    ⚠ **次のティックでまた登れてしまう**——登る／削るを交互に繰り返す形だった。
		//    滑っている間はずっと削る。⚠ 上りでは段に引っかかるが、
		//    「滑りが上へは行かない」はあなたの見立てどおり自然な挙動。
		setStepHeightCut(player, isDoing());
	}

	/**
	 * Minecraft-bu (eruto) patch: take most of the auto step-up away while sliding.
	 *
	 * <p>Vanilla lifts the player over anything up to 0.6 blocks, and a stair step
	 * is 0.5, so sliding down a staircase bumps the body upward at every step -
	 * the faster the slide, the more it reads as bouncing. That lift happens
	 * inside {@code Entity#move}, which moves the position directly and never
	 * touches {@code deltaMovement}, so it cannot be cancelled from the velocity
	 * side.
	 *
	 * <p>Leaving 0.1 keeps carpets and pressure plates passable.
	 */
	private static void setStepHeightCut(Player player, boolean on) {
		AttributeInstance attr = player.getAttribute(ForgeMod.STEP_HEIGHT_ADDITION.get());
		if (attr == null) return;
		if (attr.getModifier(STEP_HEIGHT_MODIFIER_UUID) != null) {
			attr.removeModifier(STEP_HEIGHT_MODIFIER_UUID);
		}
		if (on) {
			attr.addTransientModifier(new AttributeModifier(
					STEP_HEIGHT_MODIFIER_UUID,
					STEP_HEIGHT_MODIFIER_NAME,
					-scaled(player, SLIDE_STEP_HEIGHT_CUT),
					AttributeModifier.Operation.ADDITION
			));
		}
	}
}
