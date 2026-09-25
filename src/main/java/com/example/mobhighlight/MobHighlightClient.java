package com.example.mobhighlight;

import com.example.mobhighlight.command.ModCommands;
import com.example.mobhighlight.config.ModConfig;
import com.example.mobhighlight.core.FactionResolver;
import com.example.mobhighlight.core.GlowApplier;
import com.example.mobhighlight.input.ModKeyBindings;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 唯一入口点。所有注册集中在这里。
 *
 * <p>本模组是纯客户端模组：不注册网络包、不加 Mixin，只挂 Fabric 的客户端事件。</p>
 */
public final class MobHighlightClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 1. 配置：读写 config/mobhighlight.json
		ModConfig.load();

		// 2. 渲染：抽取结束后改写 EntityRenderState#outlineColor —— 发光轮廓本体
		GlowApplier.register();

		// 3. 输入：J 打开配置界面，另有总开关与半径快捷键
		ModKeyBindings.register();

		// 4. 低频校正：用附近真实存在的生物修正分类判定（唯一一次遍历世界，且有频率上限）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ModKeyBindings.onClientTick(client);

			Vec3 camera = client.gameRenderer.getMainCamera().position();
			FactionResolver.maybeRefine(client.level, camera, ModConfig.get().radius);
		});

		// 5. 客户端命令 /mobhl ...
		ModCommands.register();

		ModConfig config = ModConfig.get();

		MobHighlight.LOGGER.info("Mob Highlight 已加载：半径 {} 格，{} 条实体级规则，{} 条自定义规则",
				config.radius, config.activeOverrideCount(), config.customRules.size());
	}
}
