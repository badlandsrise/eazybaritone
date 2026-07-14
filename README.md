# EazyBaritone

A fork of [Baritone](https://github.com/cabaletta/baritone) updated to run on **Minecraft 26.2**, with an in-game menu bolted on so you don't have to memorize chat commands to use it. Named to distinguish it from the original — all the pathfinding is Baritone's; the GUI is the "eazy" part.

Baritone is the pathfinding and automation engine behind a lot of Minecraft tools — it can walk you to coordinates, mine ore, build schematics, farm, follow entities, and fill/clear regions. This fork keeps all of that intact and adds a point-and-click interface on top for people who don't want to live in the chat box.

Everything here is licensed under **LGPL v3**, same as upstream — see [Credits](#credits) and [License](#license).

## What this fork changes

- **Runs on Minecraft 26.2** (Fabric). The original doesn't build for 26.2; this updates the toolchain, the ~250 changed API call sites, and the rendering to 26.2's gizmo pipeline.
- **In-game menu** — press **B** (rebindable in Controls) to open a full-screen menu. The game keeps running behind it.
- **Selection wand** — hold a blaze rod (or any item you pick), left-click a block for corner 1, right-click for corner 2, with a live outline in the world.
- **Visual copy/paste** — a "ghost" preview you place, nudge, rotate, and mirror before committing the build, so you always know where a paste will land.
- **Input macros** — auto-click or hold left/right mouse on a timer, straight from the menu; they stand down automatically while Baritone is pathing.
- **Searchable settings editor** — browse and edit all of Baritone's settings from the menu, with plain-English labels on the common ones.

Everything the base mod does is still available through normal chat commands (see [Under the hood](#under-the-hood)); the GUI just wraps the common jobs.

## The menu

Open it with **B**. Tabs:

- **Mine** — search a block, pick it, and it starts mining. Optional amount to stop at.
- **Go to** — type coordinates and go; save and travel to waypoints.
- **Follow** — follow the nearest player/entity.
- **Farm** — auto-farm within a radius.
- **Area** — set two corners (or use the wand), then **clear** the region or fill it: solid, walls, shell, sphere, hollow sphere, cylinder, hollow cylinder, or **replace** one block with another. Fill block is chosen from an icon picker, and **Move** buttons nudge the whole selection a block at a time on any axis. Clears dig out top-to-bottom and fills build bottom-to-top, so the job looks tidy in progress.
- **Clip** — **Copy**/**Cut** a selection, then **Place paste**: a ghost drops into the world where it'll build. Nudge it on any axis, **rotate** 90°, **mirror** it, then **Build here**. Paste builds bottom-to-top, places wall-mounted items as their wall variant (a torch becomes a wall torch), and skips anything it genuinely can't place instead of getting stuck.
- **Saved** — save the clipboard to disk as a `.schem` file that survives restarts. Name it, **Save to disk**, and it shows up in the list; **Place** loads it back into the ghost flow. Saved files live in your `schematics/` folder and also load with `#build <name>.schem`.
- **Macros** — light input helpers: auto-click **left** (attack) or **right** (use) on a seconds timer, or **hold** either button down. They only run in-world with no menu open, and pause while Baritone is pathing, mining or building.
- **Settings** — search across every setting; toggles for booleans, editable fields for numbers/colors/lists; the common ones have descriptions.

There's also a one-line HUD showing the current job (toggle with the `guiHud` setting), and Pause/Resume/Stop buttons on every tab.

## Under the hood

The GUI is a layer over the normal Baritone command system — the engine is unchanged. All the usual chat commands still work (default prefix `#`):

```
#goto 100 64 -200
#mine diamond_ore
#build mybase          # build a schematic from the schematics/ folder
#sel pos1 / #sel pos2 / #sel walls stone / #sel copy / #sel paste
#elytra
#help
```

Schematic building, block substitutions, elytra flight, waypoints, and the full ~200 settings are all still there via commands and `settings.txt`, even though the GUI doesn't surface every one of them.

## Install

1. Minecraft 26.2 with **Fabric Loader**.
2. Drop the jar in your `mods/` folder. (No Fabric API dependency.)
3. Launch, load a world, press **B**.

## Build from source

Requires **JDK 25**.

```
./gradlew build
```

The mod jar lands in `fabric/build/libs/`. For development you can run a test client with:

```
./gradlew runClient
```

## Credits

This is a fork of **[Baritone](https://github.com/cabaletta/baritone)** by Leijurv, cabaletta, and its many contributors. All of the pathfinding, building, and automation is their work. This fork only adds the 26.2 port and the GUI/wand/clipboard layer on top.

## License

GNU Lesser General Public License v3.0 — see [`LICENSE`](LICENSE). As a derivative of an LGPL project, this fork is and stays LGPL; the source is here for anyone who has the binary.
