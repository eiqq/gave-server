package kr.gave.mob.boss;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Look;
import kr.gave.mob.LookState;

/**
 * 바닥 예고 하나(docs/모듈/보스.md 6.1 신호 문법). 보스 바탕({@link Boss#disc} 등)이 만들고 매 틱 {@link #tick()} 을 부른다(보스가 사라지면 함께 지움).
 *
 * <table>
 * <tr><td>{@link Kind#DISC}</td><td>빨간 원, 안쪽 고리가 가운데에서 차올라 테두리에 닿는 순간 맞는다</td></tr>
 * <tr><td>{@link Kind#STRIP}</td><td>빨간 띠, 달려 나가는 고리가 끝에 닿는 순간 맞는다</td></tr>
 * <tr><td>{@link Kind#SHADOW}</td><td>검은 그림자 원이 짙어짐(위에서 떨어짐)</td></tr>
 * <tr><td>{@link Kind#SAFE}</td><td>초록 고리(그 안은 안전)</td></tr>
 * <tr><td>{@link Kind#WAVE}</td><td>흰 파도 고리가 바닥을 기어 퍼짐(땅에 있으면 맞음, 뛰어 넘음)</td></tr>
 * </table>
 *
 * <p>그림: 보스 팩(tools/gabe/sp/b1.py)이 공용 판 모델 {@link #M_DISC}, {@link #M_STRIP}, {@link #M_SHADOW}, {@link #M_SAFE}, {@link #M_WAVE} 를
 * 구우면 그것(아래 '판 모델 약속')으로, 없으면 ShotFx 셰이더 모양으로 대신 그린다: 원과 띠는 빨간 쓰러짐 고리(fx_flat 종류 14), 안전은 초록 고리,
 * 그림자는 바닥에 붙는 검은 그을음 자국(종류 5 변형 1), 파도는 충격파 고리(종류 7)를 3틱마다 이어 찍은 것. 발광 테두리, 블록 디스플레이 상자 하나는 쓰지 않는다.
 *
 * <p>판 모델 약속(b1.py): 모두 바닥에 눕는 얇은 판(XZ 면, 두께 1/16 아래), 가운데 (0, 0, 0). 원, 그림자, 안전, 파도는 반지름 1 블록(배율 r 로 키움),
 * 띠는 폭 1, 길이 1 이고 뒤 끝(z = 0)에서 +Z 로 뻗음(배율 (w, 1, len)). 상태: 원과 띠는 "" = 테두리 + 옅은 점묘 채움(늘 그 크기), "fill" = 짙은
 * 채움(가운데 또는 뒤 끝에서 차오름). 그림자는 "" 옅음, "dark" 짙음(60% 지점에 바뀜). 컷아웃 점묘 텍스처, 반투명 금지.
 */
public final class Tele {

    public enum Kind { DISC, STRIP, SHADOW, SAFE, WAVE }

    public static final String M_DISC = "gabe/b1_warn_disc", M_STRIP = "gabe/b1_warn_strip", M_SHADOW = "gabe/b1_shadow", M_SAFE = "gabe/b1_safe",
        M_WAVE = "gabe/b1_wave";
    /** ShotFx 고리(종류 7, 14)는 판 가운데에서 0.4 x 배율 자리에 그려진다: 반지름 r 이면 배율 r / 0.4. */
    static final float RING_K = 2.5f;
    /** 바닥에서 띄우는 높이(바닥 z 싸움 막기). */
    static double LIFT = 0.07;
    /**
     * 대체 그림 디스플레이의 컬링 상자(한 변 = 다 자란 그림 크기 x BOX, 높이 4 이상. 0 이면 안 줌). 게임 안 시험(2026-11-12): 상자를 안 주면 큰 판이
     * 보였다 안 보였다 했고(반지름 4 원이 두 번은 안 보이고 세 번은 보임), 다 자란 크기로 주면 보였다. 시험 훅 '예고값 BOX' 로 바꿔 봄.
     */
    static float BOX = 1.5f;

    static void box(ItemDisplay d, float size) {
        if (d == null || BOX <= 0) return;
        d.setDisplayWidth(Math.max(2f, size * BOX));
        d.setDisplayHeight(4f);
    }

    final Game game;
    public final Kind kind;
    /** 원의 가운데(띠는 뒤 끝). y 는 바닥 높이. */
    public final Vector at;
    /** 띠의 앞 끝(그 밖은 null). */
    public final Vector to;
    /** 반지름(띠는 폭의 반). 파도는 지금 반지름. */
    double r;
    /** 파도: 처음, 끝 반지름, 틱당 퍼지는 블록. */
    final double r0, r1, speed;
    public final long start;
    /** 예고 길이(맞는 때) 또는 사는 틱(안전, 파도는 r1 까지 퍼지는 틱). */
    public final int ticks;
    /** 끝난 뒤 남는 틱(맞은 순간이 보이게). */
    int linger = 6;
    final boolean model;
    final List<ItemDisplay> ds = new ArrayList<>(4);
    ItemDisplay inner;
    long nextRing;
    int lastQ = -1;
    boolean gone;

    private Tele(Game game, Kind kind, Vector at, Vector to, double r, double r0, double r1, double speed, int ticks) {
        this.game = game;
        this.kind = kind;
        this.at = at.clone();
        this.to = to == null ? null : to.clone();
        this.r = r;
        this.r0 = r0;
        this.r1 = r1;
        this.speed = speed;
        this.start = game.now();
        this.ticks = Math.max(1, ticks);
        this.model = Look.has(modelOf(kind));
    }

    static String modelOf(Kind k) {
        return switch (k) {
            case DISC -> M_DISC;
            case STRIP -> M_STRIP;
            case SHADOW -> M_SHADOW;
            case SAFE -> M_SAFE;
            case WAVE -> M_WAVE;
        };
    }

    // ------------------------------------------------------------------ 만들기(보스 바탕이 부름)

    static Tele disc(Game g, Vector c, double r, int ticks) {
        Tele t = new Tele(g, Kind.DISC, ground(g, c), null, r, r, r, 0, ticks);
        t.draw();
        return t;
    }

    static Tele strip(Game g, Vector from, Vector to, double width, int ticks) {
        Vector a = ground(g, from), b = ground(g, to);
        b.setY(a.getY());
        Tele t = new Tele(g, Kind.STRIP, a, b, width / 2, 0, 0, 0, ticks);
        t.draw();
        return t;
    }

    static Tele shadow(Game g, Vector c, double r, int ticks) {
        Tele t = new Tele(g, Kind.SHADOW, ground(g, c), null, r, r, r, 0, ticks);
        t.draw();
        return t;
    }

    static Tele safe(Game g, Vector c, double r, int ticks) {
        Tele t = new Tele(g, Kind.SAFE, ground(g, c), null, r, r, r, 0, ticks);
        t.linger = 0;
        t.draw();
        return t;
    }

    static Tele wave(Game g, Vector c, double r0, double r1, double speed) {
        double sp = Math.max(0.05, speed);
        Tele t = new Tele(g, Kind.WAVE, ground(g, c), null, r0, r0, r1, sp, (int) Math.ceil(Math.max(0, r1 - r0) / sp));
        t.linger = 0;
        t.draw();
        return t;
    }

    private static Vector ground(Game g, Vector v) {
        double y = g.mobs.flow.floor(v.getX(), v.getZ());
        return new Vector(v.getX(), y, v.getZ());
    }

    // ------------------------------------------------------------------읽기(맞음 판정)

    /** 지난 틱 수. */
    public int age() {
        return (int) (game.now() - start);
    }

    /** 예고 진행 0..1(1 이면 맞는 때). */
    public double progress() {
        return Math.min(1, age() / (double) ticks);
    }

    /** 맞는 때가 됐나(진행 1). 원, 띠, 그림자는 이 틱에 판정하면 된다. */
    public boolean due() {
        return age() >= ticks;
    }

    /** 지금 반지름(파도는 퍼진 만큼, 띠는 폭의 반). */
    public double radius() {
        return r;
    }

    /** p(발 자리)가 수평으로 이 예고 안인가(원, 그림자, 안전: 반지름 + pad 안, 띠: 띠 안). 파도는 {@link #passing}. */
    public boolean inside(Vector p, double pad) {
        double dx = p.getX() - at.getX(), dz = p.getZ() - at.getZ();
        if (kind == Kind.STRIP) {
            double lx = to.getX() - at.getX(), lz = to.getZ() - at.getZ(), len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1e-6) return dx * dx + dz * dz <= (r + pad) * (r + pad);
            double ux = lx / len, uz = lz / len, along = dx * ux + dz * uz, side = Math.abs(-dx * uz + dz * ux);
            return along >= -pad && along <= len + pad && side <= r + pad;
        }
        double rr = r + pad;
        return dx * dx + dz * dz <= rr * rr;
    }

    public boolean inside(Vector p) {
        return inside(p, 0);
    }

    /** 파도: 이 틱에 파도 앞이 p 를 지나나(수평 거리가 지금 반지름 ± band, 지난 틱 반지름을 포함). */
    public boolean passing(Vector p, double band) {
        double dx = p.getX() - at.getX(), dz = p.getZ() - at.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        return d >= r - speed - band && d <= r + band;
    }

    /** 원(그림자, 안전)을 옮김(따라다니는 그림자): 디스플레이도 옮긴다. 대체 그림(판 모델 없음)의 움직이는 그림자는 게임 안에서 안 보였다(확인 못 함). */
    public void move(Vector c) {
        Vector g = ground(game, c);
        if (g.distanceSquared(at) < 1e-6) return;
        at.copy(g);
        for (ItemDisplay d : ds) {
            if (d == null || !d.isValid()) continue;
            d.setTeleportDuration(3);
            d.teleport(new Location(game.world, g.getX(), g.getY() + LIFT, g.getZ()));
        }
    }

    public boolean gone() {
        return gone;
    }

    // ------------------------------------------------------------------ 그리기

    private void draw() {
        if (model) drawModel();
        else drawFx();
    }

    private Location loc(Vector v, double up) {
        return new Location(game.world, v.getX(), v.getY() + LIFT + up, v.getZ());
    }

    /** 판 모델 디스플레이 하나(바닥에 눕힌 변환 m, life 틱 뒤 지움). */
    private ItemDisplay plate(Location l, String state, Matrix4f m) {
        LookState ls = new LookState();
        ls.freeze = true;
        var item = Body.item(modelOf(kind), state, ls, game.worldTime());
        ItemDisplay d = game.world.spawn(l, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setItemStack(item);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            x.setBillboard(Display.Billboard.FIXED);
            x.setBrightness(Body.LIGHT);
            x.setViewRange(3.0f);
            x.setTransformationMatrix(m);
        });
        ds.add(d);
        return d;
    }

    private static Matrix4f flat(float sx, float sz) {
        return new Matrix4f().scale(sx, 1, sz).rotateY((float) Math.PI);
    }

    private Matrix4f stripM(float w, float len) {
        double yaw = Math.atan2(to.getX() - at.getX(), to.getZ() - at.getZ());
        return new Matrix4f().rotateY((float) yaw).scale(w, 1, len).rotateY((float) Math.PI);
    }

    private double length() {
        return to == null ? 0 : Math.hypot(to.getX() - at.getX(), to.getZ() - at.getZ());
    }

    private void drawModel() {
        float rr = (float) r;
        switch (kind) {
            case DISC -> {
                plate(loc(at, 0), "", flat(rr, rr));
                inner = plate(loc(at, 0.01), "fill", flat(0.05f, 0.05f));
                grow(inner, flat(rr, rr), ticks);
            }
            case STRIP -> {
                float len = (float) length();
                plate(loc(at, 0), "", stripM(rr * 2, len));
                inner = plate(loc(at, 0.01), "fill", stripM(rr * 2, 0.05f));
                grow(inner, stripM(rr * 2, len), ticks);
            }
            case SHADOW -> {
                inner = plate(loc(at, 0), "", flat(rr * 0.35f, rr * 0.35f));
                grow(inner, flat(rr, rr), ticks);
            }
            case SAFE -> plate(loc(at, 0), "", flat(rr, rr));
            case WAVE -> {
                inner = plate(loc(at, 0), "", flat((float) r0, (float) r0));
                grow(inner, flat((float) r1, (float) r1), ticks);
            }
        }
    }

    /** 다음 틱부터 변환을 ticks 동안 보간(생성 틱에 건 보간은 안 돎). */
    private void grow(ItemDisplay d, Matrix4f m, int t) {
        if (d == null) return;
        game.plugin.getServer().getScheduler().runTaskLater(game.plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(Math.max(1, t));
            d.setTransformationMatrix(m);
        }, 1);
    }

    private ItemDisplay ring(Vector c, double radius, boolean green, int life) {
        return ring(c, radius, green, life, radius, 0);
    }

    /** 고리 하나. grown = 다 자랐을 때 반지름(컬링 상자), up = 같은 자리의 다른 고리와 한 평면에 겹치지 않게 더 띄울 높이. */
    private ItemDisplay ring(Vector c, double radius, boolean green, int life, double grown, double up) {
        ItemDisplay d = game.shotFx.spawn(loc(c, up), "fx_flat", ShotFx.DOWN_RING, 0, ShotFx.scale((float) radius * RING_K), Display.Billboard.FIXED, true,
                life, ShotFx.KEY);
        if (d != null && green) d.setItemStack(game.shotFx.downRing(1.0, true));
        if (d != null) ds.add(d);
        box(d, (float) Math.max(radius, grown) * RING_K);
        return d;
    }

    private void drawFx() {
        int life = ticks + linger + 2;
        switch (kind) {
            case DISC -> {
                // 바깥 고리 그대로 + 안쪽 고리가 가운데에서 차올라 바깥 고리에 닿는 순간 맞음
                ring(at, r, false, life);
                inner = ring(at, Math.max(0.3, r * 0.06), false, life, r, 0.02);
                game.shotFx.grow(inner, ShotFx.scale((float) r * RING_K), ticks);
            }
            case STRIP -> {
                // 띠 가운데 줄을 따라 고리 n 개(폭 = 고리 지름), 그 위로 고리 하나가 뒤 끝에서 앞 끝으로 달려 나감
                double len = length();
                int n = (int) Math.max(2, Math.min(6, Math.round(len / Math.max(1.5, r * 2.2)) + 1));
                for (int i = 0; i < n; i++) {
                    double f = n == 1 ? 0.5 : i / (double) (n - 1);
                    ring(at.clone().add(to.clone().subtract(at).multiply(f)), r, false, life);
                }
                inner = ring(at, r * 0.6, false, life, r, 0.02);
                if (inner != null) game.shotFx.drift(inner, loc(to, 0.02), Math.min(59, ticks));
            }
            case SHADOW -> {
                // 바닥에 붙는 검은 얼룩(폭발 그을음 자국 셰이더: 예산 밖, 13초 뒤 스스로 옅어짐)이 작게 시작해 다 찰 때 제 크기가 됨
                stain(r * 0.35, r, ticks);
                nextRing = start + STAIN_LIFE;
            }
            case SAFE -> ring(at, r, true, life);
            case WAVE -> nextRing = start;
        }
    }

    /** 그을음 자국 셰이더는 판 반지름의 0.7 쯤까지 짙다: 반지름 r 이면 판 한 변 r x STAIN_K. 자국은 260틱 뒤 옅어지므로 STAIN_LIFE 마다 새로 깐다. */
    static final float STAIN_K = 2.9f;
    static final int STAIN_LIFE = 240;

    private void stain(double from, double to, int t) {
        float a = (float) from * STAIN_K, b = (float) to * STAIN_K;
        ItemDisplay d = game.shotFx.spawn(loc(at, 0.02), "fx_quad", ShotFx.DECAL, ShotFx.DECAL_SCORCH, ShotFx.along(new Vector(0, 1, 0), a, a),
                Display.Billboard.FIXED, false, Math.min(ticks + linger + 2, STAIN_LIFE + 40), ShotFx.KEY);
        if (d == null) return;
        ds.add(d);
        box(d, b);
        if (b != a) game.shotFx.grow(d, ShotFx.along(new Vector(0, 1, 0), b, b), Math.max(2, t));
    }

    // ------------------------------------------------------------------ 틱

    /** 매 틱(보스 바탕이 부름). 다 끝났으면 false(지움). */
    boolean tick() {
        if (gone) return false;
        int a = age();
        if (kind == Kind.WAVE) {
            r = Math.min(r1, r0 + speed * a);
            if (!model && game.now() >= nextRing && r < r1) {
                // 충격파 고리(9틱에 사라짐)를 3틱마다 지금 반지름에 찍고 3틱 뒤 반지름까지 키움: 이어진 파도처럼
                nextRing = game.now() + 3;
                ItemDisplay d = game.shotFx.spawn(loc(at, 0), "fx_flat", ShotFx.RING, 0, ShotFx.scale((float) Math.max(0.5, r) * RING_K),
                        Display.Billboard.FIXED, true, 10, ShotFx.KEY);
                box(d, (float) Math.min(r1, r + speed * 3) * RING_K);
                game.shotFx.grow(d, ShotFx.scale((float) Math.min(r1, r + speed * 3) * RING_K), 3);
            }
        }
        if (kind == Kind.SHADOW) {
            if (model && inner != null && inner.isValid() && a == (int) (ticks * 0.6) && Look.has(M_SHADOW)) {
                LookState ls = new LookState();
                ls.freeze = true;
                inner.setItemStack(Body.item(M_SHADOW, "dark", ls, game.worldTime()));
            }
            if (!model && game.now() >= nextRing && a < ticks) {
                nextRing = game.now() + STAIN_LIFE;
                stain(r, r, 2);
            }
        }
        if (a >= ticks + linger) {
            remove();
            return false;
        }
        return true;
    }

    /** 바로 지움(보스가 죽거나 공격이 끊길 때). */
    public void remove() {
        if (gone) return;
        gone = true;
        for (ItemDisplay d : ds) if (d != null && d.isValid()) d.remove();
        ds.clear();
    }
}
