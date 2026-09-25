package com.example.mobhighlight.input;

import java.text.DecimalFormat;

import com.example.mobhighlight.config.ModConfig;
import com.example.mobhighlight.core.GlowApplier;
import com.example.mobhighlight.ui.ConfigScreen;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import org.lwjgl.glfw.GLFW;

/**
 * 按键绑定。注意 1.21.11 的 {@link KeyMapping} 构造函数只能传 {@link KeyMapping.Category}。
 *
 * <p>默认：<b>H = 开关发光轮廓</b>（总开关）、<b>J = 打开设置界面</b>，
 * <code>[</code> / <code>]</code> 调探测范围。</p>
 *
 * <p>为什么不用 Ctrl+H：1.21.11 的 {@link KeyMapping} 只保存单个按键，不保存修饰键
 * （字段里没有 modifier，{@code matches} 也只比 {@code InputConstants.Key}）。
 * 所以按下 Ctrl+H 时，绑到 H 的那个键同样会被触发，两者无法干净区分。</p>
 */
public final class ModKeyBindings {
	private static final DecimalFormat RADIUS_FORMAT = new DecimalFormat("0.#");

	/** 默认 J：打开设置界面。 */
	public static final KeyMapping OPEN_CONFIG = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.mobhighlight.open_config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KeyMapping.Category.GAMEPLAY));

	/** 默认 H：直接开关发光轮廓。 */
	public static final KeyMapping TOGGLE = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.mobhighlight.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, KeyMapping.Category.GAMEPLAY));

	public static final KeyMapping RADIUS_UP = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.mobhighlight.radius_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, KeyMapping.Category.GAMEPLAY));

	public static final KeyMapping RADIUS_DOWN = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.mobhighlight.radius_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, KeyMapping.Category.GAMEPLAY));

	private ModKeyBindings() {
	}

	/** 静态字段在类加载时完成注册，这里只是强制触发初始化。 */
	public static void register() {
		if (OPEN_CONFIG == null || TOGGLE == null || RADIUS_UP == null || RADIUS_DOWN == null) {
			throw new IllegalStateException("按键绑定注册失败");
		}
	}

	public static void onClientTick(Minecraft client) {
		if (OPEN_CONFIG.consumeClick()) {
			ModConfig.save();
			client.setScreen(new ConfigScreen());
			return;
		}

		if (TOGGLE.consumeClick()) {
			ModConfig config = ModConfig.get();
			config.enabled = !config.enabled;
			ModConfig.save();
			GlowApplier.invalidateRules();
			feedback(client, Component.translatable(config.enabled
					? "message.mobhighlight.enabled"
					: "message.mobhighlight.disabled"));
		}

		if (RADIUS_UP.consumeClick()) {
			setRadius(client, ModConfig.get().radius + 8.0D);
		}

		if (RADIUS_DOWN.consumeClick()) {
			setRadius(client, ModConfig.get().radius - 8.0D);
		}
	}

	private static void setRadius(Minecraft client, double radius) {
		ModConfig config = ModConfig.get();
		config.radius = Math.max(ModConfig.MIN_RADIUS, Math.min(ModConfig.MAX_RADIUS, radius));
		ModConfig.save();
		feedback(client, Component.translatable("message.mobhighlight.radius", RADIUS_FORMAT.format(config.radius)));
	}

	private static void feedback(Minecraft client, Component message) {
		if (client.player != null) {
			client.player.displayClientMessage(message, true);
		}
	}
}
