package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G133 물마루 게이브(4막, 떼, 돌격). 얼굴 비늘이 박힌 폭 3블록 낮은 물결 띠가 바닥에 깔려 흘러온다(0.22/틱). 표적 4블록 앞에서 곁의 띠(3.6블록 안)와
 * 같은 틱에 0.6초 동안 3블록 물마루로 일어서고(말목 둘이 먼저 벌떡 섬), 앞으로 엎어져 깔린 사람에게 띠마다 7(세 띠가 겹치면 21)과 넘어짐 0.5초.
 * 엎어진 뒤에는 온 쪽으로 쓸려 나가며(썰물) 곁의 사람을 2블록 바깥으로 끌고 갔다가 다시 밀려온다. 낮게 흐를 때 띠에 발이 닿으면 3.
 *
 * <p>떼 묶기: 감독은 개체를 고리의 여러 각도에 하나씩 놓으므로 놓인 직후 '열린 물'(이 종 static, 생긴 지 JOIN 틱 안)에 붙어 그 첫 띠 뒤쪽 넓은
 * 자리로 옮긴다(고리는 흙먼지 속). 그래서 20~30장이 한 장의 물로 흘러온다.
 *
 * <p>맞는 구: 낮을 때 가운데 하나(r 0.35, 납작해서 잘 안 맞음), 설 때 띠 폭을 덮는 세로 구 셋(산탄, 폭발 한 방에 셋이 들어감).
 *
 * <p>팩 계약(tools/gabe/sp/g133.py): 상태 "", "crest", "ebb". 소리 surge(32), crash(24), lap(16). 글귀 cause_g133.
 */
public final class G133 extends Gabe {

    static final double SPEED = 0.22, HOVER = 0.3, LOW_R = 0.35, CREST_R = 0.8;
    /** 표적과 이 거리 안이면 일어섬. 곁 띠를 깨우는 거리. */
    static final double RISE_AT = 4.0, WAKE_R = 3.6;
    /** 일어서는 틱(0.6초), 선 채 버팀, 엎어지는 틱(맞는 때는 FALL_HIT), 썰물 틱, 다시 일어서기까지 쉼. */
    static final int RISE = 12, HOLD = 3, FALL = 8, FALL_HIT = 4, EBB = 24, REST = 50;
    /** 깔림 피해, 넘어짐 틱, 발목 물기 피해와 간격, 썰물 끌림 세기. */
    static final double CRUSH = 7, ANKLE = 3, DRAG = 0.55;
    static final int DOWN = 10, ANKLE_CD = 20;
    /** 깔리는 자리: 앞쪽 길이, 옆 반 폭. */
    static final double REACH = 3.4, HALF_W = 1.9;
    /** 떼 묶기: 열린 물이 받아 주는 틱, 묶음 상한, 뒤로 퍼지는 깊이, 옆 폭. */
    static final int JOIN = 80, MAX_PACK = 30;
    static final double PACK_DEPTH = 9, PACK_SIDE = 7;

    enum Phase { FLOW, RISE, CREST, FALL, EBB }

    /** 한 무리(첫 띠 둘레로 모임). */
    static final class Pack {
        final Game game;
        final long t0;
        final G133 first;
        int n = 1;

        Pack(Game game, long t0, G133 first) {
            this.game = game;
            this.t0 = t0;
            this.first = first;
        }
    }

    private static final List<Pack> OPEN = new ArrayList<>();

    private Phase phase = Phase.FLOW;
    private int phaseAt, readyAt, ankleAt, surgeAt;
    private final Vector dir = new Vector(0, 0, 1);
    private Player prey;
    private final Map<UUID, Boolean> dragged = new HashMap<>(4);

    public G133(Game game, Species sp) {
        super(game, sp);
        radius = LOW_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g133", 3.6f, 3.4f);
    }

    @Override
    protected void spawned() {
        readyAt = 20 + game.random.nextInt(20);
        surgeAt = game.random.nextInt(60);
        G150.arm(game);
        if (game.director.cageSpawning()) return;
        long now = game.now();
        Pack join = null;
        for (Iterator<Pack> it = OPEN.iterator(); it.hasNext();) {
            Pack p = it.next();
            if (p.game != game || now - p.t0 > JOIN || p.first.dead || p.n >= MAX_PACK) {
                it.remove();
                continue;
            }
            if (join == null) join = p;
        }
        if (join == null) {
            OPEN.add(new Pack(game, now, this));
            return;
        }
        // 첫 띠 뒤쪽(사람 반대쪽) 넓은 자리로: 옆으로 퍼지고 뒤로 깊게
        G133 f = join.first;
        Vector fwd = game.mobs.flow.dir(f.pos).setY(0);
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector(1, 0, 0);
        fwd.normalize();
        Vector side = new Vector(fwd.getZ(), 0, -fwd.getX());
        double back = 1.2 + game.random.nextDouble() * PACK_DEPTH, lat = (game.random.nextDouble() * 2 - 1) * PACK_SIDE;
        Vector at = f.pos.clone().subtract(fwd.multiply(back)).add(side.multiply(lat));
        if (game.mobs.flow.walkable(at.getX(), at.getZ())) {
            pos.setX(at.getX());
            pos.setZ(at.getZ());
            pos.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
            join.n++;
        }
    }

    private void go(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case FLOW -> flow();
            case RISE -> rise();
            case CREST -> crest();
            case FALL -> fall();
            case EBB -> ebb();
        }
    }

    /** 낮게 깔려 흘러옴. 발이 닿으면 물고, 표적 4블록 앞에서 곁 띠와 함께 일어섬. */
    private void flow() {
        follow(speed());
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        Player p = nearest(400);
        if (p == null) return;
        double d = flatDist(p);
        if (age >= surgeAt && d < 40) {
            sound("surge", Sound.WEATHER_RAIN_ABOVE, 0.6f, 32);
            surgeAt = age + 70 + game.random.nextInt(50);
        }
        if (age >= ankleAt) {
            for (Player q : game.mobs.standing()) {
                if (onBand(q, 0.9, HALF_W - 0.2)) {
                    hit(q, ANKLE, "cause_g133");
                    sound("lap", Sound.ENTITY_GENERIC_SPLASH, 1.3f, 16);
                    ankleAt = age + cd(ANKLE_CD);
                    break;
                }
            }
        }
        if (age >= readyAt && d <= RISE_AT) {
            Vector to = toward(p);
            if (to.lengthSquared() < 1e-6) return;
            startRise(p, to);
            int woke = 1;
            for (Gabe g : game.mobs.near(pos, WAKE_R, o -> o instanceof G133 && o != this)) {
                G133 o = (G133) g;
                if (o.phase == Phase.FLOW && Math.abs(o.pos.getY() - pos.getY()) < 1.5) {
                    o.startRise(p, to);
                    woke++;
                }
            }
            log("물마루 일어섬 띠 " + woke + " 표적 " + p.getName());
        }
    }

    private double flatDist(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    /** 이 사람이 띠 자리 위에 섰나(앞뒤 반 길이 along, 옆 반 폭 lat, 발이 바닥 가까이). */
    private boolean onBand(Player p, double along, double lat) {
        Location l = p.getLocation();
        double rx = l.getX() - pos.getX(), rz = l.getZ() - pos.getZ();
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        double a = rx * fx + rz * fz, s = rx * fz - rz * fx;
        return Math.abs(a) <= along && Math.abs(s) <= lat && l.getY() - (pos.getY() - HOVER) < 1.2;
    }

    void startRise(Player p, Vector to) {
        prey = p;
        dir.copy(to.clone().setY(0).normalize());
        yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        mark(p);
        hold();
        state("crest");
        // 말목이 먼저 서고 띠가 솟음: 납작하게 시작해 RISE 틱 동안 펴짐
        body.squash(1f, 0.12f, 1f, 0);
        body.pose(0);
        sound("surge", Sound.WEATHER_RAIN_ABOVE, 0.8f, 32);
        go(Phase.RISE);
    }

    private void rise() {
        hold();
        int t = inPhase();
        if (t == 1) body.squash(1f, 1f, 1f, cd(RISE) - 1);
        if (t >= cd(RISE)) go(Phase.CREST);
    }

    private void crest() {
        hold();
        if (inPhase() >= HOLD) {
            pitch = 72;
            body.pose(FALL - 2);
            go(Phase.FALL);
        }
    }

    /** 앞으로 엎어짐: FALL_HIT 틱에 앞쪽 자리 사람을 깔음. */
    private void fall() {
        dash(dir, 0.08);
        if (inPhase() == FALL_HIT) {
            sound("crash", Sound.ENTITY_GENERIC_SPLASH, 0.7f, 24);
            double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                double rx = l.getX() - pos.getX(), rz = l.getZ() - pos.getZ();
                double a = rx * fx + rz * fz, s = rx * fz - rz * fx;
                if (a < -0.6 || a > REACH || Math.abs(s) > HALF_W || l.getY() - (pos.getY() - HOVER) > 2.5) continue;
                hit(q, CRUSH, "cause_g133");
                game.players.root(q, DOWN, "g133");
                log("깔음 " + q.getName() + " 체력 " + Math.round(game.players.get(q).hp));
            }
        }
        if (inPhase() >= FALL) {
            pitch = 0;
            state("ebb");
            body.pose(3);
            dragged.clear();
            mark(null);
            go(Phase.EBB);
        }
    }

    /** 썰물: 온 쪽으로 도로 쓸려 나가며 곁의 사람을 끌고 감. */
    private void ebb() {
        dash(dir, -0.26);
        if (inPhase() <= 8) {
            for (Player q : game.mobs.standing()) {
                if (dragged.containsKey(q.getUniqueId()) || !onBand(q, 1.6, HALF_W)) continue;
                dragged.put(q.getUniqueId(), Boolean.TRUE);
                knock(q, dir.clone().multiply(-1), DRAG, 0.1);
                log("썰물 끌림 " + q.getName());
            }
        }
        if (inPhase() >= cd(EBB) || wallAhead(0.6)) {
            state("");
            readyAt = age + cd(REST);
            prey = null;
            go(Phase.FLOW);
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (phase == Phase.FLOW || phase == Phase.EBB) return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), LOW_R});
        double r = Math.toRadians(yaw), lx = Math.cos(r), lz = Math.sin(r);
        double up = phase == Phase.FALL ? 0.6 : 1.3;
        double fx = -Math.sin(r), fz = Math.cos(r), fwd = phase == Phase.FALL ? 1.0 : 0;
        List<double[]> out = new ArrayList<>(3);
        for (int i = -1; i <= 1; i++) {
            out.add(new double[] {pos.getX() + lx * i * 1.0 + fx * fwd, pos.getY() + up, pos.getZ() + lz * i * 1.0 + fz * fwd, CREST_R});
        }
        return out;
    }

    @Override
    public void die(kr.gave.mob.Damage d) {
        if (dead) return;
        if (pitch != 0) pitch = 0;
        super.die(d);
    }

    /** 시험과 판 뒤 분석용 기록(드문 사건만). */
    void log(String s) {
        game.plugin.getLogger().info("[G133] " + s);
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase;
    }
}
