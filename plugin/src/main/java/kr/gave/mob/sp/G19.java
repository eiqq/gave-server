package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G19 펼친 얼굴 방패 게이브(방패, 지원). 얼굴을 가로 4배, 세로 3배로 늘인 막(3.2 x 2.4 블록)을 엔드 막대기 넷이 네 모서리에서 꿰어 들고,
 * 막 뒤에 반 크기 본체가 손잡이처럼 붙는다. 느리게 와서 가장 가까운 사람 쪽으로 막을 천천히(한 틱 3.5도) 돌려 세운다.
 * 황야는 나오는 고리가 멀어서(72블록 넘게) 사람이 24블록 밖이면 걸음을 재촉해 떼 앞에 서고, 그 안에서부터 느리게 온다.
 *
 * <p>막기: 서버가 막 사각형(몸 기준 가로, 세로)과 탄의 맞은 점, 방향으로 판정한다. 정면에서 온 총알이 막에 박히면 피해 없이 막이 살처럼 움푹 들어가고
 * 맞은 자리 눈이 찡그린다(상태 w, 6틱). 옆, 뒤에서 쏘거나 막이 처진 귀퉁이로 들어간 탄은 본체에 그대로. 모서리 막대기 끝(막 귀퉁이 0.5블록 안)을
 * 맞히면 그 모서리가 끊겨 귀퉁이가 처지고(부위 cut_a .. cut_d), 그 칸은 더 막지 못한다. 폭발이 막 앞에서 터지면 4분의 1만, 막 너머(뒤)는 그대로.
 *
 * <p>가까우면(2.3블록) 막을 앞으로 덮어 2 피해와 느려짐 40%(2초). 8블록 안에 다른 게이브 시체가 있으면 가서 덮었다 들어 올려 삼키고 막에 얼굴 무늬가
 * 하나 늘며 커진다(무늬 단계 0..3, 크기 x1.12씩, 체력 +15%).
 *
 * <p>팩 계약(tools/gabe/sp/g19.py): 모델 gave:gabe/g19, shrink 0.5, 상태 "s0".."s3"(실눈), "o0".."o3"(표적), "w0".."w3"(찡그림),
 * 부위 cut_a(+X 위), cut_b(-X 위), cut_c(-X 아래), cut_d(+X 아래), 소리 stretch, absorb, wrap, snap, gulp, 원인 글귀 cause_g19.
 */
public final class G19 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 빠르기(블록/틱). 사람이 FAR 블록 밖이면 FAR_V 로 재촉. */
    static final double SPEED = 0.075, FAR = 24, FAR_V = 0.16;
    /** 막을 돌려 세우는 빠르기(도/틱). */
    static final float TURN = 3.5f;
    /** 막 반폭, 반높이(블록, 크기 1), 막 판정 두께, 모서리 판정 반지름, 본체 뒤 거리. */
    static final double HW = 1.6, HH = 1.2, SLAB = 1.3, CORNER_R = 0.5, BODY_Z = -0.55;
    /** 덮기: 거리, 피해, 느려짐, 틱, 쉬는 틱. */
    static final double WRAP_RANGE = 2.3, WRAP_DMG = 3, WRAP_SLOW = 0.4;
    static final int WRAP_SLOW_TICKS = 40, WRAP_TICKS = 14, WRAP_COOLDOWN = 50;
    /** 시체 찾는 거리, 덮는 틱, 최대 무늬 단계, 단계마다 크기와 체력. */
    static final double CORPSE_R = 8;
    static final int GULP_TICKS = 22, MAX_STAGE = 3;
    static final float GROW = 0.12f;
    static final double HEAL_FRAC = 0.15;
    /** 정면 막기로 치는 방향(탄 방향과 막 앞의 내적이 이보다 작으면 정면). */
    static final double FRONT_DOT = -0.25;
    /** 막 앞 폭발 피해 배율. */
    static final double BLAST_MUL = 0.25;
    static final int WINCE = 6;

    enum Phase { ADVANCE, WRAP, GULP }

    private Phase phase = Phase.ADVANCE;
    private int phaseAt, readyAt, winceUntil = -1, relaxAt = -1, nextStretch, absorbSoundAt = -100;
    private int stage;
    /** 끊긴 모서리(a +X위, b -X위, c -X아래, d +X아래). */
    private final boolean[] cut = new boolean[4];
    private static final String[] CUT = {"cut_a", "cut_b", "cut_c", "cut_d"};
    private Corpse meal;
    private Player prey;
    private final Map<UUID, Long> wrapped = new HashMap<>();

    public G19(Game game, Species sp) {
        super(game, sp);
        radius = 1.0;
        hover = 1.55; // 막 아래 가장자리가 바닥 가까이
        baseSpeed = SPEED;
        state("s0");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/" + sp.key(), 3.8f, 3.0f);
    }

    @Override
    protected void spawned() {
        nextStretch = age + game.random.nextInt(60);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private float size() {
        return 1f + GROW * stage;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        switch (phase) {
            case ADVANCE -> advance();
            case WRAP -> wrap();
            case GULP -> gulp();
        }
        if (age == relaxAt) body.squash(1f, 1f, 1f, 3);
        look();
    }

    /** 표정: 막 직후 찡그림, 표적이면 눈 뜸, 아니면 실눈. 무늬 단계는 숫자. */
    private void look() {
        String e = age < winceUntil ? "w" : target != null ? "o" : "s";
        state(e + stage);
    }

    private void advance() {
        Player p = nearest(40);
        double dp = p == null ? Double.MAX_VALUE : dist(p);
        if (p != null) {
            turnTo(chest(p), TURN);
            pitch = 0;
        }
        follow(speed() * (dp > FAR ? FAR_V / SPEED : 1.0));
        // 정면을 사람 쪽으로 유지하며 오므로 몸 방향과 걷는 방향이 다를 수 있음(옆걸음)
        if (dp <= 14 && sees(p)) {
            if (target != p) mark(p);
        } else if (target != null) mark(null);
        if (age >= nextStretch && vel.lengthSquared() > 1e-5) {
            sound("stretch", Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.7f, 24);
            body.squash(1.04f, 0.97f, 1f, 6);
            relaxAt = age + 8;
            nextStretch = age + 60 + game.random.nextInt(40);
        }
        if (age < readyAt) return;
        if (dp <= WRAP_RANGE * size() && facing(p) > 0.6) {
            prey = p;
            sound("wrap", Sound.ENTITY_PHANTOM_FLAP, 0.6f, 16);
            phase(Phase.WRAP);
            return;
        }
        if (stage < MAX_STAGE && every(10)) {
            Corpse c = corpse();
            if (c != null) {
                meal = c;
                phase(Phase.GULP);
            }
        }
    }

    /** 막을 앞으로 덮음: 앞으로 기울며 조금 나아가 닿은 사람에게 2 피해와 느려짐. */
    private void wrap() {
        int t = inPhase();
        pitch = t < WRAP_TICKS / 2 ? 40f : 40f * (WRAP_TICKS - t) / (WRAP_TICKS / 2f);
        body.pose(2);
        if (prey != null && standing(prey)) {
            face(chest(prey));
            pitch = Math.max(0, pitch);
            vel.copy(toward(prey).multiply(t < WRAP_TICKS / 2 ? 0.16 : 0));
        } else hold();
        if (t == WRAP_TICKS / 2) {
            long now = game.now();
            Vector f = front();
            for (Player q : game.mobs.standing()) {
                Vector c = chest(q).subtract(pos);
                double along = c.dot(f);
                if (along < -0.3 || along > 1.8 * size() || c.clone().subtract(f.clone().multiply(along)).length() > HW * size()) continue;
                Long last = wrapped.get(q.getUniqueId());
                if (last != null && now - last < cd(WRAP_COOLDOWN)) continue;
                wrapped.put(q.getUniqueId(), now);
                hit(q, WRAP_DMG, "cause_g19");
                game.players.slow(q, WRAP_SLOW, WRAP_SLOW_TICKS, "g19");
            }
        }
        if (t >= WRAP_TICKS) {
            pitch = 0;
            body.pose(4);
            readyAt = age + cd(WRAP_COOLDOWN);
            prey = null;
            phase(Phase.ADVANCE);
        }
    }

    /** 시체 덮어 삼키기: 가서 엎드려 덮었다 들어 올림. 무늬 하나 늘고 커짐. */
    private void gulp() {
        Corpse c = meal;
        if (c == null || c.gone()) {
            meal = null;
            pitch = 0;
            phase(Phase.ADVANCE);
            return;
        }
        Vector to = c.pos.clone();
        double d = Math.hypot(to.getX() - pos.getX(), to.getZ() - pos.getZ());
        int t = inPhase();
        if (d > 1.0 && t < 160) {
            Vector dir = to.clone().subtract(pos).setY(0);
            vel.copy(dir.normalize().multiply(speed() * 1.6));
            face(to.clone().setY(pos.getY()));
            phaseAt = age; // 닿을 때까지 시계를 붙잡음
            return;
        }
        if (t >= 160) {
            meal = null;
            phase(Phase.ADVANCE);
            return;
        }
        hold();
        pitch = t < GULP_TICKS / 2 ? 75f : 0f;
        body.pose(t < GULP_TICKS / 2 ? 6 : 8);
        if (t == GULP_TICKS / 2) {
            if (game.mobs.eat(c)) {
                stage = Math.min(MAX_STAGE, stage + 1);
                body.scale(size(), 10);
                body.size(3.8f * size(), 3.0f * size());
                hover = 1.55 + HH * (size() - 1);
                double add = maxHp * HEAL_FRAC;
                maxHp += add;
                hp += add;
                sound("gulp", Sound.ENTITY_SLIME_SQUISH, 0.6f, 20);
            }
        }
        if (t >= GULP_TICKS) {
            meal = null;
            pitch = 0;
            phase(Phase.ADVANCE);
        }
    }

    private Corpse corpse() {
        Corpse best = null;
        double bd = CORPSE_R * CORPSE_R;
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone() || c.sp == sp) continue;
            double d = c.pos.distanceSquared(pos.clone().setY(c.pos.getY()));
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 막 판정

    /** 막이 보는 쪽(수평 단위). */
    private Vector front() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** 몸 왼쪽(+X 모델 축). */
    private Vector left() {
        Vector f = front();
        return new Vector(f.getZ(), 0, -f.getX());
    }

    private double facing(Player p) {
        Vector d = toward(p);
        return d.dot(front());
    }

    /** 귀퉁이 번호(lx 가 + 면 몸 왼쪽, ly 가 + 면 위). */
    private static int quadrant(double lx, double ly) {
        if (lx >= 0) return ly >= 0 ? 0 : 3;
        return ly >= 0 ? 1 : 2;
    }

    private static final double[] SX = {-1.05, 0, 1.05}, SY = {0.6, -0.6};
    /** 맞는 구 모음(막 여섯 + 본체). 엔진이 사람마다 매 틱 묻기 때문에 자리, 방향, 크기, 끊긴 모서리가 그대로면 다시 만들지 않는다. */
    private final List<double[]> sph = new ArrayList<>(7);
    private final double[][] sphBuf = new double[7][4];
    private double sphX = Double.NaN, sphY, sphZ;
    private float sphYaw, sphSize;
    private int sphCut = -1;

    @Override
    public List<double[]> spheres() {
        float s = size();
        int mask = (cut[0] ? 1 : 0) | (cut[1] ? 2 : 0) | (cut[2] ? 4 : 0) | (cut[3] ? 8 : 0);
        double px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        if (px == sphX && py == sphY && pz == sphZ && yaw == sphYaw && s == sphSize && mask == sphCut) return sph;
        sphX = px;
        sphY = py;
        sphZ = pz;
        sphYaw = yaw;
        sphSize = s;
        sphCut = mask;
        sph.clear();
        double a = Math.toRadians(yaw);
        double fx = -Math.sin(a), fz = Math.cos(a); // 막이 보는 쪽, 몸 왼쪽은 (fz, 0, -fx)
        double r = 0.62 * s;
        int n = 0;
        for (double x : SX) {
            for (double y : SY) {
                int q = quadrant(x == 0 ? 0 : x, y);
                if (x != 0 && cut[q]) continue;
                if (x == 0 && cut[quadrant(1, y)] && cut[quadrant(-1, y)]) continue;
                double[] o = sphBuf[n++];
                o[0] = px + fz * x * s;
                o[1] = py + y * s;
                o[2] = pz - fx * x * s;
                o[3] = r;
                sph.add(o);
            }
        }
        double[] o = sphBuf[n];
        o[0] = px + fx * BODY_Z * s;
        o[1] = py;
        o[2] = pz + fz * BODY_Z * s;
        o[3] = 0.3 * s;
        sph.add(o);
        return sph;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        float s = size();
        Vector f = front(), l = left();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            Vector rel = d.at().clone().subtract(pos);
            double lx = rel.dot(l) / s, ly = rel.getY() / s, lz = rel.dot(f) / s;
            boolean onSheet = Math.abs(lz) <= SLAB && Math.abs(lx) <= HW + 0.3 && Math.abs(ly) <= HH + 0.3;
            if (onSheet) {
                // 모서리 막대기 끝: 끊김(어느 쪽에서 쏴도)
                for (int q = 0; q < 4; q++) {
                    if (cut[q]) continue;
                    double cx = (q == 0 || q == 3) ? HW : -HW, cy = (q == 0 || q == 1) ? HH : -HH;
                    if (Math.hypot(lx - cx, ly - cy) <= CORNER_R) {
                        cutCorner(q);
                        return super.damage(d.times(0.5));
                    }
                }
                int q = quadrant(lx, ly);
                boolean front = d.dir() != null && d.dir().clone().normalize().dot(f) < FRONT_DOT;
                if (front && !cut[q] && lz > -0.3) {
                    absorb();
                    return false;
                }
            }
        } else if (t == DamageType.EXPLOSION && d.at() != null) {
            double lz = d.at().clone().subtract(pos).dot(f) / s;
            if (lz > 0.3) d = d.times(BLAST_MUL);
        }
        return super.damage(d);
    }

    /** 정면 탄이 막에 박힘: 피해 없이 움푹(찌그러짐), 맞은 자리 눈이 찡그림. */
    private void absorb() {
        winceUntil = age + WINCE;
        relaxAt = age + WINCE;
        body.squash(1.02f, 1.02f, 0.7f, 1);
        if (age - absorbSoundAt >= 3) {
            absorbSoundAt = age;
            sound("absorb", Sound.BLOCK_MUD_STEP, 0.8f, 20);
        }
    }

    private void cutCorner(int q) {
        cut[q] = true;
        body.look.on.add(CUT[q]);
        sound("snap", Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.6f, 20);
    }

    @Override
    public String toString() {
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < 4; i++) if (cut[i]) c.append("abcd".charAt(i));
        return super.toString() + " ph=" + phase + " stage=" + stage + (c.length() > 0 ? " cut=" + c : "");
    }
}
