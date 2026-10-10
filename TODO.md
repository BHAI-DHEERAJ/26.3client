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

- [ ] **Build is unverified.** `./gradlew build` had not finished cleanly when this was
      written. The Mavenizer needed a **JDK 8** (auto-provision failed; a Temurin 8 tarball
      was placed in the Mavenizer cache manually). Re-run `./gradlew build` and fix any
      compile errors.
- [ ] Verify the `LightmapExtractorMixin` actually injects (`extract` TAIL) — check the
      mixin applies in `runClient` logs.
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
- The Mavenizer needs JDK 8 on first build; if the auto-download fails, place
  `OpenJDK8U-jdk_x64_linux_hotspot_8u504b01.tar.gz` in
  `~/.gradle/caches/minecraftforge/forgegradle/mavenizer/caches/`.

## Handoff notes (for the next agents)

- MC 26.3 is **unobfuscated**; inspect classes with
  `javap -p -classpath <client.jar> <fqcn>` or CFR.
- Client classes live in the Mojang-named package tree; `ResourceLocation` is now
  `net.minecraft.resources.Identifier`.
- Forge events use the EventBus 7 pattern: `SomeEvent.BUS.addListener(...)`.
- Add new modules in `CypherV6#registerModules` and, if they need events, in
  `ClientEvents#register`.
