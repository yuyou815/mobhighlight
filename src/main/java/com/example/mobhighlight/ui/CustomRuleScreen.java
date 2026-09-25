package com.example.mobhighlight.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.example.mobhighlight.config.Faction;
import com.example.mobhighlight.config.ModConfig;
import com.example.mobhighlight.core.FactionResolver;
import com.example.mobhighlight.core.HighlightRules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * 编辑一条自定义规则：改名、换色、开关，以及挑 / 踢实体。
 *
 * <h3>两级选择</h3>
 * <p>「添加实体」先从<b>默认规则已经分好的分类</b>里挑一个，进去才是该分类下的实体列表。
 * 两级都直接从 {@link BuiltInRegistries#ENTITY_TYPE} 动态生成，覆盖所有实体（含其他模组的），
 * 不存在写死的名单。</p>
 *
 * <h3>规则之间互斥</h3>
 * <p>一个实体只能属于一条自定义规则：已经被别的规则选走的在这里显示为不可点，
 * 想换就先到那条规则里把它移出。</p>
 */
public final class CustomRuleScreen extends Screen {
	private enum Mode {
		/** 已选实体。 */
		ENTITIES,
		/** 一级：默认规则的分类。 */
		GROUPS,
		/** 二级：某个分类下的实体。 */
		PICKS;

		public String actionKey() {
			return switch (this) {
				case ENTITIES -> "gui.mobhighlight.add_entities";
				case GROUPS -> "gui.mobhighlight.show_selected";
				case PICKS -> "gui.mobhighlight.back_to_groups";
			};
		}
	}

	private record Group(Faction faction, List<EntityType<?>> types) {
	}

	private record GridLayout(int top, int columns, int cellWidth, int pageSize, int pages) {
	}

	private static final int PAD = 20;
	private static final int BTN_H = 20;
	private static final int ROW_H = 22;
	private static final int NAME_Y = 34;
	private static final int OPT_Y = 60;
	private static final int LIST_TOP = 96;
	private static final int PICK_TOP = 112;

	private final Screen parent;
	private final String ruleId;

	private Mode mode = Mode.ENTITIES;
	private Group selected = null;
	private String search = "";
	private int page = 0;
	private boolean refreshPending = false;
	private Component status = Component.empty();

	private final List<AbstractWidget> gridWidgets = new ArrayList<>();
	private EditBox searchBox;
	private Button actionButton;

	public CustomRuleScreen(Screen parent, String ruleId) {
		super(Component.translatable("screen.mobhighlight.custom_rule"));
		this.parent = parent;
		this.ruleId = ruleId;
	}

	private ModConfig.CustomRule rule() {
		return ModConfig.get().findRule(this.ruleId);
	}

	// ---------------------------------------------------------------- 骨架

	@Override
	protected void init() {
		ModConfig.CustomRule rule = this.rule();

		if (rule == null) {
			this.onClose();
			return;
		}

		this.clearWidgets();
		this.gridWidgets.clear();

		EditBox nameBox = new EditBox(this.font, PAD, NAME_Y, 200, BTN_H, Component.translatable("gui.mobhighlight.rule_name"));
		nameBox.setValue(rule.name);
		nameBox.setMaxLength(32);
		nameBox.setResponder(value -> this.edit(current -> current.name = value));
		this.addRenderableWidget(nameBox);

		this.addRenderableWidget(Checkbox.builder(Component.translatable("option.mobhighlight.enabled"), this.font)
				.pos(PAD, OPT_Y)
				.selected(rule.enabled)
				.onValueChange((box, value) -> this.edit(current -> {
					current.enabled = value;
					HighlightRules.invalidate();
				}))
				.build());

		this.addRenderableWidget(Button.builder(ConfigScreen.colorLabel(rule.color), pressed -> {
			ModConfig.CustomRule current = this.rule();

			if (current != null) {
				current.color = ConfigScreen.nextColor(current.color);
				ModConfig.save();
				HighlightRules.invalidate();
				pressed.setMessage(ConfigScreen.colorLabel(current.color));
			}
		}).bounds(PAD + 220, OPT_Y - 1, 120, BTN_H).build());

		this.searchBox = new EditBox(this.font, PAD, PICK_TOP - 26, 200, BTN_H, Component.translatable("gui.mobhighlight.search"));
		this.searchBox.setValue(this.search);
		this.searchBox.setMaxLength(64);
		this.searchBox.visible = this.mode == Mode.PICKS;
		this.searchBox.setResponder(value -> {
			this.search = value;
			this.page = 0;
			this.refreshPending = true;
		});
		this.addRenderableWidget(this.searchBox);

		int bottom = this.height - 30;

		this.actionButton = Button.builder(Component.translatable(this.mode.actionKey()), pressed -> this.onAction())
				.bounds(PAD, bottom, 120, BTN_H)
				.build();
		this.addRenderableWidget(this.actionButton);

		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobhighlight.delete_rule"), pressed -> {
			ModConfig.get().deleteRule(this.ruleId);
			ModConfig.save();
			HighlightRules.invalidate();
			this.onClose();
		})
				.bounds(PAD + 128, bottom, 110, BTN_H)
				.tooltip(Tooltip.create(Component.translatable("gui.mobhighlight.delete_rule.tooltip")))
				.build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobhighlight.done"), pressed -> this.onClose())
				.bounds(PAD + 246, bottom, 100, BTN_H)
				.build());

		this.fillGrid();
	}

	/** 改完立刻落盘；{@code edit} 返回表示这条规则是否还存在。 */
	private void edit(Consumer<ModConfig.CustomRule> change) {
		ModConfig.CustomRule current = this.rule();

		if (current != null) {
			change.accept(current);
			ModConfig.save();
		}
	}

	/** 底部左边那个按钮随视图换角色：添加实体 / 查看已选 / 返回上层。 */
	private void onAction() {
		switch (this.mode) {
			case ENTITIES -> this.mode = Mode.GROUPS;
			case GROUPS -> this.mode = Mode.ENTITIES;
			case PICKS -> this.mode = Mode.GROUPS;
		}

		this.page = 0;
		this.selected = null;
		this.searchBox.visible = this.mode == Mode.PICKS;
		this.actionButton.setMessage(Component.translatable(this.mode.actionKey()));
		this.refreshPending = true;
	}

	@Override
	public void tick() {
		if (this.refreshPending) {
			this.refreshPending = false;
			this.fillGrid();
		}
	}

	@Override
	public void onClose() {
		ModConfig.save();
		Minecraft client = this.minecraft;

		if (client != null) {
			client.setScreen(this.parent);
		} else {
			super.onClose();
		}
	}

	// ---------------------------------------------------------------- 列表

	private void addGrid(AbstractWidget widget) {
		this.addRenderableWidget(widget);
		this.gridWidgets.add(widget);
	}

	private void clearGrid() {
		for (AbstractWidget widget : this.gridWidgets) {
			this.removeWidget(widget);
		}

		this.gridWidgets.clear();
	}

	private void fillGrid() {
		this.clearGrid();

		switch (this.mode) {
			case ENTITIES -> this.fillSelected();
			case GROUPS -> this.fillGroups();
			case PICKS -> this.fillPicks();
		}
	}

	/** 按默认规则的分类把整个实体注册表分组；分类顺序固定跟 {@link Faction#values()} 一致。 */
	private List<Group> buildGroups() {
		Map<Faction, List<EntityType<?>>> buckets = new LinkedHashMap<>();

		for (Faction faction : Faction.values()) {
			buckets.put(faction, new ArrayList<>());
		}

		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (BuiltInRegistries.ENTITY_TYPE.getKey(type) == null) {
				continue;
			}

			buckets.computeIfAbsent(FactionResolver.resolve(type), key -> new ArrayList<>()).add(type);
		}

		List<Group> result = new ArrayList<>();

		for (Faction faction : Faction.values()) {
			List<EntityType<?>> types = buckets.get(faction);

			if (types == null || types.isEmpty()) {
				continue;
			}

			types.sort((left, right) -> BuiltInRegistries.ENTITY_TYPE.getKey(left).toString()
					.compareTo(BuiltInRegistries.ENTITY_TYPE.getKey(right).toString()));
			result.add(new Group(faction, types));
		}

		return result;
	}

	/** 已从这条规则里挑走的实体 id。 */
	private Set<String> selected() {
		ModConfig.CustomRule rule = this.rule();
		return rule == null ? Set.of() : Set.copyOf(rule.entities);
	}

	private GridLayout layout(int top, int entries, int maxColumns) {
		int bottom = this.height - 40;
		int available = this.width - PAD * 2;
		int columns = Math.max(1, Math.min(maxColumns, available / 170));
		int cellWidth = (available - (columns - 1) * 4) / columns;
		int rows = Math.max(1, (bottom - top) / ROW_H);
		int pageSize = Math.max(1, columns * rows);
		int pages = Math.max(1, (entries + pageSize - 1) / pageSize);
		this.page = Math.max(0, Math.min(pages - 1, this.page));
		return new GridLayout(top, columns, cellWidth, pageSize, pages);
	}

	private void fillSelected() {
		List<String> members = new ArrayList<>(this.selected());
		GridLayout layout = this.layout(LIST_TOP, members.size(), 3);
		int start = this.page * layout.pageSize();

		for (int index = start; index < Math.min(members.size(), start + layout.pageSize()); index++) {
			String key = members.get(index);
			int slot = index - start;
			int x = PAD + (slot % layout.columns()) * (layout.cellWidth() + 4);
			int y = layout.top() + (slot / layout.columns()) * ROW_H;

			this.addGrid(Button.builder(literal("- " + displayLabel(key), 0xE0E0E0), pressed -> {
				ModConfig.get().removeEntity(key);
				ModConfig.save();
				HighlightRules.invalidate();
				this.refreshPending = true;
			})
					.bounds(x, y, layout.cellWidth(), ROW_H)
					.tooltip(Tooltip.create(Component.literal(key)
							.append("\n")
							.append(Component.translatable("gui.mobhighlight.remove_entity.tooltip"))))
					.build());
		}

		this.pageLabel(members.size(), layout.pages());
	}

	private void fillGroups() {
		List<Group> groups = this.buildGroups();
		GridLayout layout = this.layout(LIST_TOP, groups.size(), 2);
		int start = this.page * layout.pageSize();
		Set<String> members = this.selected();

		for (int index = start; index < Math.min(groups.size(), start + layout.pageSize()); index++) {
			Group group = groups.get(index);
			int slot = index - start;
			int x = PAD + (slot % layout.columns()) * (layout.cellWidth() + 4);
			int y = layout.top() + (slot / layout.columns()) * ROW_H;
			int color = ModConfig.get().factionColor(group.faction());

			this.addGrid(Button.builder(literal(groupLabel(group, members), color), pressed -> this.openGroup(group))
					.bounds(x, y, layout.cellWidth(), ROW_H)
					.tooltip(Tooltip.create(Component.translatable("gui.mobhighlight.group.tooltip", group.types().size())))
					.build());
		}

		this.pageLabel(groups.size(), layout.pages());
	}

	private void openGroup(Group group) {
		this.selected = group;
		this.mode = Mode.PICKS;
		this.page = 0;
		this.search = "";
		this.searchBox.setValue("");
		this.searchBox.visible = true;
		this.actionButton.setMessage(Component.translatable(this.mode.actionKey()));
		this.refreshPending = true;
	}

	private String groupLabel(Group group, Set<String> members) {
		long chosen = group.types().stream()
				.map(BuiltInRegistries.ENTITY_TYPE::getKey)
				.filter(key -> key != null && members.contains(key.toString()))
				.count();

		return Component.translatable(group.faction().translationKey()).getString()
				+ "  " + chosen + "/" + group.types().size();
	}

	private void fillPicks() {
		Group group = this.selected;

		if (group == null) {
			this.mode = Mode.GROUPS;
			this.fillGrid();
			return;
		}

		List<EntityType<?>> visible = this.visibleIn(group);
		GridLayout layout = this.layout(PICK_TOP, visible.size(), 3);
		int start = this.page * layout.pageSize();

		for (int index = start; index < Math.min(visible.size(), start + layout.pageSize()); index++) {
			EntityType<?> type = visible.get(index);
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

			if (id == null) {
				continue;
			}

			String key = id.toString();
			int slot = index - start;
			int x = PAD + (slot % layout.columns()) * (layout.cellWidth() + 4);
			int y = layout.top() + (slot / layout.columns()) * ROW_H;
			ModConfig.CustomRule owner = ModConfig.get().ruleOf(key);
			boolean locked = owner != null;

			Button.Builder builder = Button.builder(literal((locked ? "[x] " : "+ ") + FactionResolver.displayName(type),
					locked ? 0x8A8A8A : 0xE0E0E0),
					pressed -> {
						if (ModConfig.get().addEntity(key, this.ruleId)) {
							ModConfig.save();
							HighlightRules.invalidate();
							this.refreshPending = true;
						}
					});

			builder.bounds(x, y, layout.cellWidth(), ROW_H);
			builder.tooltip(Tooltip.create(Component.literal(key).append("\n").append(
					locked
							? Component.translatable("gui.mobhighlight.entity_locked.tooltip", owner.toString())
							: Component.translatable("gui.mobhighlight.add_entity.tooltip"))));

			Button cell = builder.build();
			cell.active = !locked;
			this.addGrid(cell);
		}

		this.pageLabel(visible.size(), layout.pages());
	}

	/** 某个分类下还能加的实体：已选的要排除，被别的规则锁住的留着显示（灰）。 */
	private List<EntityType<?>> visibleIn(Group group) {
		Set<String> members = this.selected();
		String needle = this.search == null ? "" : this.search.trim().toLowerCase(Locale.ROOT);
		List<EntityType<?>> visible = new ArrayList<>();

		for (EntityType<?> type : group.types()) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

			if (id == null || members.contains(id.toString())) {
				continue;
			}

			String haystack = (id + " " + FactionResolver.displayName(type)).toLowerCase(Locale.ROOT);

			if (needle.isEmpty() || haystack.contains(needle)) {
				visible.add(type);
			}
		}

		return visible;
	}

	private void pageLabel(int entries, int pages) {
		this.status = Component.translatable("gui.mobhighlight.page_short", this.page + 1, pages, entries);
	}

	private static String displayLabel(String typeId) {
		Identifier id = Identifier.tryParse(typeId);
		EntityType<?> type = id == null ? null
				: BuiltInRegistries.ENTITY_TYPE.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(null);

		return type == null ? typeId : FactionResolver.displayName(type);
	}

	private static Component literal(String text, int color) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
	}

	// ---------------------------------------------------------------- 绘制

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		// 同 ConfigScreen：这里不能再调 renderBackground，一帧只允许模糊一次
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		guiGraphics.drawString(this.font, this.title, PAD, 8, 0xFFFFFF, false);

		ModConfig.CustomRule rule = this.rule();

		if (rule == null) {
			return;
		}

		guiGraphics.drawString(this.font, Component.translatable("gui.mobhighlight.rule_name"), PAD + 208, NAME_Y + 6, 0x9A9A9A, false);
		guiGraphics.drawString(this.font, Component.translatable("gui.mobhighlight.custom_hint_short"), PAD, OPT_Y + 22, 0x9A9A9A, false);

		if (this.mode == Mode.ENTITIES) {
			guiGraphics.drawString(this.font, Component.translatable("gui.mobhighlight.entity_count", rule.entities.size()),
					PAD, LIST_TOP - 14, 0xC0C0C0, false);

			if (rule.entities.isEmpty()) {
				guiGraphics.drawString(this.font, Component.translatable("gui.mobhighlight.no_entities"), PAD + 4, LIST_TOP + 4, 0x9A9A9A, false);
			}
		} else {
			guiGraphics.drawString(this.font, this.leadText(), PAD, LIST_TOP - 14, 0xC0C0C0, false);
		}

		guiGraphics.drawString(this.font, this.status, PAD, this.height - PAD + 2, 0xC0C0C0, false);
	}

	private Component leadText() {
		if (this.mode == Mode.GROUPS || this.selected == null) {
			return Component.translatable("gui.mobhighlight.pick_group");
		}

		return Component.translatable("gui.mobhighlight.pick_entity",
				Component.translatable(this.selected.faction().translationKey()));
	}
}
