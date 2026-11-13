package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G06 침 뱉는 게이브(원거리). 15~20블록에 자리를 잡고 막대기 다리로 버텨 선다. 사람이 보이면 찍고(실눈 뜸) 고개를 뒤로 젖혀 볼을 부풀렸다('크흡')
 * 앞으로 숙이며 느린 분홍 침 덩이를 포물선으로 뱉는다('퉤'). 맞으면 3 피해와 화면 가장자리에 침 방울이 흘러내리고, 떨어진 자리는 2초 끈적이
 * (그 위에 서면 느려짐). 가까이 오면 깡충 물러나 거리를 다시 잡는다('폴짝', 한 번 뛰면 1.5초는 다시 못 뛰고 그동안은 코앞에서도 뱉는다).
 * 체력이 낮아 두세 발이면 죽는다. 대처(구조물 없는 황야): 침은 뱉는 순간 서 있던 자리로 느리게 날아오므로 옆걸음으로 피한다.
 *
 * <p>도감과 다른 점: 침은 바닐라 눈덩이 대신 몸의 부품 디스플레이(세계 축, 2틱마다 자세 보간)로 그리고 서버가 포물선과 맞음을 센다. 끈적이 원판은
 * 같은 부품이 그 자리에서 원판 상태로 바뀐 것이다.
 *
 * <p>팩 계약(tools/gabe/sp/g06.py): 모델 {@code gave:gabe/g06} 상태 {@code ""}, {@code "puff"}, {@code "spit"}, 부품 {@code gave:gabe/g06_glob}
 * 상태 {@code ""}, {@code "splat"}, 덮개 {@code g06_drip}. 소리 inhale(뱉기 예고에만), spit, splat, hop. 글귀 cause_g06.
 */
public final class G06 extends Gabe {

    static final double SPEED = 0.12, HOVER = 0.8;
    /** 자리 잡는 거리(이보다 멀면 다가오고 가까우면 멈춤), 이보다 가까우면 깡충 물러남, 뱉는 최대 거리. */
    static final double FAR = 20, NEAR = 15, HOP_RANGE = 8, SHOOT_RANGE = 24;
    /** 볼 부풀리는 틱, 뱉은 뒤 숙인 틱, 다음 뱉기까지(최소, 무작위 더함). 깡충 틱과 높이. */
    static final int PUFF = 18, BOW = 8, RELOAD = 46, RELOAD_RND = 24, HOP = 10, HOP_REST = 30;
    static final double HOP_V = 0.32, HOP_UP = 0.7;
    /** 침: 수평 빠르기, 중력, 맞는 거리, 피해, 끈적이 반지름, 틱, 느려짐. */
    static final double SPIT_V = 0.42, GRAV = 0.03, SPIT_HIT = 0.65, DAMAGE = 4, STICKY_R = 1.0;
    static final int STICKY = 40, FLY_MAX = 80;
    static final double STICKY_SLOW = 40;
    static final double FACE_R = 0.4;

    enum Phase { HOLD, PUFF, BOW, HOP }

    /** 침 덩이 하나(날아감 → 끈적이). */
    final class Glob {
        Body.Part p;
        final Vector at = new Vector(), v = new Vector();
        int t;
        boolean landed;
        final Set<UUID> hit = new HashSet<>();
    }

    private final List<Glob> globs = new ArrayList<>(2);
    private Phase phase = Phase.HOLD;
    private int phaseAt, readyAt, hopReadyAt, unsquashAt = -1;
    private Player prey;
    private final Vector hopDir = new Vector();

    public G06(Game game, Species sp) {
        super(game, sp);
        radius = FACE_R;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g06", 2.4f, 1.8f); // 두꺼비 꼴(뒷다리까지 폭 2): 컬링 상자
    }

    @Override
    protected void spawned() {
        readyAt = age + 30 + game.random.nextInt(30);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    @Override
    public void tick() {
        if (prey != null && !standing(prey)) prey = null;
        // 뱉으며 찌그러진 몸 되돌리기(틱을 건너뛰어도 다음에 꼭)
        if (unsquashAt >= 0 && age >= unsquashAt) {
            body.squash(1f, 1f, 1f, 4);
            unsquashAt = -1;
        }
        switch (phase) {
            case HOLD -> stand();
            case PUFF -> puff();
            case BOW -> bow();
            case HOP -> hop();
        }
        for (int i = globs.size() - 1; i >= 0; i--) globTick(globs.get(i));
    }

    /** 거리 15~20 을 잡고 버팀. 너무 가까우면 깡충 물러남. 보이면 뱉기. */
    private void stand() {
        Player p = nearest(48);
        pitch = 0;
        if (p == null) {
            follow(speed());
            return;
        }
        double d = dist(p);
        face(chest(p));
        pitch = 0;
        if (d < HOP_RANGE && age >= hopReadyAt) {
            // '크흡'은 뱉기 예고에만 쓴다(깡충은 제 소리)
            hopDir.copy(toward(p).multiply(-1));
            sound("hop", Sound.ENTITY_SLIME_JUMP_SMALL, 1.4f, 12);
            phase(Phase.HOP);
            return;
        }
        boolean seen = d <= SHOOT_RANGE && sees(p);
        if (d > FAR || !seen) follow(speed());
        else if (d < NEAR) vel.copy(toward(p).multiply(-speed() * 0.6));
        else hold();
        if (wallAhead(0.5)) hold();
        if (seen && age >= readyAt && globs.size() < 2) {
            prey = p;
            mark(p);
            hold();
            state("puff");
            sound("inhale", Sound.ENTITY_BREEZE_INHALE, 1.3f, 24);
            phase(Phase.PUFF);
        }
    }

    /** 고개를 젖혀 볼을 부풀림('크흡'), 끝에 숙이며 뱉음. */
    private void puff() {
        hold();
        if (prey == null || !sees(prey)) {
            calm();
            return;
        }
        face(chest(prey));
        int t = inPhase();
        pitch = -Math.min(30f, t * 3f); // 뒤로 젖힘
        body.pose(2);
        if (t >= cd(PUFF)) {
            pitch = 25f; // 앞으로 숙이며
            body.pose(2);
            state("spit");
            body.squash(1.1f, 0.9f, 1.15f, 2);
            unsquashAt = age + 3;
            spit(prey);
            phase(Phase.BOW);
        }
    }

    private void bow() {
        hold();
        if (inPhase() >= BOW) calm();
    }

    private void calm() {
        mark(null);
        prey = null;
        pitch = 0;
        body.pose(4);
        state("");
        readyAt = age + cd(RELOAD) + game.random.nextInt(cd(RELOAD_RND));
        phase(Phase.HOLD);
    }

    /** 깡충: 다리로 차고 뒤로 뛰어 거리를 다시 잡음. */
    private void hop() {
        int t = inPhase();
        double k = Math.sin(Math.PI * Math.min(1.0, t / (double) HOP));
        hover = HOVER + HOP_UP * k;
        vel.copy(hopDir.clone().multiply(HOP_V));
        if (wallAhead(0.5)) vel.zero();
        // 첫 틱은 1(단계를 바꾼 다음 틱부터 불림): 움츠렸다가 길게 펴며 뜀
        if (t == 1) body.squash(1.1f, 0.8f, 1.1f, 1);
        if (t == 3) body.squash(0.9f, 1.15f, 0.9f, 3);
        if (t >= HOP) {
            hover = HOVER;
            body.squash(1f, 1f, 1f, 3);
            unsquashAt = -1;
            hold();
            readyAt = Math.max(readyAt, age + 15);
            hopReadyAt = age + HOP_REST;
            phase(Phase.HOLD);
        }
    }

    // ------------------------------------------------------------------ 침

    private void spit(Player target) {
        // 부품 모델 이름은 g06_glob(g06_spit 이면 몸 상태 "spit" 의 모델 파일 g06_spit.json 과 겹침)
        Body.Part part = body.attach("gabe/g06_glob");
        if (part == null) return;
        part.follow = false;
        if (part.d != null) {
            part.d.setDisplayWidth(0f);
            part.d.setDisplayHeight(0f);
        }
        Glob g = new Glob();
        g.p = part;
        g.at.copy(mouth());
        Vector to = chest(target).add(new Vector(0, -0.35, 0));
        Vector d = to.clone().subtract(g.at);
        double flat = Math.hypot(d.getX(), d.getZ());
        double T = Math.max(10, flat / SPIT_V);
        g.v.setX(d.getX() / T).setZ(d.getZ() / T).setY((d.getY() + 0.5 * GRAV * T * T) / T);
        globs.add(g);
        sound("spit", Sound.ENTITY_LLAMA_SPIT, 1f, 24);
        poseGlob(g, 1);
    }

    private void globTick(Glob g) {
        g.t++;
        if (!g.landed) {
            Vector prev = g.at.clone();
            g.at.add(g.v);
            g.v.setY(g.v.getY() - GRAV);
            for (Player p : game.mobs.standing()) {
                if (g.hit.contains(p.getUniqueId())) continue;
                Vector c = chest(p);
                if (Math.abs(g.at.getY() - c.getY()) > 1.0) continue;
                double dx = g.at.getX() - c.getX(), dz = g.at.getZ() - c.getZ();
                if (dx * dx + dz * dz > SPIT_HIT * SPIT_HIT) continue;
                g.hit.add(p.getUniqueId());
                hit(p, DAMAGE, "cause_g06");
                drip(p);
                g.v.setX(0).setZ(0).setY(Math.min(0, g.v.getY()));
            }
            boolean wall = blocked(g.at);
            if (wall) g.at.copy(prev);
            double fl = game.mobs.flow.floor(g.at.getX(), g.at.getZ());
            if (wall || g.at.getY() <= fl + 0.05 || g.t >= FLY_MAX) {
                g.at.setY(wall ? floorBelow(g.at) : fl);
                land(g);
            } else if (g.t % 2 == 0) poseGlob(g, 2);
            return;
        }
        // 끈적이: 위에 선 사람 느려짐
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - g.at.getX(), dz = l.getZ() - g.at.getZ();
            if (dx * dx + dz * dz <= STICKY_R * STICKY_R && Math.abs(l.getY() - g.at.getY()) < 0.7)
                game.players.slow(p, STICKY_SLOW, 8, "g06_sticky");
        }
        if (g.t >= STICKY) {
            if (g.p != null) g.p.remove();
            globs.remove(g);
        } else if (g.t % 2 == 0) poseGlob(g, 2);
    }

    private void land(Glob g) {
        g.landed = true;
        g.t = 0;
        g.v.zero();
        if (g.p != null) g.p.state("splat");
        poseGlob(g, 1);
        if (!game.director.silent())
            Props.soundAround(new Location(game.world, g.at.getX(), g.at.getY(), g.at.getZ()), "gabe.g06.splat", Sound.ENTITY_SLIME_SQUISH_SMALL, 1f,
                    0.94f + game.random.nextFloat() * 0.12f, 0.8f, 16, -1);
    }

    private void poseGlob(Glob g, int ticks) {
        if (g.p == null) return;
        Vector off = g.at.clone().subtract(pos);
        Matrix4f m = new Matrix4f().translate((float) off.getX(), (float) off.getY() + (g.landed ? 0.02f : 0f), (float) off.getZ());
        if (!g.landed) m.rotateY((float) Math.toRadians(-yaw));
        g.p.pose(m, ticks);
    }

    private boolean blocked(Vector at) {
        Block bl = game.world.getBlockAt((int) Math.floor(at.getX()), (int) Math.floor(at.getY()), (int) Math.floor(at.getZ()));
        return !bl.isPassable();
    }

    private double floorBelow(Vector at) {
        for (int y = (int) Math.floor(at.getY()); y > at.getY() - 6; y--) {
            Block bl = game.world.getBlockAt((int) Math.floor(at.getX()), y - 1, (int) Math.floor(at.getZ()));
            if (!bl.isPassable() && game.world.getBlockAt((int) Math.floor(at.getX()), y, (int) Math.floor(at.getZ())).isPassable()) return y;
        }
        return game.mobs.flow.floor(at.getX(), at.getZ());
    }

    /** 입 자리(세계): 얼굴 앞 아래. */
    private Vector mouth() {
        double r = Math.toRadians(yaw);
        Vector f = new Vector(-Math.sin(r), 0, Math.cos(r));
        return pos.clone().add(f.multiply(0.35)).add(new Vector(0, -0.15, 0));
    }

    /** 화면 가장자리에 침 방울(다른 덮개가 걸려 있으면 안 그림, 비옷을 쓰지 않는 그림만). */
    private void drip(Player p) {
        PState s = game.players.get(p);
        if (s.cover != null && s.coverUntil > game.now()) return;
        game.hud.cover(p, "g06_drip", 50);
    }

    @Override
    public void die(kr.gave.mob.Damage d) {
        // 날아가던 침은 치우고 떨어진 끈적이는 시체와 함께 남김(시체 풀이 치움)
        for (Glob g : globs) {
            if (!g.landed && g.p != null) {
                g.p.remove();
                g.p = null;
            }
        }
        super.die(d);
    }

    /** 맞는 구: 얼굴(몸 가운데) + 두꺼비 등(뒤 0.5, 아래 0.2: 젖히고 숙이는 pitch 를 따라감). 2026-11-12 외형 두꺼비. */
    @Override
    public List<double[]> spheres() {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double y2 = -0.2 * Math.cos(pr) + 0.5 * Math.sin(pr), z2 = -0.2 * Math.sin(pr) - 0.5 * Math.cos(pr);
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), radius},
                new double[] {pos.getX() - z2 * Math.sin(yr), pos.getY() + y2, pos.getZ() + z2 * Math.cos(yr), 0.4});
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " globs=" + globs.size();
    }
}
