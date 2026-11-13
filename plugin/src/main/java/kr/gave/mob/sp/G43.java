package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.world.Plaza;

/**
 * G43 탐조등 게이브(원거리, 방해, 미리 깔림). 감독이 맵 가장자리 10~12블록 높이에 놓는다(자리를 옮기지 않는다).
 *
 * <p>도감 + 맵 변경(황야, 가릴 구조물 없음): 거의 고정된 채 고개를 천천히 돌려 연노랑 빛 원뿔로 바닥을 쓴다. 바닥의 빛 자리(반지름 4.5블록,
 * 테두리 고리가 보임) 안에 서 있으면 '변이' 게이지가 쌓인다({@link G43Glow}: 6초에 다 참, 화면 가장자리 분홍 살, 남들 눈에 머리가 게이브 얼굴로).
 * 다 차면 3초 굳고 동료가 돕기로 떼어 주지 않으면 쓰러지며 기본 게이브 2마리가 나온다. 빛 자리는 걷는 것보다 느리게(초당 3블록) 사람 쪽으로
 * 쓸고 오므로 테두리를 보고 걸어서 벗어날 수 있다. 동공 둘이 약점(2배)이고 맞히면 3초 눈을 감아 빛이 꺼진다. 체력은 낮지만 멀고 높다.
 *
 * <p>바꾼 것: 맵에 가릴 것이 없어 '두꺼운 구조물 뒤로 숨기, 밀기'와 벽 두께 판정은 없다(대처는 피하기와 눈 맞히기, 다 찼을 때 동료 돕기).
 * 빛은 반투명 대신 성긴 빛살 무늬 원뿔(텍스처 모델)과 바닥 빛 자리 판으로 그린다.
 *
 * <p>몸: 닻 {@code gave:gabe/g43}(상태 {@code ""}, {@code "dark"}) + 부품 {@code gave:gabe/g43_beam}(몸 방향을 따름, 길이와 굵기를 늘림) +
 * 부품 {@code gave:gabe/g43_spot}(세계 축, 바닥 빛 자리). 소리 hum(72), birds(본인만), off(48), on(48), bake(24). 글귀 {@code cause_g43},
 * {@code notice_g43_full}.
 */
public final class G43 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치

    static final double HEAD_R = 1.3; // 2026-11-12 거대화(얼굴 2.6 블록, 새장)
    /** 동공 자리(얼굴 기준 옆, 위, 앞)와 약점 판정 각(도), 약점 배율, 눈 감는 틱. */
    static final double PUPIL_X = 0.61, PUPIL_Y = 0.04, PUPIL_Z = 0.91, PUPIL_DEG = 13, PUPIL_MUL = 2;
    static final int DARK_T = 60;
    /** 바닥 빛 자리 반지름, 쓸고 가는 빠르기(초당 3블록), 사람이 다닐 수 있는 반경 안에서만 쓴다. */
    static final double SPOT_R = 4.5, SWEEP_V = 0.15, ROAM_R = 46;
    static final int WAY_MAX = 240;
    /** 빛 원뿔 단위 모델(길이 1.5, 끝 반지름 0.5)과 눈 앞 자리. */
    static final float CONE_LEN = 1.5f, CONE_R = 0.5f, EYE_Z = 0.98f;

    private final Vector spot = new Vector(), way = new Vector();
    private int wayUntil, darkUntil = -1, nextHum;
    private boolean dark, started;
    private Body.Part beam, disc;

    public G43(Game game, Species sp) {
        super(game, sp);
        radius = HEAD_R;
        flying = true;
        noPush = true;
        baseSpeed = 0;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g43", 6.0f, 5.2f);
        beam = body.attach("gabe/g43_beam");
        disc = body.attach("gabe/g43_spot");
        for (Body.Part p : new Body.Part[] {beam, disc}) {
            if (p == null || p.d == null) continue;
            // 빛은 스스로 빛나고(밝기 15), 등불에서 수십 블록 떨어져 그려지므로 컬링 상자를 없앤다
            p.d.setBrightness(new Display.Brightness(15, 15));
            p.d.setDisplayWidth(0f);
            p.d.setDisplayHeight(0f);
        }
        if (disc != null) disc.follow = false;
    }

    @Override
    protected void spawned() {
        nextHum = age + game.random.nextInt(40);
        // 처음 빛 자리: 등불에서 광장 가운데 쪽 바닥
        Vector c = new Vector(Plaza.CX + 0.5, 0, Plaza.CZ + 0.5);
        Vector d = c.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l < 1e-3) d = new Vector(0, 0, 1);
        else d.multiply(1 / l);
        double reach = Math.max(10, Math.min(l, 28));
        spot.setX(pos.getX() + d.getX() * reach).setZ(pos.getZ() + d.getZ() * reach);
        spot.setY(game.mobs.flow.floor(spot.getX(), spot.getZ()));
        way.copy(spot);
        aim(0);
        if (game.director.cageSpawning()) {
            // 대기실 우리: 빛은 그리지 않는다
            if (beam != null) beam.model(Body.EMPTY);
            if (disc != null) disc.model(Body.EMPTY);
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        vel.zero();
        G43Glow glow = G43Glow.of(game);
        if (!started) {
            started = true;
            glow.enter(this);
        }
        if (dark && age >= darkUntil) lightOn();
        sweep();
        if (!dark) {
            Player top = null;
            double best = 0;
            for (Player p : game.mobs.standing()) {
                Location l = p.getLocation();
                double dx = l.getX() - spot.getX(), dz = l.getZ() - spot.getZ();
                if (dx * dx + dz * dz > SPOT_R * SPOT_R) continue;
                glow.lit(p, this);
                double v = glow.dose(p);
                if (top == null || v > best) {
                    best = v;
                    top = p;
                }
            }
            // 빛 안에 든 사람을 찍음(미리 깔린 종: 사람을 쬐는 동안은 웨이브가 끝나지 않게)
            if (top != target) mark(top);
        } else if (target != null) mark(null);
        if (age % 2 == 0) aim(2);
        if (age >= nextHum) {
            nextHum = age + 40;
            sound("hum", Sound.BLOCK_BEACON_AMBIENT, 0.6f, 72);
        }
        glow.tick();
    }

    /** 빛 자리가 바닥을 천천히 쓴다: 사람 근처나 광장 안 아무 데로 길을 잡아 초당 3블록으로. */
    private void sweep() {
        double dx = way.getX() - spot.getX(), dz = way.getZ() - spot.getZ();
        double l = Math.hypot(dx, dz);
        if (l < 0.8 || age >= wayUntil) {
            pickWay();
            return;
        }
        double v = Math.min(SWEEP_V, l);
        spot.setX(spot.getX() + dx / l * v).setZ(spot.getZ() + dz / l * v);
        spot.setY(game.mobs.flow.floor(spot.getX(), spot.getZ()));
    }

    private void pickWay() {
        List<Player> ps = game.mobs.standing();
        double x, z;
        if (!ps.isEmpty() && game.random.nextInt(4) != 0) {
            Location l = ps.get(game.random.nextInt(ps.size())).getLocation();
            double a = game.random.nextDouble() * Math.PI * 2, r = game.random.nextDouble() * 5;
            x = l.getX() + Math.cos(a) * r;
            z = l.getZ() + Math.sin(a) * r;
        } else {
            double a = game.random.nextDouble() * Math.PI * 2, r = Math.sqrt(game.random.nextDouble()) * 36;
            x = Plaza.CX + Math.cos(a) * r;
            z = Plaza.CZ + Math.sin(a) * r;
        }
        double cx = x - Plaza.CX, cz = z - Plaza.CZ, cr = Math.hypot(cx, cz);
        if (cr > ROAM_R) {
            x = Plaza.CX + cx / cr * ROAM_R;
            z = Plaza.CZ + cz / cr * ROAM_R;
        }
        way.setX(x).setZ(z);
        wayUntil = age + WAY_MAX;
    }

    /** 고개를 빛 자리로 돌리고 원뿔과 바닥 빛 자리를 맞춘다. */
    private void aim(int ticks) {
        Vector s = new Vector(spot.getX(), spot.getY() + 0.06, spot.getZ());
        face(s);
        body.pose(ticks);
        if (dark) return;
        double dx = s.getX() - pos.getX(), dy = s.getY() - pos.getY(), dz = s.getZ() - pos.getZ();
        double len = Math.max(2, Math.sqrt(dx * dx + dy * dy + dz * dz) - EYE_Z);
        // 비스듬히 닿는 원뿔 끝이 바닥에서 반지름 SPOT_R 원이 되게: 가로는 그대로, 세로는 내려다보는 각의 사인만큼
        double sinE = Math.max(0.12, Math.min(1.0, -dy / Math.max(1e-3, Math.sqrt(dx * dx + dy * dy + dz * dz))));
        if (beam != null)
            beam.pose(new Matrix4f().translate(0f, 0f, EYE_Z).scale((float) (SPOT_R / CONE_R), (float) (SPOT_R * sinE / CONE_R), (float) (len / CONE_LEN)),
                    ticks);
        if (disc != null)
            disc.pose(new Matrix4f().translate((float) dx, (float) dy, (float) dz).scale((float) (SPOT_R * 2), 1f, (float) (SPOT_R * 2)), ticks);
    }

    private void lightOff() {
        dark = true;
        darkUntil = age + DARK_T;
        state("dark");
        if (beam != null) beam.model(Body.EMPTY);
        if (disc != null) disc.model(Body.EMPTY);
        sound("off", Sound.BLOCK_BEACON_DEACTIVATE, 1.6f, 48);
    }

    private void lightOn() {
        dark = false;
        state("");
        if (beam != null) beam.model("gabe/g43_beam");
        if (disc != null) disc.model("gabe/g43_spot");
        aim(0);
        sound("on", Sound.BLOCK_BEACON_POWER_SELECT, 1.5f, 48);
    }

    // ------------------------------------------------------------------ 맞음, 떼기, 죽음

    /** 얼굴 기준 점(옆 x, 위 y, 앞 z)의 세계 좌표(몸이 보는 방향과 숙임 반영). */
    private Vector local(double x, double y, double z) {
        double pr = Math.toRadians(pitch), yr = Math.toRadians(yaw);
        double y2 = y * Math.cos(pr) - z * Math.sin(pr), z2 = y * Math.sin(pr) + z * Math.cos(pr);
        double wx = x * Math.cos(yr) - z2 * Math.sin(yr), wz = x * Math.sin(yr) + z2 * Math.cos(yr);
        return new Vector(pos.getX() + wx, pos.getY() + y2, pos.getZ() + wz);
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(1);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), HEAD_R});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (gun && d.at() != null) {
            // 동공(약점): 맞은 점이 몸 중심에서 볼 때 동공 쪽 좁은 각 안이면 2배, 3초 눈을 감아 빛이 꺼진다
            Vector rel = d.at().clone().subtract(pos);
            double rl = rel.length();
            if (rl > 1e-4) {
                rel.multiply(1 / rl);
                double lim = Math.cos(Math.toRadians(PUPIL_DEG));
                for (int side = -1; side <= 1; side += 2) {
                    Vector pd = local(side * PUPIL_X, PUPIL_Y, PUPIL_Z).subtract(pos).normalize();
                    if (rel.dot(pd) < lim) continue;
                    if (!dark) lightOff();
                    else darkUntil = Math.max(darkUntil, age + DARK_T / 2);
                    return super.damage(d.times(PUPIL_MUL));
                }
            }
        }
        return super.damage(d);
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        G43Glow.of(game).detached(victim, helper, how);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 빛(원뿔, 바닥 빛 자리)은 시체로 납작하게 남지 않게 먼저 끈다
        if (beam != null) beam.model(Body.EMPTY);
        if (disc != null) disc.model(Body.EMPTY);
        super.die(d);
        G43Glow.of(game).leave(this);
    }

    @Override
    public void remove() {
        super.remove();
        G43Glow.of(game).leave(this);
    }

    @Override
    public String toString() {
        return super.toString() + (dark ? " dark" : " lit") + String.format(java.util.Locale.ROOT, " spot=%.1f,%.1f", spot.getX(), spot.getZ());
    }
}
