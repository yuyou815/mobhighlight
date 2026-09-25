package com.example.mobhighlight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

/**
 * 模组常量入口。本模组是纯客户端模组，不需要 {@code ModInitializer}。
 */
public final class MobHighlight {
	public static final String MOD_ID = "mobhighlight";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private MobHighlight() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
