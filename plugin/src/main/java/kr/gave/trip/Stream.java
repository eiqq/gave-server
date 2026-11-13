package kr.gave.trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.world.Plaza;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 달리는 느낌(원정.md 3.4, 5.4절): 사람도 트럭도 옮기지 않고 세상이 뒤로 흐른다. 길 가운데 점선, 땅에 붙은 먼지 줄, 길가 소품이 앞(북쪽) 안개에서 나와
 * 트럭 곁을 지나 뒤(남쪽) 안개로 사라지고, 끝에서 앞으로 되돌아간다. 이동 연출({@link Travel})이 쓰고, 추격전 특수 라운드가 그대로 다시 쓴다.
 *
 * <p>모든 조각은 가운데에 선 디스플레이 하나씩이고 자리는 변환의 옮김 값으로만 바꾼다(변환 보간으로 미끄러짐): 엔티티는 움직이지 않는다.
 * 한 조각은 끝까지 한 번에(빠르기가 일정하면 {@link #STEP_MAX} 틱 보간) 미끄러지고, 끝에 닿으면 보간 없이 앞으로 건너뛴 뒤 다음 틱부터 다시 미끄러진다.
 * 건너뛰는 자리(가운데에서 {@link #span} 블록)는 안개 끝 밖이어야 눈에 안 띈다(이동 연출은 안개 5: 끝 14, 추격전은 안개 4: 끝 26).
 *
 * <p>쓰는 법: {@code start(빠르기)} → 매 틱 {@code tick()} → {@code speed(목표, 틱)} 으로 빨라지고 느려짐 → {@code stop()}.
 * 디스플레이: 점선 {@link #DASHES} + 먼지 줄 {@link #DUSTS} + 길가 소품 {@link #PROPS} + 거리 표지 2 = 42.
 * 점선은 gave:trip/dash(길 무늬 판), 소품은 gave:trip/&lt;이름&gt;(tools/trip/route.py 가 모드에서 옮긴 것), 먼지 줄은 연출 셰이더의 먼지(fx_flat, 종류 3, 흙빛)를
 * 길게 늘여 조각마다 다시 찍는다. 팩에 모델이 없으면 그 조각은 빠진다(먼지 줄만 남아도 달리는 느낌은 난다).
 */
public final class Stream {

    public static final int DASHES = 12, DUSTS = 20, PROPS = 8;
    /** 빠르기가 일정할 때 한 번에 보간하는 틱, 빨라지고 느려지는 중일 때. */
    public static final int STEP_MAX = 10, STEP_RAMP = 2;
    /** 먼지 줄 수명(틱: 셰이더 먼지가 16틱에 사라짐), 먼지 줄 폭과 길이(블록). */
    public static final int DUST_LIFE = 16;
    public static final float DUST_W = 1.5f, DUST_L = 6.5f;
    /** 길가 소품 이름(팩에 있는 것만 씀): 앞의 것일수록 자주. */
    private static final String[] PROP_MODELS = {"trip/delineator", "trip/delineator", "trip/lamp", "trip/wreck_sedan", "trip/barrel", "trip/cone",
        "trip/post_fight", "trip/delineator_red", "trip/wreck_pickup", "trip/barrier"};

    private static final int DASH = 0, DUST = 1, PROP = 2, MARK = 3, MARK_TEXT = 4;

    private final class Piece {
        final int kind;
        final Display d;
        /** 옆(동쪽 +), 흐름 방향 자리(남쪽 +: 앞 -span 에서 뒤 +span 으로), 높이, 도는 각(라디안), 배율. */
        double x, z, y;
        float yaw, sx = 1, sy = 1, sz = 1;
        /** 이 틱까지는 보간 중(손대지 않음). */
        long until;
        /** 먼지 줄: 이번 줄이 사라질 틱. */
        long dieAt;
        boolean parked;

        Piece(int kind, Display d) {
            this.kind = kind;
            this.d = d;
        }
    }

    private final Game game;
    private final Random rnd = new Random();
    private final List<Piece> pieces = new ArrayList<>();
    private boolean running;
    /** 지금 빠르기(블록/틱), 목표, 틱당 바뀌는 양. */
    private double speed, goal, ramp;
    /** 건너뛰는 자리(가운데에서 블록). */
    public double span = 24;
    /** 흐름이 도는 각(도: 굽은 길). 0 이면 곧게 남쪽으로. */
    public double bend;
    /** 달린 거리(블록, start 뒤). */
    private double dist;
    /** 거리 표지에 적을 수(음수면 표지 없음). */
    private int markNumber = -1;
    private String markSign = "launch";

    public Stream(Game game) {
        this.game = game;
    }

    public boolean running() {
        return running;
    }

    public int displays() {
        return pieces.size();
    }

    /** 달린 거리(블록). */
    public double distance() {
        return dist;
    }

    public double speed() {
        return speed;
    }

    /** 거리 표지(뒤로 흐르는 판 하나)에 적을 수와 판 상태. start 앞에 부른다. 음수면 표지를 안 세움. */
    public void mark(int number, String sign) {
        markNumber = number;
        markSign = sign == null ? "launch" : sign;
    }

    /** 흐름을 켠다(조각을 만들고 0 에서 목표 빠르기까지 rampTicks 동안 빨라짐). 이미 켜져 있으면 목표만 바꾼다. */
    public void start(double target, int rampTicks) {
        if (!running) {
            running = true;
            speed = 0;
            dist = 0;
            build();
        }
        speed(target, rampTicks);
    }

    /** 목표 빠르기를 바꾼다(ticks 동안 고르게). */
    public void speed(double target, int ticks) {
        goal = Math.max(0, target);
        ramp = ticks <= 0 ? Double.MAX_VALUE : Math.abs(goal - speed) / ticks;
    }

    /** 흐름을 끄고 조각을 치운다. */
    public void stop() {
        running = false;
        speed = goal = 0;
        for (Piece p : pieces) if (p.d != null && p.d.isValid()) p.d.remove();
        pieces.clear();
    }

    // ------------------------------------------------------------------ 조각 만들기

    private Location origin() {
        return new Location(game.world, Plaza.CX + 0.5, Plaza.FLOOR_Y, Plaza.CZ + 0.5);
    }

    private void build() {
        Location o = origin();
        if (Scenery.has(game, "trip/dash")) {
            ItemStack dash = Scenery.item("trip/dash");
            for (int i = 0; i < DASHES; i++) {
                Piece p = new Piece(DASH, item(o, dash, 15, true));
                p.x = 0;
                p.y = 0.03;
                p.z = -span + (2 * span) * i / DASHES;
                p.sx = 1.3f;
                p.sz = 2.6f;
                // 점선 판은 월드 소품 꼴(바닥이 엔티티 y - 0.5): 바닥에 붙게 올린다
                p.y += 0.5;
                pieces.add(p);
                apply(p, 0);
            }
        }
        for (int i = 0; i < DUSTS; i++) {
            Piece p = new Piece(DUST, item(o, game.shotFx.fx("fx_flat", ShotFx.DUST, 2), -1, false));
            p.parked = true;
            p.until = game.now() + 1 + rnd.nextInt(DUST_LIFE);
            p.sx = p.sz = 0.01f;
            p.y = 0.05;
            pieces.add(p);
            apply(p, 0);
        }
        List<String> models = new ArrayList<>();
        for (String m : PROP_MODELS) if (Scenery.has(game, m)) models.add(m);
        if (!models.isEmpty()) {
            for (int i = 0; i < PROPS; i++) {
                Piece p = new Piece(PROP, item(o, Scenery.item(models.get(rnd.nextInt(models.size()))), -1, true));
                p.z = -span + (2 * span) * (i + rnd.nextDouble() * 0.6) / PROPS;
                prop(p, models, false);
                pieces.add(p);
                apply(p, 0);
            }
        }
        if (markNumber >= 0 && Scenery.has(game, "trip/post_" + markSign)) {
            Piece sign = new Piece(MARK, item(o, Scenery.item("trip/post_" + markSign), 15, true));
            sign.x = 5.2;
            sign.y = 0.8;
            sign.sx = sign.sy = sign.sz = 1.6f;
            sign.z = -span;
            pieces.add(sign);
            apply(sign, 0);
            TextDisplay t = game.world.spawn(o, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.FIXED);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(true);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(1.2f);
                d.text(Component.text(Integer.toString(markNumber), NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
            });
            Piece text = new Piece(MARK_TEXT, t);
            text.x = 5.2;
            text.y = 3.3;
            text.sx = text.sy = text.sz = 3.0f;
            text.z = -span;
            pieces.add(text);
            apply(text, 0);
        }
    }

    /** 조각 디스플레이. fixed: 월드 소품 꼴 모델(fixed 표시 변환), 아니면 연출 판(fx_flat: 표시 변환 없이). */
    private ItemDisplay item(Location o, ItemStack it, int light, boolean fixed) {
        return game.world.spawn(o, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(it);
            d.setItemDisplayTransform(fixed ? ItemDisplay.ItemDisplayTransform.FIXED : ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            if (light >= 0) d.setBrightness(new Display.Brightness(light, light));
            d.setViewRange(1.2f);
        });
    }

    /** 길가 소품을 새 자리(길 양옆)와 새 모델로. */
    private void prop(Piece p, List<String> models, boolean swap) {
        boolean east = rnd.nextBoolean();
        p.x = (east ? 1 : -1) * (Scenery.ROAD_HALF + 2 + rnd.nextDouble() * 7);
        p.y = 0.5;
        p.yaw = east ? (float) (Math.PI / 2) : (float) (-Math.PI / 2);
        if (swap && p.d instanceof ItemDisplay id) id.setItemStack(Scenery.item(models.get(rnd.nextInt(models.size()))));
    }

    // ------------------------------------------------------------------ 매 틱

    public void tick() {
        if (!running) return;
        slide();
        boolean ramping = speed != goal;
        if (ramping) speed = Math.abs(goal - speed) <= ramp ? goal : speed + Math.signum(goal - speed) * ramp;
        dist += speed;
        long now = game.now();
        if (speed <= 1e-6) return;
        int stepMax = ramping ? STEP_RAMP : STEP_MAX;
        List<String> models = null;
        for (Piece p : pieces) {
            if (p.until > now) continue;
            if (p.d == null || !p.d.isValid()) continue;
            if (p.kind == DUST) {
                dust(p, now);
                continue;
            }
            if (p.kind == MARK_TEXT || p.kind == MARK) {
                // 거리 표지는 한 번만 지나간다
                if (p.z >= span) continue;
            } else if (p.z >= span) {
                // 끝: 보간 없이 앞으로 건너뛰고 다음 틱부터 다시
                p.z -= 2 * span;
                if (p.kind == PROP) {
                    if (models == null) {
                        models = new ArrayList<>();
                        for (String m : PROP_MODELS) if (Scenery.has(game, m)) models.add(m);
                    }
                    if (!models.isEmpty()) prop(p, models, true);
                }
                apply(p, 0);
                p.until = now + 1;
                continue;
            }
            int step = (int) Math.max(1, Math.min(stepMax, Math.ceil((span - p.z) / speed)));
            p.z += speed * step;
            apply(p, step);
            p.until = now + step;
        }
    }

    /** 먼지 줄: 줄 하나가 수명 동안 뒤로 미끄러지며 사라지고, 사라지면 새 자리에서 다시 찍는다. */
    private void dust(Piece p, long now) {
        if (!p.parked && now < p.dieAt) return;
        // 새 줄: 길 양옆 가까이에서 시작해 수명 동안 뒤로
        boolean east = rnd.nextBoolean();
        p.x = (east ? 1 : -1) * (1.6 + rnd.nextDouble() * 9);
        p.z = -8 + rnd.nextDouble() * 9;
        p.sx = DUST_W * (0.7f + rnd.nextFloat() * 0.6f);
        p.sz = DUST_L * (float) Math.min(1.4, 0.4 + speed);
        p.parked = false;
        if (p.d instanceof ItemDisplay id) id.setItemStack(game.shotFx.fx("fx_flat", ShotFx.DUST, 2));
        apply(p, 0);
        // 다음 틱에 미끄러지기 시작(찍은 틱의 건너뜀과 보간이 한 묶음으로 가지 않게)
        p.dieAt = now + 1 + DUST_LIFE;
        p.until = now + 1;
        p.z += speed * DUST_LIFE;
        pending.add(p);
    }

    /** 다음 틱에 미끄러지기를 시작할 먼지 줄. */
    private final List<Piece> pending = new ArrayList<>();

    /** 지난 틱에 새로 찍은 먼지 줄을 수명 동안 뒤로 미끄러뜨린다(tick 맨 앞). */
    private void slide() {
        if (pending.isEmpty()) return;
        for (Piece p : pending) {
            if (p.d == null || !p.d.isValid()) continue;
            apply(p, DUST_LIFE);
            p.until = p.dieAt;
        }
        pending.clear();
    }

    /** 조각의 변환을 지금 값으로(ticks 동안 보간, 0 이면 바로). */
    private void apply(Piece p, int ticks) {
        Matrix4f m = new Matrix4f();
        if (bend != 0) m.rotateY((float) Math.toRadians(-bend));
        m.translate((float) p.x, (float) p.y, (float) p.z).rotateY(p.yaw).scale(p.sx, p.sy, p.sz);
        p.d.setInterpolationDelay(0);
        p.d.setInterpolationDuration(ticks);
        p.d.setTransformationMatrix(m);
    }
}
