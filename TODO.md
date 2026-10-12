# CypherV6 — TODO

Status of the Minecraft **26.3 Forge** client. Target: a polished "legit cheat" client.

## Done

- [x] Forge 26.3 MDK project (`build.gradle`, wrapper, Java 25, mixins wired).
- [x] Module / Setting framework (`Module`, `ModuleManager`, `Category`,
      `Boolean/Number/Mode` settings).
- [x] Click GUI opened with **RIGHT SHIFT** (`ClickGui`).
- [x] Keybind system (bind any module from the GUI; key toggles it in game).
- [x] Chat feedback system (`ChatUtil`, `Notifications`, `[CypherV6] ...` messages).
- [x] Render modules: CustomFOV, Fullbright (mixin), Nametags, NoRain, FPSBooster.
- [x] HUD module (watermark + array list).
- [x] Combat modules: KillAura, TriggerBot, Tracer (2D overlay).
- [x] Movement: AutoSprint.

## In progress / needs verification

- [x] **Build verified.** `./gradlew build` succeeds and produces
      `build/libs/cypherv6-1.0.0.jar` (all 44 entries: classes, `mods.toml`,
      `cypherv6.mixins.json`, `pack.mcmeta`).
- [ ] `runClient` smoke test: GUI opens on RIGHT SHIFT, keybinds bind/toggle, chat messages
      appear, fullbright mixin applies (check the log for the mixin).
- [ ] Verify KillAura/TriggerBot attack timing and that rotations look natural.
- [ ] Verify Tracer projection lines up with entities (currently a linear FOV approximation).

## Next features (suggested)

- [ ] Combat: Criticals, Velocity / AntiKnockback, AutoBlock, Reach (**intentionally omitted**
      per request), AimAssist, AutoTotem.
- [ ] Render: ESP (box/outline), Storage ESP, Item ESP, Chams, NoHurtCam, NoFire, Trajectories.
- [ ] Player: AutoTool, FastPlace, NoFall, Scaffold, ChestStealer.
- [ ] Movement: Sprint, NoSlow, Step, Speed, Fly (server-dependent).
- [ ] Misc: AntiAFK, AutoRespawn, MiddleClick, friends/enemies list.
- [ ] GUI: draggable panels, search, config save/load, custom colours, HUD editor.
- [ ] Config: persist modules/settings/keybinds to disk (`config/cypherv6.json`).
- [ ] Commands: `.bind`, `.toggle`, `.config` via chat prefix.

## Known issues / notes

- No **Reach** module by design.
- **2b2t** does not run 26.3, so this build will not connect there directly.
- **Sodium** is Fabric/NeoForge only — no Forge 26.3 build (see README).
- MC 26.3 **dropped GLFW for SDL**: there is no `org.lwjgl.glfw` on the classpath. Use
  `com.mojang.blaze3d.platform.InputConstants` (`KEY_RSHIFT = 229`, `PRESS = 1`).
- The Mavenizer needs JDK 8 on first build; if the auto-download fails, place
  `OpenJDK8U-jdk_x64_linux_hotspot_8u504b01.tar.gz` in
  `~/.gradle/caches/minecraftforge/forgegradle/mavenizer/caches/`.
- First build also needs the **Gradle 9.7.1** wrapper distribution; if that cache is
  missing the wrapper re-downloads it from `services.gradle.org`, which can time out —
  fetch `gradle-9.7.1-bin.zip` from a mirror and unzip it into
  `~/.gradle/wrapper/dists/gradle-9.7.1-bin/<hash>/` (plus a `.zip.ok` marker).
- A **truncated jar** in the Mavenizer cache breaks `javac` with
  `zip END header not found`. Delete the offending artifact so the Mavenizer re-fetches
  it (verified: `mixin-0.8.7.jar`, sha1 `8ab114ac385e6dbdad5efafe28aba4df8120915f`).

## Handoff notes (for the next agents)

- MC 26.3 is **unobfuscated**; inspect classes with
  `javap -p -classpath <client.jar> <fqcn>` or CFR.
- Client classes live in the Mojang-named package tree; `ResourceLocation` is now
  `net.minecraft.resources.Identifier`.
- Forge events use the EventBus 7 pattern: `SomeEvent.BUS.addListener(...)`.
- Add new modules in `CypherV6#registerModules` and, if they need events, in
  `ClientEvents#register`.
