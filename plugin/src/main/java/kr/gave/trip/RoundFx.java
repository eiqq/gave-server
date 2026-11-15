package kr.gave.trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.DeathBorn;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * 라운드 종류의 날씨와 시야(env), 덧붙는 값(mods), 따로 도는 장치(fx: rounds_final.json 의 fx 칸)를 돌린다. 감독을 고치지 않고 할 수 있는 것만:
 * 테마(Theme: 어둡게, 비, 폭풍, 번개, 물), 피해 배율 훅(Mobs.scale), 감독이 낸 개체에 거는 것(빠르기, 얼굴 색, 쪼개짐, 터짐), 땅 위에 놓는 것
 * (쏠 수 있는 알, 미끼 상자, 가짜 등불, 불씨, 고리). 놓은 것은 {@link #stop()} 이 모두 치운다(라운드 끝, 판 끝, 정리). 숫자는 {@link Rounds.Tune}.
 */
final class RoundFx {

    final Game game;
    final Trip trip;
    final Rounds rounds;
    private final Random rnd = new Random();

    private RoundKind k;
    private int round;
    private long beginAt;
    private boolean on;
    /** 라운드 내내 거는 어둡게(0..1), 이 라운드가 정한 안개 단계(번쩍임 뒤 되돌릴 곳, -1 없음). */
    private double baseDim;
    private int baseFog = -1, preFlashFog = 0;
    private long flashUntil = -1, nextBolt = -1, nextFlicker = -1;
    // 바람
    private double windA;
    // 저주 둘
    private final List<String> curses = new ArrayList<>();
    // 쪼개짐, 터짐, 먹고 자람: 내가 만든 조각, 자란 횟수
    private final Set<Gabe> children = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Gabe, Integer> grown = new IdentityHashMap<>();
    // 번개 예고
    private final List<Strike> strikes = new ArrayList<>();
    // 사슬
    private final Map<UUID, Hook> hooks = new HashMap<>();
    private long nextHook = -1;
    // 알, 미끼, 가짜 등불, 끄는 상자, 불씨, 고리
    private final List<Egg> eggs = new ArrayList<>();
    private int eggsBroken, eggsHatched;
    private final List<Bait> baits = new ArrayList<>();
    private final List<Lamp> lamps = new ArrayList<>();
    private final List<Block> lit = new ArrayList<>();
    private Haul haul;
    private final List<double[]> embers = new ArrayList<>();
    private long nextEmber = -1;
    private ItemDisplay ringDisp;
    private double ringR = -1;
    // 소리 쪽(메아리), 쏜 자리(침묵)
    private long nextEcho = -1;
    private final Map<UUID, Integer> shots = new HashMap<>();
    private double noiseA = Double.NaN;
    private long noiseAt = -1;
    // 배움
    private final Map<String, Double> learnHits = new LinkedHashMap<>();
    private String learned;
    private long learnAt;
    // 정예 둘
    private boolean pairLinked;
    // 총 든 것, 보급 비
    private long nextArmed = -1;
    private int crateDrops;
    private int ambushes, struck, hooked;
    private final Map<String, Integer> notes = new LinkedHashMap<>();

    RoundFx(Game game, Trip trip, Rounds rounds) {
        this.game = game;
        this.trip = trip;
        this.rounds = rounds;
    }

    private Theme theme() {
        return trip.part(Theme.class);
    }

    boolean has(String fx) {
        return on && k != null && (k.has(fx) || envFx(fx));
    }

    /** env 축이 켜는 장치(바람, 깜빡임, 비, 폭풍, 재). */
    private boolean envFx(String fx) {
        return switch (fx) {
            case "wind" -> k.env.equals("wind");
            case "flicker" -> k.env.equals("flicker");
            case "rain" -> k.env.equals("rain") || k.env.equals("storm");
            case "storm" -> k.env.equals("storm");
            case "ash" -> k.env.equals("ash");
            case "dark" -> k.env.equals("dark") || k.flag("dark");
            default -> false;
        };
    }

    // ------------------------------------------------------------------ 열기, 닫기

    /** 라운드 시작(감독이 웨이브를 연 뒤). fog = 이 라운드가 정한 안개(-1 없음). */
    void begin(RoundKind kind, int n, int fog) {
        stop();
        if (kind == null) return;
        k = kind;
        round = n;
        on = true;
        beginAt = game.now();
        rnd.setSeed(trip.seed * 977 + n);
        baseFog = fog;
        baseDim = Math.max(k.num("dim", 0), has("dark") ? Rounds.Tune.DARK_DIM : 0);
        if (has("ash")) baseDim = Math.max(baseDim, Rounds.Tune.ASH_DIM);
        Theme th = theme();
        if (th != null) {
            if (has("rain")) th.rain(Boolean.TRUE);
            if (has("storm")) th.storm(true);
            if (has("rain") && Plaza.groundAct() == 2 && !has("tide") && !has("blackwater")) th.flood(Rounds.Tune.RAIN_FLOOD, 2);
            if (has("blackwater")) th.flood(Rounds.Tune.BLACKWATER_FLOOD, 3);
            if (has("tide")) th.flood(0.1, 3);
        }
        if (has("wind")) {
            windA = k.dir.equals("lean") ? rounds.dirAngle() : rnd.nextDouble() * Math.PI * 2;
            game.hud.waveFrom("rd_wind", 100, yawOf(windA));
        }
        if (has("curse2")) pickCurses();
        if (has("strike") || has("flash")) nextBolt = beginAt + 100 + rnd.nextInt(80);
        if (has("flicker")) nextFlicker = beginAt + 40;
        if (has("hook")) nextHook = beginAt + 160;
        if (has("eggs")) placeEggs();
        if (has("bait")) placeBaits();
        if (has("decoy")) placeLamps();
        if (has("haul")) startHaul();
        if (has("ember")) {
            for (int i = 0; i < Rounds.Tune.EMBER_START; i++) addEmber();
            nextEmber = beginAt + Rounds.Tune.EMBER_EVERY;
        }
        if (has("shrink") || has("tight")) {
            ringR = has("tight") ? Rounds.Tune.TIGHT_R : Plaza.R_ROAM;
            drawShrink();
        }
        if (has("echo")) nextEcho = beginAt + 40;
        if (has("faces")) spawnFaces();
        if (has("armed")) nextArmed = beginAt + 60;
        if (has("blessing")) spawnBlessing();
        if (has("learn")) {
            learnAt = beginAt;
            game.mobs.scale("trip_learn", (g, d) -> learnScale(g, d));
        }
        if (has("pair")) game.mobs.scale("trip_pair", (g, d) -> pairLinked && rounds.goals().isTarget(g) ? Rounds.Tune.PAIR_DMG : 1);
        if (has("wind")) game.mobs.scale("trip_wind", (g, d) -> windScale(g, d));
        if (has("shoplock")) for (Player p : game.players.inPlay()) if (game.shop.view(p) != null) game.shop.close(p);
        if (has("shoplock")) game.hud.notice("rd_shop_locked", 60);
        if (has("scarce")) {
            // 총알 아껴라: 예비탄 반(장전된 탄창은 그대로)
            for (Player p : game.players.standing()) {
                Guns.Arms a = game.guns.arms(p);
                if (a == null) continue;
                for (int i = 0; i < a.res.length; i++) if (a.res[i] > 0) a.res[i] = a.res[i] / 2;
                note("scarce");
            }
        }
        applyDim();
    }

    /** 놓은 것과 건 것을 모두 되돌림. */
    void stop() {
        if (k != null) {
            Theme th = theme();
            if (th != null) {
                if (has("rain")) th.rain(null);
                if (has("storm")) th.storm(false);
                if ((has("rain") || has("tide") || has("blackwater")) && Plaza.groundAct() == 2) th.flood(0, 3);
                th.dim(0, 1);
            }
        }
        game.mobs.unscale("trip_learn");
        game.mobs.unscale("trip_pair");
        game.mobs.unscale("trip_wind");
        for (Strike s : strikes) remove(s.mark);
        strikes.clear();
        hooks.clear();
        for (Egg e : eggs) e.gone = true;
        for (Egg e : eggs) {
            remove(e.d);
            game.mobs.removeShootable(e);
        }
        eggs.clear();
        for (Bait b : baits) remove(b.d);
        baits.clear();
        for (Lamp l : lamps) remove(l.d);
        lamps.clear();
        for (Block b : lit) if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
        lit.clear();
        if (haul != null) remove(haul.crate);
        haul = null;
        embers.clear();
        remove(ringDisp);
        ringDisp = null;
        ringR = -1;
        children.clear();
        grown.clear();
        curses.clear();
        learnHits.clear();
        learned = null;
        shots.clear();
        noiseA = Double.NaN;
        pairLinked = false;
        flashUntil = nextBolt = nextFlicker = nextHook = nextEmber = nextEcho = nextArmed = -1;
        eggsBroken = eggsHatched = crateDrops = ambushes = struck = hooked = 0;
        notes.clear();
        baseDim = 0;
        baseFog = -1;
        on = false;
        k = null;
    }

    private static void remove(Display d) {
        if (d != null && d.isValid()) d.remove();
    }

    // ------------------------------------------------------------------ 틱

    void tick() {
        if (!on || game.state != kr.gave.game.Game.State.WAVE) return;
        long now = game.now(), t = now - beginAt;
        if (now % 20 == 0) applyDim();
        if (has("flicker") && now >= nextFlicker) {
            nextFlicker = now + Rounds.Tune.FLICKER_EVERY;
            Theme th = theme();
            if (th != null) th.dim(Math.max(baseDim, Rounds.Tune.FLICKER_DIM), Rounds.Tune.FLICKER_OFF);
            Props.soundAround(Truck.center().toLocation(game.world).add(0, 2.5, 0), "trip.flicker", Sound.BLOCK_COPPER_BULB_TURN_OFF, 1f, 0.1f, 0.8f, 32, -1);
        }
        if (has("ash") && now % 10 == 0) for (Player p : game.players.inPlay()) p.spawnParticle(Particle.ASH, p.getLocation().add(0, 2, 0), 40, 6, 3, 6, 0);
        boltTick(now);
        if (flashUntil >= 0 && now >= flashUntil) {
            flashUntil = -1;
            game.director.bossFog(baseFog >= 0 ? baseFog : preFlashFog);
            applyDim();
        }
        if (has("wind")) windTick(now);
        if (has("mire") && now % 10 == 0) for (Player p : game.players.standing()) game.players.slow(p, Rounds.Tune.MIRE_SLOW, 14, "trip_mire");
        if (has("tide") || has("blackwater")) waterTick(now, t);
        if (has("latent") && now % 5 == 0) latentTick();
        if (has("hook")) hookTick(now);
        if (has("eggs")) eggTick(now);
        if (has("bait")) baitTick(now, t);
        if (has("decoy")) lampTick(now);
        if (has("haul")) haulTick(now);
        if (has("ember")) emberTick(now);
        if (has("shrink") || has("tight")) shrinkTick(now, t);
        if (has("regen") && now % 10 == 0) regenTick(now);
        if (has("kamikaze") && now % 2 == 0) kamikazeTick();
        if (has("lunge")) lungeTick(now);
        if (has("echo")) echoTick(now);
        if (has("noise")) noiseTick(now);
        if (has("gaze")) gazeTick();
        if (has("feed") && now % 40 == 0) feedTick();
        if (has("pair") && now % 10 == 0) pairTick(now);
        if (has("armed") && now >= nextArmed) {
            nextArmed = now + Rounds.Tune.ARMED_EVERY;
            spawnArmed();
        }
        if (has("crates3") && crateDrops < Rounds.Tune.CRATE_AT.length && t >= Rounds.Tune.CRATE_AT[crateDrops]) {
            crateDrops++;
            game.crates.drop(null);
        }
        if (has("shoplock") && now % 5 == 0) for (Player p : game.players.inPlay()) if (game.shop.view(p) != null) game.shop.close(p);
    }

    private void applyDim() {
        Theme th = theme();
        if (th == null) return;
        double d = baseDim;
        if (curses.contains("dark")) d = Math.max(d, Rounds.Tune.DARK_DIM);
        if (flashUntil >= 0) d = 0;
        if (d > 0) th.dim(d, 40);
    }

    // ------------------------------------------------------------------ 감독이 낸 개체

    /** 감독이 막 낸 개체(첫 틱). */
    void onSpawn(Gabe g) {
        if (!on) return;
        if (has("gold") && g.body != null) g.body.look.palette |= Look.PAL_GOLD;
        if (has("shadow") && g.body != null) g.body.look.palette |= Look.PAL_BLANK;
        if (has("pale") && g.body != null) g.body.look.palette |= Look.PAL_PALE;
        if (has("riders") && g.sp.is(Role.SWARM) && Species.G54.implemented() && game.mobs.budgetFor(Species.G54)) {
            Gabe r = game.mobs.spawn(Species.G54, g.pos.clone().add(new Vector(0.8, 0, 0.8)), false);
            if (r != null) {
                r.maxHp *= game.director.hpMul(Species.G54);
                r.hp = r.maxHp;
                children.add(r);
                note("riders");
            }
        }
    }

    /** 이 라운드에 추적하던 개체가 죽음(사람에게 잡혔으면 byPlayer). */
    void onDeath(Gabe g, boolean byPlayer) {
        if (!on || !byPlayer || g.sp.is(Role.BOSS)) return;
        if (has("split") && !children.contains(g) && !g.sp.is(Role.SWARM)) {
            for (int i = 0; i < 2; i++) {
                if (!game.mobs.budgetFor(game.mobs.costOf(g.sp))) break;
                Vector at = g.pos.clone().add(new Vector((i == 0 ? 0.8 : -0.8), 0, (i == 0 ? -0.5 : 0.5)));
                Gabe c = game.mobs.spawn(g.sp, at, true);
                if (c == null) break;
                c.maxHp = Math.max(1, g.maxHp * Rounds.Tune.SPLIT_HP);
                c.hp = c.maxHp;
                c.reward = 0;
                c.noReward = true;
                children.add(c);
                note("split");
            }
        }
        if (has("burst")) {
            Location at = g.loc();
            game.shotFx.burst(at);
            for (Player p : game.players.standing()) {
                if (p.getLocation().add(0, 0.9, 0).distanceSquared(at) <= Rounds.Tune.BURST_R * Rounds.Tune.BURST_R) game.players.hurt(p, Rounds.Tune.BURST_DMG, g, "cause_mut_burst");
            }
            note("burst");
        }
    }

    // ------------------------------------------------------------------ 번개(폭풍: 떨어짐, 번개만: 비춤)

    private record Strike(Vector at, long when, ItemDisplay mark) {
    }

    private void boltTick(long now) {
        for (Iterator<Strike> it = strikes.iterator(); it.hasNext();) {
            Strike s = it.next();
            if (now < s.when) continue;
            it.remove();
            remove(s.mark);
            Location l = new Location(game.world, s.at.getX(), Plaza.FLOOR_Y, s.at.getZ());
            game.world.strikeLightningEffect(l);
            for (Object o : game.mobs.inRadius(l.clone().add(0, 1, 0), Rounds.Tune.STRIKE_R)) {
                if (!(o instanceof Gabe g) || g.dead || g.sp.is(Role.BOSS)) continue;
                g.damage(new Damage(g.maxHp * Rounds.Tune.STRIKE_MOB + 20, DamageType.OTHER, null, g.pos.clone(), null, "trip_strike", 0));
            }
            for (Player p : game.players.standing()) {
                if (p.getLocation().distanceSquared(l) <= Rounds.Tune.STRIKE_R * Rounds.Tune.STRIKE_R) game.players.hurt(p, Rounds.Tune.STRIKE_DMG * game.director.dmgCmd(), null, "cause_trip_strike");
            }
            struck++;
            flash(now);
        }
        if (nextBolt < 0 || now < nextBolt) return;
        nextBolt = now + Rounds.Tune.BOLT_MIN + rnd.nextInt(Math.max(1, Rounds.Tune.BOLT_MAX - Rounds.Tune.BOLT_MIN));
        if (has("strike")) {
            List<Player> ps = game.players.standing();
            Vector at;
            if (!ps.isEmpty() && rnd.nextDouble() < 0.6) {
                Location pl = ps.get(rnd.nextInt(ps.size())).getLocation();
                double a = rnd.nextDouble() * Math.PI * 2, r = 4 + rnd.nextDouble() * 8;
                at = new Vector(pl.getX() + Math.cos(a) * r, Plaza.FLOOR_Y, pl.getZ() + Math.sin(a) * r);
            } else {
                double a = rnd.nextDouble() * Math.PI * 2, r = 10 + rnd.nextDouble() * 30;
                at = new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r);
            }
            strikes.add(new Strike(at, now + Rounds.Tune.STRIKE_WARN, warnRing(at, Rounds.Tune.STRIKE_R)));
        } else {
            Theme th = theme();
            if (th != null) th.lightning();
            flash(now);
        }
    }

    /** 번쩍임: FLASH 틱 동안 맑음, 어둠 걷힘. */
    private void flash(long now) {
        if (flashUntil < 0) preFlashFog = game.director.fog();
        flashUntil = now + Rounds.Tune.FLASH;
        game.director.bossFog(1);
        Theme th = theme();
        if (th != null) th.dim(0, 1);
    }

    /** 바닥 예고 원(쓰러짐 고리 셰이더 빨강 가득). */
    private ItemDisplay warnRing(Vector at, double r) {
        ItemStack it = game.shotFx.downRing(1, false);
        float s = (float) (r / Rounds.Tune.RING_BASE_R);
        Location l = new Location(game.world, at.getX(), Plaza.FLOOR_Y + 0.06, at.getZ());
        return game.world.spawn(l, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformationMatrix(new Matrix4f().scale(s));
            d.setDisplayWidth((float) (r * 2.2));
            d.setDisplayHeight(1f);
            d.setViewRange(2.0f);
        });
    }

    // ------------------------------------------------------------------ 바람

    private void windTick(long now) {
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp.is(Role.BOSS)) continue;
            if (upwind(g.pos)) g.speedBuff *= Rounds.Tune.WIND_SPEED;
        }
        if (now % 6 == 0) {
            Vector w = new Vector(-Math.cos(windA), 0, -Math.sin(windA)).multiply(0.6);
            for (Player p : game.players.inPlay()) {
                Location l = p.getLocation().add(Math.cos(windA) * 10, 1.5, Math.sin(windA) * 10);
                p.spawnParticle(Particle.CLOUD, l, 0, w.getX(), 0, w.getZ(), 1);
                p.spawnParticle(Particle.WHITE_ASH, l, 20, 6, 2, 6, 0);
            }
        }
    }

    /** 바람이 불어오는 쪽(가운데에서 본 각이 windA 60도 안)인가. */
    private boolean upwind(Vector v) {
        double a = Math.atan2(v.getZ() - Plaza.CZ - 0.5, v.getX() - Plaza.CX - 0.5);
        return Math.abs(Math.atan2(Math.sin(a - windA), Math.cos(a - windA))) < Math.toRadians(60);
    }

    private double windScale(Gabe g, Damage d) {
        if (!on || d.by() == null || !upwind(g.pos)) return 1;
        Location l = d.by().getLocation();
        double dx = g.pos.getX() - l.getX(), dz = g.pos.getZ() - l.getZ();
        return dx * dx + dz * dz > Rounds.Tune.WIND_NEAR * Rounds.Tune.WIND_NEAR ? Rounds.Tune.WIND_DMG : 1;
    }

    // ------------------------------------------------------------------ 물(차오르는 물, 검은 물)

    private void waterTick(long now, long t) {
        Theme th = theme();
        if (has("tide") && th != null && now % 100 == 0) {
            double dur = Math.max(20, k.goalValue) * 20;
            th.flood(Math.min(Rounds.Tune.TIDE_MAX, 0.1 + (Rounds.Tune.TIDE_MAX - 0.1) * t / dur), 3);
        }
        if (now % 10 != 0) return;
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            if (!Plaza.wet(l.getBlockX(), l.getBlockZ())) continue;
            game.players.slow(p, has("blackwater") ? Rounds.Tune.BLACKWATER_SLOW : Rounds.Tune.TIDE_SLOW, 14, "trip_water");
            if (has("blackwater") && now % 20 == 0) game.players.hurt(p, Rounds.Tune.BLACKWATER_DMG * game.director.dmgCmd(), null, "cause_trip_water");
        }
    }

    // ------------------------------------------------------------------ 붙은 것

    private void latentTick() {
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s.latent == null) continue;
            for (Gabe g : game.mobs.near(p.getLocation().toVector(), Rounds.Tune.LATENT_R, x -> !x.dead && !x.sp.is(Role.BOSS))) g.speedBuff *= Rounds.Tune.LATENT_SPEED;
        }
    }

    // ------------------------------------------------------------------ 사슬 하늘

    private static final class Hook {
        final Gabe g;
        final long until;
        final double hp;

        Hook(Gabe g, long until) {
            this.g = g;
            this.until = until;
            this.hp = g.hp;
        }
    }

    private void hookTick(long now) {
        if (now >= nextHook) {
            nextHook = now + Rounds.Tune.HOOK_EVERY;
            List<Player> ps = new ArrayList<>(game.players.standing());
            Collections.shuffle(ps, rnd);
            for (Player p : ps) {
                if (hooks.containsKey(p.getUniqueId())) continue;
                Gabe best = null;
                double bd = Rounds.Tune.HOOK_R * Rounds.Tune.HOOK_R;
                for (Gabe g : game.mobs.all()) {
                    if (g.dead || !g.flying || g.sp.is(Role.BOSS)) continue;
                    double d = g.pos.distanceSquared(p.getLocation().toVector());
                    if (d < bd) {
                        bd = d;
                        best = g;
                    }
                }
                if (best == null) continue;
                hooks.put(p.getUniqueId(), new Hook(best, now + Rounds.Tune.HOOK_TICKS));
                game.hud.notice(p, "rd_hooked", 40);
                hooked++;
                break;
            }
        }
        for (Iterator<Map.Entry<UUID, Hook>> it = hooks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Hook> e = it.next();
            Player p = game.plugin.getServer().getPlayer(e.getKey());
            Hook h = e.getValue();
            if (p == null || h.g.dead || h.g.removed || h.g.hp < h.hp - 0.5 || now >= h.until || game.players.get(p).life != Life.ALIVE) {
                it.remove();
                continue;
            }
            Location l = p.getLocation().add(0, 1.0, 0);
            Vector pull = h.g.pos.clone().subtract(l.toVector());
            double len = pull.length();
            if (len > 1.5) p.setVelocity(pull.multiply(Rounds.Tune.HOOK_PULL / len).add(new Vector(0, 0.08, 0)));
            if (now % 8 == 0) game.shotFx.beam(l, h.g.loc());
        }
    }

    // ------------------------------------------------------------------ 알 무더기(쏠 수 있는 알)

    private final class Egg implements Mobs.Shootable {
        final Vector at;
        final long hatchAt;
        double hp;
        boolean gone;
        ItemDisplay d;

        Egg(Vector at, long hatchAt, double hp) {
            this.at = at;
            this.hatchAt = hatchAt;
            this.hp = hp;
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            Vector c = center(), oc = c.clone().subtract(o);
            double tca = oc.dot(dir);
            if (tca < 0) return Double.POSITIVE_INFINITY;
            double d2 = oc.lengthSquared() - tca * tca, r = Rounds.Tune.EGG_R;
            if (d2 > r * r) return Double.POSITIVE_INFINITY;
            double t = tca - Math.sqrt(r * r - d2);
            return t <= max ? Math.max(0, t) : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage dmg) {
            if (gone) return false;
            hp -= dmg.amount();
            if (hp <= 0) {
                gone = true;
                eggsBroken++;
                remove(d);
                game.shotFx.pop(center().toLocation(game.world));
                Props.soundAround(center().toLocation(game.world), "trip.egg", Sound.ENTITY_TURTLE_EGG_BREAK, 1f, 0.15f, 0.7f, 32, -1);
            }
            return false;
        }

        @Override
        public Vector center() {
            return at.clone().setY(Plaza.FLOOR_Y + 0.7);
        }

        @Override
        public boolean gone() {
            return gone;
        }
    }

    private void placeEggs() {
        int n = Rounds.Tune.EGGS;
        double hp = Rounds.Tune.EGG_HP * Math.sqrt(game.director.hpMul());
        double base = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double a = base + i * Math.PI * 2 / n + (rnd.nextDouble() - 0.5) * 0.4, r = 14 + rnd.nextDouble() * 24;
            Vector v = new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r);
            Egg e = new Egg(v, beginAt + Rounds.Tune.EGG_HATCH + rnd.nextInt(Rounds.Tune.EGG_SPREAD), hp);
            LookState ls = new LookState();
            ItemStack it = Body.item("gabe/g42_sac", "solid", ls, game.worldTime());
            Location l = new Location(game.world, v.getX(), Plaza.FLOOR_Y + 0.7, v.getZ());
            e.d = game.world.spawn(l, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(it);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(13, 13));
                d.setTransformationMatrix(new Matrix4f().scale(Rounds.Tune.EGG_SCALE));
                d.setViewRange(1.6f);
            });
            eggs.add(e);
            game.mobs.addShootable(e);
            game.hud.point(null, v.clone().setY(Plaza.FLOOR_Y + 1), 60);
        }
        rounds.goals().count(0, n);
    }

    private void eggTick(long now) {
        int left = 0;
        for (Egg e : eggs) {
            if (e.gone) continue;
            if (now >= e.hatchAt) {
                e.gone = true;
                eggsHatched++;
                remove(e.d);
                game.mobs.removeShootable(e);
                game.hud.notice("rd_hatch", 40);
                Species sw = rounds.cheapSwarm();
                for (int i = 0; i < Rounds.Tune.EGG_BROOD && sw != null; i++) rounds.spawnAt(sw, e.at.clone().add(new Vector(rnd.nextDouble() * 2 - 1, 0, rnd.nextDouble() * 2 - 1)));
                continue;
            }
            left++;
            if (now % 40 == 0 && e.hatchAt - now < 200) game.hud.point(null, e.center(), 30);
        }
        Goals gl = rounds.goals();
        gl.count(eggsBroken, eggs.size());
        if (left == 0 && !eggs.isEmpty()) {
            if (eggsBroken > 0) gl.succeed();
            else gl.fail();
        }
    }

    // ------------------------------------------------------------------ 미끼(줍지 마라)

    private static final class Bait {
        final Vector at;
        ItemDisplay d;
        boolean gone;

        Bait(Vector at) {
            this.at = at;
        }
    }

    private void placeBaits() {
        for (int i = 0; i < Rounds.Tune.BAITS; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 14 + rnd.nextDouble() * 22;
            Bait b = new Bait(new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r));
            b.d = rounds.goals().spawnItem(new Location(game.world, b.at.getX(), Plaza.FLOOR_Y + 0.62, b.at.getZ()), "ammo_box", 1.3f);
            baits.add(b);
        }
    }

    private void baitTick(long now, long t) {
        if (t == Rounds.Tune.BAIT_REAL_AT) game.crates.drop(null);
        for (Bait b : baits) {
            if (b.gone) continue;
            if (b.d != null && b.d.isValid() && now % 2 == 0) {
                Location l = new Location(game.world, b.at.getX(), Plaza.FLOOR_Y + 0.62 + 0.06 * Math.sin(now * 0.12), b.at.getZ());
                l.setYaw((now * 6) % 360);
                b.d.teleport(l);
            }
            for (Player p : game.players.standing()) {
                Location l = p.getLocation();
                double dx = l.getX() - b.at.getX(), dz = l.getZ() - b.at.getZ();
                if (dx * dx + dz * dz <= Rounds.Tune.BAIT_R * Rounds.Tune.BAIT_R) {
                    springBait(b, p);
                    break;
                }
            }
        }
    }

    private void springBait(Bait b, Player p) {
        b.gone = true;
        remove(b.d);
        game.players.hurt(p, Rounds.Tune.BAIT_DMG * game.director.dmgCmd(), null, "cause_trip_bait");
        ambush(p.getLocation().toVector(), Rounds.Tune.AMBUSH_N);
        Props.soundAround(p.getLocation(), "trip.bait", Sound.ENTITY_EVOKER_FANGS_ATTACK, 1f, 0.1f, 0.8f, 32, -1);
    }

    /** 좌클릭(돕기)으로 미끼를 집으면 덮침. */
    boolean help(Player p) {
        if (!has("bait")) return false;
        Location l = p.getLocation();
        for (Bait b : baits) {
            if (b.gone) continue;
            double dx = l.getX() - b.at.getX(), dz = l.getZ() - b.at.getZ();
            if (dx * dx + dz * dz <= 9) {
                springBait(b, p);
                return true;
            }
        }
        return false;
    }

    /** at 둘레 4~5블록에 돌격 종 n. */
    private void ambush(Vector at, int n) {
        Species sp = rounds.pick(s -> s.is(Role.CHARGE) && s.spawn == Species.Spawn.RING);
        if (sp == null) sp = rounds.cheapSwarm();
        for (int i = 0; i < n && sp != null; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 4 + rnd.nextDouble();
            rounds.spawnAt(sp, at.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r)));
        }
        ambushes++;
    }

    // ------------------------------------------------------------------ 가짜 등불

    private static final class Lamp {
        final Vector at;
        ItemDisplay d;
        boolean gone;

        Lamp(Vector at) {
            this.at = at;
        }
    }

    private void placeLamps() {
        for (int i = 0; i < Rounds.Tune.LAMPS; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 18 + rnd.nextDouble() * 14;
            Lamp l = new Lamp(new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r));
            Location at = new Location(game.world, l.at.getX(), Plaza.FLOOR_Y + 2.4, l.at.getZ());
            ItemStack it = ItemStack.of(Material.LANTERN);
            l.d = game.world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(it);
                d.setBillboard(Display.Billboard.FIXED);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformationMatrix(new Matrix4f().scale(0.9f));
                d.setViewRange(2.5f);
            });
            Block b = game.world.getBlockAt(at.getBlockX(), Plaza.FLOOR_Y + 2, at.getBlockZ());
            if (b.getType() == Material.AIR) {
                b.setType(Material.LIGHT, false);
                lit.add(b);
            }
            lamps.add(l);
        }
    }

    private void lampTick(long now) {
        if (now % 5 != 0) return;
        for (Lamp lp : lamps) {
            if (lp.gone) continue;
            for (Player p : game.players.standing()) {
                Location l = p.getLocation();
                double dx = l.getX() - lp.at.getX(), dz = l.getZ() - lp.at.getZ();
                if (dx * dx + dz * dz > Rounds.Tune.LAMP_R * Rounds.Tune.LAMP_R) continue;
                lp.gone = true;
                remove(lp.d);
                Block b = game.world.getBlockAt(lp.at.getBlockX(), Plaza.FLOOR_Y + 2, lp.at.getBlockZ());
                if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
                game.players.hurt(p, Rounds.Tune.LAMP_DMG * game.director.dmgCmd(), null, "cause_trip_lamp");
                ambush(l.toVector(), Rounds.Tune.AMBUSH_N + 1);
                Props.soundAround(l, "trip.lamp", Sound.ENTITY_WITCH_CELEBRATE, 1f, 0.1f, 0.6f, 40, -1);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ 묶인 보급(끄는 넷)

    private static final class Haul {
        Vector at;
        final double ang;
        ItemDisplay crate;
        final List<Gabe> crew = new ArrayList<>();
        boolean lost;

        Haul(Vector at, double ang) {
            this.at = at;
            this.ang = ang;
        }
    }

    private void startHaul() {
        double a = rnd.nextDouble() * Math.PI * 2, r = Rounds.Tune.HAUL_START;
        haul = new Haul(new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r), a);
        haul.crate = rounds.goals().spawnItem(new Location(game.world, haul.at.getX(), Plaza.FLOOR_Y + 0.7, haul.at.getZ()), "ammo_box", 1.7f);
        Species sp = rounds.pick(s -> (s.is(Role.TANK) || s.is(Role.ELITE) || kr.gave.game.Director.EATERS.contains(s)) && s.spawn == Species.Spawn.RING);
        if (sp == null) sp = rounds.pick(s -> s.spawn == Species.Spawn.RING && !s.is(Role.SWARM));
        for (int i = 0; i < Rounds.Tune.HAULERS && sp != null; i++) {
            double o = i * Math.PI / 2;
            Gabe g = rounds.spawnAt(sp, haul.at.clone().add(new Vector(Math.cos(o) * 2, 0, Math.sin(o) * 2)));
            if (g != null) {
                haul.crew.add(g);
                rounds.goals().target(g, true);
            }
        }
        game.hud.point(null, haul.at.clone().setY(Plaza.FLOOR_Y + 1), 100);
    }

    private void haulTick(long now) {
        if (haul == null || haul.lost) return;
        int alive = 0;
        for (Gabe g : haul.crew) if (!g.dead && !g.removed) alive++;
        Goals gl = rounds.goals();
        gl.count(haul.crew.size() - alive, haul.crew.size());
        if (alive == 0) {
            remove(haul.crate);
            gl.succeed();
            haul.lost = true;
            return;
        }
        Vector out = new Vector(Math.cos(haul.ang), 0, Math.sin(haul.ang));
        haul.at.add(out.clone().multiply(Rounds.Tune.HAUL_SPEED * alive / haul.crew.size()));
        if (haul.crate != null && haul.crate.isValid() && now % 2 == 0) haul.crate.teleport(new Location(game.world, haul.at.getX(), Plaza.FLOOR_Y + 0.7, haul.at.getZ()));
        if (now % 10 == 0) {
            int i = 0;
            for (Gabe g : haul.crew) {
                if (g.dead || g.removed) continue;
                double o = haul.ang + Math.PI + (i++ - 1.5) * 0.5;
                game.mobs.lure(g, haul.at.clone().add(new Vector(Math.cos(o) * 1.6, 0, Math.sin(o) * 1.6)).setY(g.pos.getY()), 14);
            }
        }
        if (now % 60 == 0) game.hud.point(null, haul.at.clone().setY(Plaza.FLOOR_Y + 1), 40);
        if (Plaza.radius(haul.at.getX(), haul.at.getZ()) >= Rounds.Tune.HAUL_ESCAPE) {
            haul.lost = true;
            remove(haul.crate);
            game.hud.notice("rd_lost_cargo", 60);
            gl.fail();
        }
    }

    // ------------------------------------------------------------------ 불씨 땅

    private void addEmber() {
        if (embers.size() >= Rounds.Tune.EMBER_MAX) {
            for (double[] e : embers) e[2] = Math.min(Rounds.Tune.EMBER_RMAX, e[2] + 0.5);
            return;
        }
        double a = rnd.nextDouble() * Math.PI * 2, r = 13 + rnd.nextDouble() * 28;
        double[] e = {Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.CZ + 0.5 + Math.sin(a) * r, Rounds.Tune.EMBER_R0};
        embers.add(e);
        stampEmber(e);
    }

    private void stampEmber(double[] e) {
        Location l = new Location(game.world, e[0], Plaza.FLOOR_Y + 0.02, e[1]);
        game.shotFx.decal(l, new Vector(0, 1, 0), ShotFx.DECAL_SCORCH, (float) (e[2] * 2), Rounds.Tune.EMBER_EVERY + 60);
    }

    private void emberTick(long now) {
        if (now >= nextEmber) {
            nextEmber = now + Rounds.Tune.EMBER_EVERY;
            addEmber();
            for (double[] e : embers) stampEmber(e);
        }
        if (now % 8 == 0 && !embers.isEmpty()) {
            double[] e = embers.get(rnd.nextInt(embers.size()));
            double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * e[2];
            game.shotFx.flame(new Location(game.world, e[0] + Math.cos(a) * r, Plaza.FLOOR_Y + 0.3, e[1] + Math.sin(a) * r), 0.9f, 24, ShotFx.EXTRA);
        }
        if (now % 20 != 0) return;
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            for (double[] e : embers) {
                double dx = l.getX() - e[0], dz = l.getZ() - e[1];
                if (dx * dx + dz * dz <= e[2] * e[2]) {
                    game.players.hurt(p, Rounds.Tune.EMBER_DMG * game.director.dmgCmd(), null, "cause_trip_ember");
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 조여 오는 고리

    private void drawShrink() {
        ItemStack it = game.shotFx.downRing(1, false);
        Location at = new Location(game.world, Plaza.CX + 0.5, Plaza.FLOOR_Y + 0.07, Plaza.CZ + 0.5);
        float s = (float) (ringR / Rounds.Tune.RING_BASE_R);
        ringDisp = game.world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformationMatrix(new Matrix4f().scale(s));
            d.setDisplayWidth((float) (Plaza.R_ROAM * 2.4));
            d.setDisplayHeight(2f);
            d.setViewRange(4f);
            d.setInterpolationDuration(10);
        });
    }

    private void shrinkTick(long now, long t) {
        double dur = Math.max(20, k.goalValue) * 20;
        ringR = has("tight") ? Rounds.Tune.TIGHT_R : Plaza.R_ROAM - (Plaza.R_ROAM - k.num("roamShrink", Rounds.Tune.SHRINK_MIN)) * Math.min(1, t / (dur * 0.8));
        if (now % 10 == 0 && ringDisp != null && ringDisp.isValid()) {
            ringDisp.setInterpolationDelay(0);
            ringDisp.setInterpolationDuration(10);
            ringDisp.setTransformationMatrix(new Matrix4f().scale((float) (ringR / Rounds.Tune.RING_BASE_R)));
        }
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            double r = Plaza.radius(l.getX(), l.getZ());
            if (r <= ringR) continue;
            Vector in = new Vector(Plaza.CX + 0.5 - l.getX(), 0, Plaza.CZ + 0.5 - l.getZ()).normalize().multiply(Rounds.Tune.SHRINK_PUSH);
            p.setVelocity(p.getVelocity().setX(in.getX()).setZ(in.getZ()));
            if (now % 20 == 0) game.players.hurt(p, Rounds.Tune.SHRINK_DMG * game.director.dmgCmd(), null, "cause_trip_ring");
        }
    }

    // ------------------------------------------------------------------ 질긴 것, 자폭, 튀어 나감

    /** 질긴 것들: 한동안 안 맞은 개체가 다시 참. */
    private void regenTick(long now) {
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp.is(Role.BOSS) || !game.director.fromDirector(g) || g.hp >= g.maxHp) continue;
            if (g.sinceHit() >= Rounds.Tune.REGEN_AFTER) g.hp = Math.min(g.maxHp, g.hp + g.maxHp * Rounds.Tune.REGEN / 2);
        }
    }

    /** 트럭을 노린다: 트럭 노림 개체가 발자국에 닿으면 터진다. */
    private void kamikazeTick() {
        Truck t = trip.truck();
        if (t == null || !t.alive()) return;
        for (Gabe g : new ArrayList<>(game.mobs.all())) {
            if (g.dead || g.sp.is(Role.BOSS) || !t.isSeeker(g) || Truck.dist(g.pos.getX(), g.pos.getZ()) > Rounds.Tune.KAMIKAZE_R) continue;
            Location at = g.loc();
            game.shotFx.explosion(at);
            t.hitFrac(Rounds.Tune.KAMIKAZE_TRUCK, g, "cause_trip_kamikaze");
            for (Player p : game.players.standing())
                if (p.getLocation().distanceSquared(at) <= Rounds.Tune.KAMIKAZE_BLAST * Rounds.Tune.KAMIKAZE_BLAST) game.players.hurt(p, Rounds.Tune.KAMIKAZE_DMG, g, "cause_trip_kamikaze");
            g.noReward = true;
            g.reward = 0;
            g.damage(new Damage(g.hp + 1e6, DamageType.EXPLOSION, null, g.pos.clone(), null, "trip_kamikaze", 0));
            note("kamikaze");
        }
    }

    /** 빠른 것들: 개체마다 어긋난 박자로 3초마다 반 초씩 튀어 나감. */
    private void lungeTick(long now) {
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.flying || g.sp.is(Role.BOSS) || !game.director.fromDirector(g)) continue;
            if (Math.floorMod(now + g.id * 7L, Rounds.Tune.LUNGE_EVERY) < Rounds.Tune.LUNGE_TICKS) g.speedBuff *= Rounds.Tune.LUNGE_SPEED;
        }
    }

    // ------------------------------------------------------------------ 메아리, 침묵

    private void echoTick(long now) {
        if (now < nextEcho) return;
        nextEcho = now + Rounds.Tune.ECHO_MIN + rnd.nextInt(Math.max(1, Rounds.Tune.ECHO_MAX - Rounds.Tune.ECHO_MIN));
        List<Gabe> live = new ArrayList<>();
        for (Gabe g : game.mobs.all()) if (!g.dead && game.director.fromDirector(g)) live.add(g);
        for (Player p : game.players.inPlay()) {
            Location l = p.getLocation();
            double a;
            if (!live.isEmpty()) {
                Gabe g = live.get(rnd.nextInt(live.size()));
                a = Math.atan2(g.pos.getZ() - l.getZ(), g.pos.getX() - l.getX()) + Math.PI;
            } else a = rnd.nextDouble() * Math.PI * 2;
            double d = 12 + rnd.nextDouble() * 8;
            Location at = l.clone().add(Math.cos(a) * d, 1.5, Math.sin(a) * d);
            if (rnd.nextBoolean()) Props.soundTo(p, at, "director.horde", Sound.ENTITY_PHANTOM_FLAP, 0.9f, 0.15f, 0.55f, -1);
            else Props.soundTo(p, at, "director.chorus", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.8f, 0.15f, 0.5f, -1);
        }
        note("echo");
    }

    private void noiseTick(long now) {
        for (Player p : game.players.standing()) {
            Guns.Arms a = game.guns.arms(p);
            if (a == null) continue;
            Integer prev = shots.put(p.getUniqueId(), a.shots);
            if (prev != null && a.shots > prev) {
                Location l = p.getLocation();
                noiseA = Math.atan2(l.getZ() - Plaza.CZ - 0.5, l.getX() - Plaza.CX - 0.5);
                if (Plaza.radius(l.getX(), l.getZ()) < 4) noiseA = Math.toRadians(-l.getYaw() - 90) + Math.PI;
                noiseAt = now;
            }
        }
    }

    /** 침묵 라운드에서 다음 무리가 나올 각(최근 5초 안에 쏜 사람 쪽, 없으면 NaN). */
    double noiseAngle() {
        return noiseAt >= 0 && game.now() - noiseAt <= 100 ? noiseA : Double.NaN;
    }

    // ------------------------------------------------------------------ 보는 동안

    private void gazeTick() {
        List<Player> ps = game.players.standing();
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.sp.is(Role.BOSS)) continue;
            boolean seen = false;
            for (Player p : ps) {
                Location eye = p.getEyeLocation();
                Vector to = g.pos.clone().subtract(eye.toVector());
                double d = to.length();
                if (d > Rounds.Tune.GAZE_RANGE || d < 0.1) continue;
                if (eye.getDirection().dot(to.multiply(1 / d)) >= Rounds.Tune.GAZE_COS) {
                    seen = true;
                    break;
                }
            }
            g.speedBuff *= seen ? Rounds.Tune.GAZE_FROZEN : Rounds.Tune.GAZE_RUSH;
        }
    }

    // ------------------------------------------------------------------ 시체 먹는 밤

    private void feedTick() {
        int checked = 0;
        for (kr.gave.mob.Corpse c : new ArrayList<>(game.mobs.corpses())) {
            if (++checked > 40 || c.gone()) continue;
            Gabe eater = game.mobs.nearest(c.pos, Rounds.Tune.FEED_R, g -> !g.dead && !g.sp.is(Role.BOSS) && !g.flying && grown.getOrDefault(g, 0) < Rounds.Tune.FEED_MAX);
            if (eater == null || !game.mobs.eat(c)) continue;
            int n = grown.merge(eater, 1, Integer::sum);
            eater.maxHp *= Rounds.Tune.FEED_HP;
            eater.hp = Math.min(eater.maxHp, eater.hp + eater.maxHp * (Rounds.Tune.FEED_HP - 1));
            if (eater.body != null) eater.body.scale((float) Math.pow(Rounds.Tune.FEED_SCALE, n), 10);
            note("feed");
        }
    }

    // ------------------------------------------------------------------ 정예 둘

    private void pairTick(long now) {
        List<Gabe> t = new ArrayList<>();
        for (Gabe g : rounds.goals().targets()) if (!g.dead) t.add(g);
        pairLinked = false;
        if (t.size() < 2) return;
        Gabe a = t.get(0), b = t.get(1);
        if (a.pos.distanceSquared(b.pos) <= Rounds.Tune.PAIR_R * Rounds.Tune.PAIR_R) {
            pairLinked = true;
            for (Gabe g : new Gabe[] {a, b}) g.hp = Math.min(g.maxHp, g.hp + g.maxHp * Rounds.Tune.PAIR_REGEN / 2);
            game.shotFx.beam(a.loc().add(0, 0.5, 0), b.loc().add(0, 0.5, 0));
        }
    }

    // ------------------------------------------------------------------ 배움

    private double learnScale(Gabe g, Damage d) {
        if (!on) return 1;
        String how = d.how() == null ? d.type().name() : d.how();
        learnHits.merge(how, d.amount(), Double::sum);
        long now = game.now();
        if (now - learnAt >= Rounds.Tune.LEARN_EVERY) {
            learnAt = now;
            double total = 0, best = 0;
            String top = null;
            for (Map.Entry<String, Double> e : learnHits.entrySet()) {
                total += e.getValue();
                if (e.getValue() > best) {
                    best = e.getValue();
                    top = e.getKey();
                }
            }
            String was = learned;
            learned = total > 0 && best / total >= Rounds.Tune.LEARN_SHARE ? top : null;
            if (learned != null && !learned.equals(was)) game.hud.notice("rd_learned", 60);
            for (Map.Entry<String, Double> e : learnHits.entrySet()) e.setValue(e.getValue() * 0.5);
        }
        return learned != null && learned.equals(how) ? Rounds.Tune.LEARN_MUL : 1;
    }

    // ------------------------------------------------------------------ 얼굴, 총, 축복

    private void spawnFaces() {
        if (!Species.G50.implemented()) return;
        List<Player> who = new ArrayList<>();
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            if (s.deaths > 0 && !s.noLikeness) who.add(p);
        }
        Collections.shuffle(who, rnd);
        for (int i = 0; i < Math.min(Rounds.Tune.FACES_MAX, who.size()); i++) {
            Player p = who.get(i);
            Gabe g = rounds.spawnRing(Species.G50, rnd.nextDouble() * Math.PI * 2, Plaza.R_SPAWN_IN);
            if (g instanceof DeathBorn db) {
                try {
                    db.bornFrom(p, game.players.get(p), g.loc());
                    note("faces");
                } catch (RuntimeException e) {
                    trip.log("아는 얼굴 bornFrom 오류 " + e);
                }
            }
        }
    }

    private void spawnArmed() {
        if (!Species.G52.implemented()) return;
        int want = Math.min(Rounds.Tune.ARMED_MAX, 2 + trip.people() / 5), have = 0;
        for (Gabe g : game.mobs.all()) if (!g.dead && g.sp == Species.G52) have++;
        for (int i = have; i < want; i++) {
            if (rounds.spawnRing(Species.G52, rounds.dirAngle(), Plaza.R_SPAWN_IN) != null) note("armed");
        }
    }

    private void spawnBlessing() {
        if (!Species.G49.implemented()) return;
        int n = Math.min(4, 2 + trip.people() / 8);
        for (int i = 0; i < n; i++) {
            Vector at = game.director.placeFor(Species.G49);
            if (at != null && rounds.spawnAt(Species.G49, at) != null) note("blessing");
        }
    }

    // ------------------------------------------------------------------ 겹저주

    private static final String[] CURSES = {"fast", "thick", "hurt", "fog", "dark", "thin", "truck"};

    private void pickCurses() {
        List<String> l = new ArrayList<>(List.of(CURSES));
        Collections.shuffle(l, rnd);
        curses.add(l.get(0));
        curses.add(l.get(1));
        for (String c : curses) game.hud.notice("rd_cv_" + c, 60);
        if (curses.contains("fog")) {
            baseFog = 4;
            game.director.bossFog(4);
        }
    }

    /** 이 라운드 장치가 더하는 배율(겹저주, 바람 없음). */
    double mul(String key) {
        if (!on || curses.isEmpty()) return 1;
        double m = 1;
        for (String c : curses) {
            switch (c) {
                case "fast" -> m *= key.equals("speed") ? 1.3 : 1;
                case "thick" -> m *= key.equals("hp") ? 1.4 : 1;
                case "hurt" -> m *= key.equals("dmg") ? 1.4 : 1;
                case "thin" -> m *= key.equals("price.ammo") || key.equals("price.auto_ammo") ? 2.0 : 1;
                case "truck" -> m *= key.equals("truckShare") ? 1.6 : 1;
                default -> {
                }
            }
        }
        return m;
    }

    /** 상점이 잠겼나(잠긴 트럭). */
    boolean shopLocked() {
        return has("shoplock");
    }

    private void note(String k) {
        notes.merge(k, 1, Integer::sum);
    }

    private static double yawOf(double ang) {
        // 가운데에서 본 각(라디안, 0 = +x 동) → 마크 yaw(0 = +Z 남, 90 = -X 서)
        return Math.toDegrees(Math.atan2(-Math.cos(ang), Math.sin(ang)));
    }

    String status() {
        if (!on) return null;
        StringBuilder sb = new StringBuilder();
        if (baseDim > 0) sb.append(String.format(Locale.ROOT, "dim=%.2f ", baseDim));
        if (!curses.isEmpty()) sb.append("curses=").append(String.join(",", curses)).append(' ');
        if (!eggs.isEmpty()) sb.append("eggs=").append(eggsBroken).append('/').append(eggs.size()).append("(h").append(eggsHatched).append(") ");
        if (!baits.isEmpty() || !lamps.isEmpty()) sb.append("ambush=").append(ambushes).append(' ');
        if (haul != null) sb.append(String.format(Locale.ROOT, "haul=r%.0f%s ", Plaza.radius(haul.at.getX(), haul.at.getZ()), haul.lost ? "(끝)" : ""));
        if (!embers.isEmpty()) sb.append("embers=").append(embers.size()).append(' ');
        if (ringR > 0) sb.append(String.format(Locale.ROOT, "ring=%.1f ", ringR));
        if (struck > 0) sb.append("struck=").append(struck).append(' ');
        if (hooked > 0) sb.append("hooked=").append(hooked).append(' ');
        if (learned != null) sb.append("learned=").append(learned).append(' ');
        if (pairLinked) sb.append("pair=linked ");
        if (has("wind")) sb.append(String.format(Locale.ROOT, "wind=%.0f ", Math.toDegrees(windA)));
        for (Map.Entry<String, Integer> e : notes.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        return sb.toString().trim();
    }

    /** 시험: 살아 있는 알, 미끼, 가짜 등불, 끄는 상자 가운데 첫 자리(없으면 null). */
    Vector spot() {
        for (Egg e : eggs) if (!e.gone) return e.at.clone();
        for (Lamp l : lamps) if (!l.gone) return l.at.clone();
        for (Bait b : baits) if (!b.gone) return b.at.clone();
        if (haul != null && !haul.lost) return haul.at.clone();
        if (!embers.isEmpty()) return new Vector(embers.get(0)[0], Plaza.FLOOR_Y, embers.get(0)[1]);
        return null;
    }

    /** 시험: 알 하나를 깸(총에 맞은 것처럼). */
    String breakEgg() {
        for (Egg e : eggs) {
            if (e.gone) continue;
            e.shot(new Damage(1e6, DamageType.BULLET, null, e.center(), null, "test", 0));
            return "알 " + eggsBroken + "/" + eggs.size();
        }
        return "알 없음";
    }

    int displays() {
        int n = 0;
        for (Egg e : eggs) if (!e.gone) n++;
        for (Bait b : baits) if (!b.gone) n++;
        for (Lamp l : lamps) if (!l.gone) n++;
        if (haul != null && !haul.lost) n++;
        if (ringDisp != null) n++;
        return n + strikes.size();
    }
}
