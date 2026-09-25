package com.example.mobhighlight.command;

import java.text.DecimalFormat;
import java.util.List;

import com.example.mobhighlight.config.Faction;
import com.example.mobhighlight.config.ModConfig;
import com.example.mobhighlight.core.FactionResolver;
import com.example.mobhighlight.core.GlowApplier;
import com.example.mobhighlight.core.HighlightRules;
import com.example.mobhighlight.ui.ConfigScreen;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * 客户端命令 {@code /mobhl ...}。
 *
 * <p>用客户端命令的好处：服务端不用装模组，联机也能用（且完全不来往网络包）。</p>
 */
public final class ModCommands {
	private static final DecimalFormat NUMBER = new DecimalFormat("0.#");

	private ModCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("mobhl")
						.executes(context -> openConfig(context.getSource()))
						.then(ClientCommandManager.literal("toggle")
								.executes(context -> toggle(context.getSource())))
						.then(ClientCommandManager.literal("config")
								.executes(context -> openConfig(context.getSource())))
						.then(ClientCommandManager.literal("radius")
								.executes(context -> showRadius(context.getSource()))
								.then(ClientCommandManager.argument("blocks", DoubleArgumentType.doubleArg(4.0D, 128.0D))
										.executes(context -> setRadius(context.getSource(), DoubleArgumentType.getDouble(context, "blocks")))))
						.then(ClientCommandManager.literal("max")
								.then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 512))
										.executes(context -> setMax(context.getSource(), IntegerArgumentType.getInteger(context, "count")))))
						.then(ClientCommandManager.literal("scan")
								.then(ClientCommandManager.argument("millis", IntegerArgumentType.integer(16, 5000))
										.executes(context -> setScanInterval(context.getSource(), IntegerArgumentType.getInteger(context, "millis")))))
						.then(ClientCommandManager.literal("rule")
								.then(ClientCommandManager.argument("id", IdentifierArgument.id())
										.suggests((context, builder) -> {
											for (Faction faction : Faction.values()) {
												builder.suggest(faction.id);
											}

											return builder.buildFuture();
										})
										.executes(context -> toggleFaction(context.getSource(), context.getArgument("id", Identifier.class)))))
						.then(ClientCommandManager.literal("reload")
								.executes(context -> reload(context.getSource())))
						.then(ClientCommandManager.literal("reset")
								.executes(context -> reset(context.getSource())))
						.then(ClientCommandManager.literal("status")
								.executes(context -> status(context.getSource())))));
	}

	private static int openConfig(FabricClientCommandSource source) {
		// 命令在客户端线程执行，切屏是安全的
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new ConfigScreen()));
		source.sendFeedback(Component.translatable("message.mobhighlight.config_opened"));
		return 1;
	}

	private static int toggle(FabricClientCommandSource source) {
		ModConfig config = ModConfig.get();
		config.enabled = !config.enabled;
		ModConfig.save();
		GlowApplier.invalidateRules();
		source.sendFeedback(Component.translatable(config.enabled
				? "message.mobhighlight.enabled"
				: "message.mobhighlight.disabled"));
		return 1;
	}

	private static int setRadius(FabricClientCommandSource source, double radius) {
		ModConfig.get().radius = radius;
		ModConfig.save();
		source.sendFeedback(Component.translatable("message.mobhighlight.radius", NUMBER.format(ModConfig.get().radius)));
		return 1;
	}

	private static int showRadius(FabricClientCommandSource source) {
		source.sendFeedback(Component.translatable("message.mobhighlight.radius", NUMBER.format(ModConfig.get().radius)));
		return 1;
	}

	private static int setMax(FabricClientCommandSource source, int max) {
		ModConfig.get().maxGlow = max;
		ModConfig.save();
		source.sendFeedback(Component.translatable("message.mobhighlight.max_glow", max));
		return 1;
	}

	private static int setScanInterval(FabricClientCommandSource source, int millis) {
		ModConfig.get().scanIntervalMs = millis;
		ModConfig.save();
		source.sendFeedback(Component.translatable("message.mobhighlight.scan_interval", millis));
		return 1;
	}

	private static int toggleFaction(FabricClientCommandSource source, Identifier id) {
		Faction faction = Faction.fromId(id.getPath());

		if (faction == null) {
			source.sendFeedback(Component.translatable("message.mobhighlight.unknown_rule", id.toString()));
			return 0;
		}

		ModConfig.FactionSetting setting = ModConfig.get().faction(faction);
		setting.enabled = !setting.enabled;
		ModConfig.save();
		HighlightRules.invalidate();
		source.sendFeedback(Component.translatable("message.mobhighlight.rule_toggled",
				Component.translatable(faction.translationKey()), onOff(setting.enabled)));
		return 1;
	}

	private static int reload(FabricClientCommandSource source) {
		ModConfig.load();
		GlowApplier.invalidateRules();
		source.sendFeedback(Component.translatable("message.mobhighlight.reloaded"));
		return 1;
	}

	private static int reset(FabricClientCommandSource source) {
		ModConfig.get().reset();
		ModConfig.save();
		GlowApplier.invalidateRules();
		source.sendFeedback(Component.translatable("message.mobhighlight.reset"));
		return 1;
	}

	private static int status(FabricClientCommandSource source) {
		ModConfig config = ModConfig.get();
		StringBuilder factions = new StringBuilder();

		for (Faction faction : Faction.values()) {
			if (factions.length() > 0) {
				factions.append(", ");
			}

			factions.append(Component.translatable(faction.translationKey()).getString())
					.append('=')
					.append(config.factionEnabled(faction) ? Faction.toHex(config.factionColor(faction)) : "off");
		}

		List<String> overrides = List.copyOf(config.typeOverrides.keySet());
		StringBuilder types = new StringBuilder();

		for (int index = 0; index < Math.min(overrides.size(), 12); index++) {
			String key = overrides.get(index);
			// 注意：IdentifierArgument.getId 只接受 CommandSourceStack，客户端命令这里直接取参
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(key))
					.map(net.minecraft.core.Holder.Reference::value)
					.orElse(null);
			String name = type == null ? key : FactionResolver.displayName(type);

			if (types.length() > 0) {
				types.append(", ");
			}

			types.append(name).append('=').append(config.override(key).name().toLowerCase(java.util.Locale.ROOT));
		}

		if (overrides.size() > 12) {
			types.append(", ...");
		}

		source.sendFeedback(Component.translatable("message.mobhighlight.status",
				onOff(config.enabled).getString(),
				NUMBER.format(config.radius),
				config.maxGlow,
				config.scanIntervalMs,
				factions.toString(),
				types.length() == 0 ? "-" : types.toString()));
		return 1;
	}

	private static Component onOff(boolean value) {
		return Component.translatable(value ? "message.mobhighlight.on" : "message.mobhighlight.off");
	}
}
