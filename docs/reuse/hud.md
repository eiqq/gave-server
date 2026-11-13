# Gave reuse map: screen HUD (zombierun)

## 0. Files and pipeline

| File | Role | Reuse |
|---|---|---|
| `plugin/src/main/java/kr/zombierun/hud/HudLine.java` | Builds one slot's glyph line (pen-based) | Copy verbatim, rename package |
| `plugin/src/main/java/kr/zombierun/hud/HudScreen.java` | One player's boss bar, dirty check, renders the Component | Copy verbatim, rename package |
| `plugin/src/main/java/kr/zombierun/hud/HudSpec.java` | Generated tables (never hand-edit) | Regenerate |
| `plugin/src/main/java/kr/zombierun/game/Hud.java` | Zombie-specific composer | Use as a template only |
| `tools/make_hud.py` | Generator: fonts, glyph PNGs, shape sheets, core/text patch, HudSpec.java | Copy, edit the tables |
| `pack_hud/hud.glsl`, `pack_hud/hud_frag.glsl` | Shader sources (make_hud copies them into the pack) | Copy verbatim |
| `tools/make_pack_src.py` | Makes vanilla HUD sprites transparent: `HIDE = hud/{air,armor_,experience_bar_,food_,hotbar,jump_bar_,heart/}` + `boss_bar/` | Copy (this is what makes the carrier bar invisible) |

- **Build order:** `make_pack_src.py` → `make_fx.py` → `make_hud.py` → `build_pack.py`.
- **HudSpec.java location:** make_hud writes it to `build/wip/hud/plugin/src/main/java/kr/zombierun/hud/HudSpec.java`. You must then run `python -X utf8 tools/compile_wip.py --name hud --promote`, or the pack and the code drift apart.
- **External inputs to make_hud.py:**
  - `%APPDATA%/.minecraft/versions/26.3/26.3.jar` (for ascii.png and font/include/default.json)
  - vanilla shaders at `~/.cache/mc-shader-vanilla/26.3/assets/minecraft/shaders/core/text.{vsh,fsh}`
  - `~/.claude/skills/minecraft-shader/scripts/check_shaders.py`
  - `build/props/pack_layer` (read only: models and textures)
  - `tools/props/vmodel.py` (orthographic model renderer)
  - numpy, PIL

## 1. Architecture

### Carrier
- Each player gets one `BossBar` (`HudScreen.bar`: WHITE, PROGRESS, progress 0). Its sprites are transparent.
- The bar's title holds every slot's glyphs. Each slot is written as its pieces, then `moves(-pen)`, so the title's net advance is 0.
- Because of that, vanilla always starts the title at `x = floor(guiW/2)`, with the baseline at `y = 10`. The comment in hud.glsl says why: in 26.3 BossHealthOverlay the first title's top is at y=3, and the baseline is 7 below that.

### Color signal (per glyph, set in `HudScreen.render`)
- **R** = slot index (the order of `SLOTS` in make_hud; asserted < 128).
- **B** = kind:
  - `96 B_IMG`: image or text. G = palette index (`PALETTE`).
  - `97 B_SHAPE`: shader shape. G = fill 0..255. Color comes from the slot color.
  - `98..109 B_TIMED`: `(B-98)*256 + G` = end tick mod `PERIOD` (3000). `TIMED_SPAN = ceil(3000/256) = 12`.
- **Text shadow:** shadow color = `0xFF000000 | slot<<16 | SHADOW<<8 | 96`. The shadow glyph is itself a signal glyph, so it moves with its text.
- **Images and shapes:** `ShadowColor.none()`. Pen moves have no color.
- **Why this is safe:** vanilla text never uses B 96..109. Named colors use B ∈ {0, 85, 170, 255}, and default shadows are ≤ 63.

### Vertex stage (`hud.glsl`, `zrhud_vertex`)
1. Only runs under an ortho projection (`zrhud_ortho`), for a known kind, with `r < ZR_SLOT_COUNT`, and with the palette index in range.
2. Only relocates glyphs whose ModelView y is inside the band `[ZR_BAND_TOP, ZR_BAND_BOT]`. The band is computed from the tallest ascent/descent ± 4 (currently −18..38).
3. Computes:
   - `local = pos.xy - (floor(gui.x/2), 10)`
   - `anchor = SLOT.xy*gui + SLOT.zw`, snapped to the screen pixel grid (`px = ScreenSize/gui`)
   - `glPos = ProjMat*(anchor+local)`
4. Images take their color from `ZR_PAL`. Shapes take it from `ZR_SLOT_COLOR`.
5. PULSE is `0.6 + 0.4cos(GameTime*24000*0.314)`, which is a 20-tick breath. It applies to the slot (mode bit 2) or to the palette (`ZR_PAL_FX = 1`).
6. Outputs `zrHud = (kind, fill-or-remaining, mode, 0)` at `location = 4`.
7. Timed glyphs: `rem = mod(v - now + P/2, P) - P/2`, where `now = mod(GameTime*24000, P)`. P=3000 divides 24000, so wrap-around is seamless.

### Fragment stage (`hud_frag.glsl`, `zrhud_fragment`)
- **Images and text:** "sharp bilinear" `zrhud_sample` × vcolor. Discards when `a < 0.02`, which is lower than vanilla's 0.1, so translucent palettes like PANEL 0.46 and GHOST 0.24 survive.
- **Shapes:** the glyph texel stores geometry, and the shader draws the shape analytically:
  - **Bars (B code < 64):**
    - R = position of the column's center along the bar.
    - G = role: 0 = border `ZR_EDGE`, 1 = inner, 2 = shaded inner (×0.74), ≥3 = discard.
    - A column is lit when `t.r < fill`; otherwise it shows `ZR_TRACK`.
  - **Coord cells (32×32 texels, R,G = texel xy):**
    - 64..127 → `ring_a` (radii 0.80..0.97)
    - 128..191 → `ring_b` (radii 0.88..0.97)
    - 192 → hit marker (four diagonals)
    - 193 → dot
    - 194 → infinity
    - All are SDFs with 1-pixel AA (`zrhud_cov`) plus a dark edge. Rings fill clockwise from 12 o'clock.
  - **Timed glyphs:**
    - `dur = ZR_DUR[code & 63]` (codes ≥192 use `HIT_TICKS = 7`).
    - `left = clamp(rem/dur)`.
    - Mode bit 1 (DRAIN) → `fill = left`; otherwise `fill = 1 - left`, so the shape grows.
    - Mode bit 4 (HIDE) → discard once `rem <= 0`.
    - The hit marker uses `life` to spread out and fade.
- **How duration is chosen:** each shape sheet holds 64 cells with identical geometry, differing only in the B code (the bin). `HudLine.timed` picks `char = base + bin(ticks)`, where the bin is the nearest of the 64 `DUR` entries.

### Patching vanilla
- `make_hud.shaders()` calls `patch()` on these exact anchor strings in vanilla `text.vsh` / `text.fsh`:
  - `#include <minecraft:projection.glsl>\n`
  - `layout(location = 3) out vec2 texCoord0;\n`
  - `    texCoord0 = UV0;\n`
  - `uniform sampler2D Sampler0;\n`
  - `void main() {\n`
- Each anchor must match exactly once, or the script exits.
- Only the `IS_GUI` variant is touched. The fragment's early return is guarded by `!defined(OIT_ALPHA_ONLY)`.

## 2. Defining a new element end to end

### make_hud.py tables
- **SLOTS** tuple: `(name, anchor x ratio, anchor y ratio, offset x, offset y = text baseline in GUI px, shape color or None, mode bits DRAIN=1|PULSE=2|HIDE=4)`.
  - Slot color and mode only affect shapes. Images and text get their color per piece from the palette.
- **PALETTE** tuple: `(name, RGBA multiplier, fx)` (fx 1 = blink). Append new entries; `SHADOW` must stay.
- **Images:** `add_image(name, PIL_img, height_gui, ascent_gui)`. Art is usually `RES = 4` texels per GUI px.
- **Shape sheets:** `add_sheet(name, cells, cols, height, ascent, first_char)`.
  - A bar family is `[bar_cell(w, h, b) for b in range(64)]`.
  - A coord family is `[coord_cell(code) ...]`.
- **Flat rects:** `rects()` builds rectangle kinds in 2^k widths. Add a tuple `(kind, h, ink_rows, ascent)`.

### Worked example: currency counter, HP bar, bleed-out ring

make_hud.py:
```python
SLOTS += [("MONEY", 1.0, 0.0, -6, 14, None, 0),
          ("HP", 0.0, 1.0, 6, -10, RED, 0), ("HP_LOW", 0.0, 1.0, 6, -10, RED, PULSE),
          ("C_BLEED", 0.5, 0.5, 0, 0, RED, DRAIN)]
PALETTE.append(("COIN", (1.0, 0.82, 0.25, 1.0), 0))
# in shapes():
add_sheet("bar_hp", [bar_cell(80, 5, b) for b in range(64)], 1, 5, 5, 0xE600)
# in images():
add_image("coin", Image.open(SRC / "coin.png").convert("RGBA"), 9, 8)  # 36x36 texels -> 9 GUI
```

Then run:
- `python -X utf8 tools/make_hud.py --mock build/hud/mock.png` (the mock is a layout preview at 427×240 GUI ×3)
- `compile_wip.py --name hud --promote`
- `build_pack.py`

Java:
```java
static final int MONEY = HudLine.slot("MONEY"), HP = HudLine.slot("HP"), HP_LOW = HudLine.slot("HP_LOW"),
    C_BLEED = HudLine.slot("C_BLEED"), COIN = HudLine.pal("COIN"), WHITE = HudLine.pal("WHITE");
String m = Integer.toString(money);
HudLine l = new HudLine().textRight(0, HudLine.M, m, COIN);              // right ink edge at anchor
l.at(-(HudLine.width(HudLine.M, m) - 1) - 3 - HudLine.ink("coin")).img("coin", WHITE);
s.set(MONEY, l);
double f = hp / max;
s.set(f <= .25 ? HP_LOW : HP, new HudLine().shape("bar_hp", HudLine.cells((int) Math.round(f * 80), 80)));
if (downed) s.set(C_BLEED, new HudLine().at(-24).timed("ring_b", bleedEndWorldTick, bleedTicks)); // reuse ring_b, no new art
```

### Positioning rules
- `at(x)` is relative to the slot anchor, and it may move backwards. `move(n)` is a relative move.
- Right-anchored slots use negative x together with `ink()` / `width()`.
- Center a glyph with `at(-ink/2)`. Hud uses `at(-24)` for the 48 px ring and `at(-20)` for the 40 px ring.
- Vertical placement is fixed per glyph by its ascent. To center a glyph on the anchor, set `ascent = height/2` (ring_a is 40 high with ascent 20).
- To put pixels above the baseline without a negative ascent, pad transparent rows at the bottom (`bar_cell(..., pad=2)`; `strike` inks only its top row).
- One slot can mix pieces. Example from the roster: gun icon + name + strike rect.

## 3. Numbers, text, silhouettes

### Text
- Fonts `zombierun:hud_s` and `hud_m` both reference vanilla `minecraft:font/ascii.png`.
  - `hud_s`: height 8, ascent 7, space 4.
  - `hud_m`: height 16, ascent 14, space 8.
- Only 0x21..0x7E are covered. `HudLine.safe()` turns everything else into `?`, so **Korean text cannot be shown**.
- `ADV_S` / `ADV_M` store each char's advance + 0x1000. They follow the 26.3 BitmapProvider rule: `(int)(0.5 + ink*height/cellH) + 1`.
- Digits are 6 px wide (S) and 11 px wide (M).
- `width()` includes the trailing 1 px gap. `textRight` uses `+1` so the ink, not the gap, lines up. `Hud.number()` centers with `width - 1`, and turns GOLD at ≤ 3.

### Korean in Gave
Pre-render a finite set of phrases (augment names, shop label) as image glyphs at pack-build time with `PIL.ImageFont`.
- 240 texels at RES 4 gives at most 60 GUI px. For wider text, render at 2 texels/GUI (up to 120 GUI px), or split it into several glyphs.
- A unifont/reference font provider is riskier, because Java would need a Hangul advance table.
- Keep labels minimal (user principle: layout over labels).

### Gun silhouettes (`gun_icon`)
1. Loads `build/props/pack_layer/assets/zombierun/models/item/<key>.json`.
2. Renders the vmodel "side" view, orthographic, 4× supersampled, then box-averaged.
3. `silhouette()` remaps luminance to light gray 0.70..1.0 and adds a 2-texel dark outline (`dilate`, alpha 0.72).
4. Rounds the height up to a multiple of RES so the GUI height is an integer.

Sizes are set in `GUN_SIZES`: `l` 60×22, `m` 30×10 (sits 1 px below the baseline), `s` 18×6. `_nomag` variants are drawn inside the base gun's frame so the body doesn't shift while reloading. `nade_icon` works the same way, in a square.

### Faces (`face(look, px, frame)`)
- Crops the head front `(8,8,16,16)` and the hat layer `(40,8,48,16)` from `textures/entity/equipment/humanoid/<look>.png`, scales up with NEAREST, and adds a dark frame.
- Sizes: `c` 13, `s` 17, `l` 22 GUI.
- **For Gave:** write a new face function that crops the face region of each _Gave mob texture, or renders the mob model's front view through vmodel (`geo_elements` / `bbmodel_elements` are available), keeping color.

### Missing art
Missing art is skipped and printed. In Java, check `HudLine.has()` and fall back (Hud shows the gun key as GRAY text).

## 4. Update and dirty logic, packet cost

### Per-tick flow (`Hud.tick`)
For each online player:
1. `computeIfAbsent(HudScreen)`.
2. If the player is not `g.inGame` (creative), `hide`; otherwise `show`.
3. `reset()` (all slots null), then `compose`, then `flush()`.

`flush()` compares `Arrays.equals(lines, sent)`. Equality is by content (records `Piece(font, text, g, b, shadow)` + `pen`), so rebuilding lines every tick costs nothing. When any slot differs, the whole title is sent as one boss-bar name update. `set()` stores an empty line (only pen moves) as null. Shared lines, like the phase line, are built once per tick and reused for every player.

### How sends are kept low
- **Timed shapes:** reload, cooldown, grab, prep and lobby are only sent when they start or end.
- **Quantized fills:** `cells(k, w)` lines fill values up exactly with `bar_cell` column centers. The shutter bar moves in 2-cell steps ("half the sends").
- **Stable end ticks:** the lobby end tick is only replaced when it drifts by more than 1. Recomputing it from a float progress would jitter ±1 and resend every tick.
- **`track(map, key, endTick, now)`:** records the start tick the moment the end value changes, which gives the full duration to `timed()`.

### Cost
- Every piece is a component carrying font, color and shadow_color, and every change resends the whole title.
- Per-player triggers: each shot (the mag number changes), each hit (the hit marker's end tick moves), and state changes.
- Paper's packet compression helps, because the payload is very repetitive.
- **For 20-30 players:** never put values that change every tick into other players' rows (teammate HP), and cap the number of rows.

### Test hook
`/zr 시험 허드 <name>` prints `sends()` and `describe()` (`SLOT[img/PAL shape=fill shape#bin@end]`). Keep it for QA.

## 5. Constraints and gotchas

1. **The HUD boss bar must be the first boss bar.** The shader assumes baseline y=10. A second bar's title would land in the band about 19 px lower and appear in the wrong place. `hide`/`show` re-appends the bar to the end of the list. No other code may show boss bars; `Hud.phase(Component, float, BossBar.Color)` is a leftover signature that ignores its title and color.
2. **Glyph size and ascent.** Images must be ≤ 240 texels in each dimension, and `0 <= ascent <= height` (both asserted).
3. **Lowercase names.** Resource names must match `[a-z0-9_.-]` (asserted; this is why `GUN_SIZES` keys are lowercase).
4. **Silent fallbacks.** `HudLine.pal()` silently returns palette 0 (WHITE) for an unknown name. `slot()` returns -1, and `set()` silently ignores it. Typos don't show up on screen; check them with `describe()`.
5. **Pack and code must match.** A palette or slot index out of range in the shader means the glyph is not moved: it draws as garbage at the top center. HudSpec.java and the pack must come from the same make_hud run.
6. **Advances.** `IMAGE_ADV` is ink + 1. Use `IMAGE_INK` (via `ink()`) for alignment. `IMAGE_H` and `IMAGE_ASC` are exported but unused in Java.
7. **GUI size.** It is recovered as `ceil(2/|ProjMat[0][0]| - 0.01)`. A plain floor gave 479.99997 → off by one (the eternalmc 1080p bug). Anchors are snapped to pixels. Everything is in GUI units, so layout scales with GUI scale. Check the layout at 427×240 and 480×270.
8. **Sampling.** Art at 4 texels per GUI px is drawn 1:1 at GUI scale 4 and sharp-bilinear at other scales. The 26.3 font atlas leaves a 1-texel gap between glyphs.
9. **Timed shapes.**
   - The end tick must be `World#getGameTime()`. Hud converts its own `g.now()` with `off = wt - now`.
   - Valid range is ±1500 ticks (PERIOD/2). A timed glyph left on screen more than 75 s past its end starts animating again.
   - Durations snap to the nearest `DUR` bin. `DUR` must have exactly 64 entries (max 1200).
   - `B_TIMED + TIMED_SPAN <= 128` is asserted.
10. **Band.** Only glyphs inside the y band are moved, and the band grows with the tallest glyph. Don't use colors with B 96..109 anywhere else near the top of the screen (tab list header, other text).
11. **Char ranges.**
    - Pen moves: U+E000..E009 (+2^i) and E010..E019 (−2^i), MOVE_BITS 10.
    - Images count up from U+E100 with **no bound check**. More than 768 images would collide with U+E400.
    - Shape sheets are hand-placed: E400, E440, E480 (bars, 64 each), E4C0 (`seg`), E500 and E540 (rings, 64 each), E580..E582.
    - Start new sheets at U+E600 or higher.
12. **Shape code space.**
    - All bar sheets share codes 0..63, because their geometry lives in R, so you can add any number of bar sizes.
    - Ring A/B codes work at any glyph size (the radii are fractions).
    - A new ring thickness or a new SDF shape needs new codes. Only 195..255 are free (61), so a new 64-bin timed family won't fit unless you restructure (for example, 32 bins).
13. **Shader safety and cleanup.**
    - Shaders are validated in a temporary copy of pack_src before anything is written, because a broken core shader disables the whole pack.
    - `clean()` deletes every file under `textures/font/hud/` and `font/hud*.json` that this run did not produce. Never put hand-made files there.
14. **Overlaps are handled in code.** Example: CARD is suppressed while `C_GRAB` is shown.
15. **Splatter overlay.** It is a head-slot item with `Equippable.cameraOverlay` and `ITEM_MODEL zombierun:empty`. It replaces the helmet, which matters if Gave uses armor.

## 6. What is zombie-specific in Hud.java, and Gave's HUD plan

### Zombie-specific
- **Game:** `g.state` (LOBBY/PREP/RUN/FINALE), `lobbySeconds`, `prepSeconds`, `stateTicks`, `isShutterOpen`, `shutterProgress`, `route.project/length`, `alive()`, `roster()`, `isSurvivor`, `running`, `inGame`, `now`.
- **Guns:** `arms`, `held`, `ammo` → `{mag, reserve (-1 = infinite), reloading>=0}`, `reloadPhase` → `{ticksLeft, phaseLen}`, `SPECS`, `Spec.key/mag/name`.
- **Others:** `Grenades.MAX`; `Zombies.get/grabberOf/all`; Zed fields (`type.look/special`, `readyAt`, `fuseAt`, `grabUntil`, `grabbing`, `stacks`, `deaths`, `bot`); `Director.threshold()`; `Stats.name`; `Props`.
- **Logic:** the kill-guess via `seen` death counts, the infection card, the 4-row roster, and the `잡힘시험` test hook.

### Generic (keep)
- The tick skeleton and screen-map cleanup.
- `number()`, `card()`, `track()`.
- Hit marker: `hitUntil` + `timed("hit", end, HIT_TICKS)` in a HIDE slot.
- The `허드` test hook.

### Gave slot plan

| Slot | Anchor (x, y, dx, dy) | Color / mode | Content |
|---|---|---|---|
| `WAVE_BAR`, `WAVE_BAR_BOSS` | 0.5, 0, -60, 11 | RED / GOLD+PULSE | `bar_phase` fill = remaining/total via `cells(k,120)`; one shared line for all players |
| `WAVE_BREAK` | same | WHITE DRAIN | timed `bar_phase` for intermission (≤75 s; longer needs stepped untimed fill) |
| `WAVE_NUM` | 0.5, 0, 0, 31 | none | `number(wave)` (M); remaining count as S text to the bar's right |
| `MONEY`, `MONEY_GAIN` | 1, 0, -6, 14 / 24 | none | coin + M number; "+25" in S GREEN, removed by the server after about 20 ticks |
| `W_GUN W_RLD W_MAG W_RES W_INF` | copy as is | copy | `GUNS` in make_hud must mirror Gave's gun registry (l/m/s + `_nomag`); random guns need one silhouette per model key |
| `W_NADE` → `W_AUG` | 1, 1, -58, -8 | none | small augment icons or charge pips |
| `HP`, `HP_LOW`, `HP_NUM` | 0, 1, 6, -10 | RED / RED+PULSE | `bar_hp` (new 80×5 sheet), optional S "84" |
| `TM0..TM7`, `TMG0..TMG7`, `TM_COUNT` | 0, 0, 4, 13 + 10i | none / RED DRAIN | Up to 8 rows: downed first (RED_BLINK + timed `bar_mini` bleed-out), then dead (DIM + `rect("strike")`), then alive (self GOLD); count row = person icon + "24/30". 30 rows won't fit (30×12 = 360 > 240 GUI). Rows only change on state change. |
| `C_HIT`, `C_KILL` | 0.5, 0.5, 0, 0 | WHITE / RED, HIDE | same `hit` shape; second slot for kills or headshots |
| `C_REVIVE`, `C_BLEED`, `C_FACE` | 0.5, 0.5 | GREEN (grow) / RED DRAIN | timed `ring_b`; killer face below |
| `CARD` | 0.5, 0.5, 0, 48 | none | `card()` with `face_c_<gave mob>`; the name becomes a pre-rendered image if it is Korean |
| `SHOP` | 0.5, 1, 0, -52 | none | panel + interaction icon + cart icon, while inside the shop radius |
| `AUG0..AUG2` | 0.5, 0.5, -100 / 0 / 100, -20 | none | new rect kind `("cardbg", 64, 64, 32)` tinted by rarity palette (COMMON/RARE/EPIC); augment icon (≤60 GUI at RES 4); pre-rendered name glyph; M key digit "1/2/3". Pick with `PlayerItemHeldEvent` (the hotbar sprite is already hidden), or use a 26.3 dialog if descriptions are long. |

### Namespace rename checklist (zombierun → gave)
- **make_hud.py:**
  - `TEX`
  - the provider `file` prefix `zombierun:font/hud/`
  - `assets/zombierun/font/*.json` and `shaders/include` output paths
  - the `#include <zombierun:hud.glsl>` and `<zombierun:hud_frag.glsl>` patch strings
  - `JAVA_OUT`
  - in `java()`: the package line and `FONT_*`
  - `PL` model and skin paths
- **Shader sources:** `#include <zombierun:hud_spec.glsl>` and the `ZOMBIERUN_*` guards in both pack_hud files.
- **Java:** the package of HudLine and HudScreen.
- **make_pack_src.py:** the namespace of the `empty` item model.