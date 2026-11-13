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
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G131 묻힌 얼굴 게이브(3막, 방해, 미리 깔림). 트럭에서 36블록 떨어진 땅이 갈라지며 턱까지 묻힌 폭 9, 높이 7 돌빛 얼굴이 이마부터 솟는다. 움직이지 않는다.
 * 12초마다 땅이 울리고 징이 울린 뒤(2초 예고) 눈을 뜨는데, 눈동자가 위로 쏠리면 선 사람이, 아래로 쏠리면 웅크린 사람이 맞는다(눈을 뜬 1.5초 동안 틀린
 * 자세면 거리와 방향에 상관없이 30 과 2초 느려짐). 처음 세 번은 늘 위, 그 뒤로는 뒤섞인다. 눈을 뜬 동안의 눈동자가 약점(x3), 정수리 향 막대기 둘을
 * 꺾으면 눈 뜨는 간격이 20초로 는다. 체력이 30% 아래면 흙을 털고 돌기둥 다리 둘로 일어서 걸어온다(걸음마다 반경 5, 20).
 *
 * <p>팩 계약(tools/gabe/sp/g131.py): gabe/g131(shrink 0.19, 상태 "", "up", "down", 부위 inc0, inc1, risen), 다리 gabe/g131_leg.
 * 소리 gong, rumble, crack, step. 글귀 cause_g131, cause_g131_step, mark_g131_up, mark_g131_down.
 *
 * <p>도감과 다른 점: 조준점 아래 위, 아래 표시는 HUD 작은 표시(글귀 '웅크려', '일어서')로 했다(통합 때 구워짐). 솟는 3초 동안은 안 맞는다.
 */
public final class G131 extends Gabe {

    static final double HOVER = 3.5, RISEN_HOVER = 7.5, LEG_X = 2.6, LEG_L = 4.0, R_PLACE = 36;
    static final double GAZE_DMG = 30, SLOW = 0.3, STEP_R = 5, STEP_DMG = 20, PUPIL_MUL = 3.0, INC_HP = 60, RISE_AT = 0.3, SPEED = 0.1;
    static final int INTERVAL = 240, SLOW_INTERVAL = 400, TELL = 40, SHOW = 20, WINDOW = 30, EMERGE = 60, SLOW_T = 40, FIRST_UP = 3;

    private int nextGaze, gazeAt = -1, gazes, hits;
    private boolean up, risen, emerged;
    private final double[] inc = {-1, -1};
    private boolean incInit;
    private final List<Player> punished = new ArrayList<>();
    private G129Kit.Legs legs;
    private final List<double[]> sph = new ArrayList<>(8);
    private int sphAge = -1;

    public G131(Game game, Species sp) {
        super(game, sp);
        radius = 3.8;
        hover = HOVER;
        baseSpeed = SPEED;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.1;
    }

    @Override
    protected void build() {
        // 높이를 너무 크게 잡으면 닻이 땅속 깊이 놓여 클라가 안 그림(묶음 8 G107): 묻힌 동안 닻은 땅 밑 2.5, 일어서면 땅 위 1.5
        body = Body.of(this, "gabe/g131", 11f, 12f);
        body.viewRange(3.5f);
        G129Kit.bright(body);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        // 트럭(가운데)에서 36블록 떨어진 땅으로(같은 각도), 가운데를 보게
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        double dx = pos.getX() - cx, dz = pos.getZ() - cz, l = Math.hypot(dx, dz);
        if (l < 1e-3) {
            double a = game.random.nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
            l = 1;
        }
        double x = cx + dx / l * R_PLACE, z = cz + dz / l * R_PLACE;
        pos.setX(x).setZ(z).setY(game.mobs.flow.floor(x, z) + HOVER);
        yaw = G129Kit.yawOf(new Vector(cx - x, 0, cz - z));
        body.offset.set(0, (float) -(HOVER * 2 + 0.6), 0);
        body.pose(0);
        body.warp();
        untargetable = true;
        nextGaze = EMERGE + 40;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!incInit) {
            inc[0] = inc[1] = INC_HP * Math.max(0.5, maxHp / Math.max(1, sp.hp));
            incInit = true;
        }
        Player p = nearest(400);
        mark(p);
        if (!emerged) emerge();
        if (risen) walk(p);
        else hold();
        gazeTick();
        if (legs != null) legs.tick();
    }

    /** 땅이 갈라지며 이마부터 솟음(3초). */
    private void emerge() {
        if (age == 3) {
            body.offset.set(0, 0, 0);
            body.pose(EMERGE);
            sound("rumble", Sound.ENTITY_WARDEN_EMERGE, 0.6f, 96);
            G129Kit.ring(this, G129Kit.ground(this, pos), 6, 20);
            if (game.horizon != null) game.horizon.shake(0.4, EMERGE);
        }
        if (age % 10 == 5 && age < EMERGE) G129Kit.ring(this, G129Kit.ground(this, pos), 5 + game.random.nextDouble() * 2, 12);
        if (age >= EMERGE + 4) {
            emerged = true;
            untargetable = false;
        }
    }

    /** 12초(향을 다 꺾으면 20초)마다: 땅울림과 징 → 눈을 뜨며 눈동자가 쏠림(1초) → 1.5초 동안 틀린 자세면 맞음. */
    private void gazeTick() {
        if (gazeAt < 0) {
            if (!emerged || age < nextGaze) return;
            gazeAt = age;
            up = gazes < FIRST_UP || game.random.nextBoolean();
            punished.clear();
            sound("gong", Sound.BLOCK_BELL_USE, 0.5f, 128);
            sound("rumble", Sound.ENTITY_WARDEN_EMERGE, 0.6f, 96);
            if (game.horizon != null) game.horizon.shake(0.5, 20);
            G129Kit.ring(this, G129Kit.ground(this, pos), 7, 16);
            return;
        }
        int t = age - gazeAt;
        if (t == TELL - SHOW) {
            state(up ? "up" : "down");
            for (Player q : game.mobs.standing()) game.hud.mark(q, up ? "mark_g131_up" : "mark_g131_down", SHOW + WINDOW);
        }
        if (t >= TELL && t < TELL + WINDOW) {
            for (Player q : game.mobs.standing()) {
                if (punished.contains(q)) continue;
                boolean sneak = q.isSneaking();
                if (up == sneak) continue;
                punished.add(q);
                hit(q, GAZE_DMG, "cause_g131");
                game.players.slow(q, SLOW, SLOW_T, "g131");
                hits++;
            }
        }
        if (t >= TELL + WINDOW) {
            state("");
            gazes++;
            gazeAt = -1;
            boolean slowGaze = inc[0] <= 0 && inc[1] <= 0;
            nextGaze = age + cd(slowGaze ? SLOW_INTERVAL : INTERVAL) - TELL - WINDOW;
        }
    }

    private boolean eyesOpen() {
        return "up".equals(state) || "down".equals(state);
    }

    // ------------------------------------------------------------------ 일어섬

    private void rise() {
        risen = true;
        body.look.on.add("risen");
        hover = RISEN_HOVER;
        sound("rumble", Sound.ENTITY_WARDEN_EMERGE, 0.6f, 96);
        sound("crack", Sound.BLOCK_STONE_BREAK, 0.6f, 48);
        if (game.horizon != null) game.horizon.shake(0.5, 16);
        G129Kit.ring(this, G129Kit.ground(this, pos), 6, 16);
        legs = new G129Kit.Legs(this, "gabe/g131_leg", LEG_L, new double[] {LEG_X, -LEG_X}, -3.5, 0);
        legs.reach = 1.1;
        legs.lead = 1.0;
        legs.lift = 0.9;
        legs.stance = 0.2;
        legs.swing = 14;
        legs.land = this::landed;
        G129Kit.bright(body);
    }

    private void walk(Player p) {
        if (p == null) {
            follow(speed());
            return;
        }
        if (gazeAt >= 0 && age - gazeAt >= TELL - SHOW) {
            hold();
            return;
        }
        follow(speed());
        G129Kit.faceFlat(this, p.getLocation().toVector());
    }

    private void landed(int i, Vector foot) {
        G129Kit.stomp(this, foot, 64, 12, 0.9f);
        G129Kit.soundAt(this, foot, "step", Sound.BLOCK_STONE_BREAK, 0.9f, 64);
        G129Kit.wave(this, foot, 1, STEP_R, 5);
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (G129Kit.flat(l, foot) <= STEP_R && Math.abs(l.getY() - foot.getY()) < 3) hit(q, STEP_DMG, "cause_g131_step");
        }
        G129Kit.crush(this, foot, 2.0, null, 0);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        add(G129Kit.at(this, 0, 0, 0.3), 4.0);                                   // 0 얼굴
        add(G129Kit.at(this, 2.25, 0.2, 3.3), eyesOpen() ? 0.85 : 0.01);        // 1, 2 눈동자(뜬 동안)
        add(G129Kit.at(this, -2.25, 0.2, 3.3), eyesOpen() ? 0.85 : 0.01);
        add(G129Kit.at(this, 1.4, 4.7, -0.6), inc[0] > 0 ? 0.5 : 0.01);         // 3, 4 향 막대기
        add(G129Kit.at(this, -1.4, 4.7, -0.6), inc[1] > 0 ? 0.5 : 0.01);
        if (legs != null) {
            add(legs.along(0, 0.6), 0.9);
            add(legs.along(1, 0.6), 0.9);
        }
        sphAge = age;
        return sph;
    }

    private void add(Vector w, double r) {
        sph.add(new double[] {w.getX(), w.getY(), w.getZ(), r});
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        boolean ok;
        int best = 0;
        if (d.at() != null) {
            List<double[]> s = spheres();
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                if (c[3] < 0.05) continue;
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
        }
        if ((best == 1 || best == 2) && eyesOpen()) ok = super.damage(d.times(PUPIL_MUL));
        else {
            if ((best == 3 || best == 4) && inc[best - 3] > 0) {
                int j = best - 3;
                inc[j] -= d.amount();
                if (inc[j] <= 0) {
                    body.look.on.add("inc" + j);
                    sound("crack", Sound.BLOCK_STONE_BREAK, 0.8f, 48);
                }
            }
            ok = super.damage(d);
        }
        if (!dead && !risen && hp < maxHp * RISE_AT) rise();
        return ok;
    }

    @Override
    public String toString() {
        return super.toString() + " gazes=" + gazes + " hits=" + hits + " gazing=" + (gazeAt >= 0 ? (up ? "up" : "down") : "-") + " inc="
            + Math.round(inc[0]) + "/" + Math.round(inc[1]) + " risen=" + risen + " next=" + (nextGaze - age);
    }
}
