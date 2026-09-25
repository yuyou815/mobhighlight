package com.example.mobhighlight.core;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.example.mobhighlight.config.Faction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 把任意 {@link EntityType} 归到某个 {@link Faction}，也就是「默认规则」的那七个分类。
 *
 * <p><b>没有硬编码任何生物名单。</b>判定顺序：</p>
 * <ol>
 *   <li>标签（{@code c:hostile} / {@code c:neutral} / {@code c:passive} Fabric 约定标签，
 *       以及 {@code minecraft:undead}、{@code minecraft:raiders} 等原版标签）；标签不存在时自动跳过。</li>
 *   <li>注册时的 {@link net.minecraft.world.entity.MobCategory}（其他模组注册生物时通常都会填）。</li>
 *   <li>{@link #maybeRefine} 用附近真实存在的实体做低频校正：实现了 {@link NeutralMob} 的算中立，
 *       实现了 {@link Enemy} 的算敌对。这样即便某个模组的生物把类别填成了 MISC，也能被正确归类。</li>
 * </ol>
 *
 * <p>结果按 EntityType 缓存，每帧渲染时只做一次 Map 查找。</p>
 */
public final class FactionResolver {
	private static final List<TagKey<EntityType<?>>> NEUTRAL_TAGS = List.of(tag("c", "neutral"));
	private static final List<TagKey<EntityType<?>>> HOSTILE_TAGS = List.of(
			tag("c", "hostile"),
			tag("minecraft", "undead"),
			tag("minecraft", "raiders"));
	private static final List<TagKey<EntityType<?>>> FRIENDLY_TAGS = List.of(tag("c", "passive"));

	private static final java.util.Map<EntityType<?>, Faction> BY_TAG = new ConcurrentHashMap<>();
	private static final java.util.Map<EntityType<?>, Faction> REFINED = new ConcurrentHashMap<>();

	private static long lastRefineMs = 0L;

	private FactionResolver() {
	}

	private static TagKey<EntityType<?>> tag(String namespace, String path) {
		return TagKey.create(BuiltInRegistries.ENTITY_TYPE.key(), Identifier.fromNamespaceAndPath(namespace, path));
	}

	/** 配置变了要清缓存，HighlightRules 会间接受影响。 */
	public static void invalidate() {
		BY_TAG.clear();
		REFINED.clear();
	}

	/**
	 * 低频校正：每隔 {@code scanIntervalMs} 毫秒扫一次半径内的真实实体，
	 * 用它们实现的接口修正纯以注册信息为准的判断结果。
	 * 这是本模组唯一一次遍历世界实体的地方，且是受控频率的，不是每帧。
	 */
	public static void maybeRefine(ClientLevel level, Vec3 cameraPos, double radius) {
		if (level == null || radius <= 0.0D) {
			return;
		}

		long now = System.currentTimeMillis();
		int interval = com.example.mobhighlight.config.ModConfig.get().scanIntervalMs;

		if (now - lastRefineMs < interval) {
			return;
		}

		lastRefineMs = now;

		AABB box = new AABB(
				cameraPos.x - radius, cameraPos.y - radius, cameraPos.z - radius,
				cameraPos.x + radius, cameraPos.y + radius, cameraPos.z + radius);

		boolean changed = false;

		for (Entity entity : level.getEntitiesOfClass(Mob.class, box, candidate -> true)) {
			Faction faction = null;

			if (entity instanceof NeutralMob) {
				faction = Faction.NEUTRAL;
			} else if (entity instanceof Enemy) {
				faction = Faction.HOSTILE;
			}

			if (faction != null) {
				Faction previous = REFINED.put(entity.getType(), faction);

				if (previous != faction) {
					changed = true;
				}
			}
		}

		if (changed) {
			HighlightRules.invalidate();
		}
	}

	public static Faction resolve(EntityType<?> type) {
		Faction refined = REFINED.get(type);

		if (refined != null) {
			return refined;
		}

		return BY_TAG.computeIfAbsent(type, FactionResolver::compute);
	}

	private static Faction compute(EntityType<?> type) {
		if (type == EntityType.PLAYER) {
			return Faction.PLAYER;
		}

		if (matches(type, NEUTRAL_TAGS)) {
			return Faction.NEUTRAL;
		}

		if (matches(type, HOSTILE_TAGS)) {
			return Faction.HOSTILE;
		}

		if (matches(type, FRIENDLY_TAGS)) {
			return Faction.FRIENDLY;
		}

		return switch (type.getCategory()) {
			case MONSTER -> Faction.HOSTILE;
			case CREATURE -> Faction.FRIENDLY;
			case AMBIENT -> Faction.AMBIENT;
			case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> Faction.AQUATIC;
			default -> Faction.OTHER;
		};
	}

	private static boolean matches(EntityType<?> type, List<TagKey<EntityType<?>>> tags) {
		for (TagKey<EntityType<?>> tag : tags) {
			if (type.is(tag)) {
				return true;
			}
		}

		return false;
	}

	/** 给界面用的可读名称：优先用翻译名，没有翻译时退回 id。 */
	public static String displayName(EntityType<?> type) {
		String descriptionId = type.getDescriptionId();
		String translated = Component.translatable(descriptionId).getString();

		if (translated == null || translated.isBlank() || translated.equals(descriptionId)) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			return id != null ? id.toString() : descriptionId;
		}

		return translated;
	}
}
