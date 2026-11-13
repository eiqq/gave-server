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

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Species;

/**
 * G20 얼굴 곡예 게이브(원거리, 방해). 얼굴 없는 X자 엔드 막대기 몸 위 공중에서 똑같은 얼굴 셋이 세로 바퀴(30틱 한 바퀴)를 돈다. 얼굴이 바퀴 아래를
 * 지날 때마다 막대기 손이 튕겨 받는다: 가짜 둘은 받을 때마다 납작 찌그러지고('톡'), 진짜 하나는 찌그러지지 않고('찰박') 표적을 찍으면 눈을 뜬 채
 * 바퀴가 도는 동안에도 고개만 돌려 늘 그 사람을 본다(가짜는 공처럼 구르며 웃음).
 *
 * <p>10~15블록 거리를 지키며 옆으로 미끄러지고, 4~5초마다 가짜 얼굴 하나를 표적 발밑으로 포물선으로 던진다. 떨어진 자리에서 웃으며 터지고 거미줄이
 * 퍼진다(반지름 2.1: 사람은 묶임 1.5초와 2 피해, 다른 게이브는 1.5초 제자리). 던진 가짜는 3초 뒤 바퀴에 다시 돋는다.
 *
 * <p>맞음: 탄은 맞은 점에 가장 가까운 얼굴로 친다. 진짜면 피해(체력이 다 하면 셋 다 죽음), 가짜면 피해 없이 그 얼굴이 찌그러지고 쏜 사람 화면에만
 * 분홍 잔상 1초(덮개 g20_smear). X 막대기는 맞지 않는다(탄이 지나감). 폭발과 불은 그대로.
 *
 * <p>디스플레이 넷: 닻(X 막대기, gave:gabe/g20) + 얼굴 부품 셋(gave:gabe/g20_face). 던진 얼굴과 거미줄은 연출 예산의 디스플레이(gave:gabe/g20_face,
 * gave:gabe/g20_web 를 실음). 소리 tok, chalbak, toss, pop, 원인 글귀 cause_g20.
 */
public final class G20 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.13;
    /** 지키는 거리(블록). */
    static final double NEAR = 10, FAR = 15;
    /** 바퀴: 한 바퀴 틱, 가운데 높이(몸 기준), 가로, 세로 반지름, 얼굴 구 반지름. */
    static final int WHEEL = 30;
    static final double WY = 1.25, WX = 0.6, WR = 0.72, FACE_R = 0.3;
    /** 던지기 간격(틱), 사거리, 날아가는 빠르기(블록/틱), 다시 돋는 틱. */
    static final int THROW_EVERY = 90, REGROW = 60;
    static final double THROW_RANGE = 17, FLY_V = 0.55;
    /** 거미줄: 반지름, 묶는 틱, 피해. */
    static final double WEB_R = 2.1, WEB_DMG = 7;
    static final int WEB_TICKS = 30;
    /** 분홍 잔상 틱. */
    static final int SMEAR = 20;

    private final Body.Part[] faces = new Body.Part[3];
    private final boolean[] away = new boolean[3];
    private final int[] backAt = {-1, -1, -1}, squashUntil = {-1, -1, -1};
    private int real;
    private double wheel;
    private int nextThrow, strafe = 1, strafeAt, tokAt = -100;
    // 날아가는 가짜
    private ItemDisplay flying;
    private int flyFace = -1, flyAt, flyTicks;
    private final Vector flyFrom = new Vector(), flyTo = new Vector();
    private double flyArc;

    public G20(Game game, Species sp) {
        super(game, sp);
        radius = 0.5;
        hover = 1.1; // X 막대기 아래 끝이 바닥 가까이
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/" + sp.key(), 2.2f, 4.4f);
        for (int i = 0; i < 3; i++) {
            faces[i] = body.attach("gabe/g20_face");
            if (faces[i] != null) faces[i].local.set(local(i, 0));
        }
    }

    @Override
    protected void spawned() {
        real = game.random.nextInt(3);
        // 손(막대기 끝 마디)이 가장 올라가는 자세는 20틱 주기의 7.5~10틱, 17.5~20틱(g20.py 의 flap 물결, 좌우 반 바퀴 어긋남)이다.
        // 얼굴이 바퀴 아래를 지나는 때(wheel 을 10 으로 나눈 나머지 2.5)가 거기 오게 바퀴를 월드 틱에 맞춘다. 위상은 5틱 단위(틱이 딱 떨어짐)
        body.look.phase = 5 * game.random.nextInt(4);
        wheel = Math.floorMod(game.worldTime() + body.look.phase + 5, 10L) + 10 * game.random.nextInt(3);
        nextThrow = age + 40 + game.random.nextInt(40);
        strafe = game.random.nextBoolean() ? 1 : -1;
        for (Body.Part f : faces) if (f != null) f.look.phase = body.look.phase;
    }

    // ------------------------------------------------------------------ 바퀴

    private double angle(int i) {
        return 2 * Math.PI * (wheel / WHEEL) + i * 2 * Math.PI / 3;
    }

    /** 얼굴 i 의 몸 기준 자리. */
    private Vector localPos(int i) {
        double a = angle(i);
        return new Vector(WX * Math.cos(a), WY + WR * Math.sin(a), 0.05);
    }

    /** 몸 기준 → 세계(몸 yaw 로 돌림). */
    private Vector world(Vector l) {
        double a = Math.toRadians(-yaw);
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(pos.getX() + c * l.getX() + s * l.getZ(), pos.getY() + l.getY(), pos.getZ() - s * l.getX() + c * l.getZ());
    }

    /** 부품 자세: 가짜는 공처럼 구르고(받을 때 납작), 진짜는 늘 표적 쪽으로 고개만. */
    private Matrix4f local(int i, double ang) {
        Vector l = localPos(i);
        Matrix4f m = new Matrix4f().translate((float) l.getX(), (float) l.getY(), (float) l.getZ());
        if (i == real) {
            Player t = target;
            if (t != null) {
                Vector d = t.getEyeLocation().toVector().subtract(world(l));
                double a = Math.toRadians(yaw);
                double lx = Math.cos(a) * d.getX() + Math.sin(a) * d.getZ();
                double lz = -Math.sin(a) * d.getX() + Math.cos(a) * d.getZ();
                float ly = (float) Math.atan2(-lx, lz);
                float lp = (float) -Math.atan2(d.getY(), Math.hypot(lx, lz));
                m.rotateY(-ly).rotateX(lp);
            }
        } else {
            m.rotateZ((float) -ang * 1.0f);
            if (age < squashUntil[i]) m.scale(1.3f, 0.55f, 1.3f);
        }
        return m;
    }

    private void wheel() {
        wheel += 1;
        // 바퀴 아래(손)를 지나는 얼굴: 270도 근처를 넘는 틱
        for (int i = 0; i < 3; i++) {
            double a = angle(i) % (2 * Math.PI);
            double prev = (a - 2 * Math.PI / WHEEL + 2 * Math.PI) % (2 * Math.PI);
            double bottom = 1.5 * Math.PI;
            if (prev < bottom && a >= bottom && !away[i]) catchFace(i);
        }
        if (!every(2)) return;
        for (int i = 0; i < 3; i++) {
            Body.Part f = faces[i];
            if (f == null || away[i]) continue;
            f.pose(local(i, angle(i)), 2);
        }
    }

    private void catchFace(int i) {
        if (i == real) {
            sound("chalbak", Sound.ENTITY_SLIME_SQUISH, 0.9f, 20);
        } else {
            squashUntil[i] = age + 3;
            if (faces[i] != null) faces[i].pose(local(i, angle(i)), 1);
            sound("tok", Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 1.6f, 20);
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        wheel();
        Player p = nearest(40);
        if (p != null && sees(p) && dist(p) <= FAR + 3) {
            if (target != p) mark(p);
        } else if (target != null) mark(null);
        keepRange(p);
        if (faces[real] != null) faces[real].look.open = target != null;
        for (Body.Part f : faces) if (f != null) f.look.mut = body.look.mut;
        regrow();
        fly();
        if (target != null && age >= nextThrow && flyFace < 0 && dist(target) <= THROW_RANGE) throwFace(target);
    }

    /** 10~15블록을 지키며 옆으로 미끄러짐. */
    private void keepRange(Player p) {
        double v = speed();
        if (p == null) {
            follow(v);
            faceAlong();
            return;
        }
        Vector to = toward(p);
        Vector side = new Vector(to.getZ(), 0, -to.getX()).multiply(strafe);
        double d = Math.hypot(chest(p).getX() - pos.getX(), chest(p).getZ() - pos.getZ());
        if (d > FAR) {
            follow(v);
            vel.add(side.clone().multiply(v * 0.3));
        } else if (d < NEAR) {
            vel.copy(to.clone().multiply(-v).add(side.clone().multiply(v * 0.5)));
        } else {
            vel.copy(side.clone().multiply(v * 0.8));
        }
        if (age - strafeAt > 70 + (id % 5) * 10 || wallAhead(0.8)) {
            strafe = -strafe;
            strafeAt = age;
        }
        face(chest(p));
        pitch = 0;
    }

    private void faceAlong() {
        if (Math.hypot(vel.getX(), vel.getZ()) > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        pitch = 0;
    }

    // ------------------------------------------------------------------ 던지기

    private void throwFace(Player p) {
        int pick = -1;
        double best = -1e9;
        for (int i = 0; i < 3; i++) {
            if (i == real || away[i]) continue;
            double y = localPos(i).getY();
            if (y > best) {
                best = y;
                pick = i;
            }
        }
        nextThrow = age + cd(THROW_EVERY) + game.random.nextInt(30);
        if (pick < 0) return;
        Vector from = world(localPos(pick));
        Location l = p.getLocation();
        Vector lead = p.getVelocity().clone().setY(0).multiply(8);
        Vector to = new Vector(l.getX() + lead.getX(), 0, l.getZ() + lead.getZ());
        to.setY(game.mobs.flow.floor(to.getX(), to.getZ()) + 0.25);
        double dist = from.distance(to);
        int ticks = (int) Math.max(14, Math.min(34, dist / FLY_V));
        ItemDisplay d = game.shotFx.spawn(from.toLocation(game.world), "fx_quad", 0, 0, new Matrix4f(), Display.Billboard.FIXED, false, ticks + 6,
                ShotFx.NORMAL);
        if (d == null) return;
        LookState ls = new LookState();
        ls.phase = body.look.phase;
        d.setItemStack(Body.item("gabe/g20_face", "", ls, game.worldTime()));
        d.setBrightness(Body.LIGHT);
        d.setTeleportDuration(2);
        flying = d;
        flyFace = pick;
        flyAt = age;
        flyTicks = ticks;
        flyFrom.copy(from);
        flyTo.copy(to);
        flyArc = 1.6 + dist * 0.12;
        away[pick] = true;
        if (faces[pick] != null) faces[pick].model(Body.EMPTY);
        sound("toss", Sound.ENTITY_SNOWBALL_THROW, 0.7f, 24);
    }

    private Vector flyPos(int t) {
        double u = Math.min(1.0, (double) t / flyTicks);
        Vector p = flyFrom.clone().multiply(1 - u).add(flyTo.clone().multiply(u));
        p.setY(p.getY() + 4 * flyArc * u * (1 - u));
        return p;
    }

    private void fly() {
        if (flyFace < 0) return;
        int t = age - flyAt;
        if (flying != null && flying.isValid() && t % 2 == 0) {
            flying.teleport(flyPos(t + 2).toLocation(game.world));
            flying.setInterpolationDelay(0);
            flying.setInterpolationDuration(2);
            flying.setTransformationMatrix(new Matrix4f().rotateZ(t * 0.45f).rotateX(t * 0.2f));
        }
        if (t >= flyTicks) burst(flyTo.clone());
    }

    /** 떨어진 자리에서 웃으며 터지고 거미줄이 퍼짐. */
    private void burst(Vector at) {
        int i = flyFace;
        flyFace = -1;
        if (flying != null && flying.isValid()) flying.remove();
        flying = null;
        backAt[i] = age + REGROW;
        Location l = at.toLocation(game.world);
        if (!game.director.silent())
            Props.soundAround(l, "gabe." + sp.key() + ".pop", Sound.ENTITY_SLIME_ATTACK, 1f, 0.95f + game.random.nextFloat() * 0.1f, 1.4f, 24, -1);
        double fl = game.mobs.flow.floor(at.getX(), at.getZ());
        ItemDisplay web = game.shotFx.spawn(new Location(game.world, at.getX(), fl + 0.04, at.getZ()), "fx_quad", 0, 0, ShotFx.scale(1.2f),
                Display.Billboard.FIXED, false, WEB_TICKS + 6, ShotFx.NORMAL);
        if (web != null) {
            web.setItemStack(Body.item("gabe/g20_web", "", new LookState(), game.worldTime()));
            game.shotFx.grow(web, new Matrix4f().rotateY(game.random.nextFloat() * 6.28f).scale((float) (WEB_R * 2)), 4);
        }
        for (Player p : game.mobs.standing()) {
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - at.getX(), pl.getZ() - at.getZ()) > WEB_R || Math.abs(pl.getY() - fl) > 2) continue;
            hit(p, WEB_DMG, "cause_g20");
            game.players.root(p, WEB_TICKS, "g20_web");
        }
        // 가짜를 다른 게이브 무리 위에서 터뜨리면 그쪽이 묶임(제자리로 끌림). 종의 lure 로 불러 사람에게 붙어 있는 종은 스스로 무시하게 둔다
        for (Gabe g : game.mobs.near(at, WEB_R + 0.5, x -> x != this && !x.dead && !x.untargetable)) {
            try {
                g.lure(g.pos.clone(), WEB_TICKS);
            } catch (RuntimeException e) {
                // 남의 종 코드가 던져도 이 거미줄은 계속
            }
        }
    }

    private void regrow() {
        for (int i = 0; i < 3; i++) {
            if (!away[i] || backAt[i] < 0 || age < backAt[i]) continue;
            away[i] = false;
            backAt[i] = -1;
            Body.Part f = faces[i];
            if (f != null) {
                f.model("gabe/g20_face");
                f.pose(new Matrix4f(local(i, angle(i))).scale(0.2f), 0);
            }
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            if (away[i]) continue;
            Vector w = world(localPos(i));
            out.add(new double[] {w.getX(), w.getY(), w.getZ(), FACE_R});
        }
        if (out.isEmpty()) out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.2});
        return out;
    }

    @Override
    public Vector head() {
        return world(localPos(real));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            int best = -1;
            double bd = 1e9;
            for (int i = 0; i < 3; i++) {
                if (away[i]) continue;
                double dd = world(localPos(i)).distanceSquared(d.at());
                if (dd < bd) {
                    bd = dd;
                    best = i;
                }
            }
            if (best >= 0 && best != real) {
                squashUntil[best] = age + 4;
                if (faces[best] != null) faces[best].pose(local(best, angle(best)), 1);
                Player by = d.by();
                // 비옷을 입었으면 잔상이 묻지 않는다(1초짜리 잔상에 비옷이 찢어지지 않게 덮기를 걸지 않음)
                if (by != null && by.isOnline() && game.players.get(by).raincoat <= 0) game.players.cover(by, "g20_smear", SMEAR);
                if (age - tokAt >= 3) {
                    tokAt = age;
                    sound("tok", Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 1.2f, 20);
                }
                return false;
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (flying != null && flying.isValid()) flying.remove();
        flying = null;
        flyFace = -1;
        super.die(d);
    }

    @Override
    public void remove() {
        if (flying != null && flying.isValid()) flying.remove();
        flying = null;
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " real=" + real + " away=" + (away[0] ? 1 : 0) + (away[1] ? 1 : 0) + (away[2] ? 1 : 0)
                + (flyFace >= 0 ? " fly=" + flyFace : "");
    }
}
