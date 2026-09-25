# Mob Highlight

Draws **vanilla glowing outlines** over mobs within a radius — the spectral-arrow kind
that hug the model and are visible through walls — colored by rule, with support for
modded entities and an in-game configuration screen.

- Minecraft **1.21.11** · Fabric Loader `0.19.5` · fabric-api `0.141.6+1.21.11`
- Loom `1.17.21` + Gradle `9.5.0` · **JDK 21** · Mojang official mappings
- Client-side only, zero Mixin, no server-side installation required

> Chinese documentation: [README.md](https://github.com/yuyou815/mobhighlight/blob/main/README.md).

> This mod does **not** draw hitboxes. The glow goes through the vanilla outline
> pipeline, so the outline hugs the entity model itself.

## 1. Controls

| Action | Description |
|---|---|
| **H** | Toggle glowing outlines (master switch), with an on/off message in chat |
| **J** | Open the configuration screen |
| **]** / **[** | Adjust the detection radius by ±8 blocks |
| "Keybind settings" on the General tab | Jumps to the vanilla Controls screen so you can rebind H / J |
| `/mobhl` or `/mobhl config` | Open the configuration screen |
| `/mobhl status` | Print the current configuration summary |

> Why not Ctrl+H: in 1.21.11 a `KeyMapping` stores a single key only, never modifiers.
> Pressing Ctrl+H also fires the mapping bound to H, so the two cannot be told apart.
> That is why H and J are two independent keys.

### 1.1 Through-wall display

| Option | Default | Description |
|---|---|---|
| Through-wall display | On | On is the vanilla glow behavior — outlines are visible through blocks at zero extra cost. With it off, only entities in line of sight get outlined: each frame runs a `BlockGetter#clip` raycast per candidate, capped by `maxOcclusionRays` (default 64, editable in the JSON). Anything over budget is skipped. |

> The line-of-sight test uses `ClipContext.Block.VISUAL` (the block's **visual** shape),
> so transparent blocks such as glass still count as occluders.

Every change is written to `config/mobhighlight.json` immediately and stays in effect
after a restart.

## 2. Project layout

```
src/main/java/com/example/mobhighlight/
├── MobHighlight.java            MOD_ID + logging
├── MobHighlightClient.java      the one entrypoint: config / rendering / keybinds / commands / correction scan
├── config/
│   ├── Faction.java             the seven default-rule categories + their default colors
│   └── ModConfig.java           JSON rule config + read/write (including player-made rules)
├── core/
│   ├── FactionResolver.java     entity type -> default rule category (dynamic, no hardcoded lists)
│   ├── HighlightRules.java      the final "glow or not, and in what color" decision table (cached)
│   └── GlowApplier.java         writes EntityRenderState#outlineColor — the rendering itself
├── input/ModKeyBindings.java    H = toggle, J = settings, [ / ] = radius
├── command/ModCommands.java     /mobhl ...
└── ui/
    ├── ConfigScreen.java        the five-tab configuration screen (vanilla widgets only)
    └── CustomRuleScreen.java    edit one custom rule: rename / recolor / two-level entity picking
```

## 3. How it works

### 3.1 It rides the vanilla outline pipeline

After the 1.21.9 rendering rewrite, the data path for an entity's glow outline is:

```
EntityRenderState.outlineColor (public int)
        │  LevelRenderState.haveGlowingEntities (public boolean) must be true
        ▼
EntityRenderDispatcher.submit(...)  →  the model is additionally drawn into entityOutlineTarget
        ▼
ENTITY_OUTLINE_POST_CHAIN composites that onto the main frame → a see-through outline
```

So during the extraction phase this mod does exactly one thing: **assign an ARGB color to
the target state's `outlineColor`, and set `haveGlowingEntities` to true**. Nothing here
draws geometry of its own.

### 3.2 Why it has to be `WorldRenderEvents.END_EXTRACTION`

The real order inside `LevelRenderer#renderLevel`:

```
extractVisibleEntities                     ← vanilla extracts entities, computing distanceToCameraSq along the way
  ↓
WorldBorderRenderer.extract                ← END_EXTRACTION hooks in right after this
  ↓
reads haveGlowingEntities → decides whether to attach the outline post chain to the frame graph
  ↓
addMainPass → submitEntities
```

`END_EXTRACTION` is the only moment that is both "all extracted states exist" and
"before vanilla makes its outline decision". Using `BEFORE_ENTITIES` is too late — the
frame graph is already fixed by then.

Also, `submitEntities` contains this:

```java
if (!levelRenderState.haveGlowingEntities) {
    state.outlineColor = 0;   // only cleared when there are no glowing entities
}
```

which is why both fields must be set together: setting the color alone gets wiped here.

### 3.3 Rule resolution (fully dynamic, modded-mob friendly)

Priority order in `HighlightRules`:

1. **Namespace**: if a whole mod is excluded, nothing from it glows.
2. **Per-entity "never glow"** (one of the three states on the Entity List tab).
3. **Custom rules**: an entity claimed by one uses that rule's own color and toggle (§3.4).
4. **Default rules**: whether that category is enabled, plus the category color. Seven
   categories — Player / Friendly / Neutral / Hostile / Aquatic / Ambient / Other.
5. **Per-entity "force glow"**: keeps the color of its rule, only bypasses the toggle.

The default category is decided by `FactionResolver`, with **no vanilla mob list anywhere**:

1. Tags: `c:neutral` / `c:hostile` / `c:passive` (Fabric convention tags),
   `minecraft:undead`, `minecraft:raiders`; a missing tag is simply skipped.
2. The `MobCategory` an entity was registered with (modded mobs almost always set it).
3. Low-frequency correction: every `scanIntervalMs` (default 250 ms) it scans the real
   entities inside the radius — anything implementing `NeutralMob` counts as neutral,
   anything implementing `Enemy` counts as hostile. This is the fallback for modded mobs
   registered as `MISC`, and the only world iteration this mod ever performs.

Results are cached per `EntityType`; at render time it is just a map lookup.

### 3.4 Custom rules

Default rules are "entities sorted automatically by tag / category". Custom rules invert
that — **you pick the entities yourself**:

- **Two-level picking.** Clicking "Add entity" opens level one: the **seven categories that
  default rules already sorted**, each row showing a `selected/total` count. Entering a
  category opens level two: the entity list for that category, with a search box, click a
  row to add it. Both levels are generated dynamically from `BuiltInRegistries.ENTITY_TYPE`,
  covering **every entity**, modded ones included.
- **Rules are mutually exclusive.** An entity can belong to only one custom rule. One that
  is already claimed shows as greyed-out and unclickable, with a tooltip saying which rule
  owns it — to move it, remove it from that rule first.
- **Override and restore.** Once added, the entity uses this rule's color and toggle,
  overriding whatever the Default Rules tab gave it. Removing the entity, or deleting the
  whole rule, **restores** its default category and color immediately. This note is printed
  permanently on the Custom Rules tab, with the full version on tab hover.
- In the editor you can rename a rule, cycle colors by clicking the swatch, and
  enable/disable it (disabling takes the whole rule out of effect).
- Typical use: villagers are `MobCategory = MISC` with no tags, so default rules can only
  file them under "Other". A dedicated rule is the cleanest way to pull them out.

### 3.5 Performance

- **No per-frame world iteration**: it reuses the vanilla extraction results, which have
  already been through frustum and render-distance culling, and each carries its own
  `distanceToCameraSq` — so radius culling is free.
- Per-frame cost = O(visible entities) map lookups + one squared-distance comparison.
- `maxGlow` (default 128) is a hard cap; it stops once reached in a mob farm.
- Only turning **off** the through-wall display adds raycast cost, capped by
  `maxOcclusionRays` (default 64 rays/frame).
- Rule resolution and final decisions are cached per `EntityType` and invalidated only when
  the configuration changes.

## 4. Configuration

`config/mobhighlight.json` (excerpt):

```jsonc
{
  "enabled": true,             // master switch
  "radius": 32.0,              // detection radius in blocks
  "maxGlow": 128,              // max outlines per frame
  "ignoreInvisible": true,     // skip invisible entities
  "glowSelf": false,           // outline yourself in third person
  "scanIntervalMs": 250,       // category correction scan interval
  "factions": { "hostile": { "enabled": true, "color": 0xFF3B30 }, "...": {} },
  "customFactions": [          // player-made rules
    { "id": "g1a2b3c", "name": "Villagers", "color": 0xFFD233, "enabled": true,
      "members": ["minecraft:villager", "minecraft:wandering_trader"] }
  ],
  "typeOverrides": { "minecraft:bat": "off" },   // only entries that do NOT follow rules are persisted
  "deniedNamespaces": ["twilightforest"]          // exclude a whole mod by namespace
}
```

Five tabs in the GUI:

- **General**: master switch, through-wall display, skip invisible, glow self, radius, cap,
  correction interval, keybind settings, restore defaults
- **Default Rules**: on/off switch + color swatch for each of the seven auto-sorted
  categories (click a swatch to cycle presets)
- **Custom Rules**: create / rename / recolor / toggle; click a rule name to pick entities
  (see §3.4)
- **Entity List**: generated from `BuiltInRegistries.ENTITY_TYPE`, modded mobs included;
  search, filter by namespace, pagination. Each row cycles three states — follow rules /
  force glow / never glow (a `*` prefix means it belongs to a custom rule)
- **Mod Sources**: per-namespace master toggle (also the emergency switch if a mod turns out
  to be incompatible)

## 5. Commands

```
/mobhl                      open the configuration screen
/mobhl toggle               master switch
/mobhl radius [blocks]      read / set the radius (4~128)
/mobhl max <count>          max outlines per frame
/mobhl scan <millis>        category correction interval
/mobhl rule <id>            toggle one default-rule category
                            (player/friendly/neutral/hostile/aquatic/ambient/other)
/mobhl reload               re-read the config file
/mobhl reset                restore defaults
/mobhl status               print the config summary
```

## 6. Known limitations

1. **An entity renderer without an outline variant will throw.**
   `OutlineBufferSource.getBuffer` throws
   `IllegalStateException("Can't render an outline for this rendertype!")` when a RenderType
   is neither an outline type nor has a corresponding outline type. Vanilla glow has the
   same constraint — this mod simply marks more entities at once and so hits it more easily.
   If it happens: search the game log for `Can't render an outline` and turn that namespace
   off on the Mod Sources tab.
2. `minecraft:player` cannot distinguish you from others, so by default "distance to camera
   < 1 block" is treated as yourself and skipped. Set `glowSelf` to true to outline yourself.
3. With GUI stratum rendering, "once per frame" APIs are not idempotent:
   `Screen#renderWithTooltipAndSubtitles` already calls `renderBackground(...)`, so a custom
   Screen **must not call it again** inside its own `render()` — a second
   `blurBeforeThisStratum` throws `IllegalStateException: Can only blur once per frame`.

## 7. Building

You need **JDK 21** and **Gradle 9.x** (Loom 1.17 requires Gradle 9 or newer). From the
project root:

```bash
gradle build
```

Output: `build/libs/mobhighlight-<version>.jar`, where the version comes from
`mod_version` in `gradle.properties`.

Three easy ways to trip up:

- The plugin id must be `fabric-loom`. Writing `net.fabricmc.fabric-loom` switches to the
  26.x no-remap mode and fails with
  `Cannot use Mojang mappings in a non-obfuscated environment`.
- If the `gradle.properties` in your Gradle user home (`GRADLE_USER_HOME`, `~/.gradle` by
  default) hardcodes a proxy address, override the environment variable or edit that file,
  otherwise Fabric Maven cannot fetch dependencies.
- Loom's decompile of Minecraft is memory-hungry. If you run out, add
  `org.gradle.jvmargs=-Xmx3G` to that same file.
