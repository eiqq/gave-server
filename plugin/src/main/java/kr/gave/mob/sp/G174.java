package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.trip.Trip;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G174 손자국 게이브(3막 떼, 함정). 이 막에서 사람이 쓰러졌던 자리마다 검은 손자국 얼룩이 찍혀 막이 끝날 때까지 남는다(판에 여섯까지, 넘으면 가장
 * 오래된 것이 옅어져 사라짐). 얼룩마다 4초에 손 둘씩을 내보낸다(얼룩 하나에 손 여섯까지). 손은 가장 가까운 사람에게 빠르게 기어 오고(0.3블록/틱),
 * 쓰러진 사람이 있으면 그 곁에서 일으키는 사람에게 먼저 간다. 얼룩 1.5 블록 안에 발을 들인 사람은 얼룩 얼굴이 눈을 뜨며 발목을 움켜쥔다(1초 묶임, 12).
 *
 * <p>얼룩: 감독이 미리 깔 때(PLACED) 이 막에서 쓰러진 자리(아직 얼룩이 없는 곳)에 놓이고, 그런 자리가 없으면 트럭 둘레 밖 R 10~20 에 셋(둘은 감독이 낸
 * 것처럼 spawnLike). 그 뒤 이 막에서 사람이 쓰러질 때마다(Mobs.Field "G174" 가 사람 상태를 봄) 그 자리에 새 얼룩. 얼룩은 persist(DIRECTOR): 웨이브 끝
 * 정리에서 빠져 막이 바뀌는 웨이브 시작(Field.waveStart)에 치워진다. 쉬는 시간에는 손을 내지 않고 발목도 안 잡는다. 체력 60(종 기본 10 의 6배),
 * 불과 폭발 x3, 죽으면 그 얼룩의 손도 멈춤(사라짐). 보상 얼룩 6 몫.
 *
 * <p>손: 같은 종의 다른 꼴(hand, 모델 g174_hand). 소환이라 보상 0, noHold(웨이브를 붙들지 않음, 끝 정리에서 치워짐). 할큄 5, 발목에 붙으면(한 사람에
 * 셋까지) 0.5초마다 3과 20% 느려짐. 일으키는 중인 사람에게는 손 피해 두 배.
 *
 * <p>팩 계약(tools/gabe/sp/g174.py): 모델 gave:gabe/g174(얼룩) 상태 "", gave:gabe/g174_hand 상태 "". 소리 crawl(24), grip(20), claw(16), burn(24).
 * 글귀 cause_g174, cause_g174_stain.
 */
public final class G174 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HAND_SPEED = 0.3, STAIN_HP = 6, STEP_R = 1.5, CLAW = 5, CLING = 3, CLING_SLOW = 0.2, GRIP = 12, BURN_MUL = 3;
    static final int MAX_STAINS = 6, HANDS_PER = 6, HAND_EVERY = 80, HANDS_AT_ONCE = 2, CLING_EVERY = 10, CLING_MAX = 3, GRIP_ROOT = 20,
        GRIP_CD = 40, OPEN_T = 30;
    static final double R_MIN = 10, R_MAX = 20;
    static final String FIELD = "G174";

    /** 판의 얼룩(오래된 차례). */
    static final List<G174> STAINS = new ArrayList<>();
    /** 다음 얼룩을 이 자리, 이 방향으로(spawnLike 가 부르는 spawned 가 읽음). */
    static Vector pendingAt;
    static float pendingYaw;

    /** 손 꼴인가(아니면 얼룩). 놓기 전에 asHand() 로. */
    private boolean hand;
    /** 손: 나온 얼룩. 얼룩: 내보낸 손들, 붙잡은 사람. */
    private G174 home;
    private final List<G174> hands = new ArrayList<>(HANDS_PER);
    private Player gripped;
    private long gripUntil, openUntil;
    private final Map<UUID, Long> gripCd = new HashMap<>();
    private int nextHands;
    private boolean init;
    /** 손: 붙은 사람과 발목 자리. */
    private Player clingTo;
    private final Vector clingOff = new Vector();
    private int nextCling, readyAt;

    public G174(Game game, Species sp) {
        super(game, sp);
        // 얼룩(기본 꼴): 땅에 납작, 벽 무시, 밀지 않음
        ghost = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        hover = 0.03;
        radius = 0.75;
        baseSpeed = 0;
        leaveCorpse = false;
        persist = true;
    }

    /** 손 꼴로 바꿈(place 앞에). */
    G174 asHand(G174 from) {
        hand = true;
        home = from;
        ghost = false;
        noPush = false;
        noBite = false;
        hover = 0.32;
        radius = 0.3;
        baseSpeed = HAND_SPEED;
        leaveCorpse = true;
        persist = false;
        noHold = true;
        noReward = true;
        reward = 0;
        init = true;
        return this;
    }

    @Override
    protected void build() {
        body = hand ? Body.of(this, "gabe/g174_hand", 1.0f, 0.6f) : Body.of(this, "gabe/g174", 2.2f, 0.3f);
    }

    @Override
    protected void spawned() {
        if (hand || game.director.cageSpawning()) return;
        // 막 단위 갈고리: 이 막에서 사람이 쓰러지면 그 자리에 얼룩(판이 바뀌면 엔진이 뗌, 놓일 때마다 다시)
        Watch w = game.mobs.field(FIELD) instanceof Watch x ? x : null;
        if (w == null) {
            w = new Watch(game);
            game.mobs.field(FIELD, w);
        }
        w.act = Trip.actOf(Math.max(1, game.wave));
        STAINS.removeIf(g -> g.dead || g.removed);
        if (pendingAt != null) {
            // 쓰러진 자리에 찍는 얼룩
            pos.copy(pendingAt);
            yaw = pendingYaw;
            pendingAt = null;
        } else {
            // 감독이 미리 깐 것: 이 막에서 쓰러진 자리 가운데 얼룩이 없는 곳, 없으면 트럭 둘레 밖 R 10~20
            Vector spot = w.freeSpot();
            if (spot == null) spot = ringSpot();
            pos.copy(spot);
            yaw = game.random.nextFloat() * 360f;
            if (STAINS.isEmpty()) {
                STAINS.add(this);
                // 처음 깔리는 것이면 둘을 더(셋)
                for (int i = 0; i < 2; i++) {
                    Vector at = ringSpot();
                    pendingAt = at;
                    pendingYaw = game.random.nextFloat() * 360f;
                    Gabe g = game.director.spawnLike(sp, at);
                    pendingAt = null;
                    if (g == null) break;
                }
                trim();
                return;
            }
        }
        STAINS.add(this);
        trim();
    }

    /** 트럭(가운데) 둘레 밖 R 10~20, 다른 얼룩과 4 블록 넘게 떨어진 곳. */
    private Vector ringSpot() {
        Vector c = Truck.live() ? Truck.center() : new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
        for (int t = 0; t < 24; t++) {
            double a = game.random.nextDouble() * Math.PI * 2, r = R_MIN + game.random.nextDouble() * (R_MAX - R_MIN);
            double x = c.getX() + Math.cos(a) * r, z = c.getZ() + Math.sin(a) * r;
            if (!game.mobs.flow.walkable(x, z)) continue;
            boolean near = false;
            for (G174 s : STAINS) if (!s.dead && Math.hypot(s.pos.getX() - x, s.pos.getZ() - z) < 4) near = true;
            if (near && t < 20) continue;
            return new Vector(x, game.mobs.flow.floor(x, z) + 0.03, z);
        }
        return new Vector(c.getX() + R_MIN, game.mobs.flow.floor(c.getX() + R_MIN, c.getZ()) + 0.03, c.getZ());
    }

    /** 얼룩이 여섯을 넘으면 가장 오래된 것부터 옅어져 사라짐(보상 없음). */
    private static void trim() {
        STAINS.removeIf(g -> g.dead || g.removed);
        while (STAINS.size() > MAX_STAINS) {
            G174 old = STAINS.remove(0);
            old.fade();
        }
    }

    private void fade() {
        for (G174 h : hands) if (!h.dead) h.remove();
        hands.clear();
        remove();
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (hand) handTick();
        else stainTick();
    }

    private void stainTick() {
        hold();
        if (!init) {
            init = true;
            // 종 기본 체력, 보상(손 몫)을 얼룩 몫으로: 체력 x6(60), 보상 x6
            maxHp *= STAIN_HP;
            hp = maxHp;
            if (!noReward) reward = Math.max(1, reward * 6);
            nextHands = age + 20;
        }
        long now = game.now();
        hands.removeIf(h -> h.dead);
        boolean wave = game.state == Game.State.WAVE;
        if (gripped != null && (now >= gripUntil || !standing(gripped))) gripped = null;
        body.look.open = now < openUntil || gripped != null;
        if (!wave) return;
        // 발을 들인 사람: 발목을 움켜쥠
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            if (Math.abs(l.getY() - pos.getY()) > 1.2) continue;
            if (Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) > STEP_R) continue;
            Long cdUntil = gripCd.get(p.getUniqueId());
            if (cdUntil != null && cdUntil > now) continue;
            gripCd.put(p.getUniqueId(), now + cd(GRIP_CD));
            game.players.root(p, GRIP_ROOT, "g174_stain");
            hit(p, GRIP, "cause_g174_stain");
            gripped = p;
            gripUntil = now + GRIP_ROOT;
            openUntil = now + OPEN_T;
            sound("grip", Sound.ENTITY_SLIME_SQUISH, 0.6f, 20);
        }
        // 4초에 손 둘(얼룩 하나에 여섯까지)
        if (age >= nextHands) {
            nextHands = age + cd(HAND_EVERY);
            int made = 0;
            for (int i = 0; i < HANDS_AT_ONCE && hands.size() < HANDS_PER; i++) {
                if (!game.mobs.budgetFor(1)) break;
                double a = game.random.nextDouble() * Math.PI * 2;
                Vector at = pos.clone().add(new Vector(Math.cos(a) * 0.6, 0.3, Math.sin(a) * 0.6));
                G174 h = new G174(game, sp).asHand(this);
                h.yaw = (float) Math.toDegrees(-a) - 90f;
                if (game.mobs.place(h, at) == null) continue;
                h.maxHp = h.hp = sp.hp * game.director.hpMul(sp);
                hands.add(h);
                made++;
            }
            if (made > 0) sound("crawl", Sound.ENTITY_SPIDER_STEP, 1.4f, 24);
        }
    }

    // ------------------------------------------------------------------ 손

    /** 손이 노리는 사람: 얼룩이 움켜쥔 사람, 쓰러진 동료를 일으키는 사람, 가장 가까운 사람. */
    private Player handTarget() {
        if (home != null && !home.dead && home.gripped != null && standing(home.gripped)) return home.gripped;
        Player best = null;
        double bd = 24;
        for (Player p : game.mobs.standing()) {
            if (game.players.get(p).helping == null) continue;
            double d = dist(p);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best != null ? best : nearest(400);
    }

    private static int clinging(Player p, Game game) {
        int n = 0;
        for (Gabe g : game.mobs.all()) if (g instanceof G174 h && !h.dead && h.hand && h.clingTo == p) n++;
        return n;
    }

    private void handTick() {
        if (home != null && home.dead) {
            // 얼룩이 지워지면 그 얼룩의 손도 멈춤
            remove();
            return;
        }
        if (clingTo != null) {
            cling();
            return;
        }
        Player p = handTarget();
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        if (target != p && dist(p) < 16) mark(p);
        if (dist(p) > 3) follow(speed());
        else seek(p.getLocation().toVector().setY(pos.getY()), speed());
        face(chest(p));
        if (age >= readyAt && touches(p, 0.1)) {
            hit(p, CLAW * helpMul(p), "cause_g174");
            sound("claw", Sound.ENTITY_SPIDER_HURT, 1.6f, 16);
            readyAt = age + cd(20);
            if (clinging(p, game) < CLING_MAX) {
                clingTo = p;
                double a = game.random.nextDouble() * Math.PI * 2;
                clingOff.setX(Math.cos(a) * 0.32).setY(0).setZ(Math.sin(a) * 0.32);
                nextCling = age + cd(CLING_EVERY);
                noPush = true;
            }
        }
    }

    /** 일으키는 중인 사람에게는 두 배. */
    private double helpMul(Player p) {
        return game.players.get(p).helping != null ? 2 : 1;
    }

    /** 발목에 붙음: 0.5초마다 3 과 20% 느려짐. 그 사람이 쓰러지거나 나가면 떨어져 다시 김. */
    private void cling() {
        Player p = clingTo;
        if (!standing(p)) {
            clingTo = null;
            noPush = false;
            return;
        }
        Vector want = p.getLocation().toVector().add(clingOff).setY(pos.getY());
        vel.copy(want.subtract(pos).multiply(0.6));
        face(chest(p));
        if (age >= nextCling) {
            nextCling = age + cd(CLING_EVERY);
            hit(p, CLING * helpMul(p), "cause_g174");
            game.players.slow(p, CLING_SLOW, 12, "g174_hand");
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        if (hand) return super.spheres();
        return List.of(new double[] {pos.getX(), pos.getY() + 0.1, pos.getZ(), radius});
    }

    @Override
    public boolean damage(Damage d) {
        if (hand || dead || unhittable()) return super.damage(d);
        boolean burn = d.type() == DamageType.FIRE || d.type() == DamageType.EXPLOSION;
        Damage e = burn ? d.times(BURN_MUL) : d;
        hp -= e.amount();
        game.mobs.onHurt(this, e, e.amount());
        if (hp <= 0) die(e);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (!hand) {
            sound("burn", Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 24);
            for (G174 h : hands) if (!h.dead) h.remove();
            hands.clear();
            STAINS.remove(this);
        }
        super.die(d);
    }

    @Override
    public void remove() {
        if (!hand) STAINS.remove(this);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + (hand ? " hand" + (clingTo != null ? " cling=" + clingTo.getName() : "") : " stain hands=" + hands.size()
            + " stains=" + STAINS.size() + (gripped != null ? " grip=" + gripped.getName() : ""));
    }

    // ------------------------------------------------------------------ 막 단위 갈고리(FIELDTICK)

    /** 사람이 쓰러지는 것을 보고 그 자리에 얼룩을 찍음(이 막에서만), 막이 바뀌면 얼룩을 치움. */
    static final class Watch implements Mobs.Field {
        final Game game;
        /** 얼룩을 깐 막(그 막에서만 쓰러진 자리에 찍음, -1 이면 찍지 않음). */
        int act = -1;
        final Set<UUID> down = new HashSet<>();
        /** 이 막에서 쓰러진 자리(얼룩을 찍지 못한 것: 예산이 찼을 때). */
        final List<Vector> spots = new ArrayList<>();

        Watch(Game game) {
            this.game = game;
        }

        @Override
        public boolean tick(long now) {
            if (game.state != Game.State.WAVE || (now & 1) != 0) return true;
            for (PState s : game.players.all()) {
                if (s.life != Life.DOWNED) {
                    down.remove(s.id);
                    continue;
                }
                if (!down.add(s.id)) continue;
                if (Trip.actOf(Math.max(1, game.wave)) != act) continue;
                Player p = Bukkit.getPlayer(s.id);
                if (p == null) continue;
                Location l = p.getLocation();
                Vector at = new Vector(l.getX(), game.mobs.flow.floor(l.getX(), l.getZ()) + 0.03, l.getZ());
                stamp(at, l.getYaw());
            }
            return true;
        }

        /** 쓰러진 자리에 얼룩(감독이 낸 것처럼: 체력, 보상 배율). 예산이 차면 그 자리를 기억해 두고 다음 감독 얼룩이 씀. */
        void stamp(Vector at, float yaw) {
            STAINS.removeIf(g -> g.dead || g.removed);
            for (G174 g : STAINS) if (Math.hypot(g.pos.getX() - at.getX(), g.pos.getZ() - at.getZ()) < 1.5) return;
            pendingAt = at;
            pendingYaw = yaw;
            Gabe g = game.director.spawnLike(Species.G174, at);
            pendingAt = null;
            if (g == null) {
                spots.add(at.clone());
                if (spots.size() > MAX_STAINS) spots.remove(0);
            }
        }

        /** 감독이 미리 까는 얼룩의 자리: 이 막에서 쓰러졌는데 아직 얼룩이 없는 곳. */
        Vector freeSpot() {
            while (!spots.isEmpty()) {
                Vector v = spots.remove(spots.size() - 1);
                boolean taken = false;
                for (G174 g : STAINS) if (!g.dead && Math.hypot(g.pos.getX() - v.getX(), g.pos.getZ() - v.getZ()) < 1.5) taken = true;
                if (!taken) return v;
            }
            return null;
        }

        @Override
        public void waveStart(int wave) {
            if (act < 0 || Trip.actOf(Math.max(1, wave)) == act) return;
            // 막이 바뀜: 얼룩과 손을 모두 치우고, 새 막에서 감독이 얼룩을 다시 깔 때까지 쓰러진 자리에 찍지 않음
            for (G174 g : new ArrayList<>(STAINS)) if (!g.dead) g.fade();
            STAINS.clear();
            spots.clear();
            act = -1;
        }

        @Override
        public void reset() {
            STAINS.clear();
            spots.clear();
            down.clear();
            pendingAt = null;
        }
    }
}
