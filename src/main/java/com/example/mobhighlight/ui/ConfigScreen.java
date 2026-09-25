package com.example.mobhighlight.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import com.example.mobhighlight.config.Faction;
import com.example.mobhighlight.config.ModConfig;
import com.example.mobhighlight.core.FactionResolver;
import com.example.mobhighlight.core.HighlightRules;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * J 键打开的配置界面。
 *
 * <p>刻意只用原版 widget 实现，不额外引入 YACL / ModMenu 依赖 —— 少一个依赖就少一个要拖的 jar。</p>
 *
 * <p>五个页签：</p>
 * <ul>
 *   <li>常规：总开关、穿墙显示、半径、数量上限、校正间隔。</li>
 *   <li>默认规则：按标签 / 注册类别自动归类的七个分类，各自的开关 + 轮廓颜色。</li>
 *   <li>自定义规则：玩家自己挑实体建的规则，优先级高于默认规则（见 {@link CustomRuleScreen}）。</li>
 *   <li>实体清单：直接从 {@code ENTITY_TYPE} 注册表动态生成，含其他模组的生物；支持搜索、
 *       按命名空间筛选，每个条目可在「跟随规则 / 强制发光 / 永不发光」之间切换。</li>
 *   <li>模组来源：按命名空间整包开关。</li>
 * </ul>
 *
 * <p>所有改动即时落盘到 {@code config/mobhighlight.json}。</p>
 */
public final class ConfigScreen extends Screen {
	private enum Tab {
		GENERAL, FACTIONS, CUSTOM, MOBS, NAMESPACES;

		public String key() {
			return "tab.mobhighlight." + this.name().toLowerCase(Locale.ROOT);
		}

		public String hintKey() {
			return "hint.mobhighlight." + this.name().toLowerCase(Locale.ROOT);
		}
	}

	private static final int PAD = 20;
	private static final int BTN_H = 20;
	private static final int TAB_Y = 26;
	private static final int CONTENT_TOP = 64;

	private static final int[] PALETTE = {
			0xFF3B30, 0xFF9500, 0xFFD233, 0xFFF200, 0x3BE23B, 0x00C853,
			0x00E5FF, 0x2F80FF, 0x5E5CE6, 0xB36BFF, 0xFF6BD6, 0xFFFFFF,
			0x9E9E9E, 0x4B5563
	};

	private Tab tab = Tab.GENERAL;
	private boolean rebuildPending = false;
	private boolean gridRefreshPending = false;

	private String search = "";
	private int page = 0;
	private String namespaceFilter = null;
	private Component status = Component.empty();

	private final List<AbstractWidget> gridWidgets = new ArrayList<>();

	public ConfigScreen() {
		super(Component.translatable("screen.mobhighlight.title"));
	}

	// ---------------------------------------------------------------- 骨架

	@Override
	protected void init() {
		this.rebuild();
	}

	@Override
	public void tick() {
		if (this.rebuildPending) {
			this.rebuildPending = false;
			this.rebuild();
			return;
		}

		if (this.gridRefreshPending) {
			this.gridRefreshPending = false;
			this.refreshGrid();
		}
	}

	/**
	 * 重建整屏。在鼠标事件的迭代过程中 rebuild 会触发 ConcurrentModificationException，
	 * 所以按钮里一律只置标志位，真正的重建推迟到 {@link #tick()}。
	 */
	private void requestRebuild() {
		this.rebuildPending = true;
	}

	private void rebuild() {
		this.gridWidgets.clear();
		this.clearWidgets();

		int x = PAD;

		for (Tab tab : Tab.values()) {
			Component label = Component.translatable(tab.key());
			int width = Math.min(150, Math.max(72, this.font.width(label) + 22));

			Button.Builder builder = Button.builder(label, pressed -> {
				this.tab = tab;
				this.page = 0;
				this.requestRebuild();
			}).bounds(x, TAB_Y, width, BTN_H);

			if (tab == Tab.CUSTOM) {
				builder.tooltip(Tooltip.create(Component.translatable("gui.mobhighlight.custom_hint")));
			}

			AbstractWidget button = builder.build();
			button.active = tab != this.tab;
			this.addRenderableWidget(button);
			x += width + 4;
		}

		switch (this.tab) {
			case GENERAL -> this.buildGeneral();
			case FACTIONS -> this.buildFactions();
			case CUSTOM -> this.buildCustom();
			case MOBS -> this.buildMobs();
			case NAMESPACES -> this.buildNamespaces();
		}
	}

	private <T extends AbstractWidget> T addGrid(T widget) {
		this.addRenderableWidget(widget);
		this.gridWidgets.add(widget);
		return widget;
	}

	private void clearGrid() {
		for (AbstractWidget widget : this.gridWidgets) {
			this.removeWidget(widget);
		}

		this.gridWidgets.clear();
	}

	private void refreshGrid() {
		this.clearGrid();

		switch (this.tab) {
			case MOBS -> this.fillMobGrid();
			case NAMESPACES -> this.fillNamespaceGrid();
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- 常规

	private void buildGeneral() {
		ModConfig config = ModConfig.get();
		int left = PAD;
		int width = 320;
		int top = CONTENT_TOP;

		this.addRenderableWidget(Checkbox.builder(Component.translatable("option.mobhighlight.enabled"), this.font)
				.pos(left, top)
				.selected(config.enabled)
				.onValueChange((box, value) -> {
					ModConfig.get().enabled = value;
					ModConfig.save();
				})
				.build());
		top += 26;

		this.addRenderableWidget(Checkbox.builder(Component.translatable("option.mobhighlight.ignore_invisible"), this.font)
				.pos(left, top)
				.selected(config.ignoreInvisible)
				.onValueChange((box, value) -> {
					ModConfig.get().ignoreInvisible = value;
					ModConfig.save();
				})
				.build());
		top += 26;

		this.addRenderableWidget(Checkbox.builder(Component.translatable("option.mobhighlight.glow_self"), this.font)
				.pos(left, top)
				.selected(config.glowSelf)
				.onValueChange((box, value) -> {
					ModConfig.get().glowSelf = value;
					ModConfig.save();
				})
				.build());
		top += 26;

		this.addRenderableWidget(Checkbox.builder(Component.translatable("option.mobhighlight.see_through"), this.font)
				.pos(left, top)
				.selected(config.seeThrough)
				.tooltip(Tooltip.create(Component.translatable("option.mobhighlight.see_through.tooltip")))
				.onValueChange((box, value) -> {
					ModConfig.get().seeThrough = value;
					ModConfig.save();
				})
				.build());
		top += 26;

		this.addRenderableWidget(this.slider(left, top, width, "option.mobhighlight.radius",
				ModConfig.MIN_RADIUS, ModConfig.MAX_RADIUS, 1.0D, true,
				() -> ModConfig.get().radius,
				value -> ModConfig.get().radius = value));
		top += 24;

		this.addRenderableWidget(this.slider(left, top, width, "option.mobhighlight.max_glow",
				8.0D, 512.0D, 8.0D, true,
				() -> (double) ModConfig.get().maxGlow,
				value -> ModConfig.get().maxGlow = (int) value));
		top += 24;

		this.addRenderableWidget(this.slider(left, top, width, "option.mobhighlight.scan_interval",
				16.0D, 2000.0D, 1.0D, true,
				() -> (double) ModConfig.get().scanIntervalMs,
				value -> ModConfig.get().scanIntervalMs = (int) value));
		top += 30;

		this.addRenderableWidget(Button.builder(Component.translatable("option.mobhighlight.reset"), pressed -> {
			ModConfig.get().reset();
			ModConfig.save();
			HighlightRules.invalidate();
			FactionResolver.invalidate();
			this.requestRebuild();
		}).bounds(left, top, 120, BTN_H).build());

		this.addRenderableWidget(Button.builder(Component.translatable("option.mobhighlight.hotkeys"), pressed -> {
			ModConfig.save();
			Minecraft client = Minecraft.getInstance();
			client.setScreen(new KeyBindsScreen(this, client.options));
		})
				.tooltip(Tooltip.create(Component.translatable("option.mobhighlight.hotkeys.tooltip")))
				.bounds(left + 130, top, 150, BTN_H)
				.build());

		this.status = Component.empty();
	}

	// ---------------------------------------------------------------- 默认规则

	private void buildFactions() {
		ModConfig config = ModConfig.get();
		int top = CONTENT_TOP;

		for (Faction faction : Faction.values()) {
			ModConfig.FactionSetting setting = config.faction(faction);

			this.addRenderableWidget(Checkbox.builder(Component.translatable(faction.translationKey()), this.font)
					.pos(PAD, top + 2)
					.selected(setting.enabled)
					.onValueChange((box, value) -> {
						ModConfig.get().faction(faction).enabled = value;
						ModConfig.save();
						HighlightRules.invalidate();
					})
					.build());

			this.addRenderableWidget(Button.builder(colorLabel(setting.color), pressed -> {
				ModConfig.FactionSetting current = ModConfig.get().faction(faction);
				current.color = nextColor(current.color);
				ModConfig.save();
				HighlightRules.invalidate();
				pressed.setMessage(colorLabel(current.color));
			}).bounds(PAD + 200, top, 130, BTN_H).build());

			top += 24;
		}

		this.status = Component.empty();
	}

	// ---------------------------------------------------------------- 自定义规则

	/**
	 * 默认规则是「实体按标签 / 类别自动归类」，这里反过来 —— 由玩家逐个挑实体，
	 * 优先级高于默认规则（详见 {@link CustomRuleScreen}）。
	 */
	private void buildCustom() {
		ModConfig config = ModConfig.get();
		Minecraft client = Minecraft.getInstance();

		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobhighlight.new_rule"), pressed -> {
			ModConfig.CustomRule rule = ModConfig.get().createRule(null);
			ModConfig.save();
			HighlightRules.invalidate();
			client.setScreen(new CustomRuleScreen(this, rule.id));
		}).bounds(PAD, CONTENT_TOP, 130, BTN_H).build());

		List<ModConfig.CustomRule> rules = config.customRules;

		if (rules.isEmpty()) {
			this.status = Component.translatable("gui.mobhighlight.no_rule");
			return;
		}

		int gridTop = CONTENT_TOP + 30;
		int gridBottom = this.height - 30;
		int rows = Math.max(1, (gridBottom - gridTop) / 22);
		int pages = Math.max(1, (rules.size() + rows - 1) / rows);
		this.page = Math.max(0, Math.min(pages - 1, this.page));

		int start = this.page * rows;

		for (int index = start; index < Math.min(rules.size(), start + rows); index++) {
			ModConfig.CustomRule rule = rules.get(index);
			int y = gridTop + (index - start) * 22;

			this.addRenderableWidget(Checkbox.builder(Component.empty(), this.font)
					.pos(PAD, y + 1)
					.selected(rule.enabled)
					.onValueChange((box, value) -> {
						ModConfig.get().findRule(rule.id).enabled = value;
						ModConfig.save();
						HighlightRules.invalidate();
					})
					.build());

			this.addRenderableWidget(Button.builder(
					Component.literal(ruleLabel(rule)).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rule.color & 0xFFFFFF))),
					pressed -> client.setScreen(new CustomRuleScreen(this, rule.id)))
					.bounds(PAD + 26, y, 200, BTN_H)
					.tooltip(Tooltip.create(Component.translatable("gui.mobhighlight.edit_rule.tooltip")))
					.build());

			this.addRenderableWidget(Button.builder(colorLabel(rule.color), pressed -> {
				ModConfig.CustomRule current = ModConfig.get().findRule(rule.id);

				if (current == null) {
					return;
				}

				current.color = nextColor(current.color);
				ModConfig.save();
				HighlightRules.invalidate();
				this.requestRebuild();
			}).bounds(PAD + 232, y, 130, BTN_H).build());
		}

		if (pages > 1) {
			this.addRenderableWidget(Button.builder(Component.literal("<"), pressed -> {
				this.page--;
				this.requestRebuild();
			}).bounds(PAD + 370, CONTENT_TOP, 26, BTN_H).build());

			this.addRenderableWidget(Button.builder(Component.literal(">"), pressed -> {
				this.page++;
				this.requestRebuild();
			}).bounds(PAD + 400, CONTENT_TOP, 26, BTN_H).build());
		}

		this.status = Component.translatable("gui.mobhighlight.page_short", this.page + 1, pages, rules.size());
	}

	private static String ruleLabel(ModConfig.CustomRule rule) {
		return rule + "  (" + rule.entities.size() + ")";
	}

	// ---------------------------------------------------------------- 实体清单

	private void buildMobs() {
		int left = PAD;
		int top = CONTENT_TOP;

		EditBox searchBox = new EditBox(this.font, left, top, 200, BTN_H, Component.translatable("gui.mobhighlight.search"));
		searchBox.setValue(this.search);
		searchBox.setMaxLength(64);
		searchBox.setResponder(value -> {
			this.search = value;
			this.page = 0;
			this.gridRefreshPending = true;
		});
		this.addRenderableWidget(searchBox);

		this.addRenderableWidget(Button.builder(namespaceFilterLabel(), pressed -> {
			this.cycleNamespaceFilter();
			this.page = 0;
			pressed.setMessage(namespaceFilterLabel());
			this.gridRefreshPending = true;
		}).bounds(left + 208, top, 140, BTN_H).build());

		this.addRenderableWidget(Button.builder(Component.literal("<"), pressed -> {
			this.page--;
			this.gridRefreshPending = true;
		}).bounds(left + 356, top, 26, BTN_H).build());

		this.addRenderableWidget(Button.builder(Component.literal(">"), pressed -> {
			this.page++;
			this.gridRefreshPending = true;
		}).bounds(left + 386, top, 26, BTN_H).build());

		this.fillMobGrid();
	}

	private void fillMobGrid() {
		List<EntityType<?>> visible = this.visibleMobTypes();
		int gridTop = CONTENT_TOP + 30;
		int gridBottom = this.height - PAD - 26;
		int available = this.width - PAD * 2;
		int columns = Math.max(2, Math.min(5, available / 180));
		int cellWidth = (available - (columns - 1) * 4) / columns;
		int rowHeight = 22;
		int rows = Math.max(1, (gridBottom - gridTop) / rowHeight);
		int pageSize = columns * rows;
		int pages = Math.max(1, (visible.size() + pageSize - 1) / pageSize);
		this.page = Math.max(0, Math.min(pages - 1, this.page));

		int start = this.page * pageSize;

		for (int index = start; index < Math.min(visible.size(), start + pageSize); index++) {
			EntityType<?> type = visible.get(index);
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

			if (id == null) {
				continue;
			}

			String key = id.toString();
			int slot = index - start;
			int x = PAD + (slot % columns) * (cellWidth + 4);
			int y = gridTop + (slot / columns) * rowHeight;
			Faction faction = FactionResolver.resolve(type);
			ModConfig.Override override = ModConfig.get().override(key);

			AbstractWidget cell = Button.builder(cellLabel(type, key, faction, override), pressed -> {
				ModConfig config = ModConfig.get();
				config.setOverride(key, config.override(key).next());
				ModConfig.save();
				HighlightRules.invalidate();
				this.gridRefreshPending = true;
			}).bounds(x, y, cellWidth, rowHeight).tooltip(Tooltip.create(cellTooltip(id, key, faction, override))).build();

			this.addGrid(cell);
		}

		this.status = Component.translatable("gui.mobhighlight.page",
				this.page + 1, pages, visible.size(), ModConfig.get().activeOverrideCount());
	}

	private List<EntityType<?>> visibleMobTypes() {
		Map<String, EntityType<?>> sorted = new TreeMap<>();
		String needle = this.search == null ? "" : this.search.trim().toLowerCase(Locale.ROOT);

		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

			if (id == null) {
				continue;
			}

			if (this.namespaceFilter != null && !this.namespaceFilter.equals(id.getNamespace())) {
				continue;
			}

			String haystack = (id.toString() + " " + FactionResolver.displayName(type)).toLowerCase(Locale.ROOT);

			if (!needle.isEmpty() && !haystack.contains(needle)) {
				continue;
			}

			sorted.put(id.toString(), type);
		}

		return new ArrayList<>(sorted.values());
	}

	// ---------------------------------------------------------------- 命名空间

	private void buildNamespaces() {
		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobhighlight.allow_all"), pressed -> {
			for (String namespace : new ArrayList<>(ModConfig.get().deniedNamespaces)) {
				ModConfig.get().setNamespaceDenied(namespace, false);
			}

			ModConfig.save();
			HighlightRules.invalidate();
			this.gridRefreshPending = true;
		}).bounds(PAD, CONTENT_TOP, 130, BTN_H).build());

		this.fillNamespaceGrid();
	}

	private void fillNamespaceGrid() {
		Map<String, Integer> counts = new LinkedHashMap<>();

		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

			if (id != null) {
				counts.merge(id.getNamespace(), 1, Integer::sum);
			}
		}

		List<String> namespaces = new ArrayList<>(counts.keySet());
		namespaces.sort(String::compareTo);

		if (this.namespaceFilter != null && !namespaces.contains(this.namespaceFilter)) {
			this.namespaceFilter = null;
		}

		int gridTop = CONTENT_TOP + 30;
		int gridBottom = this.height - PAD - 26;
		int available = this.width - PAD * 2;
		int columns = Math.max(2, Math.min(4, available / 220));
		int cellWidth = (available - (columns - 1) * 4) / columns;
		int rowHeight = 22;
		int rows = Math.max(1, (gridBottom - gridTop) / rowHeight);
		int pageSize = columns * rows;
		int pages = Math.max(1, (namespaces.size() + pageSize - 1) / pageSize);
		this.page = Math.max(0, Math.min(pages - 1, this.page));

		int start = this.page * pageSize;

		for (int index = start; index < Math.min(namespaces.size(), start + pageSize); index++) {
			String namespace = namespaces.get(index);
			int slot = index - start;
			int x = PAD + (slot % columns) * (cellWidth + 4);
			int y = gridTop + (slot / columns) * rowHeight;
			boolean denied = ModConfig.get().namespaceDenied(namespace);

			AbstractWidget cell = Button.builder(Component.literal((denied ? "[x] " : "[o] ") + namespace + "  (" + counts.get(namespace) + ")")
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(denied ? 0x8A8A8A : 0xE0E0E0))),
					pressed -> {
						ModConfig.get().setNamespaceDenied(namespace, !ModConfig.get().namespaceDenied(namespace));
						ModConfig.save();
						HighlightRules.invalidate();
						this.gridRefreshPending = true;
					}).bounds(x, y, cellWidth, rowHeight).build();

			this.addGrid(cell);
		}

		this.status = Component.translatable("gui.mobhighlight.page", this.page + 1, pages, namespaces.size(),
				ModConfig.get().deniedNamespaces.size());
	}

	// ---------------------------------------------------------------- 小工具

	private void cycleNamespaceFilter() {
		List<String> namespaces = new ArrayList<>();

		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

			if (id != null && !namespaces.contains(id.getNamespace())) {
				namespaces.add(id.getNamespace());
			}
		}

		namespaces.sort(String::compareTo);

		if (this.namespaceFilter == null) {
			this.namespaceFilter = namespaces.isEmpty() ? null : namespaces.get(0);
			return;
		}

		int index = namespaces.indexOf(this.namespaceFilter);

		if (index < 0 || index >= namespaces.size() - 1) {
			this.namespaceFilter = null;
		} else {
			this.namespaceFilter = namespaces.get(index + 1);
		}
	}

	private Component namespaceFilterLabel() {
		Component value = this.namespaceFilter == null
				? Component.translatable("gui.mobhighlight.filter_all")
				: Component.literal(this.namespaceFilter);

		return Component.translatable("gui.mobhighlight.filter", value);
	}

	private NumberSlider slider(int x, int y, int width, String key, double min, double max, double step,
			boolean integer, DoubleSupplier getter, DoubleConsumer setter) {
		return new NumberSlider(x, y, width, Component.translatable(key), min, max, step, integer, getter, setter, ModConfig::save);
	}

	static int nextColor(int current) {
		for (int index = 0; index < PALETTE.length; index++) {
			if (PALETTE[index] == (current & 0xFFFFFF)) {
				return PALETTE[(index + 1) % PALETTE.length];
			}
		}

		return PALETTE[0];
	}

	static MutableComponent colorLabel(int rgb) {
		return Component.literal("■ ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb & 0xFFFFFF)))
				.append(Component.literal(Faction.toHex(rgb)).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xE0E0E0))));
	}

	private static MutableComponent cellLabel(EntityType<?> type, String key, Faction faction, ModConfig.Override override) {
		ModConfig.CustomRule custom = ModConfig.get().ruleOf(key);
		String prefix = switch (override) {
			case ENABLE -> "+ ";
			case DISABLE -> "- ";
			default -> custom == null ? "  " : "* ";
		};

		int color = switch (override) {
			case ENABLE -> 0x00C853;
			case DISABLE -> 0x8A8A8A;
			default -> {
				if (custom != null) {
					yield custom.enabled ? (custom.color & 0xFFFFFF) : 0x6B7280;
				}

				yield ModConfig.get().factionEnabled(faction) ? ModConfig.get().factionColor(faction) : 0x6B7280;
			}
		};

		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		String name = FactionResolver.displayName(type);
		String text = prefix + (id != null && !"minecraft".equals(id.getNamespace()) ? id.getNamespace() + ":" : "") + name;

		return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
	}

	private static Component cellTooltip(Identifier id, String key, Faction faction, ModConfig.Override override) {
		String ruleKey = switch (override) {
			case ENABLE -> "rule.mobhighlight.enable";
			case DISABLE -> "rule.mobhighlight.disable";
			default -> "rule.mobhighlight.default";
		};

		ModConfig.CustomRule custom = ModConfig.get().ruleOf(key);
		Component owner = custom != null
				? Component.literal(custom.toString())
				: Component.translatable(faction.translationKey());

		MutableComponent tooltip = Component.literal(id.toString()).append("\n");
		tooltip.append(owner).append("\n");
		tooltip.append(Component.translatable(ruleKey));
		return tooltip;
	}

	// ---------------------------------------------------------------- 绘制

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		// 不要在这里再调 renderBackground：原版 Screen#renderWithTooltipAndSubtitles 已经先于
		// render 调用过它，重复调用会在同一帧第二次触发 GuiGraphics#blurBeforeThisStratum，
		// 抛出 "IllegalStateException: Can only blur once per frame"。
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		guiGraphics.drawString(this.font, this.title, PAD, 8, 0xFFFFFF, false);
		guiGraphics.drawString(this.font, Component.translatable(this.tab.hintKey()), PAD, CONTENT_TOP - 12, 0x9A9A9A, false);

		// 自定义规则页：把「会覆盖默认规则的颜色、删掉即恢复」直接写在按钮旁边，不用悬停也能看见
		if (this.tab == Tab.CUSTOM) {
			guiGraphics.drawString(this.font, Component.translatable("gui.mobhighlight.custom_hint_short"),
					PAD + 136, CONTENT_TOP + 6, 0x9A9A9A, false);
		}

		guiGraphics.drawString(this.font, this.status, PAD, this.height - PAD + 2, 0xC0C0C0, false);
	}

	/**
	 * 数值滑块。{@link AbstractSliderButton} 的 protected {@code value} 是 0..1 的归一化值，
	 * {@code applyValue} 在拖动时被反复调用，这里同步写回配置并落盘。
	 */
	private static final class NumberSlider extends AbstractSliderButton {
		private final Component prefix;
		private final double min;
		private final double max;
		private final double step;
		private final boolean integer;
		private final DoubleConsumer setter;
		private final Runnable onCommit;

		NumberSlider(int x, int y, int width, Component prefix, double min, double max, double step,
				boolean integer, DoubleSupplier getter, DoubleConsumer setter, Runnable onCommit) {
			super(x, y, width, AbstractSliderButton.DEFAULT_HEIGHT, prefix, toUnit(getter.getAsDouble(), min, max));
			this.prefix = prefix;
			this.min = min;
			this.max = max;
			this.step = step;
			this.integer = integer;
			this.setter = setter;
			this.onCommit = onCommit;
			this.updateMessage();
		}

		private static double toUnit(double value, double min, double max) {
			double unit = (value - min) / (max - min);

			if (Double.isNaN(unit)) {
				return 0.0D;
			}

			return Math.max(0.0D, Math.min(1.0D, unit));
		}

		private double current() {
			double raw = this.min + (this.max - this.min) * this.value;

			if (this.step > 0.0D) {
				raw = Math.round(raw / this.step) * this.step;
			}

			return Math.max(this.min, Math.min(this.max, raw));
		}

		@Override
		protected void updateMessage() {
			double value = this.current();
			String text = this.integer
					? String.valueOf((long) Math.round(value))
					: String.format(Locale.ROOT, "%.1f", value);
			this.setMessage(this.prefix.copy()
					.append(Component.literal("  " + text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFCC00)))));
		}

		@Override
		protected void applyValue() {
			this.setter.accept(this.current());
			this.updateMessage();
			this.onCommit.run();
		}
	}
}
