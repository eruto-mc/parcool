package com.alrex.parcool.client.sound;

import com.alrex.parcool.api.SoundEvents;
import com.alrex.parcool.common.action.impl.RideZipline;
import com.alrex.parcool.common.capability.Parkourability;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Minecraft-bu (eruto) patch: the sound of riding a zipline.
 *
 * <p>Upstream registers sounds for placing and removing a rope but none for
 * travelling along one (upstream issue #433). Our ropes reach 320 blocks - up
 * from the stock 115 - so a long ride is silent the whole way, which is what
 * makes it feel flat.
 *
 * <p>Modelled on vanilla's {@code ElytraOnPlayerSoundInstance}: one looping
 * instance, started when the ride begins, following the player and scaling
 * volume and pitch with the speed along the rope, stopped when the ride ends.
 * The sound itself is vanilla's elytra loop, pulled in through sounds.json, so
 * no new audio file is shipped (same approach as the just-time breakfall sound,
 * which borrows the amethyst step).
 */
@OnlyIn(Dist.CLIENT)
public class ZiplineSoundInstance extends AbstractTickableSoundInstance {
	// ロープの上での速さ（ブロック/tick）のうち、ここで頭打ちにする値。
	// 素の滑走がおよそ 0.2〜0.6 なので、急な線を滑り切ったあたりで最大になる。
	private static final double REFERENCE_SPEED = 0.6;
	private static final float MAX_VOLUME = 0.7f;
	// ⚠ 0 まで下げない。下げると音の管理側に捨てられ、そのあと上げ直しても戻らない。
	private static final float MIN_VOLUME = 0.1f;

	private final LocalPlayer player;

	public ZiplineSoundInstance(LocalPlayer player) {
		super(SoundEvents.ZIPLINE_RIDE.get(), SoundSource.PLAYERS, RandomSource.create());
		this.player = player;
		this.looping = true;
		this.delay = 0;
		// ⚠ 0 から始めると鳴らない。音の管理側が「聞こえない音」として捨てるので、
		//    バニラの ElytraOnPlayerSoundInstance と同じく小さい値で始める（2026-09-03）。
		this.volume = MIN_VOLUME;
		this.x = player.getX();
		this.y = player.getY();
		this.z = player.getZ();
	}

	@Override
	public void tick() {
		if (player.isRemoved()) {
			stop();
			return;
		}
		Parkourability parkourability = Parkourability.get(player);
		if (parkourability == null) {
			stop();
			return;
		}
		RideZipline action = parkourability.get(RideZipline.class);
		if (action == null || !action.isDoing()) {
			stop();
			return;
		}
		this.x = player.getX();
		this.y = player.getY();
		this.z = player.getZ();
		double ratio = Mth.clamp(Math.abs(action.getSpeed()) / REFERENCE_SPEED, 0, 1);
		this.volume = (float) Math.max(MIN_VOLUME, MAX_VOLUME * ratio);
		this.pitch = (float) (0.8 + 0.4 * ratio);
	}
}
