package com.example.mobhighlight.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.mobhighlight.config.Faction;
import com.example.mobhighlight.config.ModConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * 「某个实体该不该发光、发什么颜色」的最终裁决表。
 *
 * <p>优先级（从上到下）：</p>
 * <ol>
 *   <li>命名空间：某个模组被整体排除时不发光。</li>
 *   <li>单个实体类型的「永不发光」（实体清单页的三态之一）。</li>
 *   <li><b>自定义规则</b>：被挑走的实体用它自己的颜色和开关，压过默认规则。</li>
 *   <li>默认规则：该分类是否启用 + 分类颜色。</li>
 *   <li>单个实体类型的「强制发光」：保留所属规则的颜色，只跳过开关。</li>
 * </ol>
 *
 * <p>结论按 {@link EntityType} 缓存。配置一变就 {@link #invalidate()}，
 * 所以每帧的开销只是一个 Map 查找，不会每次都去读配置。</p>
 */
public final class HighlightRules {
	public record Outcome(boolean enabled, int argb) {
	}

	private static final Outcome OFF = new Outcome(false, 0);

	private static final Map<EntityType<?>, Outcome> CACHE = new ConcurrentHashMap<>();

	private HighlightRules() {
	}

	public static void invalidate() {
		CACHE.clear();
	}

	public static Outcome forType(EntityType<?> type) {
		return CACHE.computeIfAbsent(type, HighlightRules::compute);
	}

	private static Outcome compute(EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		if (id == null) {
			return OFF;
		}

		ModConfig config = ModConfig.get();
		String key = id.toString();

		if (config.namespaceDenied(id.getNamespace())) {
			return OFF;
		}

		if (config.override(key) == ModConfig.Override.DISABLE) {
			return OFF;
		}

		// 归属：被自定义规则挑走的实体走那条规则，其余走默认规则的分类
		ModConfig.CustomRule custom = config.ruleOf(key);
		int rgb;
		boolean on;

		if (custom != null) {
			rgb = custom.color & 0xFFFFFF;
			on = custom.enabled;
		} else {
			Faction faction = FactionResolver.resolve(type);
			rgb = config.factionColor(faction);
			on = config.factionEnabled(faction);
		}

		return on || config.override(key) == ModConfig.Override.ENABLE
				? new Outcome(true, toArgb(rgb))
				: OFF;
	}

	/** 轮廓颜色需要带 alpha 的 ARGB；原版用的是 {@code ARGB.opaque(...)}。 */
	public static int toArgb(int rgb) {
		return 0xFF000000 | (rgb & 0xFFFFFF);
	}
}
