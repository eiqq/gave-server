package kr.gave.trip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;

/**
 * 4막 발사장 투광등 넷(원정.md 2.1, 5.3, 10절): 판마다 한 벌({@link #of(Game)}). 발사장(Launch), 투광등 라운드(special.LitPad), 보스 B5 가 같이 쓴다
 * (약속은 build/trip_special_b/progress.md 맨 위).
 *
 * <p>등 하나({@link Lamp})는 엔진 빛(LIGHTS, {@link Mobs.Light})으로 올라가 {@code game.mobs.lit(자리)}, {@code dark(자리)} 가 본다. 등은 가운데에서 R
 * {@link #R} 대각선 넷이고 자기 사분면을 비춘다(반경 {@link #R_LIT}). 끄는 길은 둘: {@link Lamp#off}(누가 끄고 그것이 풀면 켜짐: 하늘 종이 머리에 앉음 같은
 * 것)와 {@link Lamp#smash}(내리쳐 부숨: 발전기로만 다시 켜짐). 발전기는 등 밑동 R {@link #GEN_R} 안에 서 있으면 {@link #GEN_TICKS} 틱에 다시 켠다.
 *
 * <p>꺼진 사분면: 그 안 게이브는 빠르기 x {@link #DARK_SPEED} 에 몸 빛을 0 으로(실눈만 보임), 켜진 쪽 땅에는 빛 블록(LIGHT 15)을 깔아 실제로 밝다.
 * 틱은 엔진 판 갈고리(Mobs.field, 키 "trip_padlights")가 돌린다: 판이 바뀌면 엔진이 reset 을 불러 내려진다.
 *
 * <p>모델: TrafficCraft(GPL-3.0) 쌍둥이 가로등 머리와 기둥(tools/trip/padlamp.py: gave:trip/pad_lamp, pad_lamp_off). 원정 층이 없으면 바닐라 랜턴 아이템.
 */
public final class PadLights {

    /** 등 자리 반지름(대각선 넷), 비추는 반경, 발전기 거리, 꺼진 사분면 게이브 빠르기, 꺼진 등 하나마다 테마 어둡게 몫, 등 높이(블록). */
    public static double R = 30, R_LIT = 34, GEN_R = 2.4, DARK_SPEED = 1.35, DIM_PER_LAMP = 0.06, HEIGHT = 11;
    /** 발전기 채널 틱, 꺼진 사분면 판정을 다시 하는 틱 간격. */
    public static int GEN_TICKS = 100, DARK_EVERY = 5;
    /** 원정 층이 없을 때 바닐라 랜턴 배율, 빛 블록을 까는 거리(등 밑동에서 가운데 쪽으로). */
    public static float MODEL_SCALE = 3.0f;
    public static double[] LIGHT_AT = {2, 9, 16, 23};
    public static final String MODEL = "trip/pad_lamp", MODEL_OFF = "trip/pad_lamp_off", METER = "sp_litpad", FIELD = "trip_padlights";

    private static final Map<Game, PadLights> ALL = new WeakHashMap<>();

    /** 이 판의 투광등 한 벌(없으면 만듦: 세우지는 않음). */
    public static synchronized PadLights of(Game game) {
        return ALL.computeIfAbsent(game, PadLights::new);
    }

    /** 이미 만든 것만(없으면 null). */
    public static synchronized PadLights peek(Game game) {
        return ALL.get(game);
    }

    /** 각(라디안, 0 = +x 동, PI/2 = +z 남)의 사분면 0..3. 등 i 는 각 PI/4 + i x PI/2 에 서서 사분면 i 를 비춘다. */
    public static int quadrant(double x, double z) {
        double a = Math.atan2(z - Plaza.CZ - 0.5, x - Plaza.CX - 0.5);
        if (a < 0) a += Math.PI * 2;
        return (int) (a / (Math.PI / 2)) & 3;
    }

    public final class Lamp implements Mobs.Light {
        public final int index;
        public final double ang;
        /** 밑동(바닥) 자리, 머리 자리. */
        public final Vector base, head;
        private final Set<Object> offBy = new HashSet<>();
        private boolean smashed;
        private double gen;
        private ItemDisplay d;
        private final List<Block> lightBlocks = new ArrayList<>();
        private boolean shownLit;

        Lamp(int i) {
            index = i;
            ang = Math.PI / 4 + i * Math.PI / 2;
            double x = Plaza.CX + 0.5 + Math.cos(ang) * R, z = Plaza.CZ + 0.5 + Math.sin(ang) * R;
            base = new Vector(Math.floor(x) + 0.5, Plaza.FLOOR_Y, Math.floor(z) + 0.5);
            head = base.clone().add(new Vector(0, HEIGHT, 0));
        }

        @Override
        public Vector pos() {
            return base;
        }

        @Override
        public double radius() {
            return R_LIT;
        }

        @Override
        public boolean lit() {
            return raised && !smashed && offBy.isEmpty();
        }

        @Override
        public void off(Object by, boolean off) {
            if (by == null) return;
            boolean was = lit();
            if (off) offBy.add(by);
            else offBy.remove(by);
            if (was != lit()) changed(was);
        }

        @Override
        public boolean gone() {
            return !raised;
        }

        /** 내리쳐 끔(발전기로만 다시 켜짐). */
        public void smash(Object by) {
            boolean was = lit();
            smashed = true;
            gen = 0;
            if (was) changed(true);
        }

        /** 다시 켬(끈 것을 모두 풂). */
        public void relight() {
            boolean was = lit();
            smashed = false;
            offBy.clear();
            gen = 0;
            if (!was && lit()) changed(false);
        }

        public boolean smashed() {
            return smashed;
        }

        /** 끈 것 수(내리침 빼고). */
        public int offCount() {
            return offBy.size();
        }

        /** 발전기 몫 0..1. */
        public double gen() {
            return gen / GEN_TICKS;
        }

        private void changed(boolean wasLit) {
            draw();
            Location at = new Location(game.world, head.getX(), head.getY(), head.getZ());
            if (wasLit) {
                Props.soundAround(at, "trip.lamp_off", Sound.BLOCK_GLASS_BREAK, 1.2f, 0.9f, 0.7f, 96, -1);
                try {
                    game.shotFx.pop(at);
                } catch (RuntimeException e) {
                    // 효과 예산이 없으면 소리만
                }
            } else Props.soundAround(at, "trip.lamp_on", Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1f, 1.4f, 96, -1);
            darkDirty = true;
        }

        private void draw() {
            boolean on = lit();
            if (d == null || !d.isValid()) {
                // 월드 소품 꼴(모델 가운데 = 엔티티 자리, 바닥은 그 0.5 아래, fixed 표시 배율): 팔 둘이 가운데 쪽과 바깥쪽으로(모델 z 축을 반지름 쪽으로)
                boolean pack = Truck.live();
                d = game.world.spawn(new Location(game.world, base.getX(), Plaza.FLOOR_Y + (pack ? 0.5 : 0.5 * MODEL_SCALE), base.getZ()), ItemDisplay.class, e -> {
                    e.setPersistent(false);
                    e.setItemDisplayTransform(pack ? ItemDisplay.ItemDisplayTransform.FIXED : ItemDisplay.ItemDisplayTransform.NONE);
                    e.setBillboard(Display.Billboard.FIXED);
                    e.setViewRange(4f);
                    e.setDisplayWidth(5f);
                    e.setDisplayHeight((float) HEIGHT * 2f + 2f);
                    float yaw = (float) Math.atan2(Math.cos(ang), Math.sin(ang));
                    e.setTransformationMatrix(new Matrix4f().rotateY(yaw).scale(pack ? 1f : MODEL_SCALE));
                });
                shownLit = !on;
            }
            if (shownLit != on) {
                shownLit = on;
                d.setItemStack(item(on));
                d.setBrightness(on ? new Display.Brightness(15, 15) : new Display.Brightness(3, 3));
                lights(on);
            }
        }

        private void lights(boolean on) {
            if (!on) {
                for (Block b : lightBlocks) if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
                lightBlocks.clear();
                return;
            }
            if (!lightBlocks.isEmpty()) return;
            for (double s : LIGHT_AT) {
                double x = base.getX() - Math.cos(ang) * s, z = base.getZ() - Math.sin(ang) * s;
                for (int side = -1; side <= 1; side += 2) {
                    if (s < 5 && side > 0) continue;
                    double w = s < 5 ? 0 : s * 0.35 * side;
                    int bx = (int) Math.floor(x - Math.sin(ang) * w), bz = (int) Math.floor(z + Math.cos(ang) * w);
                    Block b = game.world.getBlockAt(bx, Plaza.FLOOR_Y + 1, bz);
                    if (!b.getType().isAir()) continue;
                    b.setBlockData(Bukkit.createBlockData("minecraft:light[level=15]"), false);
                    lightBlocks.add(b);
                }
            }
        }

        private void remove() {
            if (d != null && d.isValid()) d.remove();
            d = null;
            lights(false);
        }
    }

    final Game game;
    private final Lamp[] lamps = new Lamp[4];
    private boolean raised;
    private boolean generators = true, darkDirty;
    private Object owner;
    /** 남(Launch)이 세운 등을 빌려 쓰는 중이면 그 등들(모델과 빛 블록은 그쪽 것, 여기는 발전기와 꺼진 사분면만). */
    private final List<Mobs.Light> adopted = new ArrayList<>();
    private Object adoptedBy;
    private final Map<Mobs.Light, double[]> genOf = new IdentityHashMap<>();
    /** 남의 등을 부술 때 끄는 키(Mobs.Light.off 의 누가). */
    public static final Object SMASH = new Object() {
        @Override
        public String toString() {
            return "padlights.smash";
        }
    };
    /** 몸 빛을 0 으로 낮춘 게이브(되돌리기). */
    private final Map<Gabe, Boolean> darkened = new IdentityHashMap<>();

    private PadLights(Game game) {
        this.game = game;
        for (int i = 0; i < 4; i++) lamps[i] = new Lamp(i);
    }

    // ------------------------------------------------------------------ 공개

    /** 세움(이미 서 있으면 아무 일 없음). by = 세운 쪽(내릴 때 같은 쪽만 내리게: Launch 가 세운 것을 특수가 안 내림). 남의 등을 빌려 쓰는 중이면 그것을 풂. */
    public void raise(Object by) {
        if (raised) return;
        if (!adopted.isEmpty()) release(adoptedBy);
        raised = true;
        owner = by;
        for (Lamp l : lamps) {
            l.smashed = false;
            l.offBy.clear();
            l.gen = 0;
            l.shownLit = false;
            l.draw();
            game.mobs.addLight(l);
        }
        darkDirty = true;
        field();
    }

    /** 세움(세운 쪽 없음). */
    public void raise() {
        raise(null);
    }

    /**
     * 남(발사장 Launch)이 세운 등을 빌려 씀: 모델과 빛은 그쪽 것 그대로, 이 클래스는 발전기(꺼진 등 밑동에 서 있기)와 꺼진 사분면의 게이브(몸 빛, 빠르기)만 맡는다.
     * 등은 Mobs.Light 로만 다룬다(부숨 = off(SMASH, true), 다시 켬 = off(SMASH, false)). 이미 제 등이 서 있으면 아무 일 없음.
     */
    public void adopt(List<? extends Mobs.Light> lights, Object by) {
        if (raised || lights == null || lights.isEmpty()) return;
        adopted.clear();
        adopted.addAll(lights);
        adopted.sort((a, b) -> Double.compare(angle(a.pos()), angle(b.pos())));
        adoptedBy = by;
        genOf.clear();
        darkDirty = true;
        field();
    }

    /** by 가 세웠거나 빌린 것을 놓음(제 등은 내리고, 빌린 등은 다시 켜고 꺼진 사분면 효과만 풂). */
    public void release(Object by) {
        if (raised) {
            lower(by);
            return;
        }
        if (adopted.isEmpty() || by != null && adoptedBy != null && by != adoptedBy) return;
        for (Mobs.Light l : adopted) relight(l);
        adopted.clear();
        adoptedBy = null;
        genOf.clear();
        undark();
        game.mobs.unfield(FIELD);
    }

    private void field() {
        game.mobs.field(FIELD, new Mobs.Field() {
            @Override
            public boolean tick(long now) {
                if (!raised && adopted.isEmpty()) return false;
                PadLights.this.tick(now);
                return true;
            }

            @Override
            public void reset() {
                if (raised) lower(null);
                else release(null);
            }
        });
    }

    /** 내림(by 가 세운 쪽이 아니면 아무 일 없음, null 이면 늘 내림). */
    public void lower(Object by) {
        if (!raised) return;
        if (by != null && owner != null && by != owner) return;
        raised = false;
        owner = null;
        for (Lamp l : lamps) {
            game.mobs.removeLight(l);
            l.remove();
        }
        undark();
        game.mobs.unfield(FIELD);
    }

    /** 제 등이 서 있나. */
    public boolean raised() {
        return raised;
    }

    /** 제 등이든 빌린 등이든 쓰는 중인가. */
    public boolean active() {
        return raised || !adopted.isEmpty();
    }

    /** 세운 쪽(없으면 null). */
    public Object owner() {
        return raised ? owner : adoptedBy;
    }

    public Lamp lamp(int i) {
        return lamps[i & 3];
    }

    public Lamp[] lamps() {
        return lamps.clone();
    }

    /** 쓰는 등(제 등 넷 또는 빌린 등, 각 차례). */
    public List<Mobs.Light> lights() {
        List<Mobs.Light> out = new ArrayList<>();
        if (raised) for (Lamp l : lamps) out.add(l);
        else out.addAll(adopted);
        return out;
    }

    /** 등 머리 자리(제 등은 머리, 빌린 등은 밑동 위 HEIGHT). */
    public static Vector headOf(Mobs.Light l) {
        if (l instanceof Lamp lp) return lp.head.clone();
        return l.pos().clone().setY(Plaza.FLOOR_Y + HEIGHT);
    }

    /** 부숨(발전기로만 다시 켜짐). */
    public void smash(Mobs.Light l, Object by) {
        if (l instanceof Lamp lp) lp.smash(by);
        else if (l != null) {
            l.off(SMASH, true);
            darkDirty = true;
        }
    }

    /** 다시 켬(이 클래스가 끈 것을 풂). */
    public void relight(Mobs.Light l) {
        if (l instanceof Lamp lp) lp.relight();
        else if (l != null) {
            l.off(SMASH, false);
            darkDirty = true;
        }
    }

    private static double angle(Vector v) {
        double a = Math.atan2(v.getZ() - Plaza.CZ - 0.5, v.getX() - Plaza.CX - 0.5);
        return a < 0 ? a + Math.PI * 2 : a;
    }

    /** 그 자리 쪽(가운데에서 본 각이 가장 가까운) 등. 없으면 null. */
    public Mobs.Light lightAt(double x, double z) {
        if (raised) return lamps[quadrant(x, z)];
        double a = Math.atan2(z - Plaza.CZ - 0.5, x - Plaza.CX - 0.5);
        Mobs.Light best = null;
        double bd = 9;
        for (Mobs.Light l : adopted) {
            double d = Math.abs(a - Math.atan2(l.pos().getZ() - Plaza.CZ - 0.5, l.pos().getX() - Plaza.CX - 0.5)) % (Math.PI * 2);
            if (d > Math.PI) d = Math.PI * 2 - d;
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    /** 그 자리 사분면의 제 등. */
    public Lamp lampAt(double x, double z) {
        return lamps[quadrant(x, z)];
    }

    /** 그 자리 쪽 등이 꺼졌나(쓰는 중일 때만, 중앙 원 안은 늘 밝음: 트럭 등불). */
    public boolean darkAt(double x, double z) {
        if (!active()) return false;
        double dx = x - Plaza.CX - 0.5, dz = z - Plaza.CZ - 0.5;
        if (dx * dx + dz * dz < Plaza.R_SAFE * Plaza.R_SAFE) return false;
        Mobs.Light l = lightAt(x, z);
        return l != null && !l.lit();
    }

    public int litCount() {
        int n = 0;
        for (Mobs.Light l : lights()) if (l.lit() && !l.gone()) n++;
        return n;
    }

    /** 발전기 켬, 끔. */
    public void generators(boolean on) {
        generators = on;
    }

    /** 발전기 몫 0..1(그 등). */
    public double gen(Mobs.Light l) {
        if (l instanceof Lamp lp) return lp.gen();
        double[] g = genOf.get(l);
        return g == null ? 0 : g[0] / GEN_TICKS;
    }

    /** 놓인 디스플레이 수. */
    public int displays() {
        int n = 0;
        for (Lamp l : lamps) if (l.d != null && l.d.isValid()) n++;
        return n;
    }

    public String status() {
        if (!active()) return "pad=-";
        StringBuilder sb = new StringBuilder(raised ? "pad=" : "pad(borrowed)=");
        for (Mobs.Light l : lights()) sb.append(l.lit() ? 'O' : l instanceof Lamp lp && lp.smashed ? 'X' : 'o').append((int) (gen(l) * 9));
        return sb.append(" lit=").append(litCount()).toString();
    }

    // ------------------------------------------------------------------ 틱

    private void tick(long now) {
        if (game.state != Game.State.WAVE && game.state != Game.State.BREAK && game.state != Game.State.PREP) return;
        if (raised) for (Lamp l : lamps) l.draw();
        adopted.removeIf(Mobs.Light::gone);
        for (Mobs.Light l : lights()) if (generators && !l.lit()) genTick(l);
        if (now % DARK_EVERY == 0 || darkDirty) darkTick();
        darkDirty = false;
        for (Gabe g : darkened.keySet()) g.speedBuff *= DARK_SPEED;
    }

    private void genTick(Mobs.Light l) {
        Vector base = l.pos();
        Lamp own = l instanceof Lamp lp ? lp : null;
        double[] gen = own != null ? null : genOf.computeIfAbsent(l, k -> new double[1]);
        double cur = own != null ? own.gen : gen[0];
        boolean near = false;
        for (Player p : game.mobs.standing()) {
            Location pl = p.getLocation();
            double dx = pl.getX() - base.getX(), dz = pl.getZ() - base.getZ();
            if (dx * dx + dz * dz > GEN_R * GEN_R) continue;
            near = true;
            game.hud.meter(p, METER, cur / GEN_TICKS, 6);
        }
        if (near) {
            cur++;
            if (cur % 20 == 1) Props.soundAround(new Location(game.world, base.getX(), Plaza.FLOOR_Y + 1, base.getZ()), "trip.generator", Sound.BLOCK_PISTON_EXTEND, 0.8f,
                    0.8f + (float) (cur / GEN_TICKS) * 0.6f, 0.6f + (float) (cur / GEN_TICKS) * 0.6f, 32, -1);
        } else cur = Math.max(0, cur - 0.5);
        if (own != null) own.gen = cur;
        else gen[0] = cur;
        if (cur >= GEN_TICKS) {
            relight(l);
            if (gen != null) gen[0] = 0;
            if (own == null) Props.soundAround(new Location(game.world, base.getX(), Plaza.FLOOR_Y + 2, base.getZ()), "trip.lamp_on", Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1f, 1.4f,
                    96, -1);
        }
    }

    /** 꺼진 사분면의 게이브: 몸 빛 0(실눈만), 빠르기. 다시 켜지면 되돌림. */
    private void darkTick() {
        Map<Gabe, Boolean> seen = new IdentityHashMap<>();
        for (Gabe g : game.mobs.all()) {
            if (g.dead || g.removed || g.sp.is(Role.BOSS) || g.body == null) continue;
            if (!darkAt(g.pos.getX(), g.pos.getZ())) continue;
            seen.put(g, Boolean.TRUE);
            if (darkened.put(g, Boolean.TRUE) == null) bright(g, false);
        }
        for (Gabe g : new ArrayList<>(darkened.keySet())) {
            if (seen.containsKey(g)) continue;
            darkened.remove(g);
            if (!g.dead && !g.removed) bright(g, true);
        }
    }

    private static void bright(Gabe g, boolean on) {
        Display.Brightness b = on ? Body.LIGHT : new Display.Brightness(0, 0);
        if (g.body.anchor != null && g.body.anchor.isValid()) g.body.anchor.setBrightness(b);
        for (ItemDisplay d : g.body.parts) if (d != null && d.isValid()) d.setBrightness(b);
    }

    private void undark() {
        for (Gabe g : darkened.keySet()) if (!g.dead && !g.removed && g.body != null) bright(g, true);
        darkened.clear();
    }

    // ------------------------------------------------------------------ 그림

    private ItemStack item(boolean on) {
        ItemStack it = ItemStack.of(Material.PAPER);
        boolean pack = Truck.live();
        it.setData(DataComponentTypes.ITEM_MODEL, pack ? Key.key(Props.NS, on ? MODEL : MODEL_OFF) : Key.key("minecraft", on ? "lantern" : "soul_lantern"));
        return it;
    }
}
