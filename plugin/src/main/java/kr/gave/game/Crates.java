package kr.gave.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.gun.Supplies;
import kr.gave.mob.Damage;
import kr.gave.mob.Mobs;
import kr.gave.player.Life;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;

/**
 * 진짜 팀 보급 상자(꾸러미 게이브 G44 가 흉내 내는 것). 웨이브 중 드물게(두세 웨이브에 한 번쯤) 중앙에서 R {@link #R_MIN}..{@link #R_MAX} 사이
 * 황야에 떨어진다. 다가가 돕기(좌클릭)로 열면 판에 든 서 있는 사람 모두 탄약 가득, 연 사람은 재화나 소모품 하나를 더 받는다. 쏴도 부서지지
 * 않는다(2026-11-12 사용자: 기본 꺼짐). 설정 crates.breakable 을 켜면 겨눠 쏜 총 직격에 부서져 사라진다(아무것도 안 줌, 수류탄과 불은 그때도 안 건드림).
 *
 * <p>모습(G44 위장과 같게): 아이템 디스플레이 하나, 모델 {@code gave:ammo_box}(소품 층, 없으면 {@code gave:empty} 대신 상자 블록 아이템),
 * 바닥 위 {@link #BOX_H} 에 떠서 {@link #BOB} 만큼 둥실거리고 한 틱에 {@link #SPIN} 도 돈다. 변환 = rotY(-yaw) x scale({@link #SCALE})
 * x translate(0, {@link #LIFT}, 0) x rotY(180). 떨어질 때는 {@link #DROP_H} 위에서 {@link #FALL} 틱 동안 내려온다.
 *
 * <p>쏘기 판정은 엔진 Mobs 의 '쏠 수 있는 것'({@link Mobs.Shootable})으로 붙는다: 총 광선이 게이브보다 먼저 이 상자 구에 닿으면 상자가 맞는다.
 */
public final class Crates {

    /** 떨어지는 거리(중앙에서), 바닥 위 뜨는 높이, 둥실거림, 회전(도/틱), 모델 배율과 올림(G44 와 같음). */
    public static final double R_MIN = 14, R_MAX = 40, BOX_H = 0.62, BOB = 0.06;
    public static final float SPIN = 6f, SCALE = 1.3f, LIFT = 0.17f;
    /** 떨어지기 시작 높이, 내려오는 틱. 맞는 구 반지름. 여는 거리. */
    public static final double DROP_H = 24, HIT_R = 0.62, OPEN_REACH = 3.0;
    public static final int FALL = 40;
    /** 두세 웨이브에 한 번: 지난 상자 뒤 웨이브 수가 EVERY_MIN 이면 반반, EVERY_MAX 면 꼭. 웨이브 안 떨어질 때(틱, 웨이브 시작 뒤). */
    public static final int EVERY_MIN = 2, EVERY_MAX = 3, AT_MIN = 400, AT_MAX = 1000;
    /** 이 웨이브 수가 지나면(열지도 쏘지도 않음) 조용히 사라진다. 판에 한 번에 둘까지. */
    public static final int KEEP_WAVES = 2, MAX_LIVE = 2;

    /** 상자 하나. */
    public final class Crate implements Mobs.Shootable {
        public final Vector pos;
        final ItemDisplay d;
        final long born;
        final int wave;
        float yaw;
        boolean gone;

        Crate(Vector pos, ItemDisplay d, int wave) {
            this.pos = pos;
            this.d = d;
            this.born = game.now();
            this.wave = wave;
            this.yaw = game.random.nextFloat() * 360f;
        }

        boolean landed() {
            return game.now() - born >= FALL;
        }

        /** 맞는 구 가운데(지금 높이). */
        public Vector center() {
            return new Vector(pos.getX(), y() + LIFT * SCALE, pos.getZ());
        }

        double y() {
            long t = game.now() - born;
            double base = Plaza.FLOOR_Y + BOX_H;
            if (t < FALL) {
                double k = (double) t / FALL;
                return base + DROP_H * (1 - k * k);
            }
            return base + BOB * Math.sin(t * 0.12);
        }

        @Override
        public double rayHit(Vector o, Vector dir, double max) {
            if (gone) return Double.POSITIVE_INFINITY;
            Vector c = center();
            double ox = c.getX() - o.getX(), oy = c.getY() - o.getY(), oz = c.getZ() - o.getZ();
            double t = ox * dir.getX() + oy * dir.getY() + oz * dir.getZ();
            double m2 = ox * ox + oy * oy + oz * oz - t * t;
            if (t < -HIT_R || m2 > HIT_R * HIT_R) return Double.POSITIVE_INFINITY;
            double at = Math.max(0, t - Math.sqrt(HIT_R * HIT_R - m2));
            return at <= max ? at : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean shot(Damage dmg) {
            if (gone) return false;
            // 2026-11-12 사용자 '보급 부숴지는 것도 꺼봐': 설정 crates.breakable(기본 false)일 때만 부서짐
            if (!game.plugin.getConfig().getBoolean("crates.breakable", false)) return false;
            // 겨눠 쏜 총알(직격)만: 폭발, 불, 연쇄는 상자를 건드리지 않음
            if (dmg.by() == null || dmg.how() == null || dmg.how().equals("chain") || dmg.how().equals("explosive") || dmg.how().equals("incendiary"))
                return false;
            remove(this, true);
            game.plugin.getLogger().info("[보급] 부서짐 by " + dmg.by().getName() + " " + fmt(pos));
            return false;
        }

        @Override
        public boolean gone() {
            return gone;
        }
    }

    final Game game;
    private final List<Crate> live = new ArrayList<>();
    private int lastWave = -10, plannedWave = -1;
    private long dropAt = -1;
    /** 판 안 센 것(시험, 기록): 떨어짐, 열림, 부서짐. */
    public int dropped, opened, broken;

    Crates(Game game) {
        this.game = game;
    }

    /** 지금 놓인 상자(읽기만). */
    public List<Crate> live() {
        return live;
    }

    // ------------------------------------------------------------------ 판 흐름

    /** 웨이브 시작(Game 이 부름): 이 웨이브에 떨어뜨릴지 정한다. */
    void beginWave(int n) {
        dropAt = -1;
        plannedWave = n;
        int since = n - lastWave;
        boolean drop = since >= EVERY_MAX || since >= EVERY_MIN && game.random.nextBoolean();
        if (n >= 2 && drop) dropAt = game.now() + AT_MIN + game.random.nextInt(AT_MAX - AT_MIN);
        // 오래된 상자 치우기
        for (Crate c : new ArrayList<>(live)) if (n - c.wave >= KEEP_WAVES) remove(c, false);
    }

    /** 매 틱(PREP, WAVE, BREAK, OVER). */
    void tick() {
        if (dropAt >= 0 && game.state == Game.State.WAVE && game.wave == plannedWave && game.now() >= dropAt) {
            dropAt = -1;
            drop(null);
        }
        long now = game.now();
        for (Crate c : new ArrayList<>(live)) {
            if (c.gone || c.d == null || !c.d.isValid()) {
                remove(c, false);
                continue;
            }
            long t = now - c.born;
            if (t == FALL) {
                Location at = new Location(game.world, c.pos.getX(), Plaza.FLOOR_Y + 0.05, c.pos.getZ());
                game.shotFx.hit(at, BlockFace.UP, Material.OAK_PLANKS, new Vector(0, -1, 0), true);
                Props.sound(at, null, Sound.BLOCK_WOOD_FALL, 1.2f, 0.7f, 1f);
            }
            if (t % 2 == 0) {
                c.yaw = (c.yaw + SPIN * 2) % 360f;
                c.d.setInterpolationDelay(0);
                c.d.setInterpolationDuration(2);
                c.d.setTransformationMatrix(matrix(c.yaw));
                Location l = c.d.getLocation();
                double y = c.y();
                if (Math.abs(l.getY() - y) > 0.01) c.d.teleport(new Location(game.world, c.pos.getX(), y, c.pos.getZ()));
            }
        }
    }

    /** 판을 멈춤, 대기실로(Game.reset). */
    void clear() {
        for (Crate c : new ArrayList<>(live)) remove(c, false);
        live.clear();
        lastWave = -10;
        plannedWave = -1;
        dropAt = -1;
        dropped = opened = broken = 0;
    }

    // ------------------------------------------------------------------ 떨어뜨리기, 열기

    /** 상자 하나를 떨어뜨린다(at 이 null 이면 중앙에서 R_MIN..R_MAX 무작위). 놓았으면 그 상자. */
    public Crate drop(Vector at) {
        if (live.size() >= MAX_LIVE) remove(live.get(0), false);
        if (at == null) {
            double a = game.random.nextDouble() * Math.PI * 2, r = R_MIN + game.random.nextDouble() * (R_MAX - R_MIN);
            at = new Vector(Plaza.CX + 0.5 + Math.cos(a) * r, Plaza.FLOOR_Y, Plaza.CZ + 0.5 + Math.sin(a) * r);
        }
        Vector pos = new Vector(at.getX(), Plaza.FLOOR_Y, at.getZ());
        Location l = new Location(game.world, pos.getX(), Plaza.FLOOR_Y + BOX_H + DROP_H, pos.getZ());
        ItemStack it = model();
        float yaw0 = game.random.nextFloat() * 360f;
        ItemDisplay d = game.world.spawn(l, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setItemStack(it);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            x.setBillboard(Display.Billboard.FIXED);
            x.setBrightness(new Display.Brightness(10, 10));
            x.setViewRange(1.6f);
            x.setDisplayWidth(2.6f);
            x.setDisplayHeight(1.8f);
            x.setTeleportDuration(2);
            x.setTransformationMatrix(matrix(yaw0));
        });
        Crate c = new Crate(pos, d, Math.max(0, game.wave));
        c.yaw = yaw0;
        live.add(c);
        game.mobs.addShootable(c);
        lastWave = Math.max(lastWave, game.wave);
        dropped++;
        game.hud.notice("notice_crate", 80);
        game.hud.point(null, pos.clone().add(new Vector(0, 1, 0)), 100);
        Props.sound(l, null, Sound.ITEM_ELYTRA_FLYING, 0.6f, 1.6f, 1f);
        game.plugin.getLogger().info("[보급] 떨어짐 웨이브 " + game.wave + " " + fmt(pos) + String.format(Locale.ROOT, " r=%.1f", Plaza.radius(pos.getX(), pos.getZ())));
        return c;
    }

    /** 돕기(좌클릭)로 열기: 바라보는 OPEN_REACH 안 내려앉은 상자. 열었으면 true. */
    public boolean tryOpen(Player p) {
        Location eye = p.getEyeLocation();
        Vector o = eye.toVector(), dir = eye.getDirection();
        Crate best = null;
        double bestAt = OPEN_REACH;
        for (Crate c : live) {
            if (c.gone || !c.landed()) continue;
            double at = c.rayHit(o, dir, OPEN_REACH);
            // 가까이(1.6 블록 안)면 대충 봐도 열림
            if (at == Double.POSITIVE_INFINITY && c.center().distance(o) < 1.6) at = c.center().distance(o);
            if (at < bestAt) {
                bestAt = at;
                best = c;
            }
        }
        if (best == null) return false;
        open(best, p);
        return true;
    }

    private void open(Crate c, Player p) {
        remove(c, false);
        opened++;
        int n = 0;
        for (Player q : game.players.inPlay()) {
            if (game.players.get(q).life != Life.ALIVE) continue;
            game.guns.refillAll(q);
            n++;
        }
        // 연 사람 덤: 소모품 하나(받을 자리가 있으면), 아니면 재화
        String got = null;
        List<String> kinds = new ArrayList<>(Supplies.KINDS);
        java.util.Collections.shuffle(kinds, game.random);
        for (String k : kinds) {
            if (game.shop.supplies.canTake(p, k) && game.shop.supplies.wouldDrop(p, k) == null && game.shop.supplies.give(p, k, 1)) {
                got = k;
                break;
            }
        }
        if (got == null) {
            int money = 50 + 10 * Math.max(1, game.wave);
            game.players.addMoney(p, money);
            got = money + "원";
        }
        Location at = new Location(game.world, c.pos.getX(), Plaza.FLOOR_Y + 0.6, c.pos.getZ());
        Props.sound(at, null, Sound.BLOCK_CHEST_OPEN, 1f, 1.1f, 1f);
        Props.sound(p, null, Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1f, 1.2f);
        game.hud.notice("notice_crate_open", 60);
        game.plugin.getLogger().info("[보급] 열림 by " + p.getName() + " 탄약 " + n + "명, 덤 " + got);
    }

    private void remove(Crate c, boolean shotDown) {
        if (!c.gone) {
            c.gone = true;
            if (shotDown) {
                broken++;
                Location at = new Location(game.world, c.pos.getX(), c.y(), c.pos.getZ());
                game.shotFx.hit(at, BlockFace.UP, Material.OAK_PLANKS, new Vector(0, 1, 0), true);
                Props.sound(at, null, Sound.ENTITY_ITEM_BREAK, 1f, 0.6f, 1f);
                game.hud.notice("notice_crate_broken", 60);
            }
        }
        if (c.d != null && c.d.isValid()) c.d.remove();
        live.remove(c);
        game.mobs.removeShootable(c);
    }

    static Matrix4f matrix(float yaw) {
        return new Matrix4f().rotateY((float) Math.toRadians(-yaw)).scale(SCALE).translate(0f, LIFT, 0f).rotateY((float) Math.PI);
    }

    private static ItemStack model() {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Props.has("ammo_box") ? Key.key(Props.NS, "ammo_box") : Key.key("minecraft", "chest"));
        return it;
    }

    private static String fmt(Vector v) {
        return String.format(Locale.ROOT, "%.1f,%.1f", v.getX(), v.getZ());
    }

    // ------------------------------------------------------------------ 시험 훅

    /** 보급 &lt;이름|x&gt; [떨굼 [거리] | 열기 | 상태 | 치움]. 떨굼 거리를 주면 그 사람 앞 그 거리에(없으면 무작위). */
    public String test(Player p, String[] a) {
        if (a.length < 2 || !a[1].equals("보급")) return null;
        String sub = a.length > 3 ? a[3] : "상태";
        switch (sub) {
            case "떨굼" -> {
                Vector at = null;
                if (a.length > 4 && p != null) {
                    double dist = Double.parseDouble(a[4]);
                    Location e = p.getLocation();
                    Vector f = e.getDirection().setY(0);
                    if (f.lengthSquared() < 1e-6) f = new Vector(0, 0, 1);
                    at = e.toVector().add(f.normalize().multiply(dist));
                }
                Crate c = drop(at);
                return "[보급] 떨굼 " + fmt(c.pos);
            }
            case "열기" -> {
                if (p == null) return "[보급] 사람 없음";
                return "[보급] 열기 " + tryOpen(p);
            }
            case "치움" -> {
                for (Crate c : new ArrayList<>(live)) remove(c, false);
                return "[보급] 치움";
            }
            default -> {
                StringBuilder sb = new StringBuilder("[보급] live=" + live.size() + " dropped=" + dropped + " opened=" + opened + " broken=" + broken
                        + " lastWave=" + lastWave + " dropAt=" + (dropAt < 0 ? "-" : Long.toString(dropAt - game.now())));
                for (Crate c : live) sb.append(" | ").append(fmt(c.pos)).append(c.landed() ? " 내려앉음" : " 떨어지는중");
                return sb.toString();
            }
        }
    }
}
