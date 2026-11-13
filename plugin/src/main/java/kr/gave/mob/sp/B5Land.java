package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.boss.Boss;
import kr.gave.mob.boss.Tele;
import kr.gave.mob.boss.Terrain;
import kr.gave.trip.PadLights;
import kr.gave.world.Plaza;

/**
 * B5 모두의 게이브의 맵 연출(docs/모듈/보스.md 16절 '맵 활용', docs/모듈/원정.md 10절). 땅은 모두 바탕의 {@link Terrain} 으로 바꾸므로 쉬는 시간, 새 웨이브,
 * 판 바뀜에 저절로 되돌아간다(엔딩에서는 B5 가 물결과 함께 먼저 되돌린다).
 *
 * <ul>
 * <li>발자국: 걸음마다 발밑이 1블록 꺼진다({@link #footprint}). {@link B5.N#PRINT_KEEP} 틱 뒤 메워짐.</li>
 * <li>사체 더미: 삼킨 게이브가 쌓인 자리의 둔덕 + 작은 얼굴 무더기(모델 gabe/b5_heap). 탄과 '이마 눈'을 가리는 엄폐물({@link #pile}).</li>
 * <li>3단계 '눈을 떴다': 중앙 원 둘레가 방사형 여덟 줄로 갈라진다({@link #cracks}).</li>
 * <li>4단계 '마지막 얼굴': 머리가 기어 올 길이 도랑으로 파이고 그 양옆 바닥 판이 솟아 경기장이 조각난다({@link #lastStand}).</li>
 * <li>투광등: 발사장 투광등 한 벌(공용 {@link PadLights}: Launch 가 세웠으면 그것, 없으면 등장 때 B5 가 세우고 사라질 때 내림). 보스가 팔로 내리쳐 끈다
 * ({@code PadLights.smash}). 꺼진 사분면의 어둠과 떼 빠르기, 밑동 발전기로 다시 켜기는 PadLights 가 한다.</li>
 * <li>엔진 시험 분사: 관제 자리(초록 고리, R 20)를 사람들이 {@link B5.N#ENGINE_HOLD} 틱 지키면 분사구 쪽 60도 22블록이 불길이 된다(보스 체력 4%, 그 안의 떼
 * 전멸, 그 안에 선 사람도 탄다). 우주선과 발사대는 아직 없다: 분사구 자리는 그을음 부채꼴로만 표시.</li>
 * </ul>
 */
final class B5Land {

    static final int FY = Plaza.FLOOR_Y;

    final B5 b;
    final Game game;

    B5Land(B5 b) {
        this.b = b;
        this.game = b.game;
    }

    private Terrain land() {
        return b.land();
    }

    private static Vector center() {
        return new Vector(Plaza.CX + 0.5, FY, Plaza.CZ + 0.5);
    }

    private static Vector polar(double r, double ang) {
        return new Vector(Plaza.CX + 0.5 + Math.cos(ang) * r, FY, Plaza.CZ + 0.5 + Math.sin(ang) * r);
    }

    static double angOf(Vector v) {
        return Math.atan2(v.getZ() - Plaza.CZ - 0.5, v.getX() - Plaza.CX - 0.5);
    }

    static double radOf(Vector v) {
        return Math.hypot(v.getX() - Plaza.CX - 0.5, v.getZ() - Plaza.CZ - 0.5);
    }

    /** 두 각의 차(라디안, -PI..PI). */
    static double wrap(double a) {
        a %= Math.PI * 2;
        if (a > Math.PI) a -= Math.PI * 2;
        if (a < -Math.PI) a += Math.PI * 2;
        return a;
    }

    // ------------------------------------------------------------------ 발자국

    /** 발밑이 1블록 꺼졌다가 PRINT_KEEP 틱 뒤 메워진다. */
    void footprint(Vector at) {
        if (land() == null) return;
        int g = b.pit(at, B5.N.PRINT_R, 1, 3);
        if (g > 0) b.mend(g, 20, B5.N.PRINT_KEEP);
    }

    /** 팔이 찍은 자리: 띠를 따라 금(무늬), 끝에 얕은 구덩이. MARK_KEEP 틱 뒤 메워짐. */
    void strike(Vector from, Vector to) {
        if (land() == null) return;
        Vector d = to.clone().subtract(from);
        int c = b.crack(from.clone().add(d.clone().multiply(0.15)), from.clone().add(d.clone().multiply(0.9)), 2.0);
        if (c > 0) b.mend(c, 0, B5.N.MARK_KEEP);
        int g = b.pit(from.clone().add(d.clone().multiply(0.8)), 2.4, 1, 3);
        if (g > 0) b.mend(g, 20, B5.N.MARK_KEEP);
    }

    // ------------------------------------------------------------------ 사체 더미(엄폐물)

    static final class Pile {
        Vector at;
        int group;
        ItemDisplay heap;
    }

    final List<Pile> piles = new ArrayList<>();

    /** 사체 더미 하나(둔덕 r PILE_R, 높이 PILE_H + 얼굴 무더기 디스플레이). 다른 더미, 보스 발밑, 중앙 원, 투광등과 겹치면 안 만든다. */
    boolean pile(Vector where) {
        if (land() == null || piles.size() >= B5.N.PILE_MAX) return false;
        Vector at = where.clone().setY(FY);
        double r = radOf(at);
        if (r < Plaza.R_SAFE + B5.N.PILE_R + 1 || r > Plaza.R_ROAM - 2) return false;
        if (Boss.horiz(at, b.pos) < B5.N.PILE_R + 5) return false;
        for (Pile p : piles) if (Boss.horiz(p.at, at) < B5.N.PILE_GAP) return false;
        for (Mobs.Light l : lampList()) if (Boss.horiz(l.pos(), at) < B5.N.PILE_R + 2.5) return false;
        if (control != null && Boss.horiz(control, at) < B5.N.PILE_R + 4) return false;
        for (Player q : game.mobs.standing()) if (Boss.horiz(q.getLocation().toVector(), at) < B5.N.PILE_R + 1.5) return false;
        int g = heapUp(at);
        if (g <= 0) return false;
        b.quake(at, 30, 0.5);
        Props.soundAround(new Location(game.world, at.getX(), FY + 1, at.getZ()), "sfx.stomp", Sound.ENTITY_RAVAGER_STEP, 1f, 0.9f, 0.6f, 64, -1);
        Pile p = new Pile();
        p.at = at;
        p.group = g;
        if (Look.has("gabe/b5_heap")) {
            LookState ls = new LookState();
            ls.phase = game.random.nextInt(240);
            float k = (float) (B5.N.PILE_R * 0.95);
            Location l = new Location(game.world, at.getX(), FY + B5.N.PILE_H - 0.9, at.getZ());
            try {
                p.heap = game.world.spawn(l, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setItemStack(Body.item("gabe/b5_heap", "", ls, game.worldTime()));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setBrightness(Body.LIGHT);
                    d.setViewRange(2.5f);
                    d.setDisplayWidth(k * 3f);
                    d.setDisplayHeight(k * 3f);
                    d.setInterpolationDuration(14);
                    d.setTransformationMatrix(new Matrix4f().translate(0, -2.5f, 0).scale(0.05f));
                });
                ItemDisplay hd = p.heap;
                float yaw = game.random.nextFloat() * 6.28f;
                game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
                    if (!hd.isValid()) return;
                    hd.setInterpolationDelay(0);
                    hd.setInterpolationDuration(14);
                    hd.setTransformationMatrix(new Matrix4f().rotateY(yaw).scale(k, k * 0.8f, k));
                }, 2);
            } catch (RuntimeException e) {
                p.heap = null;
            }
        }
        piles.add(p);
        return true;
    }

    /** 더미의 블록: 가파른 둔덕(가운데 PILE_H, 옆은 세제곱으로 떨어짐: 뒤에 붙어 서면 위에서 오는 빛도 가려진다). 그 자리 흙으로. 묶음 번호. */
    private int heapUp(Vector at) {
        Terrain t = land();
        double r = B5.N.PILE_R;
        int g = 0, x0 = (int) Math.floor(at.getX() - r), x1 = (int) Math.floor(at.getX() + r), z0 = (int) Math.floor(at.getZ() - r), z1 = (int) Math.floor(at.getZ() + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double d = Math.hypot(x + 0.5 - at.getX(), z + 0.5 - at.getZ()) / r;
                if (d > 1) continue;
                int hh = Math.max(1, (int) Math.ceil(B5.N.PILE_H * (1 - d * d * d) - 1e-6));
                Material m = game.world.getBlockAt(x, FY - 1, z).getType();
                if (!m.isSolid() || !m.isOccluding()) m = Material.COARSE_DIRT;
                for (int k = 0; k < hh; k++) {
                    int rr = t.set(x, FY + k, z, m, g);
                    if (g == 0) g = rr;
                }
            }
        }
        return g;
    }

    /** 보스가 밟고 지나가는 더미는 뭉개진다(보스 발밑 바닥이 솟아 몸이 들썩이지 않게). */
    void trample(Vector g) {
        for (java.util.Iterator<Pile> it = piles.iterator(); it.hasNext();) {
            Pile p = it.next();
            if (Boss.horiz(p.at, g) > B5.N.PILE_R + 3.5) continue;
            it.remove();
            if (p.heap != null && p.heap.isValid()) p.heap.remove();
            b.mend(p.group, 8);
        }
    }

    private void clearPiles() {
        for (Pile p : piles) if (p.heap != null && p.heap.isValid()) p.heap.remove();
        piles.clear();
    }

    // ------------------------------------------------------------------ 금, 도랑, 솟은 판

    /** 3단계 '눈을 떴다': 중앙 원 둘레에서 바깥으로 방사형 여덟 줄(무늬만). */
    void cracks() {
        if (land() == null) return;
        double a0 = game.random.nextDouble() * Math.PI / 4;
        for (int i = 0; i < 8; i++) {
            double a = a0 + i * Math.PI / 4;
            b.crack(polar(Plaza.R_SAFE + 1, a), polar(B5.N.CRACK_R + game.random.nextDouble() * 6, a + (game.random.nextDouble() - 0.5) * 0.2), 2.2);
        }
    }

    /** 4단계 '마지막 얼굴': 머리가 기어 올 길(보스 자리 → 중앙 원)이 도랑으로 파이고, 그 양옆과 둘레의 바닥 판 넷이 솟는다. */
    void lastStand(Vector from) {
        Terrain t = land();
        if (t == null) return;
        double a = angOf(from), r = Math.max(Plaza.R_SAFE + 2, radOf(from) + 3);
        t.crack(polar(Plaza.R_SAFE - 4, a), polar(r, a), 3, true);
        Material m = game.world.getBlockAt((int) Math.floor(from.getX()) + 3, FY - 1, (int) Math.floor(from.getZ())).getType();
        if (!m.isSolid() || !m.isOccluding()) m = Material.COARSE_DIRT;
        for (int i = 0; i < 4; i++) {
            // 기어 오는 길에서 72도, 144도 벗어난 쪽에 판(길이 8, 높이 2): 길은 막지 않는다
            double sa = a + (i < 2 ? 1 : -1) * Math.toRadians(i % 2 == 0 ? 68 : 138);
            Vector p0 = polar(Plaza.R_SAFE + 3, sa), p1 = polar(Plaza.R_SAFE + 11, sa + 0.12);
            b.wall(p0, p1, 2, m, 16);
        }
    }

    // ------------------------------------------------------------------ 투광등(공용 kr.gave.trip.PadLights: 발사장과 같은 한 벌)

    /** 발사장 투광등 한 벌(Launch 가 세웠으면 그것, 아니면 B5 가 세움). 발전기(꺼진 등 밑동에 서 있기)와 꺼진 사분면 어둠은 PadLights 가 한다. */
    private PadLights pad;
    /** B5 가 세웠나(그러면 사라질 때 내림. Launch 가 세운 것은 안 내림). */
    private boolean padMine;
    boolean lampHint;

    /** 등장 때 한 번: 발사장 투광등을 쓴다(이미 서 있거나 빌려 쓰는 중이면 그대로, 없으면 세움). */
    void lamps() {
        try {
            pad = PadLights.of(game);
            if (!pad.active()) {
                pad.raise(b);
                padMine = true;
            }
        } catch (RuntimeException | LinkageError e) {
            pad = null;
        }
    }

    /** 쓰는 등(없으면 빈 목록). */
    List<Mobs.Light> lampList() {
        return pad == null || !pad.active() ? List.of() : pad.lights();
    }

    /** 등 밑동(바닥 높이). */
    static Vector baseOf(Mobs.Light l) {
        return l.pos().clone().setY(FY);
    }

    /** 켜진 등 가운데 at 에서 range 안 가장 가까운 것. */
    Mobs.Light litNear(Vector at, double range) {
        Mobs.Light best = null;
        double bd = range;
        for (Mobs.Light l : lampList()) {
            if (l.gone() || !l.lit()) continue;
            double d = Boss.horiz(l.pos(), at);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    int lit() {
        return pad == null ? 0 : pad.litCount();
    }

    /** 등이 내리쳐져 꺼진다(발전기로만 다시 켜짐: PadLights). */
    void smash(Mobs.Light l) {
        if (pad == null || l == null || !l.lit()) return;
        pad.smash(l, b);
        if (!lampHint) {
            lampHint = true;
            b.hintAll("hint_b5_lamp", 80);
        }
    }

    /** 그 자리가 꺼진 등의 사분면인가(PadLights 와 같은 판정). */
    boolean dark(double x, double z) {
        return pad != null && pad.darkAt(x, z);
    }

    boolean anyDark() {
        return pad != null && pad.active() && pad.litCount() < lampList().size();
    }

    // ------------------------------------------------------------------ 엔진 시험 분사

    /** 분사구 방향(라디안), 관제 자리, 다음에 쓸 수 있는 싸움 틱, 채널 틱, 불길 남은 틱. */
    double nozzle;
    Vector control;
    int engineAt, hold, fire = -1, warn = -1;
    boolean engineHint;
    int engineShots;
    private Tele spot;
    private final List<Tele> fan = new ArrayList<>();

    /** 등장 때 한 번: 분사구 방향(보스가 온 쪽에서 4분의 1 바퀴)과 관제 자리(분사구 맞은편 R 20)를 정한다. 바닥 그을음 부채꼴은 {@link #engineMark}. */
    void engine(double bossAng) {
        nozzle = wrap(bossAng + Math.PI / 2);
        control = polar(B5.N.ENGINE_SPOT_R, nozzle + Math.PI);
        engineAt = B5.N.ENGINE_FIRST;
    }

    /** 분사구 쪽 바닥 그을음 i(0..2, 가운데에서 바깥으로). 한 틱에 하나씩(땅 바꾸기를 나눔). */
    void engineMark(int i) {
        if (land() == null || control == null || i < 0 || i > 2) return;
        b.scorch(polar(7 + i * 6, nozzle), 2.2 + i * 1.3);
    }

    boolean inFan(Vector v, double pad) {
        double r = radOf(v);
        if (r < 2.5 || r > B5.N.ENGINE_REACH + pad) return false;
        double half = Math.toRadians(B5.N.ENGINE_ARC / 2) + (pad > 0 ? Math.asin(Math.min(1, pad / Math.max(pad, r))) : 0);
        return Math.abs(wrap(angOf(v) - nozzle)) <= half;
    }

    private void tickEngine() {
        if (control == null) return;
        int now = b.fightTicks;
        if (fire >= 0) {
            burn();
            return;
        }
        if (warn >= 0) {
            if (--warn < 0) ignite();
            return;
        }
        if (now < engineAt) return;
        if (spot == null || spot.gone()) spot = b.safe(control, B5.N.ENGINE_SPOT, 200);
        if (!engineHint) {
            engineHint = true;
            b.hintAll("hint_b5_engine", 80);
        }
        int in = 0, all = game.mobs.standing().size();
        List<Player> on = new ArrayList<>(4);
        for (Player p : game.mobs.standing()) {
            if (Boss.horiz(p.getLocation().toVector(), control) > B5.N.ENGINE_SPOT) continue;
            in++;
            on.add(p);
        }
        int need = Math.max(1, Math.min(B5.N.ENGINE_PEOPLE, all));
        if (in >= need) {
            hold++;
            if (hold % 20 == 1) Props.soundAround(loc(control, 1.5), "gabe.b5.alarm", Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 1f, 0.6f, 96, -1);
        } else hold = Math.max(0, hold - 2);
        for (Player p : on) game.hud.meter(p, b.sp.key(), hold / (double) B5.N.ENGINE_HOLD, 6);
        if (hold >= B5.N.ENGINE_HOLD) {
            hold = 0;
            warn = B5.N.ENGINE_WARN;
            if (spot != null) spot.remove();
            spot = null;
            // 분사구 쪽 부채꼴 예고: 빨간 띠 셋
            double half = Math.toRadians(B5.N.ENGINE_ARC / 2);
            for (int i = -1; i <= 1; i++) {
                double a = nozzle + i * half * 0.66;
                fan.add(b.strip(polar(3, a), polar(B5.N.ENGINE_REACH, a), 2 * B5.N.ENGINE_REACH * Math.tan(half * 0.36), B5.N.ENGINE_WARN));
            }
            Props.soundAround(loc(center(), 2), "gabe.b5.alarm", Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f, 0.5f, 96, -1);
        }
    }

    private Location loc(Vector v, double up) {
        return new Location(game.world, v.getX(), v.getY() + up, v.getZ());
    }

    /** 점화: 부채꼴 안 보스 4%(4단계 1%), 그 안의 떼 전멸, 그 안에 선 사람도 탄다. 그 뒤 ENGINE_BURN 틱 불길. */
    private void ignite() {
        fan.clear();
        fire = B5.N.ENGINE_BURN;
        engineShots++;
        engineAt = b.fightTicks + B5.N.ENGINE_CD;
        Vector c = center();
        Props.soundAround(loc(c, 2), "gabe.b5.engine", Sound.ENTITY_BLAZE_SHOOT, 1f, 1f, 0.5f, 192, -1);
        b.quake(c, 64, 0.9);
        // 점화: 분사구에서 부채꼴 끝까지 불덩이 넷(섬광과 첫 불덩이는 효과 예산의 '꼭' 몫이라 바쁠 때도 보인다)
        for (int i = 0; i < 4; i++) {
            try {
                game.shotFx.blast(loc(polar(4 + i * 5.5, nozzle), 0.6), 4.5f);
            } catch (RuntimeException e) {
                break;
            }
        }
        if (inFan(b.pos, B5.N.ENGINE_BOSS_PAD)) b.engineHit();
        for (Gabe g : new ArrayList<>(game.mobs.all())) {
            if (g.dead || g == b || g.sp.is(Role.BOSS) || game.director.inCage(g) || !inFan(g.pos, 0)) continue;
            // 사람이 일으킨 분사로 죽인 것: 보상은 없지만 '정리로 치워짐'이 아니다(noReward 를 켜면 원정 판에서 그 개체에 갇힌 사람이 죽음 처리됨).
            // 죽음 결과 종(G38, G52, G50)도 사람이 잡은 것처럼(꺼내는 값이 싸짐)
            g.noReward = false;
            g.reward = 0;
            Vector out = g.pos.clone().subtract(c).setY(0);
            if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
            out.normalize();
            Damage d = new Damage(Math.max(1, g.hp) + 1, DamageType.FIRE, null, g.pos.clone(), out.clone().add(new Vector(0, 0.5, 0)).normalize(), "boss_engine", 0);
            try {
                g.die(d);
            } catch (RuntimeException e) {
                game.players.freeFrom(g);
                g.remove();
            }
            if (!g.dead) g.remove();
        }
        for (Player p : game.mobs.standing()) {
            Vector f = p.getLocation().toVector();
            if (!inFan(f, 0)) continue;
            b.hitBoss(p, B5.N.ENGINE_DMG, "cause_b5_fire");
            Vector out = f.clone().subtract(c).setY(0);
            b.knock(p, out.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : out, 0.9, 0.4);
        }
    }

    private void burn() {
        fire--;
        double half = Math.toRadians(B5.N.ENGINE_ARC / 2);
        for (int i = 0; i < 3; i++) {
            double a = nozzle + (game.random.nextDouble() * 2 - 1) * half, r = 3 + game.random.nextDouble() * (B5.N.ENGINE_REACH - 3);
            Vector p = polar(r, a);
            try {
                // 틱마다 하나는 '꼭' 몫: 효과 예산이 차도 불길이 끊기지 않게(14틱 사는 덩이 하나씩이라 꼭 몫을 많이 쓰지 않음)
                game.shotFx.flame(loc(p, 0.8 + game.random.nextDouble()), 3.0f + (float) (r * 0.14), 14, i == 0 ? ShotFx.KEY : i == 1 ? ShotFx.NORMAL : ShotFx.EXTRA);
            } catch (RuntimeException e) {
                break;
            }
        }
        if (fire % 10 == 0 && land() != null) b.scorch(polar(4 + game.random.nextDouble() * (B5.N.ENGINE_REACH - 6), nozzle + (game.random.nextDouble() - 0.5) * half), 3);
        // 불길 속에 들어온 사람은 10틱마다 덴다
        if (fire % 10 == 5) {
            for (Player p : game.mobs.standing()) if (inFan(p.getLocation().toVector(), 0)) b.hitBoss(p, B5.N.ENGINE_DMG * 0.3, "cause_b5_fire");
        }
    }

    // ------------------------------------------------------------------ 틱, 치우기

    /** 싸움 중 매 틱. */
    void tick() {
        tickEngine();
        if (b.fightTicks % 10 == 0 && !piles.isEmpty()) trample(b.ground());
    }

    /** 시험: 엔진을 바로 쓸 수 있게(채널도 거의 다 참). */
    void engineNow() {
        engineAt = b.fightTicks;
        hold = B5.N.ENGINE_HOLD - 5;
    }

    /** 엔딩: 금, 구덩이, 더미, 등을 모두 되돌린다(아침에는 땅이 멀쩡하다). */
    void heal() {
        clearPiles();
        if (pad != null) for (Mobs.Light l : lampList()) pad.relight(l);
        control = null;
        if (spot != null) spot.remove();
        spot = null;
        Terrain t = land();
        if (t != null) t.restoreAll();
    }

    /** 보스가 사라질 때: 내 디스플레이만 치운다(땅은 바탕이 쉬는 시간과 새 웨이브에 되돌림). */
    void remove() {
        clearPiles();
        if (pad != null && padMine) pad.lower(b);
        padMine = false;
        if (spot != null) spot.remove();
        spot = null;
        fan.clear();
    }

    String status() {
        return "lamps=" + lit() + "/" + lampList().size() + " piles=" + piles.size() + " engine=" + (control == null ? "-" : fire >= 0 ? "fire" : warn >= 0 ? "warn" : b.fightTicks < engineAt
                ? "cd" + (engineAt - b.fightTicks) / 20 : "ready:" + hold) + " shots=" + engineShots;
    }
}
