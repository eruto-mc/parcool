package com.alrex.parcool.common.action.impl;

import com.alrex.parcool.api.SoundEvents;
import com.alrex.parcool.client.animation.impl.BackwardWallJumpAnimator;
import com.alrex.parcool.client.animation.impl.WallJumpAnimator;
import com.alrex.parcool.client.input.KeyRecorder;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.BehaviorEnforcer;
import com.alrex.parcool.common.action.StaminaConsumeTiming;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.utilities.WorldUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

public class WallJump extends Action {
	public enum ControlType {
		PressKey, ReleaseKey
	}

	private static final BehaviorEnforcer.ID ID_FALL_FLY_CANCEL = BehaviorEnforcer.newID();
	private boolean jump = false;
	private boolean inPossibleState = false;
	private final ByteBuffer startInfoTempBuffer = ByteBuffer.allocate(64);
	private final BehaviorEnforcer.Marker persistentFallFlyCanceler = () -> this.inPossibleState;

	public boolean justJumped() {
		return jump;
	}

	private static final float MAX_COOL_DOWN_TICK = 8;

	private boolean isInCooldown(Parkourability parkourability) {
		return (parkourability.getClientInfo().get(ParCoolConfig.Client.Booleans.EnableWallJumpCooldown)
				|| !parkourability.getServerLimitation().get(ParCoolConfig.Server.Booleans.AllowDisableWallJumpCooldown))
				&& getNotDoingTick() <= MAX_COOL_DOWN_TICK;
	}
	@Override
	public void onTick(Player player, Parkourability parkourability, IStamina stamina) {
		jump = false;
	}

	@Override
	public void onClientTick(Player player, Parkourability parkourability, IStamina stamina) {
		startInfoTempBuffer.clear();
		inPossibleState = checkCanStart(player, parkourability, stamina, startInfoTempBuffer);
		startInfoTempBuffer.flip();
		parkourability.getBehaviorEnforcer().addMarkerCancellingFallFlying(ID_FALL_FLY_CANCEL, persistentFallFlyCanceler);
	}

	@Override
	public StaminaConsumeTiming getStaminaConsumeTiming() {
		return StaminaConsumeTiming.OnStart;
	}

	/**
	 * Minecraft-bu (eruto) patch: the direction the player is steering, in world
	 * space, or null when no movement key is held.
	 *
	 * <p>Built the same way as {@link HorizontalWallRun#canContinue} does it, so
	 * the result is directly comparable to the wall vector.
	 */
	@OnlyIn(Dist.CLIENT)
	@Nullable
	private static Vec3 getSteeringDirection(Player player) {
		if (!(player instanceof LocalPlayer localPlayer)) return null;
		if (localPlayer.input == null) return null;
		Vec2 moveVector = localPlayer.input.getMoveVector();
		if (Math.abs(moveVector.x) < 1e-4 && Math.abs(moveVector.y) < 1e-4) return null;
		return new Vec3(moveVector.x, 0, moveVector.y)
				.normalize()
				.yRot((float) -Math.toRadians(player.getYRot()));
	}

	@OnlyIn(Dist.CLIENT)
	@Nullable
	private Vec3 getJumpDirection(Player player, Vec3 wall) {
		if (wall == null) return null;
		wall = wall.normalize();

		// Minecraft-bu (eruto) patch: kick where the player is *steering*, not
		// where they are looking.
		//
		// Upstream only allowed a wall jump when the look direction was at least
		// `acceptable_angle_wall_jump` away from the wall. At our 110 that means
		// facing the wall - the pose you are in the instant after running into
		// one - could never kick off, and WallJumpAnimationType.Back (picked when
		// the look is within 45 degrees of the wall) was unreachable, so its
		// camera flip never played once.
		//
		// Steering carries the intent better than the eyes do: A/D kicks
		// sideways, W into the wall or S kicks straight back, and holding no
		// movement key falls through to the old look-angle rule below - which is
		// what keeps standing next to a wall while building from firing.
		Vec3 steering = getSteeringDirection(player);
		if (steering != null) {
			Vec3 kick = wall.dot(steering) > 0 ? wall.reverse() : steering;
			return kick.normalize().add(wall.scale(-0.7)).normalize();
		}

		Vec3 lookVec = player.getLookAngle();
		Vec3 vec = new Vec3(lookVec.x(), 0, lookVec.z()).normalize();
		Vec3 value;
		double dotProduct = wall.dot(vec);

        if (dotProduct > -Math.cos(Math.toRadians(ParCoolConfig.Client.Integers.AcceptableAngleOfWallJump.get()))) {
            return null;
		}
		if (dotProduct > 0) {//To Wall
			double dot = vec.reverse().dot(wall);
			value = vec.add(wall.scale(2 * dot / wall.length()));
		} else {//back on Wall
			value = vec;
		}

        return value.normalize().add(wall.scale(-0.7)).normalize();
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public boolean canStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		if (inPossibleState && isInputDone() && startInfoTempBuffer.hasRemaining()) {
			startInfo.put(startInfoTempBuffer);
			startInfoTempBuffer.rewind();
			return true;
		}
		return false;
	}

	@OnlyIn(Dist.CLIENT)
	public boolean isInputDone() {
		ControlType control = ParCoolConfig.Client.WallJumpControl.get();
		return (control == ControlType.PressKey && KeyRecorder.keyWallJump.isPressed()) || (control == ControlType.ReleaseKey && KeyRecorder.keyWallJump.isReleased());
	}

	@OnlyIn(Dist.CLIENT)
	public boolean checkCanStart(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startInfo) {
		Vec3 wallDirection = WorldUtil.getWall(player, player.getBbWidth() * 0.65);
		Vec3 jumpDirection = getJumpDirection(player, wallDirection);
		if (jumpDirection == null) return false;
		ClingToCliff cling = parkourability.get(ClingToCliff.class);

		boolean value = (!stamina.isExhausted()
				&& !player.onGround()
				&& !player.isInWaterOrBubble()
				&& !player.isFallFlying()
				&& !player.getAbilities().flying
				&& parkourability.getAdditionalProperties().getNotCreativeFlyingTick() > 10
				&& ((!cling.isDoing() && cling.getNotDoingTick() > 3)
				|| (cling.isDoing() && cling.getFacingDirection() != ClingToCliff.FacingDirection.ToWall))
				&& !parkourability.get(Crawl.class).isDoing()
				&& !parkourability.get(VerticalWallRun.class).isDoing()
                && !parkourability.get(RideZipline.class).isDoing()
				&& parkourability.getAdditionalProperties().getNotLandingTick() > 4
				&& !isInCooldown(parkourability)
		);
		if (!value) return false;

		//doing "wallDirection/jumpDirection" as complex number(x + z i) to calculate difference of player's direction to wall
		Vec3 dividedVec =
				new Vec3(
						wallDirection.x() * jumpDirection.x() + wallDirection.z() * jumpDirection.z(), 0,
						-wallDirection.x() * jumpDirection.z() + wallDirection.z() * jumpDirection.x()
				).normalize();
		Vec3 lookVec = player.getLookAngle().multiply(1, 0, 1).normalize();
		Vec3 lookDividedVec =
				new Vec3(
						lookVec.x() * wallDirection.x() + lookVec.z() * wallDirection.z(), 0,
						-lookVec.x() * wallDirection.z() + lookVec.z() * wallDirection.x()
				).normalize();

		WallJumpAnimationType type;
		// Minecraft-bu (eruto) patch: the backflip reads as "shoved straight off
		// the wall", so ask for that as well as for facing it. Steering sideways
		// while looking at the wall now kicks sideways, and used to get the
		// backflip anyway. dividedVec.x is cos(angle between wall and kick), so
		// < -0.85 means the kick is within ~32 degrees of straight away.
		if (lookDividedVec.x() > 0.707 && dividedVec.x() < -0.85) {
			type = WallJumpAnimationType.Back;
		} else if (dividedVec.z() > 0) {
			type = WallJumpAnimationType.SwingRightArm;
		} else {
			type = WallJumpAnimationType.SwingLeftArm;
		}

        double lookAngleY = player.getLookAngle().normalize().y();
        if (lookAngleY > 0.5) { // Looking upward
            jumpDirection = jumpDirection.add(0, lookAngleY * 2, 0).normalize();
        } else {
            jumpDirection = jumpDirection.add(0, 1, 0).normalize();
        }
		startInfo
				.putDouble(jumpDirection.x())
                .putDouble(jumpDirection.y())
				.putDouble(jumpDirection.z())
				.putDouble(wallDirection.x())
				.putDouble(wallDirection.z())
				.put(type.getCode());
		return true;
	}

	@Override
	public boolean canContinue(Player player, Parkourability parkourability, IStamina stamina) {
		return false;
	}

	@Override
    public void onStart(Player player, Parkourability parkourability, ByteBuffer startData) {
		jump = true;
		player.fallDistance = 0;
	}

	@Override
	public void onStartInLocalClient(Player player, Parkourability parkourability, IStamina stamina, ByteBuffer startData) {
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
            player.playSound(SoundEvents.WALL_JUMP.get(), 1f, 1f);
        Vec3 jumpDirection = new Vec3(startData.getDouble(), startData.getDouble(), startData.getDouble());
        Vec3 jumpMotion = jumpDirection.scale(0.59);
		Vec3 wallDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
		Vec3 motion = player.getDeltaMovement();

		BlockPos leanedBlock = new BlockPos(
				Mth.floor(player.getX() + wallDirection.x()),
				Mth.floor(player.getBoundingBox().minY + player.getBbHeight() * 0.25),
				Mth.floor(player.getZ() + wallDirection.z())
		);
		float slipperiness = player.getCommandSenderWorld().isLoaded(leanedBlock) ?
				player.getCommandSenderWorld().getBlockState(leanedBlock).getFriction(player.getCommandSenderWorld(), leanedBlock, player)
				: 0.6f;

		double ySpeed;
		if (slipperiness > 0.9) {// icy blocks
			ySpeed = motion.y();
		} else {
            ySpeed = motion.y() > jumpMotion.y() ? motion.y + jumpMotion.y() : jumpMotion.y();
            spawnJumpParticles(player, wallDirection, jumpDirection);
		}
		player.setDeltaMovement(
                motion.x() + jumpMotion.x(),
				ySpeed,
                motion.z() + jumpMotion.z()
		);

		WallJumpAnimationType type = WallJumpAnimationType.fromCode(startData.get());
		Animation animation = Animation.get(player);
		if (animation != null) {
			switch (type) {
				case Back:
					animation.setAnimator(new BackwardWallJumpAnimator());
					break;
				case SwingLeftArm:
					animation.setAnimator(new WallJumpAnimator(false));
					break;
				case SwingRightArm:
					animation.setAnimator(new WallJumpAnimator(true));
			}
		}
	}

	@Override
	public void onStartInOtherClient(Player player, Parkourability parkourability, ByteBuffer startData) {
		if (ParCoolConfig.Client.Booleans.EnableActionSounds.get())
			player.playSound(SoundEvents.WALL_JUMP.get(), 1f, 1f);
		Vec3 jumpDirection = new Vec3(startData.getDouble(), startData.getDouble(), startData.getDouble());
		Vec3 wallDirection = new Vec3(startData.getDouble(), 0, startData.getDouble());
        BlockPos leanedBlock = new BlockPos(
				Mth.floor(player.getX() + wallDirection.x()),
				Mth.floor(player.getBoundingBox().minY + player.getBbHeight() * 0.25),
				Mth.floor(player.getZ() + wallDirection.z())
        );
        float slipperiness = player.level().isLoaded(leanedBlock) ?
                player.level().getBlockState(leanedBlock).getFriction(player.level(), leanedBlock, player)
                : 1f;
        if (slipperiness <= 0.9) {// icy blocks
            spawnJumpParticles(player, wallDirection, jumpDirection);
        }

		WallJumpAnimationType type = WallJumpAnimationType.fromCode(startData.get());
		Animation animation = Animation.get(player);
		if (animation != null) {
			switch (type) {
				case Back:
					animation.setAnimator(new BackwardWallJumpAnimator());
					break;
				case SwingLeftArm:
					animation.setAnimator(new WallJumpAnimator(false));
					break;
				case SwingRightArm:
					animation.setAnimator(new WallJumpAnimator(true));
			}
		}
	}

	@Override
	public void onWorkingTickInClient(Player player, Parkourability parkourability, IStamina stamina) {
		super.onWorkingTickInClient(player, parkourability, stamina);
	}

	@OnlyIn(Dist.CLIENT)
	private void spawnJumpParticles(Player player, Vec3 wallDirection, Vec3 jumpDirection) {
		if (!ParCoolConfig.Client.Booleans.EnableActionParticles.get()) return;
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

        Vec3 horizontalJumpDirection = jumpDirection.multiply(1, 0, 1).normalize();

        wallDirection = wallDirection.normalize();
        Vec3 orthogonalToWallVec = wallDirection.yRot((float) (Math.PI / 2)).normalize();

        //doing "Conjugate of (horizontalJumpDirection/-wallDirection)" as complex number(x + z i)
        Vec3 differenceVec =
                new Vec3(
                        -wallDirection.x() * horizontalJumpDirection.x() - wallDirection.z() * horizontalJumpDirection.z(), 0,
                        wallDirection.z() * horizontalJumpDirection.x() - wallDirection.x() * horizontalJumpDirection.z()
                ).multiply(1, 0, -1).normalize();
        Vec3 particleBaseDirection =
                new Vec3(
                        -wallDirection.x() * differenceVec.x() + wallDirection.z() * differenceVec.z(), 0,
                        -wallDirection.x() * differenceVec.z() - wallDirection.z() * differenceVec.x()
                );
        if (blockstate.getRenderShape() != RenderShape.INVISIBLE) {
            for (int i = 0; i < 10; i++) {
                Vec3 particlePos = new Vec3(
                        pos.x() + (wallDirection.x() * 0.4 + orthogonalToWallVec.x() * (player.getRandom().nextDouble() - 0.5D)) * width,
                        pos.y() + 0.1D + 0.3 * player.getRandom().nextDouble(),
                        pos.z() + (wallDirection.z() * 0.4 + orthogonalToWallVec.z() * (player.getRandom().nextDouble() - 0.5D)) * width
                );
                Vec3 particleSpeed = particleBaseDirection
                        .yRot((float) (Math.PI * 0.2 * (player.getRandom().nextDouble() - 0.5)))
                        .scale(3 + 9 * player.getRandom().nextDouble())
                        .add(0, -jumpDirection.y() * 3 * player.getRandom().nextDouble(), 0);
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

	private enum WallJumpAnimationType {
		Back, SwingRightArm, SwingLeftArm;

		public byte getCode() {
			return (byte) this.ordinal();
		}

		public static WallJumpAnimationType fromCode(byte code) {
			return values()[code];
		}
	}
}
