package com.example.mobhighlight.core;

import java.util.List;

import com.example.mobhighlight.config.ModConfig;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 发光轮廓本体。
 *
 * <p><b>原理（1.21.11 实测）：</b>原版本来就有一整套「实体发光轮廓」管线 —— 就是光灵箭命中后
 * 那种贴着模型、且能透视看到的描边。每帧抽取阶段结束后，每个实体的
 * {@link EntityRenderState} 上有一个公开的 {@code outlineColor} 字段；提交阶段原版实体渲染器
 * 会据此把该实体的模型额外画进 {@code entityOutlineTarget}，最后由轮廓后处理链叠加到主画面上。</p>
 *
 * <p>我们要做的只有一件事：把符合条件的状态的 {@code outlineColor} 改成目标颜色，
 * 并把 {@link LevelRenderState#haveGlowingEntities} 置为 true。</p>
 *
 * <h3>为什么必须是 END_EXTRACTION 这个时机</h3>
 * <p>{@code renderLevel} 的顺序是：
 * {@code extractVisibleEntities → WorldBorderRenderer.extract（Fabric 的 END_EXTRACTION 就在这里触发）
 * → 新建 FrameGraphBuilder → 读取 haveGlowingEntities 决定是否挂轮廓后处理链 → addMainPass → submitEntities}。
 * 这里既拿得到全部已抽取好的状态，又恰好赶在原版做「要不要描边」的决策之前。
 * 而 {@link WorldExtractionContext} 还额外提供了 {@link WorldExtractionContext#world()} 与
 * {@link WorldExtractionContext#camera()}，所以「穿墙显示」所需的射线检测也能在这段里做。</p>
 *
 * <h3>性能</h3>
 * <p>完全复用原版抽取结果：这些实体<b>已经被视锥和渲染距离剔除过</b>，并且自带现成的
 * {@code distanceToCameraSq}，所以不存在「每帧遍历世界」，每帧只做 O(可见实体数) 次
 * Map 查找 + 平方距离比较，外加 {@code maxGlow} 硬上限兜底。</p>
 *
 * <p>唯一额外开销来自关闭「穿墙显示」后的视线射线检测，用 {@code maxOcclusionRays}
 * 限制每帧最多发多少条射线，超预算的直接跳过。</p>
 */
public final class GlowApplier {
	private GlowApplier() {
	}

	public static void register() {
		WorldRenderEvents.END_EXTRACTION.register(context -> apply(context));
	}

	private static void apply(WorldExtractionContext context) {
		ModConfig config = ModConfig.get();

		if (!config.enabled) {
			return;
		}

		LevelRenderState levelState = context.worldState();
		List<EntityRenderState> states = levelState.entityRenderStates;

		if (states == null || states.isEmpty()) {
			return;
		}

		double radius = config.radius;

		if (radius <= 0.0D) {
			return;
		}

		double radiusSq = radius * radius;
		int limit = Math.max(1, config.maxGlow);
		int marked = 0;

		// 穿墙显示 = 原版发光的行为（轮廓直接叠在最上层）。关掉它才需要额外的视线检测。
		boolean needsLineOfSight = !config.seeThrough;
		ClientLevel level = needsLineOfSight ? context.world() : null;
		Vec3 eye = needsLineOfSight ? context.camera().position() : null;
		int maxRays = Math.max(1, config.maxOcclusionRays);
		int rays = 0;

		for (EntityRenderState state : states) {
			// 半径剔除：distanceToCameraSq 是原版抽取时顺手算好的，这里免费
			if (state.distanceToCameraSq > radiusSq) {
				continue;
			}

			EntityType<?> type = state.entityType;

			if (type == null) {
				continue;
			}

			if (config.ignoreInvisible && state.isInvisible) {
				continue;
			}

			// 第三人称下的自己：离相机极近的那个 PLAYER
			if (!config.glowSelf && type == EntityType.PLAYER && state.distanceToCameraSq < 1.0D) {
				continue;
			}

			HighlightRules.Outcome outcome = HighlightRules.forType(type);

			if (!outcome.enabled()) {
				continue;
			}

			if (needsLineOfSight) {
				if (rays >= maxRays) {
					break;
				}

				rays++;

				// 近在咫尺的就不用算了
				if (state.distanceToCameraSq > 4.0D && isOccluded(level, eye, state)) {
					continue;
				}
			}

			state.outlineColor = outcome.argb();
			marked++;

			if (marked >= limit) {
				break;
			}
		}

		if (marked > 0) {
			levelState.haveGlowingEntities = true;
		}
	}

	/**
	 * 相机到实体之间是否有遮挡。
	 *
	 * <p>用的是 {@link ClipContext.Block#VISUAL}：看的到底是方块的视觉形状，
	 * 所以玻璃这类透明方块同样会被判定为遮挡（语义上就是「中间隔着东西」）。</p>
	 */
	private static boolean isOccluded(ClientLevel level, Vec3 eye, EntityRenderState state) {
		if (level == null || eye == null) {
			return false;
		}

		Vec3 target = new Vec3(state.x, state.y + state.boundingBoxHeight * 0.5D, state.z);
		double targetDistSq = eye.distanceToSqr(target);

		if (targetDistSq < 1.0E-6D) {
			return false;
		}

		BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));

		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			return false;
		}

		return hit.getLocation().distanceToSqr(eye) < targetDistSq - 0.01D;
	}

	/** 配置变更后同步清缓存，保证下一次渲染立刻生效。 */
	public static void invalidateRules() {
		HighlightRules.invalidate();
		FactionResolver.invalidate();
	}
}
