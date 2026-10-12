# CypherV6
**this doesn't related to me its just a fork of a private client, which is discontinued**
A **client-side** Minecraft client for **Minecraft 26.3** on **Forge** (`26.3-66.0.10`),
in the style of a "legit cheat" client (LegitCheat / Meteor / Wurst feel): a click GUI
with toggleable modules, keybinds, and `[CypherV6]` chat feedback.

Opened with **RIGHT SHIFT**. Built with the official Forge 26.3 MDK.

> ⚠️ Client-side only (`clientSideOnly=true`). Use at your own risk. Combat modules are
> intended for anarchy servers that allow them.

---

## Modules

| Module | Category | What it does |
|--------|----------|--------------|
| **KillAura** | Combat | Attacks the closest entity in range (players/mobs toggles, CPS, rotation, "only charged" attack). |
| **TriggerBot** | Combat | Attacks whatever entity is under your crosshair. |
| **Tracer** | Combat | 2D screen lines pointing at nearby entities. |
| **AutoSprint** | Movement | Keeps you sprinting while moving forward. |
| **CustomFOV** | Render | Overrides the camera FOV (slider 30–160). |
| **Fullbright** | Render | Real fullbright — forces the lightmap to full white light (mixin). |
| **Nametags** | Render | Appends live player health to name tags; can hide the score line. |
| **NoRain** | Render | Disables rain and thunder client-side. |
| **FPSBooster** | Render | Temporarily reduces particles / clouds / entity shadows, restoring them on disable. |
| **HUD** | Client | Watermark + right-aligned array list of enabled modules. |
| **Notifications** | Client | Cheat-client style `[CypherV6] ...` chat messages. |

**Deliberately not included:** Reach / attack-range. (Requested to be left out — use the
normal attack path.)

---

## Using the GUI

* **Right click** a module → open its settings.
* **Left click** a module → toggle it.
* Click sliders / toggles / modes to change them.
* **Keybinds:** select a module, click the `Keybind:` row, then press a key to bind it
  (right-click the row to clear). The bound key toggles the module in game.
* **RIGHT SHIFT** (or ESC) closes the GUI.

---

## Building

Requirements: **JDK 25** (ForgeGradle also provisions a **JDK 8** on first build for its
Mavenizer).

```bash
./gradlew build
```

Output jar: `build/libs/cypherv6-1.0.0.jar` → drop into `.minecraft/mods` with Forge 26.3.

Dev client:

```bash
./gradlew runClient
```

---

## Project layout

```
src/main/java/com/cypherv6/
├── CypherV6.java                 # @Mod entry (client-only), registers modules
├── client/ClientEvents.java      # Forge event wiring (keys, tick, FOV, nametags, HUD layer)
├── gui/
│   ├── ClickGui.java             # click GUI (categories/modules/settings/keybinds)
│   └── HudLayer.java             # watermark + array list + tracers
├── mixin/LightmapExtractorMixin.java  # fullbright
├── module/{Category,Module,ModuleManager}.java
├── setting/{Setting,BooleanSetting,NumberSetting,ModeSetting}.java
├── util/{KeyUtil,ChatUtil}.java
└── modules/
    ├── client/{Hud,Notifications}.java
    ├── combat/{KillAura,TriggerBot,Tracer}.java
    ├── movement/AutoSprint.java
    └── render/{CustomFov,Fullbright,Nametags,NoRain,FpsBooster}.java
src/main/resources/
├── META-INF/mods.toml            # clientSideOnly=true
├── cypherv6.mixins.json
└── pack.mcmeta
```

---

## 26.3 API notes (things that changed)

MC 26.3 ships **unobfuscated** and uses a new render-graph pipeline:

* Screens draw in `Screen#extractRenderState(GuiGraphicsExtractor, int, int, float)`
  (no `render(GuiGraphics, …)`).
* HUD overlays are added through Forge's `AddGuiOverlayLayersEvent` as a `ForgeLayer`.
* Input uses `MouseButtonEvent` / `KeyEvent` records.
* The current screen is `Minecraft#gui.screen()`; open with `Minecraft#setScreenAndShow`.
* Client chat messages: `Minecraft#gui.hud.getChat().addClientSystemMessage(Component)`.
* **Input moved off GLFW to SDL.** Keybinds are 26.3 **SDL scancodes**
  (`InputConstants.KEY_A = 4` …), RIGHT SHIFT is `InputConstants.KEY_RSHIFT` (229), and a
  key press is `InputConstants.PRESS` (1) / release `0` / repeat `-1`. There is **no
  `org.lwjgl.glfw`** in 26.3. Names render via
  `InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName()`.
* Entity reach is **attribute-based** (`Attributes.ENTITY_INTERACTION_RANGE` /
  `BLOCK_INTERACTION_RANGE`) — deliberately not modified here.
* Fullbright is a Mixin into `LightmapRenderStateExtractor#extract` setting the
  night-vision lightmap factor to white.

---

## Performance mods (OptiFine / Sodium)

* **OptiFine has no 26.3 build.** The newest OptiFine releases are on much older
  versions, so it cannot load on 26.3.
* **Sodium** *does* have 26.3 builds, but only for **Fabric** and **NeoForge**
  (e.g. `mc26.3-0.9.3-alpha.1`). There is **no Forge build** of Sodium for 26.3.
  To use Sodium you would have to run this mod's logic on NeoForge (Forge and NeoForge
  are not binary compatible for mods).

---

## Status

See `TODO.md` for the current state and remaining work.
