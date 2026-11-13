package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G16 내리꽂는 게이브. 하늘(바닥 위 20블록)을 독수리처럼 선회하다 신호를 낸 사람을 골라 그 머리 위에 멈추고, 3초 동안 표적 발밑 분홍 그림자 원이
 * 줄어드는 사이(표적 본인에게만 '후웁' 들이쉼) 천천히 따라오다가 다리를 우산처럼 접어 수직 낙하한다. 맞으면 머리를 덮는다: 남들 눈엔 머리에 덮인 갓,
 * 본인 화면은 살 주름과 코앞 실눈(덮개 g16_cap), 초당 1 피해, 6초면 쓰러짐(조준점 아래 게이지가 남은 시간). 빗나가면 얼굴부터 땅에 박혀 2초 떤다.
 *
 * <p>배우는 신호(웨이브가 갈수록): 처음엔 1초 넘게 가장 오래 연사한 사람, 15웨이브부터 10초 안에 핑을 낸 사람, 21웨이브부터 10초 안에 상점 화면을
 * 연 사람. 배운 신호마다 갓에 기운 가죽 조각이 하나씩(부위 patch1, patch2). 채팅은 듣지 않는다(음성 채팅을 쓰는 사람과 공정하게, 2026-11-11 결정).
 * 핑은 {@link #ping(Game, Player)} 로 받는다. 핑 아이템이 아직 없어서 지금은 신호 소모품(조명탄, 딸랑이)을 던진 것을 핑으로 친다.
 *
 * <p>전장은 지붕 없는 황야라(맵변경_황야.md) '지붕 없이'는 늘 참이다. 대처: 그림자 원이 줄면 달려 빠진다(따라오는 빠르기 0.09블록/틱 < 걷기).
 * 떠 있을 땐 받는 피해 1.5배. 덮인 동료는 돕기(좌클릭)로 떼거나 머리의 갓을 쏘고(동료 몸이 탄을 막지 않음), 본인은 돕기 연타 14번으로 스스로
 * 벗는다(덮인 동안 못 쏨, 걸을 수는 있음). 땅에 박혀 떠는 2초는 받는 피해 1.25배.
 *
 * <p>팩 계약(tools/gabe/sp/g16.py): 모델 gave:gabe/g16 상태 "", "fold", "hug", "stuck", 부위 patch1, patch2, 모델 gave:gabe/g16_shadow,
 * 덮개 g16_cap, 소리 inhale(표적 본인만), dive, plop, thud, drift, 원인 글귀 cause_g16.
 */
public final class G16 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    /** 선회 높이(바닥 위), 반지름, 빠르기(블록/틱). */
    static final double CRUISE_UP = 20, CIRCLE_R = 11, CIRCLE_V = 0.16;
    /** 표적 고르기 간격(틱), 연사로 치는 최소 틱(1초), 연사 끊김으로 치는 쏘지 않은 틱. */
    static final int PICK_EVERY = 10, FIRE_MIN = 10, FIRE_GAP = 6;
    /** 핑, 상점 신호가 살아 있는 시간(틱). */
    static final int PING_TICKS = 200, SHOP_TICKS = 200;
    /** 신호를 배우는 웨이브(핑, 상점). */
    static final int LEARN_PING = 15, LEARN_SHOP = 21;
    /** 핑으로 치는 신호 소모품(핑 아이템이 생기기 전까지). */
    static final List<String> SIGNAL_ITEMS = List.of("flare", "rattle");
    /** 머리 위로 다가가는 빠르기, 예고 중 따라오는 빠르기(걷기 0.215 보다 느림), 예고 틱(3초), 다가가기 최대 틱. */
    static final double STALK_V = 0.24, TELL_V = 0.09;
    static final int TELL = 60, STALK_MAX = 240;
    /** 낙하: 첫 빠르기, 틱마다 더함, 최대(블록/틱). */
    static final double DROP_V0 = 0.45, DROP_ACC = 0.14, DROP_VMAX = 1.7;
    /** 낙하선에서 표적 머리까지 수평 거리가 이 안이면 덮음. */
    static final double CATCH_R = 0.95;
    /** 덮는 틱(6초면 쓰러짐), 초당 피해, 본인 연타 수. */
    static final int COVER = 120, ESCAPE = 14;
    static final double COVER_DMG = 5;
    /** 땅에 박혀 떠는 틱, 다시 고르기까지 쉬는 틱. */
    static final int STUCK = 40, COOLDOWN = 100;
    /** 떠 있을 때, 박혔을 때 받는 피해 배율. */
    static final double AIR_MUL = 1.5, STUCK_MUL = 1.25;
    /** 그림자 원 크기(블록, 처음과 끝). */
    static final float SHADOW_FROM = 3.4f, SHADOW_TO = 0.7f;
    /** 몸 반지름. */
    static final double R = 0.5;

    enum Phase { RISE, CIRCLE, STALK, TELL, DROP, COVER, STUCK }

    private Phase phase = Phase.RISE;
    private int phaseAt, readyAt, nextPick, nextDrift;
    private Player prey;
    /** 덮은 사람(덮는 동안만). */
    private Player victim;
    private double dropV;
    private int circleDir = 1;
    private double circleR = CIRCLE_R;
    private ItemDisplay shadow;

    public G16(Game game, Species sp) {
        super(game, sp);
        radius = R;
        flying = true;
        baseSpeed = CIRCLE_V;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/" + sp.key(), 2.6f, 2.4f);
    }

    @Override
    protected void spawned() {
        circleDir = game.random.nextBoolean() ? 1 : -1;
        circleR = CIRCLE_R + (id % 4) * 1.6;
        nextDrift = age + game.random.nextInt(60);
        readyAt = age + 40;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private double cruiseY(double x, double z) {
        return game.mobs.flow.floor(x, z) + CRUISE_UP + (id % 3) * 0.8;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        signals(game).update(game);
        learned();
        switch (phase) {
            case RISE -> rise();
            case CIRCLE -> circling();
            case STALK -> stalk();
            case TELL -> tell();
            case DROP -> drop();
            case COVER -> cover();
            case STUCK -> stuck();
        }
    }

    /** 배운 신호 수만큼 기운 가죽 조각. */
    private void learned() {
        int st = stage();
        if (st >= 1) body.look.on.add("patch1");
        if (st >= 2) body.look.on.add("patch2");
    }

    /** 0 연사만, 1 + 핑, 2 + 상점. */
    int stage() {
        return game.wave >= LEARN_SHOP ? 2 : game.wave >= LEARN_PING ? 1 : 0;
    }

    private void rise() {
        state("");
        pitch = 0;
        double want = cruiseY(pos.getX(), pos.getZ());
        vel.setX(vel.getX() * 0.8).setZ(vel.getZ() * 0.8).setY(Math.min(0.3, Math.max(-0.3, (want - pos.getY()) * 0.15)));
        if (Math.abs(want - pos.getY()) < 0.6 || inPhase() > 160) phase(Phase.CIRCLE);
    }

    /** 가장 가까운 사람 위를 선회, 신호 낸 사람을 고름. */
    private void circling() {
        state("");
        pitch = 0;
        Player near = nearest(96);
        Vector c = near != null ? near.getLocation().toVector() : Plaza.center(game.world).toVector();
        c.setY(cruiseY(c.getX(), c.getZ()));
        circle(c, circleR, CIRCLE_V * speed() / baseSpeed, circleDir);
        faceAlong();
        if (age >= nextDrift) {
            sound("drift", Sound.ENTITY_PHANTOM_FLAP, 0.6f, 32);
            nextDrift = age + 80 + game.random.nextInt(60);
        }
        if (age >= readyAt && age >= nextPick) {
            nextPick = age + PICK_EVERY;
            Player p = pick();
            if (p == null && inPhase() >= cd(120)) p = nearest(64);
            if (p != null) {
                prey = p;
                mark(p);
                phase(Phase.STALK);
            }
        }
    }

    private void faceAlong() {
        double l = Math.hypot(vel.getX(), vel.getZ());
        if (l > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
    }

    /** 머리 위로 다가감. */
    private void stalk() {
        if (!valid(prey) || inPhase() > STALK_MAX) {
            calm();
            return;
        }
        Vector to = prey.getLocation().toVector();
        to.setY(cruiseY(to.getX(), to.getZ()) - 4);
        seek(to, STALK_V * speed() / baseSpeed);
        faceAlong();
        if (flat(prey) < 1.4 && Math.abs(pos.getY() - to.getY()) < 1.5) {
            startTell();
        }
    }

    private void startTell() {
        phase(Phase.TELL);
        // 표적 본인에게만, 본인 자리에서(머리 위 16블록에서 울리면 거리로 거의 안 들림)
        if (!game.director.silent()) Props.sound(prey, "gabe.g16.inhale", Sound.ENTITY_PLAYER_BREATH, 1f, 0.96f + game.random.nextFloat() * 0.08f, 0.6f);
        removeShadow();
        Location at = new Location(game.world, pos.getX(), game.mobs.flow.floor(pos.getX(), pos.getZ()) + 0.04, pos.getZ());
        shadow = game.shotFx.spawn(at, "fx_quad", 0, 0, ShotFx.scale(SHADOW_FROM), Display.Billboard.FIXED, false, TELL + 20, ShotFx.KEY);
        if (shadow != null) {
            shadow.setItemStack(Body.item("gabe/g16_shadow", "", new LookState(), game.worldTime()));
            shadow.setBrightness(new Display.Brightness(13, 13)); // 밤에도 분홍이 읽히게(15 로 빛나지는 않음)
            shadow.setTeleportDuration(2);
            game.shotFx.grow(shadow, ShotFx.scale(SHADOW_TO), TELL);
        }
    }

    /** 3초 예고: 머리 위에서 느리게 따라옴, 그림자 원이 줄어듦. */
    private void tell() {
        if (!valid(prey)) {
            calm();
            return;
        }
        Vector to = prey.getLocation().toVector();
        to.setY(pos.getY());
        seek(to, TELL_V * speed() / baseSpeed);
        // 우산처럼 접히기 시작(마지막 1초)
        if (inPhase() >= TELL - 20) state("fold");
        if (shadow != null && shadow.isValid() && every(2)) {
            shadow.teleport(new Location(game.world, pos.getX() + vel.getX(), game.mobs.flow.floor(pos.getX(), pos.getZ()) + 0.04,
                    pos.getZ() + vel.getZ()));
        }
        if (inPhase() >= TELL) {
            dropV = DROP_V0;
            state("fold");
            sound("dive", Sound.ENTITY_PHANTOM_SWOOP, 0.8f, 32);
            phase(Phase.DROP);
        }
    }

    /** 수직 낙하: 표적 머리에 닿으면 덮고, 바닥이면 박힘. */
    private void drop() {
        dropV = Math.min(DROP_VMAX, dropV + DROP_ACC);
        vel.setX(0).setZ(0).setY(-dropV);
        double nextY = pos.getY() - dropV;
        if (prey != null && standing(prey)) {
            Location eye = prey.getEyeLocation();
            double hx = eye.getX() - pos.getX(), hz = eye.getZ() - pos.getZ();
            if (Math.hypot(hx, hz) <= CATCH_R && nextY <= eye.getY() + 0.5 && pos.getY() >= eye.getY() - 0.6) {
                attach(prey);
                return;
            }
        }
        double fl = floor();
        if (nextY <= fl + 0.3) {
            vel.setY(fl + 0.25 - pos.getY());
            land();
        }
    }

    private void land() {
        removeShadow();
        sound("thud", Sound.ENTITY_SLIME_ATTACK, 0.6f, 16);
        mark(null);
        prey = null;
        state("stuck");
        pitch = 180;
        body.pose(2);
        phase(Phase.STUCK);
    }

    private void attach(Player p) {
        removeShadow();
        PState s = game.players.get(p);
        // 그사이 다른 것이 머리, 등에 붙었거나 붙잡기가 막히면(이미 붙잡힘, 면역, 비옷) 빗나간 것으로
        if (s.headBy != null || !grab(p, ESCAPE)) {
            land();
            return;
        }
        s.grabPin = false; // 눈은 가렸지만 걸을 수는 있음
        s.grabDetach = true;
        victim = p;
        mark(p);
        noPush = true;
        noBite = true; // 머리에 붙은 동안 물어뜯기로 멈추면 갓이 허공에 남는다
        game.players.cover(p, "g16_cap", COVER + 10);
        body.hide(p, true);
        state("hug");
        pitch = 0;
        sound("plop", Sound.ENTITY_SLIME_ATTACK, 0.7f, 16);
        game.hud.meter(p, sp.key(), 1, 6);
        hold();
        phase(Phase.COVER);
        perch(p);
    }

    /** 머리 위 자리로(한 틱에 그 자리까지). */
    private void perch(Player p) {
        Location eye = p.getEyeLocation();
        vel.setX(eye.getX() - pos.getX()).setY(eye.getY() + 0.24 - pos.getY()).setZ(eye.getZ() - pos.getZ());
        yaw = eye.getYaw();
        pitch = 0;
    }

    /** 머리를 덮은 동안: 머리를 따라감, 초당 1 피해, 6초면 쓰러뜨림. */
    private void cover() {
        Player p = victim;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this) {
            unhook(p);
            return;
        }
        perch(p);
        int t = inPhase();
        game.hud.meter(p, sp.key(), 1.0 - (double) t / COVER, 6);
        // 덮개가 다른 덮개에 밀려 못 걸렸으면 다시 건다
        if (t % 5 == 0 && game.players.get(p).cover == null) game.players.cover(p, "g16_cap", Math.max(10, COVER + 10 - t));
        if (t > 0 && t % 20 == 0) hit(p, COVER_DMG, "cause_g16");
        if (victim == null) return; // 피해로 쓰러지면 detached 가 풂
        // 6초: 쓰러뜨림(피해 길 hit 으로: 원인 기록과 맞음 연출이 같이 감, 남은 체력보다 넉넉히)
        if (t >= COVER) hit(p, game.players.get(p).maxHp * 10, "cause_g16");
    }

    /** 빗나감: 얼굴부터 땅에 박혀 떪(뒤집힌 채). */
    private void stuck() {
        hold();
        pitch = 180;
        body.roll = (float) Math.sin(age * 1.7) * 6f;
        double fl = floor();
        vel.setY(fl + 0.25 - pos.getY());
        if (inPhase() >= STUCK) {
            body.roll = 0;
            pitch = 0;
            body.pose(4);
            state("");
            readyAt = age + cd(COOLDOWN);
            phase(Phase.RISE);
        }
    }

    private void calm() {
        removeShadow();
        mark(null);
        prey = null;
        state("");
        readyAt = age + 40;
        phase(Phase.RISE);
    }

    /** 노릴 수 있는 사람: 서 있고 아직 아무에게도 붙잡히거나 머리, 등에 붙은 것이 없음(지붕은 없다). */
    private boolean valid(Player p) {
        if (p == null || !standing(p)) return false;
        PState s = game.players.get(p);
        return s.grabbedBy == null && s.headBy == null;
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 딸랑이, 거미줄: 날고 있을 때만 끌린다(머리를 덮었거나 내려꽂는 중, 땅에 박힌 동안은 무시). */
    @Override
    public void lure(Vector at, int ticks) {
        if (phase == Phase.RISE || phase == Phase.CIRCLE) super.lure(at, ticks);
    }

    // ------------------------------------------------------------------ 신호(웨이브가 갈수록 배움)

    /**
     * 핑을 냈다(핑 아이템이 생기면 그 아이템이 부른다). 15웨이브부터 내리꽂는 게이브가 10초 동안 이 사람을 노릴 수 있다.
     * 지금은 신호 소모품(조명탄, 딸랑이)을 던지면 이 종이 스스로 알아챈다(Sig.update).
     */
    public static void ping(Game game, Player p) {
        if (game != null && p != null) signals(game).pingAt.put(p.getUniqueId(), game.now());
    }

    /** 가장 센 신호를 낸 사람(다른 내리꽂는 게이브가 노리는 사람, 붙잡힌 사람 빼고). 없으면 null. */
    private Player pick() {
        Sig sig = signals(game);
        int st = stage();
        long now = game.now();
        List<Player> busy = null;
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || !(g instanceof G16 o)) continue;
            if (o.prey == null && o.victim == null) continue;
            if (busy == null) busy = new ArrayList<>(4);
            if (o.prey != null) busy.add(o.prey);
            if (o.victim != null) busy.add(o.victim);
        }
        Player best = null;
        double bs = 0;
        for (Player p : game.mobs.standing()) {
            if (!valid(p) || busy != null && busy.contains(p)) continue;
            UUID id = p.getUniqueId();
            double s = 0;
            int[] f = sig.fire.get(id);
            if (f != null && f[1] >= FIRE_MIN) s = f[1];
            if (st >= 1) {
                Long at = sig.pingAt.get(id);
                if (at != null && now - at < PING_TICKS) s += 200;
            }
            if (st >= 2) {
                Long at = sig.shopAt.get(id);
                if (at != null && now - at < SHOP_TICKS) s += 300;
            }
            if (s > bs) {
                bs = s;
                best = p;
            }
        }
        return best;
    }

    /** 판마다 하나: 사람별 연사 길이(틱), 마지막 핑, 상점 연 틱. 그 틱에 처음 부른 개체가 갱신. */
    static final class Sig {
        long tick = Long.MIN_VALUE;
        /** [마지막 쏜 발 수, 이어 쏜 틱, 쏘지 않은 틱, 지난 틱 소모품 수]. */
        final Map<UUID, int[]> fire = new HashMap<>();
        /** 지난 틱에 든 소모품 종류. */
        final Map<UUID, String> held = new HashMap<>();
        final Map<UUID, Long> shopAt = new HashMap<>(), pingAt = new HashMap<>();

        void update(Game game) {
            long now = game.now();
            if (now == tick) return;
            // 이 종이 하나도 없던 사이는 못 봤다: 그 사이의 변화는 신호로 치지 않고 다시 맞춘다
            boolean gap = now != tick + 1;
            tick = now;
            for (Player p : game.mobs.standing()) {
                Guns.Arms a = game.guns.arms(p);
                if (a == null) continue;
                UUID id = p.getUniqueId();
                int shots = a.shots;
                int[] f = fire.get(id);
                if (f == null || gap) {
                    f = new int[] {shots, 0, 99, a.itemCount};
                    fire.put(id, f);
                    held.put(id, a.item);
                    continue;
                }
                if (shots > f[0]) f[2] = 0;
                else f[2]++;
                f[0] = shots;
                f[1] = f[2] <= FIRE_GAP ? f[1] + 1 : 0;
                boolean shop = game.shop.view(p) != null;
                if (shop) shopAt.put(id, now);
                // 신호 소모품을 하나 던짐(상점 화면에서 바꿔 산 것은 빼고)
                String was = held.get(id);
                if (!shop && was != null && SIGNAL_ITEMS.contains(was) && (was.equals(a.item) ? a.itemCount == f[3] - 1 : a.item == null && f[3] == 1))
                    pingAt.put(id, now);
                if (was == null ? a.item != null : !was.equals(a.item)) held.put(id, a.item);
                f[3] = a.itemCount;
            }
            if (now % 1200 == 0) prune();
        }

        /** 나간 사람 기록을 지움. */
        private void prune() {
            fire.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            held.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            shopAt.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            pingAt.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        }
    }

    private static final Map<Game, Sig> SIGS = new WeakHashMap<>();

    static Sig signals(Game g) {
        return SIGS.computeIfAbsent(g, k -> new Sig());
    }

    // ------------------------------------------------------------------ 떼어짐, 맞음, 죽음

    @Override
    public void detached(Player p, Player helper, String how) {
        if (p != null && p == victim) unhook(p);
    }

    /** 덮기를 풂(덮개, 숨김, 게이지). 살아 있으면 그 자리 바닥에 떨어져 박힘. */
    private void unhook(Player p) {
        victim = null; // 먼저 비움: release 가 다시 detached 를 불러도 두 번 풀지 않게
        if (p != null) {
            PState s = game.players.get(p.getUniqueId());
            if (s != null && "g16_cap".equals(s.cover)) game.players.uncover(p);
            if (body != null) body.hide(p, false);
            game.hud.meter(p, sp.key(), -1, 0);
            if (s != null && s.grabbedBy == this) game.players.release(p);
        }
        noPush = false;
        noBite = false;
        if (!dead && phase == Phase.COVER) {
            Location l = p != null ? p.getLocation() : loc();
            Vector side = new Vector(game.random.nextGaussian(), 0, game.random.nextGaussian());
            if (side.lengthSquared() < 1e-6) side.setX(1);
            side.normalize().multiply(0.8);
            pos.setX(l.getX() + side.getX()).setZ(l.getZ() + side.getZ());
            land();
        }
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        double k = switch (phase) {
            case RISE, CIRCLE, STALK, TELL, DROP -> AIR_MUL;
            case STUCK -> STUCK_MUL;
            default -> 1.0;
        };
        if (k != 1.0 && d.type() != DamageType.BITE) d = d.times(k);
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        removeShadow();
        Player v = victim;
        // 하늘에서 죽으면 시체가 바닥에 놓이게(디스플레이 컬링 상자가 하늘에 남으면 바닥의 시체가 안 그려짐)
        double fl = floor();
        if (pos.getY() - fl > 0.6) pos.setY(fl + 0.3);
        super.die(d);
        if (v != null) unhook(v);
    }

    @Override
    public void remove() {
        removeShadow();
        Player v = victim;
        super.remove();
        if (v != null) unhook(v);
    }

    private void removeShadow() {
        if (shadow != null && shadow.isValid()) shadow.remove();
        shadow = null;
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY() - 0.2, pos.getZ(), R});
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " st=" + stage() + (prey != null ? " prey=" + prey.getName() : "")
                + (victim != null ? " on=" + victim.getName() : "");
    }
}
