# Reuse map: resource pack pipeline and pack serving (zombierun to Gave)

ROOT = `C:/Users/PC/Desktop/클로드/zombierun`. All paths below are relative to ROOT unless absolute.

Key files (absolute):
- `C:/Users/PC/Desktop/클로드/zombierun/tools/make_pack_src.py`
- `C:/Users/PC/Desktop/클로드/zombierun/tools/make_fx.py`
- `C:/Users/PC/Desktop/클로드/zombierun/pack_fx/zrfx.glsl` (hand-edited source of the item shader include)
- `C:/Users/PC/Desktop/클로드/zombierun/tools/make_hud.py`, `C:/Users/PC/Desktop/클로드/zombierun/pack_hud/hud.glsl`, `C:/Users/PC/Desktop/클로드/zombierun/pack_hud/hud_frag.glsl`
- `C:/Users/PC/Desktop/클로드/zombierun/tools/build_pack.py`
- `C:/Users/PC/Desktop/클로드/zombierun/tools/props/build_props.py`, `C:/Users/PC/Desktop/클로드/zombierun/tools/props/build_more.py`, `C:/Users/PC/Desktop/클로드/zombierun/tools/props/check_pack.py`, `C:/Users/PC/Desktop/클로드/zombierun/tools/props/licenses/`
- `C:/Users/PC/Desktop/클로드/zombierun/tools/props.json`
- `C:/Users/PC/Desktop/클로드/zombierun/plugin/src/main/java/kr/zombierun/PackServer.java`
- `C:/Users/PC/Desktop/클로드/zombierun/plugin/src/main/java/kr/zombierun/game/Props.java` (sound loader `packSounds`), `C:/Users/PC/Desktop/클로드/zombierun/plugin/src/main/java/kr/zombierun/game/Guns.java` (`visual`), `C:/Users/PC/Desktop/클로드/zombierun/plugin/src/main/java/kr/zombierun/game/ShotFx.java` (`fx`, `spawn`)
- Shader checker: `C:/Users/PC/.claude/skills/minecraft-shader/scripts/check_shaders.py`

---

## 1. Pipeline order, outputs, final zip

The order comes from `docs/운영.md` and must be kept:

1. **`python -X utf8 tools/props/build_props.py`** (run only when props change). It first deletes `build/props/pack_layer/`, then writes:
   - The layer `pack.mcmeta`. It is ignored at bundle time.
   - Gun models `assets/zombierun/models/item/{rifle,shotgun,pistol,smg,sniper}.json` and `{rifle,pistol,smg,sniper}_nomag.json`.
   - Textures `textures/item/<key>/*.png`. These are repacked losslessly and contain only the used rects.
   - Plain item definitions `items/<key>.json`.
   - Grenade, grenade_thrown, ammo_box, grenade_box.
   - Through `build_more.run`: `sounds.json` plus `sounds/**.ogg`, `lang/{ko_kr,en_us}.json` (subtitles), the zombie equipment layers (`equipment/*.json`, `textures/entity/equipment/humanoid{,_leggings}/*.png`) and the city props (cars, barricade, traffic_cone, street_lamp, dumpster, bench, trash_can).
   - `CREDITS.txt` plus the 3 `LICENSE-*.txt` files at the layer root, and `tools/props.json`.
   - Inputs: `ref/mods/<mod>/extracted/**` (the jars are deleted) and `ref/mods/minecraftskins`.
   - Python dependencies: Pillow, numpy, imageio_ffmpeg. Local modules: `vmodel`, `armor_layout`, `sound_info`.
2. **`tools/make_pack_src.py`** writes into `pack_src/`:
   - Transparent same-size copies of the vanilla 26.3 HUD sprites, read from `%APPDATA%/.minecraft/versions/26.3/26.3.jar`. `HIDE` covers air, armor_, experience_bar_, food_, hotbar*, jump_bar_ and heart/, plus every `boss_bar/` sprite.
   - `items/empty.json` (`minecraft:empty`).
   - `pack.mcmeta` with `min_format [97,1]`, `max_format 97`.
3. **`tools/make_fx.py`** writes:
   - `textures/item/fx_marker.png`.
   - `models/item/fx_{quad,tracer,flat}.json` and `items/fx_*.json`. The tint is `custom_model_data` color index 0, default `16777215`.
   - The patched `assets/minecraft/shaders/core/item.{vsh,fsh}`.
   - It copies `pack_fx/zrfx.glsl` to `pack_src/assets/zombierun/shaders/include/zrfx.glsl`.
   - Vanilla source: `~/.cache/mc-shader-vanilla/26.3/assets/minecraft/shaders/core`, extracted by the shader skill.
   - **It does not run the shader checker.**
4. **`tools/make_hud.py`**, then `tools/compile_wip.py --name hud --promote`. It writes:
   - `font/hud*.json` and `textures/font/hud/*`. Gun silhouettes and zombie faces are drawn from pack_layer.
   - `shaders/include/hud*.glsl` and the patched `core/text.{vsh,fsh}`.
   - `HudSpec.java`.
   - It runs the checker on a staged copy first and writes nothing if the check fails.
5. **`tools/build_pack.py`** produces `build/ZombieRun-pack.zip`, or the path in `$ZR_PACK_OUT`.
   - Layers in order: `build/props/pack_layer`, then `pack_src`. pack_src wins on conflicts. The layer `pack.mcmeta` is skipped.
   - It generates the gun item definitions, `models/item/muzzle_<gun>.json` and `models/item/kick/<gun>[_nomag].json`.
   - It wraps every other `zombierun` item definition in `display_context` with `gui` mapped to `minecraft:empty`. Exempt: keys starting with `fx_`, `empty`, `ammo_box`, `grenade_box`, `grenade_thrown`.
   - Zip entries get a fixed timestamp of 2026-01-01, so identical content gives an identical sha1.
   - If `CREDITS.txt` is missing from the zip root, it exits 1 and keeps the old zip.
6. **`bash tools/build_plugin.sh`** copies `tools/props.json` into the jar.

Current zip: 341 files, 1.9 MB.
```
pack.mcmeta  CREDITS.txt  LICENSE-GPL-3.0.txt  LICENSE-MIT-TAC-GEAR.txt  LICENSE-MIT-RealisticCities.txt
assets/minecraft/shaders/core/{item,text}.{vsh,fsh}
assets/minecraft/textures/gui/sprites/{hud/**,boss_bar/**}        (92 transparent)
assets/zombierun/items/ (25)  models/item/ (38: 21 layer + 3 fx + 5 muzzle_* + 9 kick/*)
assets/zombierun/textures/item/ (29)  textures/font/hud/ (63)  textures/entity/equipment/ (12)
assets/zombierun/font/hud{,_m,_s}.json  shaders/include/{zrfx,hud,hud_frag,hud_spec}.glsl
assets/zombierun/sounds.json  sounds/{gun,impact,grenade,zombie,survivor}/**.ogg  lang/  equipment/ (6)
```

`tools/props.json` has these top-level keys: `namespace`, `items{key:{item_model,source,license,notes,real,grip,variant_of,display_notes}}`, `equipment{}`, `sounds{"zombierun:<event>":{files,subtitle_ko,pitch,attenuation_distance,call}}`, `sound_files[{file,from,source,process,src_layout,src_dur}]`, `sound_notes`. Java reads `items`, `equipment` and `sounds` from it (`Props.load`).

---

## 2. Core shaders and how features are signalled

### item.vsh / item.fsh (`make_fx.shaders()`)
These are vanilla 26.3 files with anchored patches. `patch()` raises `SystemExit` unless the anchor appears exactly `count` times, which guards against vanilla drift.
- **vsh:**
  - Adds `#include <zombierun:zrfx.glsl>`.
  - Adds `layout(location = 7) out vec4 zrTint;` and sets `zrTint = Color` (the raw tint).
  - Replaces `gl_Position = ProjMat * ModelViewMat * vec4(Position,1)` with:
    `vec4 zrEye = ModelViewMat * vec4(Position,1); vec4 zrCol = Color; zr_kick(zrEye.xyz, zrCol); gl_Position = ProjMat * zrEye;`
  - Lighting then uses `zrCol`: `minecraft_mix_light(..., zrCol)`.
  - Comment (26.3 quirk): `Position` is camera-relative and on world axes. Camera rotation lives in `ModelViewMat`, so screen-relative motion must happen in eye space (x right, y up, -z forward).
- **fsh:** before vanilla sampling it calls `zr_fx(Sampler0, texCoord0, zrTint, zrLit)`.
  - If the result has `a >= 0`, it is our FX: discard when `a < 0.01`, otherwise handle OIT_ALPHA_ONLY, OIT_ACCUMULATE and fog itself, then `return`.
  - `zr.a < 0` means the fragment is not ours and vanilla runs unchanged.

### zrfx.glsl features
- **`zr_fx` (fragment, world FX and muzzle flash).**
  - Gated by the texture, not the tint. A fragment is ours only if the fx_marker texel is `(x*4+1, y*4+1, 197, 254)`, so `B==197 && A==254 && (R&3)==1 && (G&3)==1`. The position on the face is rebuilt from the texel index plus `fract`.
  - Alpha 254 places the marker in the translucent layer, which is why the hook handles the OIT variants.
  - The tint carries 24 bits: `[kind 4][seed 8][start tick mod 4000, 12]`. The top 2 bits of the seed are the variant.
  - `age = mod(GameTime*24000 - start, 4000)`, and `age > 3000` is treated as 0 because the client clock can lag. Lifetimes must stay under 3000 ticks.
  - Kinds in use: 1 tracer, 2 flash billboard, 3 dust, 4 sparks, 5 decal (variants: hole, scorch, blood, bile), 6 fireball, 7 shock ring, 8 smoke, 9 bile, 10 blood mist, 11 to 13 gun muzzle flash (front, horizontal side, vertical side). Kind 0 is a marker that draws nothing; it is the default for an unfired gun. **Kinds 14 and 15 are free.**
  - Java side (`ShotFx.fx`): `ItemStack.of(PAPER)` plus `ITEM_MODEL zombierun:<fx_*>` plus a CMD color `(kind<<20)|(seed<<12)|floorMod(world.getGameTime(),4000)`.
  - The display is spawned with yaw and pitch 0 and is oriented only through the transformation matrix. Other settings: `ItemDisplayTransform.NONE`, `Brightness(15,15)` for glowing kinds, `setViewRange(2.5f)`. Tracers fly using teleport interpolation (`setTeleportDuration ≤ 59`).
  - 4000 divides 24000, so the stamps survive the GameTime wrap.
- **`zr_kick` (vertex, first-person gun gestures).**
  - Returns early when `dot(pos,pos) > 6.25`, that is, beyond 2.5 blocks of the camera.
  - Body pattern: tint `R>>4 == 15`, `G>>4` in 11..14 (the signal), `B>>4 >= 12`. Packing: `[F][stamp hi4][sig4][stamp mid4][15-level 4][stamp lo4]`. Signals: E recoil (level 0..3), D draw (level from the draw length), C reload pose in, B reload pose out. It then **resets the color to white**.
  - Flash pattern: `R>>4` in 11..13 and bit 17 set (`KICK_BIT`). The seed is `[level2][kick1][rand5]`.
- **Gun custom_model_data contract** (`Guns.visual`, mirrored by `build_pack.py` constants `FLASH_THIRD=0, BODY_TINT=3, FLASH_FIRST=4`):
  - `flags[0]` = mag out, which switches to the `_nomag` model.
  - `colors[0..2]` = third-person flash (no kick bit).
  - `colors[3]` = body gesture (default white `0xFFFFFF`).
  - `colors[4..6]` = first-person flash.
  - Colors 3 to 6 are only read in the `firstperson_*` contexts, so a teammate's gun next to your camera never moves.
  - The data is sent only when it changes. `hand_animation_on_swap: false` stops the gun bobbing on every CMD change.

### core/text (make_hud)
HUD glyphs ride on a single boss-bar title per player. Display color is the signal: `B = 96` image, `97` shape, `98..` timed shape. R is the slot and G is the palette or fill. The GUI vertex variant moves glyphs to screen slots.

Gotchas from `hud.glsl` and `make_hud.py`:
- GUI size must be computed as `ceil(2/|ProjMat| - 0.01)` because floating-point gives 479.99997.
- Glyphs must be under 240 texels and need `ascent <= height`.
- Timed-shape `PERIOD = 3000` must divide 24000.

### Adding a new item-vertex feature (e.g. Gave flapping limbs) safely
- **Signal with a tint on the limb faces.** Give those faces `tintindex` k and have the item definition read `{"type":"minecraft:custom_model_data","index":k,"default":...}`. The vsh has `Color` directly, while `Sampler0` is fsh-only in vanilla, so a texture marker in the vsh would mean adding a sampler and breaking the checker's interface comparison.
- **Pick a bit pattern no existing feature claims.** Claimed today:
  - `R>>4 == 15` with `G>>4` in 11..14 and `B>>4 >= 12` (body kick).
  - `R>>4` in 11..13 with bit 17 set (flash kick).
  - White `0xFFFFFF`, which is the default for untinted faces.
  - The fx kinds only matter on marker texels.
  - A safe choice is a fixed `R>>4 == 14` plus a checksum nibble. Do not reuse fx kind 14 if you claim nibble 14.
- **Reset the color.** After decoding, set `col = vec4(1,1,1,col.a)` as `zr_kick` does. Otherwise the coded tint stains the texture through `vertexColor`.
- **Don't use the 2.5-block fence** for mob animation, since mobs are far from the camera. Put the call next to `zr_kick`, not inside it.
- **Where to displace.** For world-axis motion (a flap around a world up axis), displace before ModelViewMat: change the make_fx replacement to `vec3 zrPos = Position; gave_flap(zrPos, zrCol); vec4 zrEye = ModelViewMat * vec4(zrPos,1);`. Fog distances can keep using `Position`. Eye-space displacement goes after `zrEye`, as `zr_kick` does.
- **Time.** Use `GameTime*24000.0` (ticks, wraps every 24000). Choose frequencies whose period divides 24000, or there is a visible jump every 20 minutes. Per-mob phase can be a CMD color set once at spawn; no per-tick traffic is needed.
- **Hinge vs tip.** All four corners of a face share the tint. Options:
  - (a) Split the limb into segment cubes with rising amplitude in their tints.
  - (b) Use `gl_VertexID & 3` to tell corners apart. Unverified in 26.3; corner order must be checked in game.
  - (c) Make the limb its own item_display animated with server interpolation, which needs no shader.
- **If a varying is added,** use `location = 8` or above in both vsh and fsh. The 26.x checker matches by location; vanilla uses 0..6 and zombierun uses 7 (`zrTint`) in item and 4 (`zrHud`) in text.
- **Edit the sources.** Change `pack_fx/zrfx.glsl` (or a new include copied by make_fx), never the copy in `pack_src`.

---

## 3. Gun models from Timeless and Classics Guns

- **Source:** `ref/mods/timeless-and-classics-guns/extracted/assets/tac/models/special/<gun>_<part>.json` (vanilla Blockbench JSON parts), textures under `assets/tac/textures/`, and the scope `tac:item/8x_scope`. The jar is deleted; `extract_sources.TAC_SPECIAL` lists what was extracted.
- **`build_props.GUNS`:**

  | Key | Parts | Mag part |
  |---|---|---|
  | rifle | m4 + bolt, muzzle, rail, carry, stock_light | `m4_mag_standard` |
  | shotgun | m870_classic + pump | none |
  | pistol | glock_17 + slide | `glock_17_mag_standard` |
  | smg | hk_mp5a5 + bolt, bolt_handle, stock, rail | `hk_mp5a5_mag_standard` |
  | sniper | ai_awp + bolt | `ai_awp_mag_standard`, plus `scope=True` |

  Each entry also sets the grip `G`, third-person scale `tp`, first-person scale `fp` and first-person anchor `at`.
- **`build_gun`:**
  1. Merge the parts, `recenter` to (8,8,8), and compute `gun_display` (third person, first person, gui at 45°, ground, fixed; the left hand copies the right).
  2. `write_model` runs `fix_uv`, because 26.3 can't bake UVs outside 0..16; they are shifted or clamped.
  3. Textures are repacked to `zombierun:item/<key>/<name>`. It raises an error if one model mixes the item and block atlases ("Multiple atlases used in model").
  4. `write_variant("<key>_nomag", ...)` keeps elements up to the mag part, sharing the same textures and display.
  - Avoid the TaC `<gun>_sight` and `<gun>_bolt_handle` light-plate parts; they render as grey boxes.
- **`build_pack.gun_items`** builds, per gun:
  - `items/<gun>.json`: `select display_context`. `gui` is empty. `firstperson_*` is a composite of [the condition flag0 picking `kick/<gun>_nomag` or `kick/<gun>` with tint CMD3, plus `muzzle_<gun>` with tints CMD 4..6]. The fallback is a composite of [flag0 picking `<gun>_nomag` or `<gun>`, untinted, plus `muzzle_<gun>` with CMD 0..2].
  - The muzzle point is the centre of the elements within 0.06 of the minimum z.
  - `FLASH` sizes per gun. `SHRINK=0.5`: coordinates are shrunk and the display scale divided by SHRINK, so long guns stay inside -16..32. It asserts coordinates stay inside -16..32 and display scale `|scale| <= 4`.
- **Licenses (CREDITS [1]).** TaC 0.3.14.2 carries conflicting labels: Modrinth body CC BY-NC-SA 4.0, and GPL-3.0 in the Modrinth license field, on CurseForge and in the jar. **Both are honoured:**
  - Attribution (Serene Wave Studio, ClumsyAlien), non-commercial, share-alike.
  - The full GPL text `LICENSE-GPL-3.0.txt` at the pack root.
  - A modification notice with the date (`MODIFIED = "2026-11-10"`).
  - No donations, paid features or ads.
  - **Sounds are a separate problem:** TaC, JEG and TAC GEAR sounds are "provenance unverified" (`SOUND_DOUBT`). Only 3 files are verified (`SOUND_OK`). `docs/운영.md` says the pack is **not for public distribution**, and serving it to any joining player counts as distribution.
- **To get working guns and sounds in a fork, copy:**
  - `tools/props/{build_props.py, build_more.py, vmodel.py, armor_layout.py (imported unconditionally), sound_info.py, check_pack.py, extract_sources.py, fetch_modrinth.py}` and `tools/props/licenses/LICENSE-GPL-3.0.txt`. Add `LICENSE-MIT-TAC-GEAR.txt` too if you keep the grenade box or TAC GEAR grenade sounds.
  - `ref/mods/timeless-and-classics-guns/extracted`, `ref/mods/just-enough-guns/extracted` (mag in/out, charge, bolt, impact, whiz and second fire variants), `ref/mods/tac-gear/extracted` (grenade sounds and box), plus `_info.json`.
  - Sound events Java uses for guns: `gun.<kind>.{fire,mag_out,mag_in,dry,draw}`, `gun.{rifle,smg}.charge`, `gun.pistol.slide`, `gun.shotgun.{pump,reload_start,shell,reload_end}`, `gun.sniper.{bolt_pull,bolt_release}`, `impact.*`, `bullet.whiz`, `grenade.*`.
  - Shortcut instead of rebuilding: copy the built `models/item/<gun>[_nomag].json`, `textures/item/<gun>/` and `sounds/gun/**`. The `zombierun:` namespace is baked into the texture references and into the `sounds.json` names, so either keep that namespace or rewrite it.

---

## 4. PackServer

- **Fields and properties:**

  | Property | Default |
  |---|---|
  | `zombierun.packDir` | `dataFolder/../../../build`, i.e. `ROOT/build` |
  | `zombierun.packPort` | `8250` (`test_server.sh` uses `8250+SLOT`; ingame slot 9 uses `8259`) |
  | `zombierun.packHost` | the virtual host the player connected to, else `127.0.0.1`; IPv6 is bracketed |
  | `zombierun.packRepo` | GitHub release mode, off; `tools/upload_pack.py` does not exist |

  - File: `ZombieRun-pack.zip`.
  - Pack id: `UUID ID = nameUUIDFromBytes("zombierun:main")`.
- **HTTP:** `com.sun.net.httpserver.HttpServer`, context `/pack.zip`, a fixed pool of 2 daemon threads. It serves the in-memory `bytes` (404 if null) with `Content-Type: application/zip`. URL: `http://host:port/pack.zip?v=<sha1[0:12]>`.
- **`reload()` / `readLocal()`:**
  - Reads the whole file and computes the hex SHA-1.
  - Calls **`Props.packSounds(bytes)`**, which rebuilds the playable sound events from the served zip's `assets/zombierun/sounds.json`. Missing events fall back to vanilla with a warning, so a stale pack never goes silent.
  - With `packRepo` set, a virtual thread downloads `https://github.com/<repo>/releases/download/pack-<sha12>/ZombieRun-pack.zip` and uses it only if the sha1 matches.
- **Sending:**
  - `sendBeforeJoin(PlayerConfigurationConnection)` is called from `@EventHandler onConfigure(AsyncPlayerConnectionConfigureEvent e)` in `ZombieRun.java`. It sends `ResourcePackRequest` with `required(true)`, `replace(true)` and the prompt "좀비 탈출 총, 좀비, HUD 그림", plus a callback. It blocks the async configuration thread until a non-intermediate status arrives, the player disconnects, or 3 minutes pass, polling `done.get(1s)`.
  - `send(Player)` is used by `/zr 팩`, which runs `pack.reload()` and resends to everyone online.
- **To change for Gave:**
  - Package name.
  - Property prefix (`gave.*`).
  - File name (e.g. `Gave-pack.zip`) and the `build_pack.OUT` default to match.
  - UUID seed `"gave:main"`.
  - A port that doesn't collide with zombierun's 8250..8259.
  - Prompt text and thread names.
  - The `kr.zombierun.game.Props` import: copy the `packSounds`/`load` part of Props, or drop the call.
  - For a public 20 to 30 player server, set `packHost` to the public address.

---

## 5. Shader checker and 26.3 gotchas

- **Command:** `python ~/.claude/skills/minecraft-shader/scripts/check_shaders.py pack_src --mc 26.3` must report 0 errors; exit code 1 means an error. A broken core shader takes down the whole pack.
  - It compiles every vanilla define variant with ShaderC and compares vertex inputs, the vsh-to-fsh interface by location, std140 blocks and samplers.
  - A pass is not proof the shader looks right. Check in game with F3+T and `latest.log`.
  - `make_hud.py` runs it automatically; **`make_fx.py` does not**, so run it by hand after any item shader change.
- **Gotchas found in code comments:**
  - Item model coordinates must stay inside -16..32 and display scale within 4; large props are shrunk and scaled back through `fixed`, with `display_scale` recorded in props.json.
  - UVs outside 0..16 can't be baked (`fix_uv`).
  - Face `texture` must be a `#var` that exists in `textures`.
  - No mixing of the item and block atlases in one model; use `load_model(tex_sub=...)`.
  - `minecraft:constant` tints can recolor block-tinted mod models.
  - Item display draws the model turned 180° on y, so the model front (north, -Z) faces +Z at rotation 0.
  - First-person `Position` is camera-relative on world axes; use eye space for screen-relative motion.
  - Vanilla sounds live in `~/.minecraft/assets/objects` (index `34.json`). Stereo vanilla sounds play non-positional; positional sound needs mono, so stereo mod files are re-encoded to mono libvorbis q7 and mono files are copied byte-for-byte.
  - `attenuation_distance` must equal Java's send radius, or the sound cuts off at the radius edge: `GUN_FIRE_RANGE` rifle 96, shotgun 80, pistol 64, smg 80, sniper 128; draw and dry 12.
  - Pitch contract: `sounds.json` pitch is the audible pitch, and Java sends only jitter.
  - `hand_animation_on_swap: false` on guns.

---

## 6. Fork plan for Gave

**Copy and rename** (`zombierun` → `gave`: namespace dirs, `NS`, model ids, the `#include <zombierun:zrfx.glsl>` path, `ZOMBIERUN_*` guards optional):
- `tools/make_fx.py` and `pack_fx/zrfx.glsl`. Keep `zr_fx` kinds 1 to 13 and `zr_kick` unchanged. Add the mob-animation function plus one patch line.
- `tools/build_pack.py`. Keep the gun logic. **Change the gui-hiding pass:** guns and every other item currently render empty in `gui`, which would make guns invisible in a chest-GUI shop. Either drop the `gui` → empty cases or limit them to the hotbar-only design. Update the `OUT` name.
- `tools/make_pack_src.py`. Re-decide `HIDE`: keep the hotbar and hearts unless Gave also hides the vanilla HUD. Hide boss_bar only if you reuse the boss-bar glyph HUD. Change the `pack.mcmeta` description.
- `PackServer.java`, plus the sound half of `Props.java`.
- The prop toolchain and `ref/mods` folders listed in section 3.
- Java contracts to keep in step: `Guns.visual` CMD layout and `ShotFx.fx` bit layout.

**Strip:**
- `build_more.build_zombies` with `ZOMBIES`, `SKIN_RIGHTS` and `ref/mods/minecraftskins`. This also removes the HUD zombie-face glyphs in `make_hud.images()`.
- `build_more.build_city`: Hello Neighbor cars, TrafficCraft, Realistic Cities, the TAC GEAR crate dumpster, bench, trash can, and the `HN`/`TC`/`RC` tuples.
- From `SOUNDS`, `PITCH`, `RANGE` and `CALL`: the `zombie.*` and `survivor.infected` entries. This drops the Vampirism and JEG ghoul sounds.
- `SOUND_OK`, `SOUND_MOD` and `SOUND_DOUBT` entries for vampirism.
- `CREDIT_GROUPS` entries rc, tc, hn and skin; `HN_NOTE`; `SKIN_NOTE`; `LICENSE-MIT-RealisticCities.txt` in `LICENSE_FILES`.
- **Rewrite the hard-coded `write_credits()` text** (roughly lines 661 to 771), which names zombie skins and city props.
- Bump `MODIFIED`.

**Add:**
- Fake-mob item models with tinted limb faces and their item definitions (CMD color tints).
- The new vsh function, using the reserved pattern from section 2.
- Mob sounds from verifiable sources.
- Re-run `check_pack.py` and `check_shaders.py`.

**Before any public server:** the TaC gun sounds are unverified, so replace them or keep the server private. Keep `CREDITS.txt` and `LICENSE-GPL-3.0.txt` at the zip root; `build_pack` enforces `CREDITS.txt`.