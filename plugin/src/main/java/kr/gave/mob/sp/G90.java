package kr.gave.mob.sp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G90 등불 나방 게이브(2막, 떼, 목표 압박). 0.4블록 얼굴 양옆에 얼굴 무늬 날개 판 둘, 15~25마리가 갈지자로 팔랑이며 온다.
 *
 * <p>빛을 쫓는다(10틱마다 고름): 8블록 안에서 방금 2초 동안 가장 많이 쏜 사람(총구 불빛, FIRELOG shotsBy)의 얼굴로 달려들고, 없으면 켜진 조명탄,
 * 화염병 불(40블록 안)로 가 그 둘레에 내려앉고, 그것도 없으면 트럭 등불에 내려앉는다. 얼굴에 달려들어 물기 4, 얼굴 앞을 맴돌며 0.5초마다 2(2초,
 * 날개 가루 덮개). 트럭 등불에 앉은 수만큼 등불이 가려지고(LAMP lampCover), 여섯이면 알림, 여덟이면 꺼진다(lampOff): 꺼진 동안 저녁처럼 어둡고
 * (horizon.dusk), 트럭 둘레 게이브가 20% 빨라지며, 트럭 상점이 잠긴다. 앉은 나방을 다 떨어내야 다시 켜진다. 불에 맞으면 둘레 나방에 옮겨 붙는다.
 *
 * <p>팩 계약(tools/gabe/sp/g90.py): 모델 gabe/g90, 상태 ""(날갯짓), "sit"(날개 접음). 소리 flutter. 글귀 cause_g90, notice_g90_lamp,
 * notice_g90_out. 덮개 g90_dust.
 *
 * <p>도감과 다른 점: 등불 꺼짐의 판 단위 일(어둡게, 빨라짐, 다시 켜짐)은 나방 개체가 아니라 판 갈고리({@link Lamp}, FIELDTICK)가 한다(앉은 나방이 모두
 * 죽은 틱에 바로 켜지게). 원정이 꺼져 트럭이 없으면 등불 대신 가장 가까운 사람에게 간다.
 */
public final class G90 extends Gabe {

    static final String KEY = "G90";
    /** 팔랑이며 나는 빠르기, 달려드는 빠르기(블록/틱). */
    static final double SPEED = 0.2, DIVE = 0.42;
    /** 사격 불빛을 보는 반경, 창(틱), 조명탄과 불을 보는 반경. */
    static final double SHOOT_R = 8, LIGHT_R = 40;
    static final int SHOOT_WIN = 40;
    /** 물기, 맴돌기 피해, 맴도는 틱, 맴돌며 때리는 간격, 다시 고르기까지 쉬는 틱. */
    static final double BITE = 4, BUZZ = 2;
    static final int HOVER = 40, BUZZ_EVERY = 10, REST = 30;
    /** 순항 높이(바닥 위), 날개 가루 덮개 틱. */
    static final double CRUISE_Y = 3.2;
    static final int DUST = 40;
    /** 불이 옮겨 붙는 반경. */
    static final double BURN_R = 2.5;

    enum Phase { FLY, DIVE, HOVER, REST, SIT }

    private Phase phase = Phase.FLY;
    private int phaseAt, nextPick, nextFlap;
    private Player prey;
    /** 내려앉을 자리(등불, 조명탄, 불 둘레)와 그것이 등불인가. */
    private Vector perch;
    private boolean onLamp;
    private int seat = -1;
    private final Vector goal = new Vector();
    private static boolean burning;

    public G90(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        flying = true;
        noPush = true;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g90", 1.3f, 0.7f);
    }

    @Override
    protected void spawned() {
        nextFlap = age + game.random.nextInt(20);
        body.look.phase = game.random.nextInt(8);
        Lamp.of(game);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (prey != null && !standing(prey)) {
            prey = null;
            mark(null);
            if (phase == Phase.DIVE || phase == Phase.HOVER) rest();
        }
        switch (phase) {
            case FLY -> fly();
            case DIVE -> dive();
            case HOVER -> hover();
            case REST -> resting();
            case SIT -> sit();
        }
        if (age >= nextFlap && phase != Phase.SIT) {
            sound("flutter", Sound.ENTITY_BAT_TAKEOFF, 1.6f, 16);
            nextFlap = age + 30 + game.random.nextInt(40);
        }
    }

    /** 빛 고르기: 사격 불빛(8블록 안) → 조명탄, 불(40블록 안) → 트럭 등불 → (트럭이 없으면) 가장 가까운 사람. */
    private void pick() {
        nextPick = age + 10;
        Player shooter = brightest();
        if (shooter != null) {
            prey = shooter;
            mark(shooter);
            leavePerch();
            phase(Phase.DIVE);
            return;
        }
        Vector light = nearestLight();
        if (light != null) {
            setPerch(light.clone().add(new Vector((game.random.nextDouble() - 0.5) * 2.4, 0, (game.random.nextDouble() - 0.5) * 2.4)), false);
            return;
        }
        Truck t = Truck.get();
        if (t != null && t.alive()) {
            if (!onLamp || perch == null) setPerch(lampSeat(), true);
            return;
        }
        leavePerch();
        Player p = nearest(400);
        if (p != null && dist(p) <= SHOOT_R) {
            prey = p;
            mark(p);
            phase(Phase.DIVE);
        } else perch = null;
    }

    /** 8블록 안에서 최근 2초 동안 가장 많이 쏜 서 있는 사람(없으면 null). */
    private Player brightest() {
        Player forced = game.mobs.forcedFor(this);
        if (forced != null && dist(forced) <= SHOOT_R) return forced;
        Player best = null;
        int bn = 0;
        for (Player p : game.mobs.standing()) {
            if (dist(p) > SHOOT_R) continue;
            int n = game.mobs.shotsBy(p, SHOOT_WIN);
            if (n > bn) {
                bn = n;
                best = p;
            }
        }
        return best;
    }

    private Vector nearestLight() {
        Vector best = null;
        double bd = LIGHT_R * LIGHT_R;
        for (List<Vector> l : List.of(game.shop.supplies.flares(), game.shop.supplies.fires())) {
            for (Vector v : l) {
                double d = v.distanceSquared(pos);
                if (d < bd) {
                    bd = d;
                    best = v;
                }
            }
        }
        return best;
    }

    private void setPerch(Vector at, boolean lamp) {
        if (!lamp) leaveLamp();
        perch = at;
        onLamp = lamp;
        if (!lamp) {
            double f = game.mobs.flow.floor(at.getX(), at.getZ());
            perch.setY(f + 0.25);
        }
    }

    private void leavePerch() {
        leaveLamp();
        perch = null;
    }

    private void leaveLamp() {
        if (onLamp) Lamp.of(game).off(this);
        onLamp = false;
        seat = -1;
    }

    /** 트럭 지붕 등불 둘레의 앉을 자리(개체마다 하나). */
    private Vector lampSeat() {
        if (seat < 0) seat = Lamp.of(game).seat();
        Vector c = Truck.center();
        int i = seat % 12;
        double a = i * Math.PI * 2 / 12 + (seat / 12) * 0.26;
        double r = 0.35 + 0.18 * (i % 3);
        return new Vector(Truck.OX + Math.cos(a) * r, c.getY() + Truck.HEIGHT + 0.12 + 0.06 * (seat / 12), Truck.Z0 + 1.5 + Math.sin(a) * r * 1.4);
    }

    /** 갈지자로 팔랑이며 목표 쪽으로(높이는 멀면 순항, 가까우면 목표 높이). */
    private void fly() {
        state("");
        if (age >= nextPick) pick();
        if (phase != Phase.FLY) return;
        Vector to;
        if (perch != null) to = perch;
        else {
            Player p = nearest(400);
            to = p != null ? chest(p).add(new Vector(0, 1.0, 0)) : new Vector(kr.gave.world.Plaza.CX + 0.5, kr.gave.world.Plaza.FLOOR_Y + 3, kr.gave.world.Plaza.CZ + 0.5);
        }
        double h = Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ());
        goal.copy(to);
        if (h > 10) goal.setY(Math.max(to.getY(), floor() + CRUISE_Y));
        flutter(goal, speed());
        if (perch != null && pos.distanceSquared(perch) < 0.25 * 0.25 + 0.04) {
            hold();
            pos.copy(perch);
            if (onLamp) Lamp.of(game).on(this);
            phase(Phase.SIT);
        }
    }

    /** 갈지자: 목표 쪽 + 옆 흔들림 + 위아래 출렁. 가까우면 흔들림을 줄여 내려앉는다. */
    private void flutter(Vector to, double v) {
        Vector d = to.clone().subtract(pos);
        double l = d.length();
        if (l < 1e-6) {
            hold();
            return;
        }
        d.multiply(1 / l);
        double near = Math.min(1, l / 3);
        Vector side = new Vector(-d.getZ(), 0, d.getX());
        if (side.lengthSquared() > 1e-6) side.normalize();
        double t = (age + id * 7) * 0.28;
        vel.copy(d.multiply(Math.min(v, l))).add(side.multiply(Math.sin(t) * v * 0.9 * near)).add(new Vector(0, Math.sin(t * 1.7 + id) * v * 0.5 * near, 0));
        face(pos.clone().add(new Vector(vel.getX(), 0, vel.getZ())));
    }

    /** 사격 불빛 쪽 얼굴로 곧장 달려듦. */
    private void dive() {
        state("");
        if (prey == null) {
            rest();
            return;
        }
        Location eye = prey.getEyeLocation();
        Vector front = eye.toVector().add(eye.getDirection().setY(0).normalize().multiply(0.45));
        flutter(front, DIVE * speed() / SPEED);
        face(eye.toVector());
        if (pos.distanceSquared(front) < 0.6 * 0.6) {
            hit(prey, BITE, "cause_g90");
            game.players.cover(prey, "g90_dust", DUST);
            phase(Phase.HOVER);
        } else if (inPhase() > 100 || dist(prey) > SHOOT_R * 2.5) rest();
    }

    /** 얼굴 앞 0.6블록에서 맴돌며 0.5초마다 2. */
    private void hover() {
        if (prey == null || inPhase() >= HOVER) {
            rest();
            return;
        }
        Location eye = prey.getEyeLocation();
        Vector f = eye.getDirection().setY(0);
        if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
        f.normalize();
        double a = (age + id) * 0.5;
        Vector side = new Vector(-f.getZ(), 0, f.getX());
        Vector at = eye.toVector().add(f.multiply(0.6)).add(side.multiply(Math.cos(a) * 0.3)).add(new Vector(0, Math.sin(a) * 0.2, 0));
        vel.copy(at.subtract(pos).multiply(0.6));
        face(eye.toVector());
        if (inPhase() % BUZZ_EVERY == BUZZ_EVERY - 1 && dist(prey) < 1.8) hit(prey, BUZZ, "cause_g90");
    }

    private void rest() {
        mark(null);
        prey = null;
        phase(Phase.REST);
    }

    /** 위로 물러나 쉬었다 다시 고름. */
    private void resting() {
        state("");
        Vector up = pos.clone().add(new Vector(Math.sin(id) * 2, 0, Math.cos(id) * 2));
        up.setY(floor() + CRUISE_Y);
        flutter(up, speed());
        if (inPhase() >= cd(REST)) {
            nextPick = age;
            phase(Phase.FLY);
        }
    }

    /** 앉음: 등불이나 조명탄 둘레. 등불에 앉은 것은 조명탄이 켜지면 그쪽으로, 조명탄 둘레는 조명탄이 꺼지면 다시 고른다. */
    private void sit() {
        state("sit");
        hold();
        if (perch != null) pos.copy(perch);
        if (!every(10)) return;
        if (onLamp) {
            Truck t = Truck.get();
            Vector light = nearestLight();
            Player shooter = brightest();
            if (t == null || !t.alive() || light != null || (shooter != null && game.random.nextDouble() < 0.15)) {
                leavePerch();
                phase(Phase.FLY);
                nextPick = age;
            }
        } else if (nearestLight() == null || brightest() != null) {
            leavePerch();
            phase(Phase.FLY);
            nextPick = age;
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        boolean r = super.damage(d);
        // 불: 둘레 나방에 옮겨 붙음(한 번에 한 겹)
        if (r && dead && d.type() == DamageType.FIRE && !burning) {
            burning = true;
            try {
                for (Gabe g : game.mobs.near(pos, BURN_R, x -> x != this && !x.dead && x instanceof G90))
                    g.damage(new Damage(g.hp + 1, DamageType.FIRE, d.by(), g.pos.clone(), d.dir(), d.how(), 0));
            } finally {
                burning = false;
            }
        }
        return r;
    }

    @Override
    public void die(Damage d) {
        leavePerch();
        super.die(d);
    }

    @Override
    public void remove() {
        leavePerch();
        super.remove();
    }

    @Override
    public String toString() {
        Truck t = Truck.get();
        return super.toString() + " ph=" + phase + (onLamp ? " onLamp" : "") + " lampN=" + Lamp.of(game).sitting.size()
            + (t == null ? " truck=-" : " lamp=" + t.lampLevel() + (t.lampDown() ? " down" : "") + (t.lampLocked() ? " locked" : ""));
    }

    // ------------------------------------------------------------------ 판 갈고리: 트럭 등불

    /**
     * 트럭 등불에 앉은 나방 셈(FIELDTICK, 키 "G90"). 앉은 수를 Truck.lampCover 로, 여덟이면 lampOff, 꺼진 동안 어둡게와 트럭 둘레 게이브 빠르기,
     * 앉은 것이 하나도 없으면 다시 켬. 앉은 것이 없고 켜져 있으면 스스로 떨어진다.
     */
    static final class Lamp implements Mobs.Field {
        final Game game;
        final Set<G90> sitting = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean off, warned;
        int sent = -1, seats;

        Lamp(Game game) {
            this.game = game;
        }

        static Lamp of(Game game) {
            if (game.mobs.field(KEY) instanceof Lamp l) return l;
            Lamp l = new Lamp(game);
            game.mobs.field(KEY, l);
            return l;
        }

        int seat() {
            return seats++ % 36;
        }

        void on(G90 g) {
            sitting.add(g);
        }

        void off(G90 g) {
            sitting.remove(g);
        }

        @Override
        public boolean tick(long now) {
            sitting.removeIf(g -> g.dead);
            int n = sitting.size();
            Truck t = Truck.get();
            if (t == null) {
                off = warned = false;
                sent = -1;
                return n > 0;
            }
            if (n >= 6 && !warned) {
                warned = true;
                game.hud.notice("notice_g90_lamp", Species.G90, 60);
            }
            if (n >= Truck.LAMP_COVER_FULL && !off) {
                off = true;
                t.lampOff(KEY, true);
                game.hud.alert("notice_g90_out", 3, 80);
            }
            if (off && n == 0) {
                off = false;
                warned = false;
                t.lampOff(KEY, false);
            }
            int cover = off ? Math.max(Truck.LAMP_COVER_FULL, n) : n;
            if (cover != sent) {
                t.lampCover(KEY, cover);
                sent = cover;
            }
            if (off) {
                if (now % 20 == 0) game.horizon.dusk(3500, 40);
                for (Gabe g : game.mobs.all()) {
                    if (g.dead || g.sp.is(Role.BOSS)) continue;
                    if (Truck.dist(g.pos.getX(), g.pos.getZ()) <= 12) g.speedBuff *= 1.2;
                }
            }
            if (n == 0 && !off) {
                if (sent > 0) t.lampCover(KEY, 0);
                sent = -1;
                warned = false;
                return false;
            }
            return true;
        }

        @Override
        public void reset() {
            sitting.clear();
            off = warned = false;
            sent = -1;
        }
    }
}
