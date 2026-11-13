package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.Swarm;

/**
 * G29 손잡고 도는 게이브(방해).
 *
 * <p>도감: 얼굴 여섯이 엔드 막대기 팔을 맞잡은 고리. 사람이 모인 곳(소집단, 혼자여도)을 골라 반지름 5 블록으로 둘러싸 빙빙 돌고, 한 얼굴이
 * '하' 하고 메기면 나머지가 차례로 받는다. 닫히면 반지름이 2.4 까지 천천히 조이며 안쪽 사람을 가운데로 몬다(바닥에 맥박치는 살 띠 고리).
 * 고리를 넘으려 하면 밀쳐 1 피해. 맞서기: 맞잡은 손(이음 가운데)을 쏘면 그 자리가 2초 열려 빠져나갈 수 있다. 얼굴(마디)을 잡아 3마디
 * 미만이 되면 고리가 떨어져 죽는다. 보상: 마디당 3원(잡은 사람에게 바로), 고리가 떨어질 때 남은 마디 x 3원(합 18원).
 *
 * <p>몸: 무리 닻(빈 몸) + 마리 여섯 {@code gave:gabe/g29}(상태 {@code r5/r4/r3/r2} 반지름별 팔 길이, 부위 {@code cut_l}, {@code cut_r} 팔 끊김)
 * + 부품 {@code gave:gabe/g29_ring}(바닥 살 띠 고리, 세계 축, 반지름만큼 키움, 부위 {@code ring} 이 켜질 때만 보임).
 * 마리 i 의 오른팔(cut_r)이 마리 i+1 쪽, 왼팔(cut_l)이 i-1 쪽(얼굴이 고리 가운데를 볼 때). 소리 ha(20), shove(16), snap(20), close(20).
 * 원인 글귀 {@code cause_g29}.
 *
 * <p>체력: 감독이 넣은 {@code maxHp} 를 첫 틱에 마디 하나 체력으로 읽고 그 뒤 {@code hp} 는 산 마디 합계다. 판 규칙 '갈라짐'(60초, 변이 split)으로
 * 생긴 고리는 받은 {@code hp}(어미의 절반)를 고리 전체 체력으로 나눠 갖고, 엔진이 어미 {@code hp} 를 절반으로 깎으면 마디 체력도 같은 비율로
 * 줄인다({@link #syncHp}). 닫힌 고리는 가운데 사람을 찍어 여섯 얼굴이 모두 실눈을 뜬다.
 */
public final class G29 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int N = 6;
    static final double R_OPEN = 5.0, R_MIN = 2.4;
    /** 도는 빠르기(라디안/틱), 닫힌 뒤 다 조이는 틱(12초). */
    static final double SPIN = 0.03;
    static final int CLOSE_T = 240;
    /** 고리 가운데가 무리 가운데에서 이 안이면 닫음. 아무도 안에 없으면 이 틱 뒤 다시 엶. */
    static final double CLOSE_AT = 1.6;
    static final int EMPTY_T = 60;
    /** 밀침 세기, 밀침 피해, 같은 사람 다시 밀쳐 다치게 하는 틱, 조여 미는 세기. */
    static final double PUSH = 0.42, SHOVE_DMG = 2, SQUEEZE = 0.07;
    static final int SHOVE_CD = 20;
    /** 이음을 쏘면 열리는 틱. */
    static final int GAP_T = 40;
    static final double FACE_R = 0.32, JOINT_R = 0.34;
    static final int SEG_REWARD = 3;
    /** 얼굴 높이(바닥에서). */
    static final double HEIGHT = 1.05;

    enum Phase { ROLL, CLOSED }

    private Swarm swarm;
    private Body.Part ring;
    private Phase phase = Phase.ROLL;
    private int phaseAt, emptyFor, chantAt = -1, chantLead, nextChant;
    private boolean init;
    private double R = R_OPEN, ang;
    private final long[] gapUntil = new long[N];
    private final Map<UUID, Boolean> inside = new HashMap<>();
    private final Map<UUID, Integer> shovedAt = new HashMap<>();
    private Player focus;
    /** '하' 하며 부푼 마디와 되돌릴 틱(없으면 -1). */
    private int puffIdx = -1, puffUntil;
    /** 무리 가운데(5틱마다 다시 셈, 없으면 crowdOk false). */
    private final Vector crowd = new Vector();
    private boolean crowdOk;

    public G29(Game game, Species sp) {
        super(game, sp);
        radius = 0.8;
        hover = HEIGHT;
        baseSpeed = 0.11;
        noPush = true;
        noBite = true; // 고리 가운데는 빈자리(물어뜯기는 얼굴이 아니라 닻으로 재므로 뺌)
    }

    @Override
    protected void build() {
        swarm = game.mobs.swarm(this, "gabe/g29", N);
        swarm.lodDist = 40;
        swarm.memberRadius = FACE_R;
        ang = game.random.nextDouble() * Math.PI * 2;
        for (Swarm.Member m : swarm.members) {
            m.home.copy(slot(m.index));
            m.state("r5");
        }
        place(0);
        ring = body.attach("gabe/g29_ring");
        if (ring != null) {
            ring.follow = false;
            poseRing(0);
        }
    }

    @Override
    protected void spawned() {
        nextChant = age + 20 + game.random.nextInt(40);
    }

    private void init() {
        init = true;
        // 감독, 소환: hp == maxHp = 마디 하나 체력. 갈라져 나온 고리(판 규칙, 변이 split): hp(어미의 절반) < maxHp = 고리 전체 체력
        boolean split = hp < maxHp - 0.01;
        double each = split ? Math.max(1, hp / N) : Math.max(1, maxHp);
        for (Swarm.Member m : swarm.members) m.hp = each;
        maxHp = hp = each * N;
        if (!noReward) reward = SEG_REWARD * N;
    }

    /** 엔진이 hp 를 바깥에서 깎았으면(갈라짐으로 절반) 산 마디 체력을 같은 비율로 줄인다. */
    private void syncHp() {
        double sum = 0;
        for (Swarm.Member m : swarm.members) if (!m.dead) sum += Math.max(0, m.hp);
        if (sum <= 0 || hp >= sum - 0.01) return;
        double k = Math.max(0.05, hp / sum);
        for (Swarm.Member m : swarm.members) if (!m.dead) m.hp *= k;
        hp = sum * k;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        else syncHp();
        ang += SPIN * Math.max(0.5, buff()) * (phase == Phase.CLOSED ? 1.0 : 0.7);
        if (phase == Phase.ROLL) {
            if (every(5) || !crowdOk) crowdOk = crowdCenter(crowd);
            roll(crowdOk ? crowd : null);
        } else closed();
        String st = R > 4.5 ? "r5" : R > 3.5 ? "r4" : R > 2.7 ? "r3" : "r2";
        for (Swarm.Member m : swarm.members) if (!m.dead && !st.equals(m.stateName())) m.state(st);
        if (every(2)) place(2);
        // 바닥 살 띠 고리는 닫혔을 때만 보이니 그때만 자리를 맞춤
        if (phase == Phase.CLOSED && every(4)) poseRing(4);
        chant();
        gaps();
    }

    /** 고리째 굴러 무리 쪽으로(가운데가 흐름장을 따라감). 가운데가 무리 가운데에 닿으면 닫음. */
    private void roll(Vector target) {
        if (R < R_OPEN) R = Math.min(R_OPEN, R + 0.03);
        if (target == null) {
            hold();
            return;
        }
        double flat = Math.hypot(target.getX() - pos.getX(), target.getZ() - pos.getZ());
        if (flat > 3) follow(speed());
        else seekFlat(target, speed());
        if (flat <= CLOSE_AT && R >= R_OPEN - 0.05) {
            if (ring != null) ring.look.on.add("ring");
            poseRing(0);
            focus = nearest(R_OPEN + 2);
            eyes(focus);
            inside.clear();
            emptyFor = 0;
            sound("close", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 20);
            phase(Phase.CLOSED);
        }
    }

    /** 닫힌 고리: 조이며 돎, 넘으려는 사람을 밀침, 안쪽 사람을 가운데로. */
    private void closed() {
        R = Math.max(R_MIN, R - (R_OPEN - R_MIN) / CLOSE_T);
        List<Player> in = new ArrayList<>();
        Vector c = pos;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ(), d = Math.hypot(dx, dz);
            if (d > R + 3 || Math.abs(l.getY() + 0.9 - c.getY()) > 2.5) {
                inside.remove(p.getUniqueId());
                continue;
            }
            UUID id = p.getUniqueId();
            Boolean was = inside.get(id);
            if (was == null) {
                was = d < R;
                inside.put(id, was);
            }
            boolean open = gapOpen(Math.atan2(dx, dz));
            if (was) {
                if (d > R - 0.55 && !open) shove(p, true, dx, dz, d);
                else if (d >= R + 0.4) inside.put(id, false); // 열린 틈으로 빠져나감
                if (d > R - 1.2 && d <= R - 0.55) knock(p, new Vector(-dx, 0, -dz), SQUEEZE, 0); // 조여 가운데로 몲
                if (d < R + 0.4) in.add(p);
            } else {
                if (d < R + 0.55 && !open) shove(p, false, dx, dz, d);
                else if (d <= R - 0.4) inside.put(id, true);
            }
        }
        if (in.isEmpty()) emptyFor++;
        else {
            emptyFor = 0;
            Vector ic = new Vector();
            for (Player p : in) ic.add(p.getLocation().toVector());
            ic.multiply(1.0 / in.size());
            seekFlat(ic, 0.05);
            if (focus == null || !in.contains(focus)) {
                focus = in.get(0);
                eyes(focus);
            }
        }
        if (emptyFor >= EMPTY_T) {
            if (ring != null) ring.look.on.remove("ring");
            eyes(null);
            focus = null;
            inside.clear();
            phase(Phase.ROLL);
        }
    }

    private void shove(Player p, boolean inward, double dx, double dz, double d) {
        Vector dir = inward ? new Vector(-dx, 0, -dz) : new Vector(dx, 0, dz);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
        knock(p, dir, PUSH, 0.12);
        UUID id = p.getUniqueId();
        Integer last = shovedAt.get(id);
        if (last == null || age - last >= cd(SHOVE_CD)) {
            shovedAt.put(id, age);
            hit(p, SHOVE_DMG, "cause_g29");
            Location l = p.getLocation();
            Props.soundAround(l, "gabe.g29.shove", Sound.ENTITY_SLIME_ATTACK, 1f, 0.95f + game.random.nextFloat() * 0.1f, 0.8f, 16, -1);
        }
    }

    /** 찍은 사람(닫힌 고리 안 사람): 여섯 얼굴이 모두 실눈을 뜸. null 이면 실눈으로. */
    private void eyes(Player p) {
        mark(p);
        boolean on = p != null;
        for (Swarm.Member m : swarm.members) m.look.open = on;
    }

    // ------------------------------------------------------------------ 고리 배치

    /** 마리 i 의 고리 자리(가운데 기준 세계 축, 각 ang + i 60도). */
    private Vector slot(int i) {
        double a = ang + Math.PI * 2 * i / N;
        return new Vector(Math.sin(a) * R, 0, Math.cos(a) * R);
    }

    /** 모든 산 마리를 고리 자리로, 얼굴은 가운데를 봄(닫혔으면 조금 내려다봄). */
    private void place(int ticks) {
        float down = phase == Phase.CLOSED ? 12f : 0f;
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            Vector off = slot(m.index);
            m.moveTo(off, ticks);
            float yw = (float) Math.toDegrees(Math.atan2(off.getX(), -off.getZ()));
            m.face(yw, down, 0, ticks);
        }
    }

    private void poseRing(int ticks) {
        if (ring == null) return;
        float dy = (float) (floor() + 0.04 - pos.getY());
        ring.pose(new Matrix4f().translate(0, dy, 0).scale((float) R, 1f, (float) R), ticks);
    }

    /** 각 a(atan2(dx, dz))의 사람이 지나는 이음이 열렸나: 그 사이 이음이 쏴서 열렸거나 양쪽 마디 하나가 죽음. */
    private boolean gapOpen(double a) {
        double step = Math.PI * 2 / N;
        double rel = ((a - ang) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
        int j = (int) Math.floor(rel / step) % N;
        return jointOpen(j);
    }

    private boolean jointOpen(int j) {
        Swarm.Member a = swarm.members.get(j), b = swarm.members.get((j + 1) % N);
        return a.dead || b.dead || gapUntil[j] > game.now();
    }

    /** 열린 이음의 팔을 숨김(틈이 닫히면 다시). */
    private void gaps() {
        long now = game.now();
        for (int i = 0; i < N; i++) {
            Swarm.Member m = swarm.members.get(i);
            if (m.dead) continue;
            int right = i, left = (i - 1 + N) % N;
            setCut(m, "cut_r", jointOpen(right));
            setCut(m, "cut_l", jointOpen(left));
        }
        if (now % 40 == 0) shovedAt.values().removeIf(t -> age - t > 200);
    }

    private static void setCut(Swarm.Member m, String part, boolean on) {
        if (on) m.look.on.add(part);
        else m.look.on.remove(part);
    }

    /** '하' 주고받기: 메기는 얼굴 하나가 높게, 나머지가 차례로(5틱 간격). 소리 내는 얼굴이 살짝 부풂. */
    private void chant() {
        if (puffIdx >= 0 && age >= puffUntil) {
            Swarm.Member pm = swarm.members.get(puffIdx);
            puffIdx = -1;
            if (!pm.dead) {
                pm.scale = 1f;
                pm.face(pm.yaw, pm.pitch, pm.roll, 3);
            }
        }
        if (chantAt < 0) {
            if (age < nextChant) return;
            chantAt = age;
            chantLead = game.random.nextInt(N);
        }
        int step = age - chantAt;
        if (step % 5 != 0) return;
        int k = step / 5;
        if (k >= N) {
            chantAt = -1;
            nextChant = age + 50 + game.random.nextInt(30);
            return;
        }
        Swarm.Member m = swarm.members.get((chantLead + k) % N);
        if (m.dead || game.director.silent()) return;
        float p = k == 0 ? 1.18f : 1.02f - 0.03f * k;
        Location at = m.pos().toLocation(game.world);
        Props.soundAround(at, "gabe.g29.ha", Sound.ENTITY_VILLAGER_AMBIENT, 0.9f, p, 1.3f * p, 20, -1);
        // 소리 내는 얼굴이 3틱 부풂(되돌리기는 다음 chant 틱에서, 예약 작업 없이)
        if (puffIdx >= 0 && puffIdx != m.index) swarm.members.get(puffIdx).scale = 1f;
        m.scale = 1.18f;
        m.face(m.yaw, m.pitch, m.roll, 1);
        puffIdx = m.index;
        puffUntil = age + 3;
    }

    // ------------------------------------------------------------------ 목표

    /** 소집단 가운데: 가장 가까운 사람 둘레 5 블록 안 사람들의 가운데. */
    private boolean crowdCenter(Vector out) {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) return false;
        Location lp = p.getLocation();
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            if (Math.hypot(l.getX() - lp.getX(), l.getZ() - lp.getZ()) <= 5) {
                x += l.getX();
                y += l.getY();
                z += l.getZ();
                n++;
            }
        }
        if (n == 0) out.setX(lp.getX()).setY(lp.getY()).setZ(lp.getZ());
        else out.setX(x / n).setY(y / n).setZ(z / n);
        return true;
    }

    private void seekFlat(Vector to, double v) {
        Vector d = to.clone().subtract(pos).setY(0);
        double l = d.length();
        vel.copy(l < 1e-6 ? d : d.multiply(Math.min(v, l) / l));
    }

    // ------------------------------------------------------------------ 맞음

    /** 이음 가운데 점(마디 j 와 j+1 사이 현의 가운데, 팔 높이). */
    private Vector joint(int j) {
        double a = ang + Math.PI * 2 * (j + 0.5) / N;
        double r = R * Math.cos(Math.PI / N);
        return new Vector(pos.getX() + Math.sin(a) * r, pos.getY() - 0.07, pos.getZ() + Math.cos(a) * r);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(swarm.spheres());
        for (int j = 0; j < N; j++) {
            if (jointOpen(j)) continue;
            Vector v = joint(j);
            out.add(new double[] {v.getX(), v.getY(), v.getZ(), JOINT_R});
        }
        return out;
    }

    @Override
    public Vector head() {
        Swarm.Member m = swarm == null ? null : swarm.nearest(null);
        return m == null ? pos : m.pos();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        double before = hp;
        DamageType t = d.type();
        Vector at = d.at() != null ? d.at() : pos;
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            for (Swarm.Member m : swarm.within(at, 3.0)) hurt(m, d.amount(), d);
        } else {
            // 가장 가까운 것이 이음이면 그 자리가 2초 열림(피해 없음), 얼굴이면 그 마디
            Swarm.Member m = swarm.nearest(at);
            int bestJ = -1;
            double bj = Double.MAX_VALUE;
            for (int j = 0; j < N; j++) {
                if (jointOpen(j)) continue;
                double dd = joint(j).distanceSquared(at);
                if (dd < bj) {
                    bj = dd;
                    bestJ = j;
                }
            }
            double bm = m == null ? Double.MAX_VALUE : m.pos().distanceSquared(at);
            if (bestJ >= 0 && bj < bm) {
                gapUntil[bestJ] = game.now() + GAP_T;
                Vector v = joint(bestJ);
                Props.soundAround(v.toLocation(game.world), "gabe.g29.snap", Sound.ENTITY_ITEM_BREAK, 1f, 0.95f + game.random.nextFloat() * 0.1f, 0.8f,
                        20, -1);
                gaps();
            } else if (m != null) hurt(m, d.amount(), d);
        }
        hp = 0;
        for (Swarm.Member m : swarm.members) if (!m.dead) hp += Math.max(0, m.hp);
        game.mobs.onHurt(this, d, before - hp);
        if (swarm.alive() < 3) {
            // 3마디 미만: 고리가 떨어져 죽음(남은 마디만큼 보상)
            if (!noReward) reward = SEG_REWARD * swarm.alive();
            die(d);
        }
        return true;
    }

    private void hurt(Swarm.Member m, double amount, Damage d) {
        m.hp -= amount;
        m.look.hurtUntil = game.worldTime() + 3;
        if (m.hp > 0) return;
        swarm.kill(m);
        if (!noReward) {
            reward = Math.max(0, reward - SEG_REWARD);
            Player by = d.by();
            if (by != null && by.isOnline()) game.players.addMoney(by, SEG_REWARD);
        }
        gaps();
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (ring != null) {
            ring.remove();
            ring = null;
        }
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " R=" + Math.round(R * 10) / 10.0 + " alive=" + (swarm == null ? 0 : swarm.alive());
    }
}
