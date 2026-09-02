package com.alrex.parcool.common.action.impl;

import com.alrex.parcool.api.Attributes;
import com.alrex.parcool.api.SoundEvents;
import com.alrex.parcool.client.animation.impl.VerticalWallRunAnimator;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.client.input.KeyRecorder;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.StaminaConsumeTiming;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.utilities.VectorUtil;
import com.alrex.parcool.utilities.WorldUtil;
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
import net.minecraftforge.event.TickEvent;

import java.nio.ByteBuffer;

public class VerticalWallRun extends Action {
	private double playerYSpeed = 0;
	private Vec3 wallDirection = null;

	@Override
	public void onTick(Player player, Parkourability parkourability, IStamina stamina) {
		playerYSpeed = player.getDeltaMovement().y();
	}

	@Override
	public boolean canStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		int tickAfterJump = parkourability.getAdditionalProperties().getTickAfterLastJump();
		// Minecraft-bu (eruto) patch: two ways in.
		//
		// Upstream only has the first: a real ground jump 5-12 ticks ago with the
		// jump key still held. LivingJumpEvent is the only thing that resets that
		// counter and Wall Jump sets velocity directly, so airborne there is no
		// second run - you get one push and fall.
		//
		// The second is ours, and only for races carrying wall_climb_chain: a
		// *fresh press* of jump while airborne. Holding jump does nothing, so it
		// stays a rhythm rather than a held key. Wall Slide is excluded further
		// down, so holding the wall-slide key still slides instead of climbing.
		//
		// The vertical-speed window belongs to the ground-jump path, not to both.
		// It is what keeps upstream's 5-12 tick window honest (a vanilla jump only
		// stays inside height/5 for ticks 5-9), but pairing it with the air path
		// would make that path dead on arrival: a run ends the tick the climb stops
		// rising, the next one has to wait `15 / wall_climb` ticks, and by tick 8 -
		// the earliest Arachnae can retry - a free fall is already at 0.57 blocks
		// per tick against a 0.36 limit. The two never overlap. The interval and
		// the hunger cost are the rate limit for the air path.
		boolean fromGroundJump = (4 < tickAfterJump && tickAfterJump < 13)
				&& KeyBindings.isKeyJumpDown()
				&& Math.abs(player.getDeltaMovement().y()) <= player.getBbHeight() / 5;
		boolean restartInAir = player.getAttributeValue(Attributes.WALL_CLIMB_CHAIN.get()) >= 1.0
				&& !player.onGround()
				&& KeyRecorder.keyJumpState.isPressed();
		boolean able = !stamina.isExhausted()
				&& (fromGroundJump || restartInAir)
				// Minecraft-bu (eruto) patch: shorten the gap between runs for
				// climbing races, so they can chain runs up a tall wall.
				&& getNotDoingTick() > 15 / player.getAttributeValue(Attributes.WALL_CLIMB.get())
				&& !player.isFallFlying()
				&& !parkourability.get(ClingToCliff.class).isDoing()
				&& !parkourability.get(Crawl.class).isDoing()
                && !parkourability.get(CatLeap.class).isDoing()
				&& !parkourability.get(WallSlide.class).isDoing()
				&& !parkourability.get(HorizontalWallRun.class).isDoing()
				&& !parkourability.get(Vault.class).isDoing()
				&& !parkourability.get(Flipping.class).isDoing()
				&& parkourability.get(FastRun.class).getNotDashTick(parkourability.getAdditionalProperties()) < 8
				// Minecraft-bu (eruto) patch: upstream also demanded `lookVec.y() > 0`
				// (looking even slightly above the horizon). Nobody could find that
				// by playing - you run at a wall and jump, and nothing happens - and
				// the remaining conditions (sprinting, the jump window, jump held,
				// facing the wall within 21.6 degrees, wall over 2.34 blocks) are
				// already specific enough that there is nothing left for it to rule
				// out.
				&& parkourability.getAdditionalProperties().getLastSprintingTick() > 12;
		if (able) {
			Vec3 wall = WorldUtil.getWall(player);
			if (wall == null) return false;
			wall = wall.normalize();
			if (wall.dot(VectorUtil.fromYawDegree(player.getYHeadRot())) > 0.93) {
				double height = WorldUtil.getWallHeight(player, wall, player.getBbHeight() * 2.2, 0.2);
                if (height > player.getBbHeight() * 1.3) {
					BlockPos targetBlock = new BlockPos(
							Mth.floor(player.getX() + wall.x()),
							Mth.floor(player.getBoundingBox().minY + player.getBbHeight() * 0.5),
							Mth.floor(player.getZ() + wall.z())
					);
					if (!player.getCommandSenderWorld().isLoaded(targetBlock)) return false;
					float slipperiness = player.getCommandSenderWorld().getBlockState(targetBlock).getFriction(player.getCommandSenderWorld(), targetBlock, player);
					startInfo.putDouble(height);
					startInfo.putFloat(slipperiness);
					startInfo.putDouble(wall.x());
					startInfo.putDouble(wall.y());
					startInfo.putDouble(wall.z());
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean canContinue(Player player, Parkourability parkourability, IStamina stamina) {
		Vec3 wall = WorldUtil.getWall(player);
		if (wall == null) return false;
		wall = wall.normalize();
		return (wall.dot(VectorUtil.fromYawDegree(player.getYHeadRot())) > 0.93
				&& playerYSpeed > 0)
				|| getDoingTick() > 30;
	}

	@Override
	public void onStartInLocalClient(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startData) {
		double height = startData.getDouble();
		float slipperiness = startData.getFloat();
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
            player.playSound(SoundEvents.VERTICAL_WALL_RUN.get(), 1f, 1f);
		// Minecraft-bu (eruto) patch: scale the single upward push by our attribute.
		// This start push is what decides how high you get - onTick only records the
		// speed and onWorkingTickInClient only spawns particles, so the climb coasts
		// on this one impulse. Cliff races climb further: Arachnae 2.0, Feline 1.3.
		// (This comment used to say 4.0 and 2.0, which never matched the datapack.)
		double erutoWallClimb = player.getAttributeValue(Attributes.WALL_CLIMB.get());
		player.setDeltaMovement(player
				.getDeltaMovement()
				.multiply(1, 0, 1)
				.add(0, (slipperiness <= 0.8f ? 0.32 : 0.16) * Math.sqrt(height) * erutoWallClimb, 0)
		);
		onStartInOtherClient(player, parkourability, startData);
	}

	@Override
	public void onStartInOtherClient(Player player, Parkourability parkourability, ByteBuffer startData) {
        startData.position(8 + 4); // skip (double * 1) and (float * 1)
		wallDirection = new Vec3(startData.getDouble(), startData.getDouble(), startData.getDouble());
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
			player.playSound(SoundEvents.VERTICAL_WALL_RUN.get(), 1f, 1f);
		Animation animation = Animation.get(player);
		if (animation != null) {
			animation.setAnimator(new VerticalWallRunAnimator());
		}
	}

	@Override
	public void onRenderTick(TickEvent.RenderTickEvent event, Player player, Parkourability parkourability) {
		if (wallDirection != null && isDoing()) {
			player.setYHeadRot((float) VectorUtil.toYawDegree(wallDirection));
            player.yBodyRotO = player.yBodyRot = player.getYHeadRot();
		}
	}

    @Override
    public void onWorkingTickInClient(Player player, Parkourability parkourability, IStamina stamina) {
        spawnRunningParticle(player);
    }

	@Override
	public StaminaConsumeTiming getStaminaConsumeTiming() {
		return StaminaConsumeTiming.OnStart;
	}

	@OnlyIn(Dist.CLIENT)
	public void spawnRunningParticle(Player player) {
		if (!ParCoolConfig.Client.Booleans.EnableActionParticles.get()) return;
		if (wallDirection == null) return;
		Level level = player.level();
		Vec3 pos = player.position();
        BlockPos leanedBlock = new BlockPos(
				Mth.floor(pos.x() + wallDirection.x()),
				Mth.floor(pos.y() + player.getBbHeight() * 0.25),
				Mth.floor(pos.z() + wallDirection.z())
        );
		if (!level.isLoaded(leanedBlock)) return;
		float width = player.getBbWidth();
		BlockState blockstate = level.getBlockState(leanedBlock);

        Vec3 normalizedWallVec = wallDirection.normalize();
        Vec3 orthogonalToWallVec = normalizedWallVec.yRot((float) (Math.PI / 2));
        if (blockstate.getRenderShape() != RenderShape.INVISIBLE) {
            Vec3 particlePos = new Vec3(
                    pos.x() + (normalizedWallVec.x() * 0.4 + orthogonalToWallVec.x() * (player.getRandom().nextDouble() - 0.5D)) * width,
                    pos.y() + 0.1D + 0.3 * player.getRandom().nextDouble(),
                    pos.z() + (normalizedWallVec.z() * 0.4 + orthogonalToWallVec.z() * (player.getRandom().nextDouble() - 0.5D)) * width
            );
            Vec3 particleSpeed = normalizedWallVec
                    .reverse()
                    .yRot((float) (Math.PI * 0.2 * (player.getRandom().nextDouble() - 0.5)))
                    .scale(2 + 4 * player.getRandom().nextDouble())
                    .add(0, 0.5, 0);
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
