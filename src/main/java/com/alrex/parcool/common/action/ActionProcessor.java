package com.alrex.parcool.common.action;

import com.alrex.parcool.api.Attributes;
import com.alrex.parcool.ParCool;
import com.alrex.parcool.api.unstable.action.ParCoolActionEvent;
import com.alrex.parcool.common.capability.Animation;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.network.SyncActionStateMessage;
import com.alrex.parcool.common.network.SyncStaminaMessage;
import com.alrex.parcool.common.network.SyncStaminaToClientMessage;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.utilities.BufferUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.apache.logging.log4j.Level;

import java.nio.ByteBuffer;
import java.util.List;

public class ActionProcessor {
	private final ByteBuffer bufferOfPostState = ByteBuffer.allocate(128);
	private final ByteBuffer bufferOfPreState = ByteBuffer.allocate(128);
	private final ByteBuffer bufferOfStarting = ByteBuffer.allocate(128);
	private int staminaSyncCoolTimeTick = 0;


	@SubscribeEvent
	public void onTick(TickEvent.PlayerTickEvent event) {
		if (event.phase == TickEvent.Phase.START) return;

		var player = event.player;
		IStamina stamina = IStamina.get(player);
		if (stamina == null) return;
		Parkourability parkourability = Parkourability.get(player);
		if (parkourability == null) return;

		boolean inClient = event.side == LogicalSide.CLIENT;
		boolean inServer = !inClient;

		onTick$doPreprocess(event, stamina);
		if (inClient) {
			onTick$doPreprocessInClient(event, parkourability);
		} else {
			onTick$doPreprocessInServer(event);
		}

		List<Action> actions = parkourability.getList();
		boolean needSync = player.isLocalPlayer();
		SyncActionStateMessage.Encoder builder = SyncActionStateMessage.Encoder.reset();

		if (needSync) {
			onTick$checkLimitationSynchronization(player, parkourability);
		}

		parkourability.getAdditionalProperties().onTick(player, parkourability);
		for (Action action : actions) {
			MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.Tick.Pre(player, action));
			processAction(player, parkourability, stamina, builder, inClient, action);
			MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.Tick.Post(player, action));
		}
		if (needSync) {
			onTick$sendSynchronizationPacket(player, parkourability, stamina, builder);
			if (stamina.isImposingExhaustionPenalty() && parkourability.getClientInfo().get(ParCoolConfig.Client.Booleans.EnableStaminaExhaustionPenalty)) {
				player.setSprinting(false);
			}
		}

		if (inServer) {
			SyncStaminaToClientMessage.tick();
		}
	}

	private void onTick$doPreprocess(TickEvent.PlayerTickEvent event, IStamina stamina) {
		stamina.tick();
	}

	private void onTick$doPreprocessInServer(TickEvent.PlayerTickEvent event) {

	}

	@OnlyIn(Dist.CLIENT)
	private void onTick$doPreprocessInClient(TickEvent.PlayerTickEvent event, Parkourability parkourability) {
		var player = (AbstractClientPlayer) event.player;
		Animation animation = Animation.get(player);
		if (animation == null) return;
		animation.tick(player, parkourability);
	}

    @OnlyIn(Dist.CLIENT)
	private void onTick$checkLimitationSynchronization(Player player, Parkourability parkourability) {
		if (player.isLocalPlayer() && player.tickCount > 127 && player.tickCount % 256 == 0 && parkourability.limitationIsNotSynced()) {
			if (player instanceof LocalPlayer localPlayer) {
				int trialCount = parkourability.getSynchronizeTrialCount();
				if (trialCount < 5) {
					parkourability.trySyncLimitation(localPlayer);
					if (ParCoolConfig.Client.Booleans.ShowAutoResynchronizationNotification.get()) {
						player.displayClientMessage(Component.translatable("parcool.message.error.limitation.not_synced"), false);
					}
					ParCool.LOGGER.log(Level.WARN, "Detected ParCool Limitation is not synced. Sending synchronization request...");
				} else if (trialCount == 5) {
					parkourability.incrementSynchronizeTrialCount();
					player.displayClientMessage(Component.translatable("parcool.message.error.limitation.fail_sync").withStyle(ChatFormatting.DARK_RED), false);
					ParCool.LOGGER.log(Level.ERROR, "Failed to synchronize ParCool Limitation. There may be problems about server connection. Please report to the developer after checking connection");
				}
			}
		}
	}

    @OnlyIn(Dist.CLIENT)
	private void onTick$sendSynchronizationPacket(Player player, Parkourability parkourability, IStamina stamina, SyncActionStateMessage.Encoder builder) {
        if (!builder.isEmpty()) {
			SyncActionStateMessage.sync(player, builder);
        }

		staminaSyncCoolTimeTick++;
		if (!parkourability.limitationIsNotSynced() && (staminaSyncCoolTimeTick > 5 || stamina.wantToConsumeOnServer())) {
			staminaSyncCoolTimeTick = 0;
			SyncStaminaMessage.sync(player);
		}
	}

	private void processAction(Player player, Parkourability parkourability, IStamina stamina, SyncActionStateMessage.Encoder builder, boolean inClientSide, Action action) {
		boolean needSync = player.isLocalPlayer();

		if (needSync) {
			saveSynchronizationState(action, bufferOfPreState);
		}
		action.tick();

		action.onTick(player, parkourability, stamina);
		if (inClientSide) {
			action.onClientTick(player, parkourability, stamina);
		} else {
			action.onServerTick(player, parkourability, stamina);
		}

		if (needSync) {
			checkAndChangeActionState(player, parkourability, stamina, action, builder);
		}

		if (action.isDoing()) {
			action.onWorkingTick(player, parkourability, stamina);
			if (inClientSide) {
				action.onWorkingTickInClient(player, parkourability, stamina);
				if (needSync) {
					action.onWorkingTickInLocalClient(player, parkourability, stamina);
					if (action.getStaminaConsumeTiming() == StaminaConsumeTiming.OnWorking) {
						stamina.consume(parkourability.getActionInfo().getStaminaConsumptionOf(action.getClass()));
					}
				} else {
					action.onWorkingTickInOtherClient(player, parkourability, stamina);
				}
			} else {
				action.onWorkingTickInServer(player, parkourability, stamina);
			}
		}

		if (needSync) {
			saveSynchronizationState(action, bufferOfPostState);

			if (!BufferUtil.haveSameContents(bufferOfPreState, bufferOfPostState)) {
				bufferOfPostState.rewind();
				builder.appendSyncData(parkourability, action, bufferOfPostState);
				bufferOfPreState.clear();
				bufferOfPostState.clear();
			}
		}
	}

	@OnlyIn(Dist.CLIENT)
	private void checkAndChangeActionState(Player player, Parkourability parkourability, IStamina stamina, Action action, SyncActionStateMessage.Encoder builder) {
		// Minecraft-bu (eruto) patch: per-player ParCool switch.
		// Every action, instant ones included, reaches this method from the one
		// loop in onTick, so gating here covers all of them at once.
		// Reads as 1.0 for anyone Origins does not touch, i.e. unchanged.
		boolean erutoParkourAllowed = player.getAttributeValue(Attributes.PARKOUR.get()) > 0.5;
		if (action.isDoing()) {
			boolean canContinue = erutoParkourAllowed
					&& parkourability.getActionInfo().can(action.getClass())
					&& !MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.TryToContinueEvent(player, action))
					&& !MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.TryToContinue(player, action))
					&& action.canContinue(player, parkourability, stamina);
			if (!canContinue) {
				MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.Finish.Pre(player, action));
				action.finish(player);
				MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.StopEvent(player, action));
				MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.Finish.Post(player, action));
				builder.appendFinishMsg(parkourability, action);
			}
		} else {
			bufferOfStarting.clear();
			boolean start = erutoParkourAllowed
					&& !player.isSpectator()
					&& parkourability.getActionInfo().can(action.getClass())
					&& !MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.TryToStartEvent(player, action))
					&& !MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.TryToStart(player, action))
					&& action.canStart(player, parkourability, stamina, bufferOfStarting);
			bufferOfStarting.flip();
			if (start) {
				MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.Start.Pre(player, action));
				action.start(player, parkourability, bufferOfStarting, stamina);
				MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.StartEvent(player, action));
				MinecraftForge.EVENT_BUS.post(new ParCoolActionEvent.Start.Post(player, action));
				if (action.getStaminaConsumeTiming() == StaminaConsumeTiming.OnStart)
					stamina.consume(parkourability.getActionInfo().getStaminaConsumptionOf(action.getClass()));
				builder.appendStartData(parkourability, action, bufferOfStarting);
			}
		}
	}

	private void saveSynchronizationState(Action action, ByteBuffer buffer) {
		buffer.clear();
		action.saveSynchronizedState(buffer);
		buffer.flip();
	}

	// ====

	@OnlyIn(Dist.CLIENT)
	@SubscribeEvent
	public void onRenderTick(TickEvent.RenderTickEvent event) {
		Player clientPlayer = Minecraft.getInstance().player;
		if (clientPlayer == null) return;
		for (Player player : clientPlayer.getCommandSenderWorld().players()) {
			Parkourability parkourability = Parkourability.get(player);
			if (parkourability == null) return;
			List<Action> actions = parkourability.getList();
			for (Action action : actions) {
				action.onRenderTick(event, player, parkourability);
			}
			Animation animation = Animation.get(player);
			if (animation == null) return;
			animation.onRenderTick(event, player, parkourability);
		}
	}

	@OnlyIn(Dist.CLIENT)
	@SubscribeEvent
	public void onViewRender(ViewportEvent.ComputeCameraAngles event) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		Parkourability parkourability = Parkourability.get(player);
		if (parkourability == null) return;
		Animation animation = Animation.get(player);
		if (animation == null) return;
		animation.cameraSetup(event, player, parkourability);
	}
}
