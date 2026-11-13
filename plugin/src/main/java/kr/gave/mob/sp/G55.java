package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G55 가짜 종소리 게이브(방해, 지원, 미리 깔림).
 *
 * <p>도감 + 황야(docs/모듈/맵변경_황야.md): 종처럼 거꾸로 선 얼굴이 중앙 원에서 15블록 넘게 떨어진 땅에 반쯤 묻혀 거의 움직이지 않는다.
 * 웨이브 막바지에 남은 수가 적어지면 '웨이브 끝' 종소리와 상점 여는 소리를 제 자리에서 흉내 내고(한쪽에서 들림, 자막 화살표),
 * 남은 수 HUD 를 0 으로 흉내 낸다({@code Hud.fakeRemaining}, 진짜 수가 0.5초마다 비침, 종료 글리프는 없음). 남은 게이브들은 소리를 멈추고
 * 안개 너머(가장자리 고리)로 물러났다가 10초 뒤 일제히 웃으며 빠르게 덮친다. 자신은 약하다. 잡으면 물러난 게이브가 바로 돌아오고
 * 그 자리가 HUD 화살촉으로 드러난다.
 *
 * <p>물러남은 엔진 끌림({@code Gabe.lure})을 2틱마다 4틱씩 다시 걸어 한다: 끌리는 동안 그 종의 tick 이 돌지 않아 소리도 멈추고,
 * 이 개체가 죽으면 4틱 안에 풀린다. 사람을 붙잡고 있는 개체, 흉내 중인 개체, 가만히 깔린 종은 건드리지 않는다.
 *
 * <p>팩 계약(tools/gabe/sp/g55.py): 모델 gave:gabe/g55, 상태 "" 묻혀 있음, "ring" 추가 맞부딪힘. 소리 toll(96), door(96), titter(48), clack(12).
 * 글귀 cause_g55(피해 없음, 쓰이지 않음), notice_g55_found.
 */
public final class G55 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 땅에 반쯤 묻힘: 바닥에서 몸 중심 높이. */
    static final double HOVER = 0.12;
    /** 놓이는 자리: 중앙 원 테두리에서 이만큼 넘게, 사람이 갈 수 있는 끝(48) 안쪽. 사람에게서도 이만큼. */
    static final double OUT_OF_CIRCLE = 15, PLACE_MAX = 45, PLAYER_FAR = 15;
    /** 놓인 뒤 이 틱은 울리지 않음, 종 울리는 틱, 그 안에서 상점 소리 틱, 물러나 있는 틱(10초), 덮치는 틱과 빠르기 배율. */
    static final int ARM_AFTER = 100, RING_T = 40, DOOR_AT = 14, HIDE_T = 200, RUSH_T = 160;
    static final double RUSH = 1.5, RETREAT = 1.8;
    /** 남은 수가 이 아래(또는 총량의 이 비율 아래)이고 물러날 게이브가 이만큼은 있을 때 울린다. */
    static final int FEW = 5, MIN_OTHERS = 2;
    static final double FEW_FRAC = 0.2;
    /** 끌림을 다시 거는 간격과 길이. */
    static final int LURE_EVERY = 2, LURE_T = 4;

    enum Phase { BURIED, RING, HIDE, RUSH, SPENT }

    private Phase phase = Phase.BURIED;
    private int phaseAt, nextClack;
    private final Map<Gabe, Vector> hidden = new HashMap<>();

    public G55(Game game, Species sp) {
        super(game, sp);
        radius = 0.4;
        hover = HOVER;
        baseSpeed = 0;
        noPush = true;
        noBite = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g55", 1.6f, 1.6f);
    }

    @Override
    protected void spawned() {
        nextClack = age + 60 + game.random.nextInt(80);
        if (game.director.cageSpawning()) return;
        // 중앙 원에서 15블록 넘게 떨어진 땅으로(감독이 준 자리가 맞으면 그대로)
        if (!goodSpot(pos.getX(), pos.getZ(), false)) {
            for (int tries = 0; tries < 40; tries++) {
                double a = game.random.nextDouble() * Math.PI * 2;
                double r = Plaza.R_SAFE + OUT_OF_CIRCLE + 1 + game.random.nextDouble() * (PLACE_MAX - Plaza.R_SAFE - OUT_OF_CIRCLE - 1);
                double x = Plaza.CX + 0.5 + Math.cos(a) * r, z = Plaza.CZ + 0.5 + Math.sin(a) * r;
                if (!goodSpot(x, z, tries < 30)) continue;
                pos.setX(x).setZ(z);
                break;
            }
        }
        pos.setY(floor() + HOVER);
        // 얼굴은 가장 가까운 사람 쪽(없으면 중앙 원 쪽)
        Player n = game.mobs.nearestPlayer(pos, 200);
        face(n != null ? new Vector(n.getLocation().getX(), pos.getY(), n.getLocation().getZ()) : new Vector(Plaza.CX + 0.5, pos.getY(), Plaza.CZ + 0.5));
        pitch = 0;
    }

    /** 중앙 원에서 15블록 넘게 떨어진 걷는 칸인가. farFromPeople 이면 사람에게서도 15블록 넘게(감독이 준 자리는 이미 12블록 넘게라 안 봄). */
    private boolean goodSpot(double x, double z, boolean farFromPeople) {
        double r = Math.hypot(x - Plaza.CX - 0.5, z - Plaza.CZ - 0.5);
        if (r < Plaza.R_SAFE + OUT_OF_CIRCLE || r > PLACE_MAX + 1) return false;
        if (!game.mobs.flow.walkable(x, z)) return false;
        if (!farFromPeople) return true;
        for (Player p : game.players.standing()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - x, l.getZ() - z) < PLAYER_FAR) return false;
        }
        return true;
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
        hold();
        switch (phase) {
            case BURIED -> buried();
            case RING -> ring();
            case HIDE -> hide();
            case RUSH -> rush();
            case SPENT -> state("");
        }
    }

    /** 묻혀 있음: 가까이서만 추가 딱. 웨이브 막바지를 기다린다. */
    private void buried() {
        state("");
        if (age >= nextClack) {
            nextClack = age + 90 + game.random.nextInt(60);
            if (nearest(12) != null) sound("clack", Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.7f, 12);
        }
        if (age < ARM_AFTER || !every(10) || game.state != Game.State.WAVE || game.director.inCage(this)) return;
        if (game.players.standing().isEmpty()) return;
        // 다른 가짜 종이 이미 울리는 중이면 기다림
        for (Gabe g : game.mobs.all()) if (g instanceof G55 o && o != this && !o.dead && (o.phase == Phase.RING || o.phase == Phase.HIDE)) return;
        int left = game.director.remaining(), total = game.director.total();
        if (left > Math.max(FEW, (int) Math.ceil(total * FEW_FRAC))) return;
        List<Gabe> others = retreaters();
        // 대기열이 남았으면(남은 수가 산 것보다 많음) 아직 막바지가 아님
        int alive = 0;
        for (Gabe g : game.mobs.all()) if (!g.dead && !game.director.inCage(g)) alive++;
        if (left > alive || others.size() < MIN_OTHERS) return;
        startRing(others);
    }

    /** 물러나게 할 게이브: 가만히 깔린 종, 흉내 중인 종, 사람을 붙잡은 개체, 같은 종은 뺌. */
    private List<Gabe> retreaters() {
        List<Gabe> out = new ArrayList<>();
        List<Player> grabbed = game.players.grabbed();
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || g.removed || g instanceof G55 || g.disguised || g.noRules || game.director.inCage(g)) continue;
            if (g.sp.spawn == Species.Spawn.PLACED && g.target == null) continue;
            boolean holding = false;
            for (Player p : grabbed) if (game.players.grabbedBy(p) == g) holding = true;
            if (holding || !game.players.trappedIn(g).isEmpty()) continue;
            out.add(g);
        }
        return out;
    }

    private void startRing(List<Gabe> others) {
        hidden.clear();
        double rr = (Plaza.R_SPAWN_IN + Plaza.R_SPAWN_OUT) / 2.0;
        double cx = Plaza.CX + 0.5, cz = Plaza.CZ + 0.5;
        for (Gabe g : others) {
            // 가장 가까운 사람에게서 멀어지는 쪽으로 곧장, 그 선이 안개 너머 고리(반지름 rr)와 만나는 곳까지(사람 곁을 지나 물러나지 않게)
            double gx = g.pos.getX(), gz = g.pos.getZ();
            Player n = game.mobs.nearestPlayer(g.pos, 400);
            double dx, dz;
            if (n != null) {
                dx = gx - n.getLocation().getX();
                dz = gz - n.getLocation().getZ();
            } else {
                dx = gx - cx;
                dz = gz - cz;
            }
            double d = Math.hypot(dx, dz);
            if (d < 1e-3) {
                double a = game.random.nextDouble() * Math.PI * 2;
                dx = Math.cos(a);
                dz = Math.sin(a);
                d = 1;
            }
            dx /= d;
            dz /= d;
            // g + t(dx, dz) 가 가운데에서 rr 인 t(앞쪽 근)
            double ox = gx - cx, oz = gz - cz, b = ox * dx + oz * dz, c = ox * ox + oz * oz - rr * rr;
            double t = -b + Math.sqrt(Math.max(0, b * b - c));
            hidden.put(g, new Vector(gx + dx * t, g.pos.getY(), gz + dz * t));
        }
        state("ring");
        sound("toll", Sound.BLOCK_BELL_USE, 1f, 96);
        game.hud.fakeRemaining(HIDE_T + 5);
        phase(Phase.RING);
        lureAll();
    }

    /** 종이 울림: 가짜 종소리, 조금 뒤 상점 여는 소리. 게이브들은 이미 물러나기 시작. */
    private void ring() {
        state("ring");
        int t = inPhase();
        if (t == DOOR_AT) sound("door", Sound.BLOCK_BARREL_OPEN, 0.9f, 96);
        if (t % LURE_EVERY == 0) lureAll();
        if (t >= RING_T) phase(Phase.HIDE);
    }

    /** 물러나 있음(10초): 조용히. */
    private void hide() {
        state("");
        int t = inPhase();
        if (t % LURE_EVERY == 0) lureAll();
        if (t >= HIDE_T - RING_T) ambush();
    }

    private void lureAll() {
        for (Map.Entry<Gabe, Vector> e : hidden.entrySet()) {
            Gabe g = e.getKey();
            if (g.dead || g.removed) continue;
            // 서둘러 물러남(안개 너머까지 10초 안에 닿게)
            g.speedBuff = Math.max(g.speedBuff, RETREAT);
            try {
                g.lure(e.getValue(), LURE_T);
            } catch (RuntimeException ignored) {
                // 그 종의 끌림 코드가 던져도 나머지는 계속
            }
        }
    }

    /** 일제히 웃으며 덮침: 물러났던 자리마다 웃음, 한동안 빠르게. 가짜 0 은 걷힘. */
    private void ambush() {
        game.hud.fakeRemaining(0);
        if (!game.director.silent()) {
            int n = 0;
            for (Gabe g : hidden.keySet()) {
                if (g.dead || g.removed) continue;
                Props.soundAround(g.loc(), "gabe.g55.titter", Sound.ENTITY_WITCH_CELEBRATE, 0.9f, 0.94f + game.random.nextFloat() * 0.12f, 1.2f, 48, -1);
                if (++n >= 6) break;
            }
        }
        state("ring");
        phase(Phase.RUSH);
    }

    private void rush() {
        int t = inPhase();
        state(t < 30 ? "ring" : "");
        boolean any = false;
        for (Gabe g : hidden.keySet()) {
            if (g.dead || g.removed) continue;
            g.speedBuff = Math.max(g.speedBuff, RUSH);
            any = true;
        }
        if (t >= RUSH_T || !any) {
            hidden.clear();
            phase(Phase.SPENT);
        }
    }

    // ------------------------------------------------------------------ 죽음

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (phase == Phase.RING || phase == Phase.HIDE) {
            // 가짜가 걷힘: 남은 수가 돌아오고, 사람이 잡았으면 물러난 게이브 자리가 드러남(끌림은 4틱 안에 풀림)
            game.hud.fakeRemaining(0);
            if (d != null && d.by() != null) {
                int n = 0;
                for (Gabe g : hidden.keySet()) {
                    if (g.dead || g.removed) continue;
                    game.hud.point(null, g.pos.clone(), 160);
                    if (++n >= 4) break;
                }
                if (n > 0) game.hud.notice("notice_g55_found", Species.G55, 80);
            }
        }
        hidden.clear();
        super.die(d);
    }

    @Override
    public void remove() {
        if (phase == Phase.RING || phase == Phase.HIDE) game.hud.fakeRemaining(0);
        hidden.clear();
        super.remove();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " hidden=" + hidden.size();
    }
}
