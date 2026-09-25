# Mob Highlight

Draws vanilla glowing outlines over mobs within a radius — the spectral-arrow look,
visible through walls — colored by rule. Modded entities included, with an in-game
configuration screen.

It rides the vanilla outline pipeline, so the outline hugs the entity model itself
rather than drawing a hitbox-style box.

Client-side only, no Mixin, nothing to install on the server.

**Requires**: Minecraft 1.21.11 · Fabric Loader 0.18.4 or newer · Fabric API

> 中文说明见 [README.md](https://github.com/yuyou815/mobhighlight/blob/main/README.md)。

## Installation

Drop `mobhighlight-<version>.jar` and Fabric API into your `mods/` folder.

Delete any older `mobhighlight-*.jar` first — two jars with the same mod id will abort
startup.

## Controls

| Key | Action |
| --- | --- |
| `H` | Toggle, with an on/off message in chat |
| `J` | Open the configuration screen |
| `]` / `[` | Detection radius ±8 blocks |

Both keys can be rebound from the General tab, which links to the vanilla Controls screen.

## Configuration screen

Five tabs:

- **General** — master switch, through-wall display, invisible entities, outline yourself,
  radius, per-frame cap, correction interval, keybinds
- **Default Rules** — the seven auto-sorted categories (Player / Friendly / Neutral /
  Hostile / Aquatic / Ambient / Other), each with a toggle and a color swatch
- **Custom Rules** — pick your own entities, name them, give them a color (see below)
- **Entity List** — every entity including modded ones, with search and filter by mod.
  Each row cycles three states: follow rules / force glow / never glow
- **Mod Sources** — per-mod master toggle, the quick way out when a mod turns out to be
  incompatible

Through-wall display is on by default, which is the vanilla glow behavior at no extra cost.
Turning it off outlines only what you can see, at the price of a raycast per frame.

Anything you change is written to `config/mobhighlight.json` immediately.

## About the rules

Default rules sort entities by tag and registered category, with no hardcoded mob list
anywhere, so modded mobs land in a sensible category on their own.

Where that gets it wrong, you can pull entities out with your own rule. Villagers are the
usual example: registered as `MISC` with no category tag, so default rules can only file
them under "Other". Click "Add entity" to get the category list, open a category, pick the
entities — from then on they use that rule's color instead.

An entity already claimed by another rule shows up greyed out and unclickable; remove it
from its current rule first. Removing an entity, or deleting the whole rule, puts it back
on its default category and color right away.

## Commands

```
/mobhl                      open the configuration screen
/mobhl toggle               master switch
/mobhl radius [blocks]      read / set the radius (4~128)
/mobhl max <count>          max outlines per frame
/mobhl scan <millis>        category correction interval
/mobhl rule <id>            toggle one default-rule category
/mobhl reload               re-read the config file
/mobhl reset                restore defaults
/mobhl status               print the config summary
```

## Known issues

1. Some mods register an entity renderer without an outline variant. Rendering then throws
   `Can't render an outline for this rendertype!` and the game may crash. Vanilla glow has
   the same limitation — this mod just marks more entities at once and so hits it more
   easily. If it happens, search the log for that line and turn the offending mod off on
   the Mod Sources tab.
2. There is no way to tell yourself apart from other players, so anything closer than one
   block to the camera is treated as you and skipped. Set `glowSelf` to outline yourself
   too.

## Building

JDK 21 and Gradle 9.x are needed (Loom 1.17 requires Gradle 9 or newer). From the project
root:

```bash
gradle build
```

The jar lands in `build/libs/`, named after `mod_version` in `gradle.properties`.

## License

MIT
