package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.game.Shop;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;
import kr.gave.world.Plaza;

/**
 * G103 국자 게이브(2막, 목표 압박, 원거리, 미리 깔림). 물웅덩이 하나가 끓다가 폭 6 블록 얼굴이 눈 아래까지 솟고, 제자리에서 긴 자루 국자로
 * 웅덩이의 분홍 물을 떠 트럭 둘레 안으로 크게 휘둘러 끼얹는다(6초에 한 번, 떨어질 자리에 빨간 고리 1.5초, 맞으면 18, 지름 7). 트럭 둘레 안 물이
 * 차오른다(여덟 번이면 가득): 절반이면 둘레 안 모두 느려짐 25%, 가득이면 둘레 안이 초당 8 피해 늪이 되고 트럭도 조금씩 잠겨 깎인다.
 * 물이 절반 넘게 차면 자루를 뻗어 둘레 가장자리 사람을 국자로 떠 가 가둔다. 국자 바닥(체력 120)을 쏴 뚫으면 물을 못 퍼고 자루로 내려치기만
 * 한다(22, 둘레 가장자리까지). 찬 물은 그것이 죽으면 빠진다.
 *
 * <p>팩 계약(tools/gabe/sp/g103.py): 모델 gabe/g103(shrink 0.25) 상태 "", "open", 부품 gabe/g103_ladle 상태 "", "empty", "holed",
 * 물 판 gabe/g103_flood. 소리 splash, bubble, rise, crack. 글귀 cause_g103, cause_g103_flood, cause_g103_rod. 게이지는 HUD meter 키 "g103"(종 아이콘).
 *
 * <p>도감과 다른 점: 지팡이는 닻 모델에 굳힘(부품 아님), 물 판은 부품이 아니라 연출 디스플레이(ShotFx 예산, 트럭 둘레 한가운데 세계 자리)에
 * 물 판 모델을 실은 것. 물 줄기는 분홍 방울(ShotFx BLOOD 분홍)을 국자에서 떨어질 자리로 띄움. 국자는 거리에 맞춰 통째로 커진다(멀리 뻗는 자루).
 */
public final class G103 extends Gabe {

    static final double HOVER = 1.0, HEAD_R = 3.2;
    /** 끼얹기: 쿨다운, 국자 담그기, 들기(예고 시작), 휘두르기, 맞는 틱, 피해, 반지름. */
    static final int THROW_CD = 120, SCOOP = 0, RAISE = 18, FLING = 46, IMPACT = 54, REST = 66;
    static final double SPLASH_DMG = 18, SPLASH_R = 3.5;
    /** 물: 가득, 절반 느려짐, 가득 피해(초당), 둘레 반지름. */
    static final int WATER_MAX = 8;
    static final double SLOW = 0.25, SWAMP_DPS = 8, RING = Plaza.R_SAFE;
    /** 떠 가기: 쿨다운, 예고, 반지름. 내려치기(뚫린 국자): 피해, 반지름, 예고. */
    static final int SCOOP_CD = 240, TAKE_TELL = 30, ROD_TELL = 26;
    static final double TAKE_R = 1.7, ROD_DMG = 22, ROD_R = 2.5;
    static final double LADLE_HP = 120, LADLE_LEN = 7.4;
    /** 국자 자루 뿌리(몸 틀). */
    static final Vector3f PIVOT = new Vector3f(-4.0f, -0.8f, 0.6f);

    enum Phase { RISE, IDLE, THROW, TAKE, ROD }

    private Phase phase = Phase.RISE;
    private int phaseAt, readyAt, takeAt;
    private int water;
    private double ladleHp = -1;
    private boolean holed;
    private Body.Part ladle;
    private final Vector3f ladleDir = new Vector3f(-0.25f, 1, 0.1f);
    private float ladleScale = 1f;
    private final Vector aim = new Vector();
    private Player taking;
    private ItemDisplay flood;
    private int floodLevel = -1;
    private final List<double[]> sph = new ArrayList<>(2);
    private int sphAge = -1;

    public G103(Game game, Species sp) {
        super(game, sp);
        radius = HEAD_R;
        hover = HOVER;
        baseSpeed = 0;
        big = true;
        noMutate = true;
        noBite = true;
        noPush = true;
        ghost = true;
        ragKick = 0.2;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g103", 24f, 9f); // 멀리 뻗는 국자(부품은 닻 상자로 컬링)가 덜 잘리게 넓게
        body.viewRange(3f);
        ladle = body.attach("gabe/g103_ladle");
        poseLadle(0);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        // 가장 가까운 빈 물웅덩이로(다른 국자가 쓰는 웅덩이는 건너뜀)
        Vector best = null;
        double bd = Double.MAX_VALUE;
        for (Vector w : Plaza.puddles()) {
            boolean used = false;
            for (Gabe g : game.mobs.all())
                if (g != this && !g.dead && g instanceof G103 && Math.hypot(g.pos.getX() - w.getX(), g.pos.getZ() - w.getZ()) < 3) used = true;
            if (used) continue;
            double d = w.distanceSquared(pos.clone().setY(w.getY()));
            if (d < bd) {
                bd = d;
                best = w;
            }
        }
        if (best != null) pos.setX(best.getX()).setZ(best.getZ());
        face(center().setY(pos.getY()));
        body.offset.set(0, -7.5f, 0);
        body.pose(0);
        sound("bubble", Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 0.6f, 48);
        readyAt = age + 90;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 트럭 둘레 한가운데(트럭이 서 있으면 트럭, 아니면 상점 자리). */
    private static Vector center() {
        return Shop.counterCenter();
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        hold();
        if (ladleHp < 0) ladleHp = LADLE_HP * Math.max(0.5, maxHp / Math.max(1, sp.hp));
        Player p = nearest(400);
        mark(p);
        floodTick();
        switch (phase) {
            case RISE -> rise();
            case IDLE -> idle(p);
            case THROW -> throwing();
            case TAKE -> take();
            case ROD -> rod();
        }
    }

    private void rise() {
        if (inPhase() == 2) {
            body.offset.set(0, 0, 0);
            body.pose(50);
            sound("rise", Sound.ENTITY_GENERIC_SPLASH, 0.5f, 64);
            G66Giant.ring(this, pos.clone().setY(floor()), 5, 16);
        }
        if (inPhase() >= 55) phase(Phase.IDLE);
    }

    private void idle(Player p) {
        turnTo(center().setY(pos.getY()), 2f);
        if (age < readyAt) return;
        // 물이 절반 넘게 찼으면 둘레 가장자리 사람을 떠 감(따로 쿨다운)
        if (!holed && water >= WATER_MAX / 2 && age >= takeAt) {
            Player edge = edgePlayer();
            if (edge != null) {
                taking = edge;
                aim.copy(edge.getLocation().toVector());
                aim.setY(game.mobs.flow.floor(aim.getX(), aim.getZ()));
                G66Giant.warn(this, aim, TAKE_R, cd(TAKE_TELL) + 2);
                ladleTo(raisePose(), 1f, 10);
                state("open");
                phase(Phase.TAKE);
                return;
            }
        }
        if (holed) {
            Player edge = edgePlayer();
            if (edge == null) edge = p;
            if (edge == null) return;
            aim.copy(edge.getLocation().toVector());
            aim.setY(game.mobs.flow.floor(aim.getX(), aim.getZ()));
            G66Giant.warn(this, aim, ROD_R, cd(ROD_TELL) + 2);
            ladleTo(raisePose(), 1f, 12);
            state("open");
            phase(Phase.ROD);
            return;
        }
        // 끼얹을 자리: 둘레 안 서 있는 사람 하나(없으면 둘레 안 아무 데)
        Vector c = center();
        List<Player> in = new ArrayList<>();
        for (Player q : game.mobs.standing()) if (hd(q.getLocation().toVector(), c) <= RING + 2) in.add(q);
        if (!in.isEmpty()) aim.copy(in.get(game.random.nextInt(in.size())).getLocation().toVector());
        else {
            double a = game.random.nextDouble() * Math.PI * 2, r = Math.sqrt(game.random.nextDouble()) * (RING - 4);
            aim.copy(c.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r)));
        }
        aim.setY(game.mobs.flow.floor(aim.getX(), aim.getZ()));
        ladle.state("");
        ladleTo(scoopPose(), 0.75f, 14);
        phase(Phase.THROW);
    }

    private static double hd(Vector a, Vector b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** 둘레 가장자리(8~16 블록)에 선 사람 가운데 이 머리에 가장 가까운 사람. */
    private Player edgePlayer() {
        Vector c = center();
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            double r = hd(q.getLocation().toVector(), c);
            if (r < RING - 4 || r > RING + 4) continue;
            double d = hd(q.getLocation().toVector(), pos);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    /** 끼얹기: 담그기 → 들어 올림(빨간 고리) → 휘두름(물 방울이 날아감) → 맞음(물 +1) → 쉼. */
    private void throwing() {
        int t = inPhase();
        if (t == RAISE) {
            ladleTo(raisePose(), 1f, 14);
            state("open");
            G66Giant.warn(this, aim, SPLASH_R, FLING - RAISE + 10);
            sound("bubble", Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 0.6f, 48);
        } else if (t == FLING) {
            reachTo(aim, 6);
            ladle.state("empty");
            sound("splash", Sound.ENTITY_GENERIC_SPLASH, 0.6f, 64);
            blobs(aim, IMPACT - FLING);
        } else if (t == IMPACT) {
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (hd(l.toVector(), aim) <= SPLASH_R && Math.abs(l.getY() - aim.getY()) < 3) hit(q, SPLASH_DMG, "cause_g103");
            }
            G66Giant.ring(this, aim, SPLASH_R, 10);
            splashFx(aim);
            if (hd(aim, center()) <= RING + 2) water = Math.min(WATER_MAX, water + 1);
        } else if (t == REST) {
            ladleTo(restPose(), 1f, 20);
            ladle.state("");
            state("");
        } else if (t > REST) {
            readyAt = age + Math.max(10, cd(THROW_CD) - REST);
            phase(Phase.IDLE);
        }
    }

    /** 자루를 뻗어 둘레 가장자리 사람을 떠 가 가둠. */
    private void take() {
        int t = inPhase();
        if (t == cd(TAKE_TELL) - 6) reachTo(aim, 6);
        if (t >= cd(TAKE_TELL)) {
            if (standing(taking) && hd(taking.getLocation().toVector(), aim) <= TAKE_R + 0.3) {
                game.players.trap(taking, this);
                sound("splash", Sound.ENTITY_GENERIC_SPLASH, 0.6f, 64);
            }
            splashFx(aim);
            taking = null;
            ladleTo(restPose(), 1f, 24);
            state("");
            takeAt = age + cd(SCOOP_CD);
            readyAt = age + 30;
            phase(Phase.IDLE);
        }
    }

    /** 뚫린 국자: 자루로 내려치기(둘레 가장자리까지). */
    private void rod() {
        int t = inPhase();
        if (t == cd(ROD_TELL) - 4) reachTo(aim, 4);
        if (t >= cd(ROD_TELL)) {
            for (Player q : game.mobs.standing()) {
                Location l = q.getLocation();
                if (hd(l.toVector(), aim) <= ROD_R && Math.abs(l.getY() - aim.getY()) < 3) {
                    hit(q, ROD_DMG, "cause_g103_rod");
                    knock(q, l.toVector().subtract(aim), 0.4, 0.3);
                }
            }
            G66Giant.ring(this, aim, ROD_R + 0.5, 10);
            G66Giant.stomp(this, aim, 48, 4, 0.8f);
            ladleTo(restPose(), 1f, 24);
            state("");
            readyAt = age + cd(THROW_CD) / 2;
            phase(Phase.IDLE);
        }
    }

    // ------------------------------------------------------------------ 국자 자세

    private static Vector3f restPose() {
        return new Vector3f(-0.3f, 1f, 0.05f);
    }

    private static Vector3f scoopPose() {
        return new Vector3f(0.25f, -0.25f, 1f);
    }

    private static Vector3f raisePose() {
        return new Vector3f(-0.1f, 1f, -0.3f);
    }

    /** 국자 끝(그릇)이 세계 자리 at 에 닿게 겨누고 그 거리에 맞춰 키움. */
    private void reachTo(Vector at, int ticks) {
        Vector f = G66Giant.fwd(this), l = G66Giant.left(this);
        Vector pv = G66Giant.at(this, PIVOT.x, PIVOT.y, PIVOT.z);
        Vector w = at.clone().add(new Vector(0, 1.0, 0)).subtract(pv);
        float lx = (float) w.dot(l), ly = (float) w.getY(), lz = (float) w.dot(f);
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        ladleTo(new Vector3f(lx, ly, lz), Math.max(0.8f, len / (float) LADLE_LEN), ticks);
    }

    private void ladleTo(Vector3f dir, float scale, int ticks) {
        ladleDir.set(dir).normalize();
        ladleScale = scale;
        poseLadle(ticks);
    }

    private void poseLadle(int ticks) {
        if (ladle == null) return;
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), ladleDir);
        ladle.pose(new Matrix4f().translate(PIVOT).rotate(q).scale(ladleScale), ticks);
    }

    /** 국자 그릇 자리(세계). */
    private Vector cup() {
        Vector3f c = new Vector3f(0, 7.2f, 1.05f).mul(ladleScale);
        new Quaternionf().rotationTo(new Vector3f(0, 1, 0), ladleDir).transform(c);
        c.add(PIVOT);
        return G66Giant.at(this, c.x, c.y, c.z);
    }

    // ------------------------------------------------------------------ 물

    /** 국자에서 떨어질 자리로 분홍 물 방울. */
    private void blobs(Vector to, int ticks) {
        Vector from = cup();
        for (int i = 0; i < 3; i++) {
            if (!game.shotFx.room(ShotFx.NORMAL)) return;
            Location a = new Location(game.world, from.getX(), from.getY() + i * 0.4, from.getZ());
            ItemDisplay b = game.shotFx.spawn(a, "fx_quad", ShotFx.BLOOD, ShotFx.BLOOD_PINK, ShotFx.scale(2.2f + i * 0.5f), Display.Billboard.CENTER,
                false, ticks + 6, ShotFx.NORMAL);
            game.shotFx.grow(b, ShotFx.scale(3.4f), ticks);
            Location t = new Location(game.world, to.getX() + (i - 1) * 1.2, to.getY() + 0.8, to.getZ() + (i - 1) * 0.8);
            game.shotFx.drift(b, t, ticks + i);
        }
    }

    private void splashFx(Vector at) {
        for (int i = 0; i < 3; i++) {
            if (!game.shotFx.room(ShotFx.EXTRA)) return;
            double a = game.random.nextDouble() * Math.PI * 2, r = game.random.nextDouble() * 2.5;
            Location l = new Location(game.world, at.getX() + Math.cos(a) * r, at.getY() + 0.5, at.getZ() + Math.sin(a) * r);
            ItemDisplay b = game.shotFx.spawn(l, "fx_quad", ShotFx.BLOOD, ShotFx.BLOOD_PINK, ShotFx.scale(1.4f), Display.Billboard.CENTER, false, 14,
                ShotFx.EXTRA);
            game.shotFx.grow(b, ShotFx.scale(3f), 12);
        }
    }

    /** 둘레 안 물: 판 높이, 느려짐, 늪 피해, 트럭 잠김, 게이지. */
    private void floodTick() {
        if (game.state != Game.State.WAVE) {
            drain();
            return;
        }
        if (water <= 0) return;
        Vector c = center();
        if (floodLevel != water) {
            floodLevel = water;
            float h = (float) (0.06 + 0.07 * water);
            Matrix4f m = new Matrix4f().translate(0, h, 0).scale((float) RING, 1f, (float) RING);
            if (flood == null || !flood.isValid()) {
                flood = game.shotFx.spawn(new Location(game.world, c.getX(), c.getY(), c.getZ()), "fx_quad", 0, 0, m, Display.Billboard.FIXED, false,
                    24000, ShotFx.KEY);
                if (flood != null) {
                    flood.setItemStack(Body.item("gabe/g103_flood", "", new LookState(), game.worldTime()));
                    flood.setBrightness(Body.LIGHT);
                    flood.setDisplayWidth((float) (RING * 2.4));
                    flood.setDisplayHeight(3f);
                    flood.setViewRange(3f);
                }
            } else game.shotFx.grow(flood, m, 20);
        }
        if ((age + id) % 10 != 0) return;
        boolean half = water >= WATER_MAX / 2, full = water >= WATER_MAX;
        for (Player q : game.mobs.standing()) {
            Location l = q.getLocation();
            boolean inside = hd(l.toVector(), c) <= RING && l.getY() < c.getY() + 2.5;
            if (inside && half) game.players.slow(q, SLOW, 20, "g103");
            if (inside && full) hit(q, SWAMP_DPS / 2, "cause_g103_flood");
        }
        for (Player q : game.players.inPlay()) game.hud.meter(q, "g103", water / (double) WATER_MAX, 30);
        if (full && (age + id) % 20 == 0) {
            Truck t = game.trip == null ? null : game.trip.truck();
            if (t != null && t.alive()) t.hit(0.5 * t.biteDamage(sp), this, "cause_g103_flood");
        }
    }

    /** 물이 빠짐(죽음, 웨이브 밖). */
    private void drain() {
        if (flood != null && flood.isValid()) flood.remove();
        flood = null;
        floodLevel = -1;
        if (water > 0) for (Player q : game.players.inPlay()) game.hud.meter(q, "g103", -1, 0);
        water = 0;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        sph.add(new double[] {pos.getX(), pos.getY() + body.offset.y, pos.getZ(), HEAD_R});
        Vector c = cup();
        sph.add(new double[] {c.getX(), c.getY() + body.offset.y, c.getZ(), holed ? 0.9 : 1.15 * Math.max(1, ladleScale)});
        sphAge = age;
        return sph;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!holed && d.at() != null) {
            List<double[]> s = spheres();
            double[] h = s.get(0), c = s.get(1);
            double dh = d.at().distance(new Vector(h[0], h[1], h[2])) - h[3], dc = d.at().distance(new Vector(c[0], c[1], c[2])) - c[3];
            if (dc < dh) {
                ladleHp -= d.amount();
                boolean ok = super.damage(d);
                if (!dead && ladleHp <= 0) {
                    holed = true;
                    ladle.state("holed");
                    sound("crack", Sound.ENTITY_ITEM_BREAK, 0.6f, 32);
                }
                return ok;
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        drain();
        super.die(d);
    }

    @Override
    public void remove() {
        drain();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " water=" + water + " ladle=" + Math.round(ladleHp) + (holed ? " holed" : "");
    }
}
