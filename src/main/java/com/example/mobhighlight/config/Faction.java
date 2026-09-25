package com.example.mobhighlight.config;

/**
 * 默认规则的七个分类。
 *
 * <p>这里不是硬编码的生物名单，而是分类标签：任何实体（含其他模组添加的生物）
 * 都会被 {@link com.example.mobhighlight.core.FactionResolver} 动态归到其中一类，
 * 每个分类在配置里各自有开关和轮廓颜色。</p>
 */
public enum Faction {
	PLAYER("player", 0x00E5FF, true),
	FRIENDLY("friendly", 0x3BE23B, true),
	NEUTRAL("neutral", 0xFFD233, true),
	HOSTILE("hostile", 0xFF3B30, true),
	AQUATIC("aquatic", 0x2F80FF, true),
	AMBIENT("ambient", 0xB36BFF, true),
	OTHER("other", 0x9E9E9E, false);

	public final String id;
	public final int defaultColor;
	public final boolean defaultEnabled;

	Faction(String id, int defaultColor, boolean defaultEnabled) {
		this.id = id;
		this.defaultColor = defaultColor;
		this.defaultEnabled = defaultEnabled;
	}

	public String translationKey() {
		return "faction.mobhighlight." + this.id;
	}

	public static Faction fromId(String id) {
		if (id != null) {
			for (Faction faction : values()) {
				if (faction.id.equalsIgnoreCase(id)) {
					return faction;
				}
			}
		}

		return null;
	}

	/** 十六进制字符串，用于界面显示，例如 {@code #FF3B30}。 */
	public static String toHex(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}
}
