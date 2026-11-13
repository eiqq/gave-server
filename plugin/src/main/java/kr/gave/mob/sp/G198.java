package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G198 나란히 걷는 거인 게이브(4막 34라운드, 튼튼이 + 방패). 키 13블록 거인 일곱이 어깨를 엔드 막대기로 꿰어 한 줄(간격 5.7, 줄 폭 약 40)로 서서
 * 지평선 한쪽(R 86)에서 3초 서 있다가 싸움터 한가운데를 지나 반대편 지평선까지 곧게 걷는다(0.09, 멈추지도 돌아보지도 않음, 흐름장 안 씀, 다른 게이브와 겹침).
 * 다리는 부품 둘(G129Kit.Legs): 발을 들 때 디딜 자리에 붉은 원이 자라며(발바닥이 닿기 전 한 박자), 디디면 반경 2.5 안 45 + 1초 묶임, 발밑 값싼 게이브는
 * 밟혀 튐. 줄 앞 6블록 안에 선 사람은 발로 차 26 과 크게 날림. 두 발 사이(가랑이 밑, 수평 1.5블록)는 밟기와 차기에서 빠진다: 거기 들어가야 산다.
 * 줄의 걸음마다 발맞춘 발소리(줄에 하나)와 화면 흔들림. 가운데 길의 거인이 우주선(트럭)을 들이받으면 한 번 6%, 몸이 걸쳐 있는 동안 초당 1%.
 * 무릎(x1.5)을 맞은 거인은 발밑을 내려다보며 눈을 뜬다(3초). 어깨 막대기(체력 80, Mobs.Shootable)를 양쪽 다 끊긴 거인은 비틀거려 받는 피해 x2.
 * 쓰러지면 앞으로 넘어지며 길이 12 띠에 40. 웨이브를 붙들지 않고(noHold) 끝 정리에서도 빠져(persist) 반대편으로 걸어 나가 사라진다(쉬는 시간엔 공격 없음).
 *
 * <p>팩 계약(tools/gabe/sp/g198.py): gabe/g198(shrink 0.28, 상태 "", "look"), gabe/g198_leg(shrink 0.22, 부위 hurt), gabe/g198_rod(shrink 0.4, "", "cut").
 * 소리 march(96), fall(96), kick(32). 글귀 cause_g198, cause_g198_kick, cause_g198_fall.
 *
 * <p>도감과 다른 점: 감독이 줄로 놓는 자리 주기가 없어 첫 개체(가운데, 번호 3)가 spawned() 에서 나머지 여섯을 spawnLike 로 옆에 놓는다. 한 웨이브에
 * 줄 하나(줄이 살아 있으면 더 나온 개체는 그 자리에서 치움). 걸음 박자는 다리 도우미의 보폭(5.4블록, 한 발에 약 60틱, 두 발 번갈아 약 30틱)에서 나온다.
 */
public final class G198 extends Gabe {

    static final double HOVER = 8.0, HIP_X = 1.0, HIP_Y = -2.2, LEG_L = 6.0, SHOULDER_X = 1.45, SHOULDER_Y = 1.75, HEAD_Y = 3.75;
    static final double SPEED = 0.09, SPACING = 5.7, START_R = 86, END_R = 88, REACH = 2.7;
    static final double STOMP_R = 2.5, STOMP_DMG = 45, KICK_R = 6, KICK_DMG = 26, CROTCH_R = 1.5, KNEE_MUL = 1.5, STAGGER_MUL = 2.0;
    static final double ROD_HP = 80, ROD_R = 0.4, FALL_LEN = 12, FALL_W = 3, FALL_DMG = 40, TRUCK_RAM = 0.06;
    static final int N = 7, MID = 3, INTRO_T = 60, ROOT_T = 20, LOOK_T = 60;

    /** 한 줄(일곱). 가운데가 처음 놓인 개체. */
    static final class Line {
        final G198[] m = new G198[N];
        final Rod[] rods = new Rod[N - 1];
        final Vector f = new Vector(), l = new Vector(), start = new Vector();
        double travel;
        long tickAt = -1, startAt;
        int wave;

        Vector spot(int i) {
            return start.clone().add(f.clone().multiply(travel)).add(l.clone().multiply((i - MID) * SPACING));
        }

        boolean alive() {
            for (G198 g : m) if (g != null && !g.dead) return true;
            return false;
        }
    }

    private static Line pending;
    private static int pendingIdx = -1;
    private static Line current;

    private Line line;
    private int idx = MID, lookUntil, kickAt, steps;
    private boolean rammed;
    private int truckAt;
    private G129Kit.Legs legs;
    private Body.Part rodPart;
    private final List<double[]> sph = new ArrayList<>(10);
    private int sphAge = -1;

    public G198(Game game, Species sp) {
        super(game, sp);
        radius = 1.6;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        noHold = true;
        persist = true;
        ragKick = 0.2;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g198", 6f, 14f);
        body.viewRange(4f);
        legs = new G129Kit.Legs(this, "gabe/g198_leg", LEG_L, new double[] {HIP_X, -HIP_X}, HIP_Y, 0);
        legs.reach = REACH;
        legs.lead = REACH;
        legs.lift = 1.6;
        legs.stance = 0.5;
        legs.swing = 24;
        legs.land = this::landed;
        legs.lifted = this::lifted;
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        if (pending != null) {
            line = pending;
            idx = pendingIdx;
        } else if (current != null && current.alive() && current.wave == game.wave) {
            remove();   // 한 웨이브에 줄 하나
            return;
        } else {
            line = new Line();
            current = line;
            line.wave = game.wave;
            Vector c = new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
            Vector d = c.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 1e-4) d = new Vector(0, 0, 1);
            d.normalize();
            line.f.copy(d);
            line.l.setX(d.getZ()).setY(0).setZ(-d.getX());
            line.start.copy(c.clone().subtract(d.clone().multiply(START_R)));
            line.startAt = now();
            idx = MID;
        }
        line.m[idx] = this;
        Vector at = line.spot(idx);
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        game.mobs.relocate(this, at);
        G66Giant.faceFlat(this, at.clone().add(line.f));
        if (idx == MID && pending == null) {
            for (int i = 0; i < N; i++) {
                if (i == MID) continue;
                pending = line;
                pendingIdx = i;
                try {
                    Vector s = line.spot(i);
                    game.director.spawnLike(sp, new Vector(s.getX(), game.mobs.flow.floor(s.getX(), s.getZ()) + HOVER, s.getZ()));
                } finally {
                    pending = null;
                    pendingIdx = -1;
                }
            }
            // 어깨 막대기: i 번 왼쪽 어깨 → i+1 번 오른쪽 어깨
            for (int i = 0; i < N - 1; i++) {
                G198 a = line.m[i], b = line.m[i + 1];
                if (a == null || b == null) continue;
                Rod r = new Rod(a, b);
                line.rods[i] = r;
                game.mobs.addShootable(r);
            }
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (line == null) {
            hold();
            return;
        }
        long now = now();
        if (line.tickAt != now) {
            line.tickAt = now;
            if (now - line.startAt >= INTRO_T) line.travel += SPEED;
        }
        Player near = nearest(400);
        mark(near != null && dist(near) < 40 ? near : null);
        Vector to = line.spot(idx);
        to.setY(pos.getY());
        Vector d = to.clone().subtract(pos);
        double dl = d.length();
        if (dl > 1e-4) vel.copy(d.multiply(Math.min(dl, SPEED * 3) / dl));
        else vel.zero();
        G66Giant.faceFlat(this, pos.clone().add(line.f));
        legs.tick();
        if (lookUntil > 0 && age >= lookUntil) {
            lookUntil = 0;
            state("");
            body.look.open = false;
        }
        truckBody();
        if (line.travel > START_R + END_R) remove();
    }

    private boolean fighting() {
        return game.state == Game.State.WAVE;
    }

    /** 가랑이 밑(두 엉덩이 가운데 아래 바닥, 수평 CROTCH_R 안)인가. */
    private boolean underCrotch(Location l) {
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) <= CROTCH_R;
    }

    private void lifted(int i, Vector to, int ticks) {
        G129Kit.plate(this, "gabe/b1_warn_disc", "fill", to, STOMP_R, ticks + 4, ticks);
    }

    private void landed(int i, Vector foot) {
        steps++;
        G129Kit.stomp(this, foot, 96, 18, idx == MID ? 1f : 0.6f);
        if (idx == MID || line.m[MID] == null || line.m[MID].dead) {
            G129Kit.soundAt(this, foot, "march", Sound.ENTITY_RAVAGER_STEP, 1f, 96);
            boolean near = false;
            for (Player q : game.mobs.standing()) if (G129Kit.flat(q.getLocation(), pos) < 70) near = true;
            if (near && game.horizon != null) game.horizon.shake(0.6, 8);
        }
        G129Kit.crush(this, foot, STOMP_R, null, 0);
        if (!fighting()) return;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.abs(l.getY() - foot.getY()) > 3 || underCrotch(l)) continue;
            if (G129Kit.flat(l, foot) <= STOMP_R) {
                hit(q, STOMP_DMG, "cause_g198");
                game.players.root(q, ROOT_T, "g198");
            }
        }
        kick();
        Truck t = Truck.get();
        if (t != null && t.alive() && Truck.dist(foot.getX(), foot.getZ()) < 2.0 && !rammed) {
            rammed = true;
            t.hitFrac(TRUCK_RAM, this, "cause_g198");
            if (game.horizon != null) game.horizon.shake(0.8, 14);
        }
    }

    /** 줄 앞 6블록 안 가장 가까운 사람을 발로 참(가랑이 밑 빼고, 이 거인 몫 폭 안). */
    private void kick() {
        if (age < kickAt) return;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (underCrotch(l) || Math.abs(l.getY() - (pos.getY() - HOVER)) > 3) continue;
            double rx = l.getX() - pos.getX(), rz = l.getZ() - pos.getZ();
            double along = rx * line.f.getX() + rz * line.f.getZ();
            double lat = Math.abs(rx * line.l.getX() + rz * line.l.getZ());
            if (along <= 0.3 || along > KICK_R || lat > SPACING / 2) continue;
            if (along < bd) {
                bd = along;
                best = q;
            }
        }
        if (best == null) return;
        kickAt = age + cd(30);
        hit(best, KICK_DMG, "cause_g198_kick");
        knock(best, line.f, 1.5, 0.65);
        G129Kit.soundAt(this, best.getLocation().toVector(), "kick", Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 32);
    }

    private void truckBody() {
        if (age < truckAt || !fighting()) return;
        Truck t = Truck.get();
        if (t == null || !t.alive() || Truck.dist(pos.getX(), pos.getZ()) > 2.2) return;
        t.hitFrac(0.01, this, "cause_g198");
        truckAt = age + 20;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G129Kit.at(this, 0, HEAD_Y, 0), 1.7);          // 0 머리
        add(G129Kit.at(this, 0, 0.9, 0), 1.45);            // 1 몸통 위
        add(G129Kit.at(this, 0, -1.2, 0), 1.45);           // 2 몸통 아래
        if (legs != null) {
            add(legs.knee(0), 0.75);                       // 3 무릎
            add(legs.knee(1), 0.75);                       // 4
            for (int i = 0; i < 2; i++) {
                add(legs.along(i, 0.22), 0.6);             // 5..8 다리
                add(legs.along(i, 0.8), 0.6);
            }
        }
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    private boolean staggered() {
        if (line == null) return false;
        boolean leftCut = idx >= N - 1 || line.rods[idx] == null || line.rods[idx].cut;
        boolean rightCut = idx <= 0 || line.rods[idx - 1] == null || line.rods[idx - 1].cut;
        boolean any = (idx < N - 1 && line.rods[idx] != null) || (idx > 0 && line.rods[idx - 1] != null);
        return any && leftCut && rightCut;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        double k = staggered() ? STAGGER_MUL : 1;
        if (d.at() != null) {
            List<double[]> s = spheres();
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best == 3 || best == 4) {
                k *= KNEE_MUL;
                Body.Part pt = legs.part[best - 3];
                if (pt != null) pt.look.on.add("hurt");
                if (lookUntil == 0) {
                    state("look");
                    body.look.open = true;
                }
                lookUntil = age + LOOK_T;
            }
        }
        return super.damage(k == 1 ? d : d.times(k));
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (line != null) {
            for (Rod r : line.rods) if (r != null && (r.a == this || r.b == this)) r.drop();
        }
        if (fighting() && !noReward) fall();
        super.die(d);
    }

    @Override
    public void remove() {
        if (line != null) {
            for (Rod r : line.rods) if (r != null && (r.a == this || r.b == this)) r.drop();
        }
        super.remove();
    }

    /** 앞으로 넘어짐: 길이 12 띠 예고, 12틱 뒤 띠 위 40. */
    private void fall() {
        Vector f = line != null ? line.f.clone() : G129Kit.fwd(this);
        Vector from = G129Kit.ground(this, pos);
        Vector to = from.clone().add(f.clone().multiply(FALL_LEN));
        G129Kit.strip(this, from, to, FALL_W, 16, true);
        final long start = now();
        final G198 self = this;
        game.mobs.field(new Object(), new Mobs.Field() {
            @Override
            public boolean tick(long now) {
                if (now - start < 12) return true;
                for (Player q : game.mobs.standing()) {
                    Location l = q.getLocation();
                    double rx = l.getX() - from.getX(), rz = l.getZ() - from.getZ();
                    double along = rx * f.getX() + rz * f.getZ(), lat = Math.abs(rx * f.getZ() - rz * f.getX());
                    if (along >= -1 && along <= FALL_LEN && lat <= FALL_W / 2 + 0.4 && Math.abs(l.getY() - from.getY()) < 4)
                        self.hit(q, FALL_DMG, "cause_g198_fall");
                }
                G129Kit.stomp(self, to, 96, 20, 1f);
                G129Kit.soundAt(self, to, "fall", Sound.ENTITY_GENERIC_EXPLODE, 1f, 96);
                G129Kit.wave(self, to, 1.0, 7, 7);
                if (game.horizon != null) game.horizon.shake(0.6, 16);
                return false;
            }
        });
    }

    @Override
    public String toString() {
        return super.toString() + " idx=" + idx + " travel=" + (line == null ? -1 : Math.round(line.travel)) + " steps=" + steps + " stagger=" + staggered()
            + " rammed=" + rammed;
    }

    // ------------------------------------------------------------------ 어깨 막대기(쏠 수 있는 것)

    /** 이웃 둘의 어깨를 꿴 막대기: a 의 부품으로 그리고, 탄은 a 왼쪽 어깨 → b 오른쪽 어깨 캡슐로 받는다. */
    final class Rod implements Mobs.Shootable {
        final G198 a, b;
        final Body.Part part;
        double hp = ROD_HP;
        boolean cut, gone;

        Rod(G198 a, G198 b) {
            this.a = a;
            this.b = b;
            this.part = a.body.attach("gabe/g198_rod");
            if (part != null) part.local.translation((float) SHOULDER_X, (float) SHOULDER_Y, 0f);
        }

        Vector p0() {
            return G129Kit.at(a, SHOULDER_X, SHOULDER_Y, 0);
        }

        Vector p1() {
            return G129Kit.at(b, -SHOULDER_X, SHOULDER_Y, 0);
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (cut || gone()) return Double.POSITIVE_INFINITY;
            Vector p = p0(), q = p1();
            Vector u = q.clone().subtract(p);
            double len = u.length();
            if (len < 1e-4) return Double.POSITIVE_INFINITY;
            u.multiply(1 / len);
            Vector w0 = o.clone().subtract(p);
            double bb = dir.dot(u), dd = dir.dot(w0), ee = u.dot(w0);
            double den = 1 - bb * bb;
            double t = den < 1e-6 ? 0 : (bb * ee - dd) / den;
            double s = Math.max(0, Math.min(len, ee + bb * t));
            t = Math.max(0, s * bb - dd);
            Vector onRay = o.clone().add(dir.clone().multiply(t)), onSeg = p.clone().add(u.clone().multiply(s));
            if (onRay.distance(onSeg) > ROD_R || t > max) return Double.POSITIVE_INFINITY;
            return t;
        }

        @Override
        public boolean shot(Damage d) {
            if (cut) return false;
            hp -= d.amount();
            if (hp <= 0) {
                cut = true;
                if (part != null) part.state("cut");
                G129Kit.soundAt(a, center(), "kick", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 48);
            }
            return false;
        }

        @Override
        public Vector center() {
            return p0().add(p1()).multiply(0.5);
        }

        @Override
        public boolean gone() {
            return gone || cut || a.dead || b.dead;
        }

        void drop() {
            gone = true;
            if (part != null && !a.dead) part.remove();
        }
    }
}
