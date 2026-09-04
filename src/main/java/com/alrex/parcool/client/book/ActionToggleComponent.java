package com.alrex.parcool.client.book;

import com.alrex.parcool.api.Attributes;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.ActionList;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.info.ClientSetting;
import com.alrex.parcool.common.network.SyncClientInformationMessage;
import com.alrex.parcool.config.ParCoolConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import vazkii.patchouli.api.IComponentRenderContext;
import vazkii.patchouli.api.ICustomComponent;
import vazkii.patchouli.api.IVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * マイクラ部（eruto）の追加: 手引書の技の頁に「入／切」のトグルを置く。
 *
 * <p>⚠⚠ <b>なぜ本に置くか</b>: 技ごとの入切は `Alt+P` の設定画面に在るが、
 * ⚠ <b>その画面が在ることを知る手段が事実上無い</b>（本にも書いていない）。
 * 技の説明を読んだその場で切り替えられるほうが、たどり着ける人が増える。
 *
 * <p>⚠ <b>本から覆せない軸が2つある。</b>押せるのに使えない状態を作らないため、
 * 3つの状態を出し分ける:
 *
 * <table>
 *   <tr><td>使える</td><td>押せるトグル（緑＝入 ／ 灰＝切）</td></tr>
 *   <tr><td>サーバが禁じている</td><td>押せない。理由を出す（`permit_&lt;技&gt;`）</td></tr>
 *   <tr><td>種族が持っていない</td><td>押せない。理由を出す（`parcool:parkour` 属性）</td></tr>
 * </table>
 *
 * <p>⚠ 保存の手順は既存の設定画面（`SettingActionLimitationScreen.save()`）と同じ3手に
 * そろえてある——⚠ <b>設定値を書く → `ClientSetting` を読み直す → サーバへ同期する</b>。
 * ⚠ どれか1つでも欠けると、画面上は変わったのに実際の判定が変わらない。
 *
 * <p>⚠ JSON の欄はそのままこのクラスの欄に入る（`ComponentCustom` が Gson で流し込む）。
 */
@OnlyIn(Dist.CLIENT)
public class ActionToggleComponent implements ICustomComponent {

	/**
	 * 技のクラス名（`ActionList.NAMES` の綴り。例: "WallJump"）。JSON から入る。
	 *
	 * <p>⚠⚠ <b>型は `String` ではなく `IVariable`。</b> テンプレートの `#action` のような
	 * 変数を使うには、⚠ <b>`lookup.apply(欄)` に通す必要がある</b>ので、
	 * 解決前の値を持てる型でなければならない（`ComponentText` が同じ形）。
	 */
	IVariable action;

	private transient int posX;
	private transient int posY;
	private transient String actionName;
	private transient Class<? extends Action> actionClass;

	private static final int WIDTH = 90;
	private static final int HEIGHT = 16;

	// ⚠ 押せるとき（入）／押せるとき（切）／押せないとき の3色。
	private static final int COLOR_ON = 0xFF2E7D32;
	private static final int COLOR_OFF = 0xFF616161;
	private static final int COLOR_LOCKED = 0xFF4A3B3B;
	private static final int COLOR_BORDER = 0xFF000000;

	@Override
	public void onVariablesAvailable(UnaryOperator<IVariable> lookup) {
		// ⚠ ここで `#action` のような変数が実際の値になる（`ComponentText` と同じ形）。
		action = lookup.apply(action);
		actionName = action == null ? "" : action.asString();
	}

	@Override
	public void build(int componentX, int componentY, int pageNum) {
		this.posX = componentX;
		this.posY = componentY;
		// ⚠ 綴りが違えば null。⚠⚠ **落とさずに「引けない」と画面へ出す**
		//    （本の頁が丸ごと出なくなるほうが困る）。
		this.actionClass = ActionList.getByName(actionName);
	}

	@Override
	public void render(GuiGraphics graphics, IComponentRenderContext context,
	                   float partialTicks, int mouseX, int mouseY) {
		if (actionClass == null) {
			graphics.drawString(Minecraft.getInstance().font,
					Component.literal("? " + actionName),
					posX, posY + 4, 0xFFAA0000, false);
			return;
		}
		State state = currentState();
		int fill = switch (state) {
			case ON -> COLOR_ON;
			case OFF -> COLOR_OFF;
			default -> COLOR_LOCKED;
		};
		graphics.fill(posX - 1, posY - 1, posX + WIDTH + 1, posY + HEIGHT + 1, COLOR_BORDER);
		graphics.fill(posX, posY, posX + WIDTH, posY + HEIGHT, fill);

		Component label = switch (state) {
			case ON -> Component.translatable("parcool.book.toggle.on");
			case OFF -> Component.translatable("parcool.book.toggle.off");
			case LOCKED_BY_SERVER -> Component.translatable("parcool.book.toggle.server");
			case LOCKED_BY_ORIGIN -> Component.translatable("parcool.book.toggle.origin");
		};
		var font = Minecraft.getInstance().font;
		int tw = font.width(label);
		graphics.drawString(font, label,
				posX + (WIDTH - tw) / 2, posY + (HEIGHT - 8) / 2, 0xFFFFFFFF, false);

		if (context.isAreaHovered(mouseX, mouseY, posX, posY, WIDTH, HEIGHT)) {
			List<Component> tip = new ArrayList<>();
			tip.add(Component.translatable("parcool.action." + actionClass.getSimpleName())
					.withStyle(ChatFormatting.WHITE));
			switch (state) {
				case ON, OFF -> tip.add(Component.translatable("parcool.book.toggle.hint")
						.withStyle(ChatFormatting.GRAY));
				case LOCKED_BY_SERVER -> tip.add(
						Component.translatable("parcool.book.toggle.server.hint")
								.withStyle(ChatFormatting.RED));
				case LOCKED_BY_ORIGIN -> tip.add(
						Component.translatable("parcool.book.toggle.origin.hint")
								.withStyle(ChatFormatting.RED));
			}
			context.setHoverTooltipComponents(tip);
		}
	}

	@Override
	public boolean mouseClicked(IComponentRenderContext context,
	                            double mouseX, double mouseY, int mouseButton) {
		if (actionClass == null || mouseButton != 0) return false;
		if (!context.isAreaHovered((int) mouseX, (int) mouseY, posX, posY, WIDTH, HEIGHT)) {
			return false;
		}
		State state = currentState();
		// ⚠ 押せない状態では何もしない（音も鳴らさない）。
		//    ⚠⚠ **押せてしまうと「押したのに使えない」になる。**
		if (state != State.ON && state != State.OFF) return false;

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return false;
		Parkourability parkourability = Parkourability.get(player);
		if (parkourability == null) return false;

		// ⚠ 既存の設定画面と同じ3手。1つでも欠けると判定が変わらない。
		ParCoolConfig.Client.getPossibilityOf(actionClass).set(state != State.ON);
		parkourability.getActionInfo().setClientSetting(ClientSetting.readFromLocalConfig());
		SyncClientInformationMessage.sync(player, true);

		// ⚠ 押したことが分かるように音を1つ。バニラのボタンと同じもの。
		Minecraft.getInstance().getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		return true;
	}

	private State currentState() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return State.OFF;
		// ⚠ 種族の停止（当部の属性）。0.0 のとき全部止まる。
		if (player.getAttributeValue(Attributes.PARKOUR.get()) <= 0.5) {
			return State.LOCKED_BY_ORIGIN;
		}
		Parkourability parkourability = Parkourability.get(player);
		if (parkourability != null
				&& !parkourability.getActionInfo().getServerLimitation().isPermitted(actionClass)) {
			return State.LOCKED_BY_SERVER;
		}
		return ParCoolConfig.Client.getPossibilityOf(actionClass).get() ? State.ON : State.OFF;
	}

	private enum State {ON, OFF, LOCKED_BY_SERVER, LOCKED_BY_ORIGIN}
}
