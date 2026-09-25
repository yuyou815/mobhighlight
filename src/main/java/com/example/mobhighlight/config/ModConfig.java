package com.example.mobhighlight.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.example.mobhighlight.MobHighlight;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 配置文件：{@code <游戏目录>/config/mobhighlight.json}。
 *
 * <p>存的都是 <b>规则</b>，不含任何硬编码的原版生物名单，因此加模组 / 换世界都不需要改代码。
 * 只对「与默认行为不一致」的实体类型落盘（{@link #typeOverrides}），文件不会随模组数量膨胀。
 * 读不到或损坏时静默回落到默认值并重写一份，不会让游戏起不来。</p>
 */
public final class ModConfig {
	/** 单个实体类型的强制规则。 */
	public enum Override {
		DEFAULT,
		ENABLE,
		DISABLE;

		public Override next() {
			return switch (this) {
				case DEFAULT -> ENABLE;
				case ENABLE -> DISABLE;
				case DISABLE -> DEFAULT;
			};
		}
	}

	/** 一个默认规则分类的开关 + 轮廓颜色。 */
	public static final class FactionSetting {
		public boolean enabled = true;
		public int color = 0xFFFFFF;
	}

	/**
	 * 玩家自建的规则。
	 *
	 * <p>默认规则是写死在 {@link Faction} 里的判定链（按标签 / 类别把实体自动归类），
	 * 自定义规则反过来 —— <b>由玩家逐个挑实体</b>，可以挑原版生物，也可以挑任何模组加的生物。
	 * 同一个实体只能属于一条自定义规则，优先级高于默认规则。</p>
	 */
	public static final class CustomRule {
		public String id = "";
		public String name = "";
		public int color = 0xFFFFFF;
		public boolean enabled = true;

		/** JSON 里仍写作 {@code members}，老配置照旧能读。 */
		@SerializedName("members")
		public Set<String> entities = new LinkedHashSet<>();

		@java.lang.Override
		public String toString() {
			return this.name.isBlank() ? this.id : this.name;
		}
	}

	private static final int[] AUTO_COLORS = {
			0xFFD233, 0x3BE23B, 0x00E5FF, 0xB36BFF, 0xFF6BD6, 0xFF9500, 0x2F80FF, 0x00C853
	};

	public static final double MIN_RADIUS = 4.0D;
	public static final double MAX_RADIUS = 128.0D;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int SCHEMA = 2;

	private static ModConfig instance;

	public int schemaVersion = SCHEMA;

	/** 总开关。关闭后完全不介入渲染管线。 */
	public boolean enabled = true;

	/** 探测半径（格）。 */
	public double radius = 32.0D;

	/** 单帧最多标记多少个实体，防止在实体堆场里掉帧。 */
	public int maxGlow = 128;

	/** 隐身实体不标记。 */
	public boolean ignoreInvisible = true;

	/** 穿墙显示：true = 隔着方块也能看到轮廓（原版发光效果的行为，无额外开销）。 */
	public boolean seeThrough = true;

	/** 关闭穿墙显示时，每帧最多发多少条视线射线（超预算的实体直接跳过）。 */
	public int maxOcclusionRays = 64;

	/** 第三人称下是否给自己的身体描边。 */
	public boolean glowSelf = false;

	/** 每隔多少毫秒用一次真实世界实体校正分类判定（详见 FactionResolver#maybeRefine）。 */
	public int scanIntervalMs = 250;

	public Map<String, FactionSetting> factions = new TreeMap<>();

	/** JSON 里仍写作 {@code customFactions}，老配置照旧能读。 */
	@SerializedName("customFactions")
	public List<CustomRule> customRules = new ArrayList<>();

	/** {@code "<namespace>:<path>" -> "on" | "off"}；没有记录表示跟随所属规则。 */
	public Map<String, String> typeOverrides = new TreeMap<>();

	/** 被整体排除的命名空间（即模组来源）。 */
	public Set<String> deniedNamespaces = new LinkedHashSet<>();

	public static synchronized ModConfig get() {
		if (instance == null) {
			instance = new ModConfig();
			instance.sanitize();
		}

		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mobhighlight.json");
	}

	public static synchronized void load() {
		Path path = path();
		ModConfig loaded = null;

		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, ModConfig.class);
			} catch (Exception exception) {
				MobHighlight.LOGGER.warn("[MobHighlight] 读取配置失败，改用默认配置：{}", exception.getMessage());
			}
		}

		if (loaded == null) {
			loaded = new ModConfig();
		}

		loaded.sanitize();
		instance = loaded;
		save();
	}

	public static synchronized void save() {
		ModConfig config = get();
		config.sanitize();

		try {
			Path path = path();
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException exception) {
			MobHighlight.LOGGER.error("[MobHighlight] 写入配置失败", exception);
		}
	}

	private void sanitize() {
		if (this.factions == null) {
			this.factions = new TreeMap<>();
		}

		for (Faction faction : Faction.values()) {
			FactionSetting setting = this.factions.get(faction.id);

			if (setting == null) {
				setting = new FactionSetting();
				setting.enabled = faction.defaultEnabled;
				setting.color = faction.defaultColor;
				this.factions.put(faction.id, setting);
			}

			setting.color &= 0xFFFFFF;
		}

		if (this.typeOverrides == null) {
			this.typeOverrides = new TreeMap<>();
		}

		this.sanitizeRules();

		if (this.deniedNamespaces == null) {
			this.deniedNamespaces = new LinkedHashSet<>();
		}

		this.radius = clamp(this.radius, MIN_RADIUS, MAX_RADIUS);
		this.maxGlow = (int) clamp(this.maxGlow, 1, 512);
		this.maxOcclusionRays = (int) clamp(this.maxOcclusionRays, 1, 512);
		this.scanIntervalMs = (int) clamp(this.scanIntervalMs, 16, 5000);
		this.schemaVersion = SCHEMA;
	}

	/** 补 id、压颜色，并保证「一个实体只属于一条自定义规则」（重复出现时保留先到的那条）。 */
	private void sanitizeRules() {
		if (this.customRules == null) {
			this.customRules = new ArrayList<>();
			return;
		}

		List<CustomRule> cleaned = new ArrayList<>();
		Set<String> usedIds = new LinkedHashSet<>();
		Set<String> claimed = new LinkedHashSet<>();

		for (CustomRule rule : this.customRules) {
			if (rule == null) {
				continue;
			}

			if (rule.entities == null) {
				rule.entities = new LinkedHashSet<>();
			}

			if (rule.id == null || rule.id.isBlank() || !usedIds.add(rule.id)) {
				rule.id = nextRuleId(usedIds);
				usedIds.add(rule.id);
			}

			if (rule.name == null) {
				rule.name = "";
			}

			rule.color &= 0xFFFFFF;

			Set<String> unique = new LinkedHashSet<>();

			for (String entity : rule.entities) {
				if (entity != null && claimed.add(entity)) {
					unique.add(entity);
				}
			}

			rule.entities = unique;
			cleaned.add(rule);
		}

		this.customRules = cleaned;
	}

	private static String nextRuleId(Set<String> used) {
		for (int attempt = 0; attempt < 1000; attempt++) {
			String candidate = "g" + Long.toHexString(System.nanoTime() + attempt * 7919L);

			if (!used.contains(candidate)) {
				return candidate;
			}
		}

		return "g" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static double clamp(double value, double min, double max) {
		if (Double.isNaN(value)) {
			return min;
		}

		return Math.max(min, Math.min(max, value));
	}

	public FactionSetting faction(Faction faction) {
		FactionSetting setting = this.factions.get(faction.id);

		if (setting == null) {
			setting = new FactionSetting();
			setting.enabled = faction.defaultEnabled;
			setting.color = faction.defaultColor;
			this.factions.put(faction.id, setting);
		}

		return setting;
	}

	public boolean factionEnabled(Faction faction) {
		return this.faction(faction).enabled;
	}

	/** 返回 0xRRGGBB（不含 alpha）。 */
	public int factionColor(Faction faction) {
		return this.faction(faction).color & 0xFFFFFF;
	}

	public Override override(String typeId) {
		String value = this.typeOverrides.get(typeId);

		if ("on".equals(value)) {
			return Override.ENABLE;
		}

		if ("off".equals(value)) {
			return Override.DISABLE;
		}

		return Override.DEFAULT;
	}

	public void setOverride(String typeId, Override value) {
		if (value == null || value == Override.DEFAULT) {
			this.typeOverrides.remove(typeId);
		} else {
			this.typeOverrides.put(typeId, value == Override.ENABLE ? "on" : "off");
		}
	}

	// ------------------------------------------------------------ 自定义规则

	/** 新建一条规则，自动分配 id 和一个尽量不与现有规则撞车的颜色。 */
	public CustomRule createRule(String name) {
		Set<String> used = new LinkedHashSet<>();

		for (CustomRule existing : this.customRules) {
			used.add(existing.id);
		}

		CustomRule rule = new CustomRule();
		rule.id = nextRuleId(used);
		rule.name = (name == null || name.isBlank()) ? ("规则 " + (this.customRules.size() + 1)) : name.trim();
		rule.color = AUTO_COLORS[this.customRules.size() % AUTO_COLORS.length];
		this.customRules.add(rule);
		return rule;
	}

	public void deleteRule(String id) {
		this.customRules.removeIf(rule -> rule.id.equals(id));
	}

	public CustomRule findRule(String id) {
		if (id == null) {
			return null;
		}

		for (CustomRule rule : this.customRules) {
			if (rule.id.equals(id)) {
				return rule;
			}
		}

		return null;
	}

	/** 某个实体类型被指派给了哪条自定义规则；没有则返回 null。 */
	public CustomRule ruleOf(String typeId) {
		if (typeId == null) {
			return null;
		}

		for (CustomRule rule : this.customRules) {
			if (rule.entities.contains(typeId)) {
				return rule;
			}
		}

		return null;
	}

	/**
	 * 把一个实体加进某条规则；已被别的规则选走的实体加不进来，成功返回 true。
	 *
	 * <p>自定义规则之间互斥：一个实体同时只能被一条规则收走，
	 * 否则两条规则各给一种颜色，最后生效哪条就成了玄学。</p>
	 */
	public boolean addEntity(String typeId, String ruleId) {
		if (typeId == null || this.ruleOf(typeId) != null) {
			return false;
		}

		CustomRule rule = this.findRule(ruleId);

		if (rule == null) {
			return false;
		}

		return rule.entities.add(typeId);
	}

	public void removeEntity(String typeId) {
		for (CustomRule rule : this.customRules) {
			rule.entities.remove(typeId);
		}
	}

	public boolean namespaceDenied(String namespace) {
		return this.deniedNamespaces.contains(namespace);
	}

	public void setNamespaceDenied(String namespace, boolean denied) {
		if (denied) {
			this.deniedNamespaces.add(namespace);
		} else {
			this.deniedNamespaces.remove(namespace);
		}
	}

	public void reset() {
		this.enabled = true;
		this.radius = 32.0D;
		this.maxGlow = 128;
		this.ignoreInvisible = true;
		this.seeThrough = true;
		this.maxOcclusionRays = 64;
		this.glowSelf = false;
		this.scanIntervalMs = 250;
		this.factions = new TreeMap<>();
		this.customRules = new ArrayList<>();
		this.typeOverrides = new TreeMap<>();
		this.deniedNamespaces = new LinkedHashSet<>();
		this.sanitize();
	}

	/** 当前记录的强制规则条数，给 {@code /mobhl status} 用。 */
	public int activeOverrideCount() {
		return this.typeOverrides.size();
	}
}
