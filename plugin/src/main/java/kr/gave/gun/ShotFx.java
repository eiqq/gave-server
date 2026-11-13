package kr.gave.gun;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import kr.gave.Props;
import net.kyori.adventure.key.Key;

/**
 * 사격, 폭발 연출(좀비런 ShotFx 를 옮김): 아이템 디스플레이에 표식 모델(gave:fx_quad, fx_tracer, fx_flat)을 얹고, 리소스팩 core/item 셰이더가
 * 정점 색 [종류 4][씨앗 8][시작 틱 mod 4000, 12] 를 읽어 모양과 시간 변화를 그린다. 씨앗 위 2비트는 변형(재질 먼지 색, 자국 종류, 연기 색, 피 색).
 * 파티클, 발광 상자는 쓰지 않는다. 디스플레이는 늘 yaw, pitch 0 으로 낳고 모양 방향은 변환 행렬로만 준다.
 * 탄 궤적은 짧은 빛줄기가 순간 이동 보간으로 총구 근처에서 맞은 자리까지 날아간다. 총구 불꽃은 손에 든 총 모델 안에서 그린다(Guns.visual),
 * 여기 muzzle 은 모드 총 모델이 없을 때 쓰는 대체.
 * <p>
 * 예산(판 규칙 '효과 50'): 자국을 뺀 연출 디스플레이는 한 번에 {@link #BUDGET} 개까지만 산다. 차 오르면 덤(full 연출: 먼지, 불똥, 뒤로 뿜는 안개,
 * 연기, 곁 탄 궤적)부터 안 낳고, 그다음 보통, 마지막까지 남는 것은 꼭(폭발 섬광 같은 것). 자국은 종류마다 따로 정한 한도(오래된 것부터 지움)를 지킨다.
 */
public final class ShotFx {
    public static final int TRACER = 1, FLASH = 2, DUST = 3, SPARK = 4, DECAL = 5, FIRE = 6, RING = 7, SMOKE = 8, BILE = 9, BLOOD = 10;
    /** 쓰러짐 고리(바닥 판 fx_flat, 늘 보임, 남은 몫만큼 호): {@link #downRing(double, boolean)} 으로 찍는다. fx() 로 찍으면 가득 찬 빨강. */
    public static final int DOWN_RING = 14;
    /**
     * 새 무기 효과(2026-11-12, pack_fx/zrfx.glsl 종류 15): 변형 0 번개(fx_tracer, 길이 따라 지그재그로 떨리는 흰 파랑 줄), 1 불길 덩이(fx_quad
     * 빌보드, 주황 노랑 혀가 일렁이며 퍼짐, 그을음 없음), 2 서리 덩이(fx_quad, 하얀 청록 안개 + 얼음 반짝임), 3 레일 광선(fx_tracer, 청록 심이 굵게
     * 났다가 가늘어짐). 색 R 위 4비트 15 는 1인칭 총 몸짓 신호(zr_kick, 씨앗 아래 4비트 11..14)와 겹칠 수 있어 씨앗을 고른다({@link #fx})
     */
    public static final int WEAPON = 15, V_BOLT = 0, V_FLAME = 1, V_FROST = 2, V_BEAM = 3;
    /** 자국 변형 */
    public static final int DECAL_HOLE = 0, DECAL_SCORCH = 1, DECAL_BLOOD = 2, DECAL_BILE = 3;
    /** 피 안개(BLOOD) 변형: 0 붉은 피, 1 분홍빛 피(게이브 얼굴, 팩 셰이더가 변형 1 을 분홍빛 빨강으로 그림) */
    public static final int BLOOD_RED = 0, BLOOD_PINK = 1;
    /** 탄착 재질 */
    public static final int MAT_STONE = 0, MAT_METAL = 1, MAT_WOOD = 2, MAT_GLASS = 3;
    /** 우선순위: 꼭, 보통, 덤(full 연출) */
    public static final int KEY = 0, NORMAL = 1, EXTRA = 2;
    /** 자국을 뺀 연출 디스플레이 한도(한 번에 살아 있는 수) */
    public static final int BUDGET = 50;
    /** 우선순위마다 새로 낳을 수 있는 살아 있는 수 위끝(이보다 적을 때만 낳음): 덤이 먼저 빠지고 꼭은 한도까지 */
    private static final int[] ROOM = {BUDGET, BUDGET - 6, BUDGET - 16};
    /**
     * 자국 한도(모두 80): 종류마다 따로 세서 오래된 것부터 지운다. 연사 탄흔(초당 수십 개)이 드문 큰 자국(그을음 15 초, 담즙 8 초)이나
     * 피 자국을 밀어내지 않게
     */
    private static final int MAX_HOLES = 56, MAX_BLOOD = 18, MAX_BIG = 6;

    private final GunHost host;
    private final Random rnd = new Random();

    private static final class Live {
        final ItemDisplay d;
        long until;
        final boolean decal;
        /** 낳게 한 사람(사람 몫 예산), 없으면 null */
        java.util.UUID owner;
        /** 예산 밖(날아가는 유탄, 로켓 몸통: 모양은 모드 모델, 수는 Arsenal.MAX_SHELLS 가 막음) */
        boolean free;

        Live(ItemDisplay d, long until, boolean decal) {
            this.d = d;
            this.until = until;
            this.decal = decal;
        }
    }

    /** 사람마다 지금 살아 있는 연출 수(줄기, 번개처럼 한 사람이 빨리 많이 낳는 효과의 몫 예산) */
    private final java.util.Map<java.util.UUID, Integer> ownerLive = new java.util.HashMap<>();
    /** 사람마다 마지막으로 몫 효과를 낳으려 한 틱(최근 20 틱 안에 낳으려 한 사람 수로 몫을 나눔) */
    private final java.util.Map<java.util.UUID, Long> wantAt = new java.util.HashMap<>();
    private long wantPrune = -1000;

    /**
     * 한 사람 몫 = 보통 자리(BUDGET - 6)를 최근 20 틱 안에 몫 효과를 낳으려 한 사람 수로 나눈 것(최소 1): 30 명이 함께 불길을 뿜어도
     * 먼저 도는 사람이 자리를 다 차지하지 않고 모두 조금씩 보인다. 혼자면 한도 전부
     */
    private int ownerCap(java.util.UUID owner) {
        long now = host.now();
        wantAt.put(owner, now);
        if (now - wantPrune >= 20) {
            wantPrune = now;
            wantAt.values().removeIf(at -> now - at > 20);
        }
        return Math.max(1, (BUDGET - 6) / Math.max(1, wantAt.size()));
    }

    private final List<Live> timed = new ArrayList<>();
    private final ArrayDeque<ItemDisplay> holes = new ArrayDeque<>(), bloods = new ArrayDeque<>(), bigs = new ArrayDeque<>();
    /** 지금 살아 있는 자국 아닌 연출 수 */
    private int live;
    /** 예산 때문에 안 낳은 수(시험용, 누적) */
    private long dropped;

    public ShotFx(GunHost host) {
        this.host = host;
    }

    private long gameTime() {
        return host.world().getGameTime();
    }

    /** 연출 아이템: PAPER + gave:&lt;model&gt; + 색 하나 [종류][씨앗(변형 2비트 + 무작위 6비트)][지금 세계 틱 mod 4000] */
    public ItemStack fx(String model, int kind, int variant) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, model));
        int seed = (variant & 3) << 6 | rnd.nextInt(64);
        // 종류 15: 씨앗 아래 4비트가 11..14 면 셰이더 zr_kick 이 1인칭 총 몸짓 신호로 읽을 수 있어 피함
        while (kind == WEAPON && (seed & 15) >= 11 && (seed & 15) <= 14) seed = (variant & 3) << 6 | rnd.nextInt(64);
        int bits = notGabe((kind << 20) | (seed << 12) | (int) Math.floorMod(gameTime(), 4000L));
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColor(Color.fromRGB(bits)).build());
        return it;
    }

    /**
     * 종류 14(쓰러짐 고리)는 색 R 위 4비트가 14 라 게이브 셰이더 신호(gabe.glsl gabe_vertex)와 겹친다. 그 검사합이 우연히 맞으면(시작 틱에 따라
     * 넷에 하나) 게이브 면으로 읽혀 고리가 통째로 숨거나 흰색이 된다(2026-11-11 final: 출혈 중 고리가 다시 찍힐 때 사라짐). 고리는 시작 틱을
     * 쓰지 않으니 시작 틱을 0..3 밀어 검사합이 어긋나게 한다. 다른 종류는 그대로.
     */
    static int notGabe(int bits) {
        if ((bits >> 20 & 15) != 14) return bits;
        int head = bits & ~0xFFF, stamp = bits & 0xFFF;
        for (int i = 0; i < 4; i++) {
            int b = head | (stamp + i) % 4000;
            int r = b >> 16 & 0xFF, pay = b & 0x3FFFF, sum = 1;
            for (int k = 0; k < 9; k++) sum += pay >> (2 * k) & 3;
            if ((sum & 3) != (r >> 2 & 3)) return b;
        }
        return bits;
    }

    /**
     * 쓰러짐 고리 아이템(모델 fx_flat, 종류 {@link #DOWN_RING}): 빨간 고리가 남은 몫 frac(0..1)만큼 호로 남고 나머지 호는 어둡다.
     * reviving 이면 초록. 셰이더가 시간으로 지우지 않으니 늘 보이고, 몫이 바뀔 때만 다시 찍으면 된다(6비트, 64 단계).
     */
    public ItemStack downRing(double frac, boolean reviving) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, "fx_flat"));
        int q = (int) Math.round(Math.max(0, Math.min(1, frac)) * 63);
        int seed = (reviving ? 2 : 1) << 6 | q;
        int bits = notGabe((DOWN_RING << 20) | (seed << 12) | (int) Math.floorMod(gameTime(), 4000L));
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColor(Color.fromRGB(bits)).build());
        return it;
    }

    /** 우선순위 prio 의 연출을 하나 더 낳을 자리가 있는가 */
    public boolean room(int prio) {
        return live < ROOM[Math.max(0, Math.min(2, prio))];
    }

    /** 보통 우선순위로 낳기 */
    public ItemDisplay spawn(Location at, String model, int kind, int variant, Matrix4f m, Display.Billboard bb, boolean glow, int life) {
        return spawn(at, model, kind, variant, m, bb, glow, life, true, NORMAL);
    }

    public ItemDisplay spawn(Location at, String model, int kind, int variant, Matrix4f m, Display.Billboard bb, boolean glow, int life, int prio) {
        return spawn(at, model, kind, variant, m, bb, glow, life, true, prio);
    }

    /**
     * 연출 디스플레이 하나를 life 틱 동안. 예산이 차서 못 낳으면 null(자국은 예산 밖, 종류별 한도로만 막음).
     * visible = false 면 아무에게도 안 보이게 낳는다(부른 쪽이 showEntity 로 보일 사람을 고름)
     */
    public ItemDisplay spawn(Location at, String model, int kind, int variant, Matrix4f m, Display.Billboard bb, boolean glow, int life,
        boolean visible, int prio) {
        return spawn(at, model, kind, variant, m, bb, glow, life, visible, prio, null);
    }

    /** 사람 몫 예산을 지키며 낳기: owner 가 이미 제 몫({@link #ownerCap})만큼 가졌으면 null */
    public ItemDisplay spawn(Location at, String model, int kind, int variant, Matrix4f m, Display.Billboard bb, boolean glow, int life,
        boolean visible, int prio, java.util.UUID owner) {
        boolean decal = kind == DECAL;
        if (!decal && !room(prio) || owner != null && ownerLive.getOrDefault(owner, 0) >= ownerCap(owner)) {
            dropped++;
            return null;
        }
        ItemStack it = fx(model, kind, variant);
        at = at.clone();
        at.setYaw(0);
        at.setPitch(0);
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, x -> {
            x.setItemStack(it);
            x.setPersistent(false);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            x.setBillboard(bb);
            if (glow) x.setBrightness(new Display.Brightness(15, 15));
            x.setTransformationMatrix(m);
            x.setVisibleByDefault(visible);
            x.setViewRange(2.5f);
        });
        Live lv = new Live(d, host.now() + life, decal);
        lv.owner = owner;
        timed.add(lv);
        if (!decal) live++;
        if (owner != null) ownerLive.merge(owner, 1, Integer::sum);
        return d;
    }

    /** 판(fx_quad)의 면이 dir 을 보게(벽, 바닥에 붙는 자국), 폭 w, 길이 len */
    public static Matrix4f along(Vector dir, float w, float len) {
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).normalize());
        return new Matrix4f().rotate(q).rotateY((float) Math.PI).scale(w, w, len);
    }

    private Matrix4f facing(Vector n, float s) {
        return along(n, s, s).rotateZ(rnd.nextFloat() * 6.2832f);
    }

    public static Matrix4f scale(float s) {
        return new Matrix4f().scale(s);
    }

    /** 다음 틱부터 크기를 바꾸며 보간(d 가 null 이면 아무것도 안 함) */
    public void grow(ItemDisplay d, Matrix4f to, int ticks) {
        if (d == null) return;
        Bukkit.getScheduler().runTaskLater(host.plugin(), () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformationMatrix(to);
        }, 1);
    }

    /** 다음 틱부터 to 로 옮겨 가며 보간(최대 59 틱, d 가 null 이면 아무것도 안 함) */
    public void drift(ItemDisplay d, Location to, int ticks) {
        if (d == null) return;
        d.setTeleportDuration(Math.min(59, ticks));
        Location t = to.clone();
        t.setYaw(0);
        t.setPitch(0);
        Bukkit.getScheduler().runTaskLater(host.plugin(), () -> {
            if (d.isValid()) d.teleport(t);
        }, 1);
    }

    private static Vector right(Vector dir) {
        Vector r = dir.clone().crossProduct(new Vector(0, 1, 0));
        return r.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : r.normalize();
    }

    /** 자국(벽, 바닥에 붙는 판): 법선 n, 변형 DECAL_*, 크기, 틱. 종류별 한도를 넘으면 가장 오래된 것을 지운다 */
    public ItemDisplay decal(Location at, Vector n, int variant, float size, int life) {
        ItemDisplay d = spawn(at.clone().add(n.clone().multiply(0.012)), "fx_quad", DECAL, variant, facing(n, size), Display.Billboard.FIXED, false, life,
            true, NORMAL);
        ArrayDeque<ItemDisplay> q = variant == DECAL_HOLE ? holes : variant == DECAL_BLOOD ? bloods : bigs;
        int cap = variant == DECAL_HOLE ? MAX_HOLES : variant == DECAL_BLOOD ? MAX_BLOOD : MAX_BIG;
        q.add(d);
        while (q.size() > cap) {
            ItemDisplay old = q.poll();
            if (old != null) old.remove();
        }
        return d;
    }

    /** 발밑 바닥(아래로 maxDown 안의 첫 블록 윗면). 없으면 그 자리 */
    public static Location ground(Location at, double maxDown) {
        RayTraceResult r = at.getWorld().rayTraceBlocks(at.clone().add(0, 0.3, 0), new Vector(0, -1, 0), maxDown + 0.3, FluidCollisionMode.NEVER, true);
        return r == null ? at.clone() : r.getHitPosition().toLocation(at.getWorld());
    }

    // ------------------------------------------------------------------ 재질

    public static int material(Material m) {
        String n = m.name();
        if (m == Material.BARRIER || n.contains("IRON") || n.contains("COPPER") || n.contains("GOLD") || n.contains("CHAIN") || n.contains("ANVIL")
            || n.contains("CAULDRON") || n.contains("HOPPER") || n.contains("LANTERN") || n.contains("RAIL") || n.contains("NETHERITE")) return MAT_METAL;
        if (n.contains("GLASS") || n.endsWith("ICE")) return MAT_GLASS;
        if (n.contains("PLANKS") || n.endsWith("_LOG") || n.endsWith("_WOOD") || n.contains("BARREL") || n.contains("FENCE") || n.endsWith("_DOOR")
            || n.contains("TRAPDOOR") || n.contains("SIGN") || n.contains("BOOKSHELF") || n.contains("CHEST") || n.contains("LADDER")
            || n.contains("SCAFFOLD") || n.contains("CRAFTING") || n.endsWith("_STEM") || n.contains("HYPHAE") || n.contains("BAMBOO")) return MAT_WOOD;
        return MAT_STONE;
    }

    /** 먼지 색 변형: 0 회색, 1 벽돌, 테라코타, 2 나무, 3 밝은 돌 */
    private static int dustColor(Material m) {
        String n = m.name();
        if (material(m) == MAT_WOOD) return 2;
        if (n.contains("BRICK") && !n.contains("STONE") && !n.contains("DEEPSLATE") && !n.contains("QUARTZ") || n.contains("GRANITE") || n.contains("MUD")
            || n.contains("TERRACOTTA") && !n.startsWith("WHITE") && !n.startsWith("LIGHT_GRAY") && !n.startsWith("GRAY") && !n.startsWith("CYAN")
            || n.startsWith("RED_") || n.startsWith("ORANGE_") || n.startsWith("BROWN_")) return 1;
        if (n.contains("QUARTZ") || n.startsWith("WHITE_") || n.contains("SANDSTONE") || n.contains("CALCITE") || material(m) == MAT_GLASS) return 3;
        return 0;
    }

    // ------------------------------------------------------------------ 사격

    /**
     * 탄 궤적: 쏜 사람 시선 기준 1인칭 총구 방향(앞 1.2, 오른쪽 0.36, 아래 0.26)에서 탄 방향으로 1.2 블록 나간 자리부터 맞은 자리까지 날아감.
     * 길이는 날아갈 거리의 1/4(0.8..4.5 블록), 가까운 탄은 짧고 가늘어 총에 붙은 빛줄기로 보이지 않게. main 이 아니면(산탄 곁 알) 덤
     */
    public void tracer(Player shooter, Location eye, Location end, boolean main) {
        Vector look = eye.getDirection();
        Vector r = right(look);
        Vector up = r.clone().crossProduct(look).normalize();
        Location muzzle = eye.clone().add(look.clone().multiply(1.2)).add(r.clone().multiply(0.36)).add(up.multiply(-0.26));
        Vector way = end.toVector().subtract(muzzle.toVector());
        double travel = way.length() - 1.2;
        if (travel < 2.0) return;
        Vector d2 = way.normalize();
        Location start = muzzle.clone().add(d2.clone().multiply(1.2));
        float len = (float) Math.min(4.5, Math.max(0.8, travel * 0.25));
        float w = (main ? 0.32f : 0.26f) * (float) Math.min(1, 0.6 + travel / 40);
        int n = (int) Math.max(1, Math.min(3, Math.round(travel / 45)));
        ItemDisplay d = spawn(start, "fx_tracer", TRACER, 0, along(d2, w, len), Display.Billboard.FIXED, true, n + 4, main ? NORMAL : EXTRA);
        Location to = end.clone().subtract(d2.clone().multiply(len));
        drift(d, to, n);
    }

    /** 대체 총구 불꽃(모드 총 모델이 없을 때): 쏜 사람에겐 화면 오른쪽 아래, 남에겐 든 총 앞 */
    public void muzzle(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector r = right(dir);
        Location first = eye.clone().add(dir.clone().multiply(1.25)).add(r.clone().multiply(0.42)).add(0, -0.34, 0);
        ItemDisplay a = spawn(first, "fx_quad", FLASH, 0, scale(0.55f), Display.Billboard.CENTER, true, 3, false, NORMAL);
        if (a != null) p.showEntity(host.plugin(), a);
        Location third = eye.clone().add(dir.clone().multiply(1.0)).add(r.clone().multiply(0.36)).add(0, -0.45, 0);
        ItemDisplay b = spawn(third, "fx_quad", FLASH, 0, scale(0.8f), Display.Billboard.CENTER, true, 3, false, EXTRA);
        if (b != null) for (Player o : Bukkit.getOnlinePlayers()) if (o != p && o.getWorld() == p.getWorld()) o.showEntity(host.plugin(), b);
    }

    /** 옛 호출(재질 모름): 돌로 친다 */
    public void impact(Location at, BlockFace face) {
        hit(at, face, Material.STONE, null, true);
    }

    /**
     * 블록 탄착: 탄흔(가로막이 블록 = 소품 충돌 블록이면 빼고) + full 이면 재질 색 먼지(법선 쪽으로 밀려남), 돌, 쇠, 유리면 불똥 다발(둘 다 덤)
     */
    public void hit(Location at, BlockFace face, Material m, Vector dir, boolean full) {
        Vector n = face == null ? new Vector(0, 1, 0) : face.getDirection();
        int mat = material(m);
        if (m != Material.BARRIER) decal(at, n, DECAL_HOLE, 0.3f + rnd.nextFloat() * 0.08f, 200);
        if (!full) return;
        if (mat != MAT_METAL) {
            Location p0 = at.clone().add(n.clone().multiply(0.15));
            ItemDisplay dust = spawn(p0, "fx_quad", DUST, dustColor(m), scale(0.45f), Display.Billboard.CENTER, false, 17, EXTRA);
            grow(dust, scale(mat == MAT_WOOD ? 1.3f : 1.6f), 14);
            drift(dust, p0.clone().add(n.clone().multiply(0.55)).add(0, 0.2, 0), 15);
        }
        if (mat != MAT_WOOD) {
            float s = mat == MAT_METAL ? 1.25f : mat == MAT_GLASS ? 0.8f : 0.7f;
            spawn(at.clone().add(n.clone().multiply(0.06)), "fx_quad", SPARK, 0, scale(s), Display.Billboard.CENTER, true, 5, EXTRA);
        }
    }

    /** 옛 호출 */
    public void blood(Location at) {
        bloodHit(at, null, true);
    }

    /** 게이브 맞음(분홍빛 피) */
    public void bloodHit(Location at, Vector dir, boolean full) {
        bloodHit(at, dir, full, BLOOD_PINK);
    }

    /**
     * 맞음: 맞은 자리 피 안개(보통) + full 이면 탄 방향 뒤로 뿜는 안개(덤)와 뒤 벽(없으면 발밑)에 피 자국. variant = BLOOD_RED 또는 BLOOD_PINK
     */
    public void bloodHit(Location at, Vector dir, boolean full, int variant) {
        ItemDisplay b = spawn(at, "fx_quad", BLOOD, variant, scale(0.6f), Display.Billboard.CENTER, false, 14, NORMAL);
        grow(b, scale(1.75f), 11);
        if (!full || dir == null) return;
        Vector d = dir.clone().normalize();
        Location exit = at.clone().add(d.clone().multiply(0.45));
        ItemDisplay s = spawn(exit, "fx_quad", BLOOD, variant, scale(0.4f), Display.Billboard.CENTER, false, 12, EXTRA);
        grow(s, scale(1.4f), 9);
        drift(s, exit.clone().add(d.clone().multiply(1.1)).add(0, -0.35, 0), 9);
        RayTraceResult r = at.getWorld().rayTraceBlocks(at, d, 3.5, FluidCollisionMode.NEVER, true);
        if (r != null && r.getHitBlockFace() != null && r.getHitBlock() != null && r.getHitBlock().getType() != Material.BARRIER) {
            decal(r.getHitPosition().toLocation(at.getWorld()), r.getHitBlockFace().getDirection(), DECAL_BLOOD, 0.8f + rnd.nextFloat() * 0.5f, 200);
        } else {
            Location gr = ground(at.clone().add(d.clone().multiply(0.8)), 2.6);
            if (gr.distanceSquared(at) < 3.2 * 3.2) decal(gr, new Vector(0, 1, 0), DECAL_BLOOD, 0.9f + rnd.nextFloat() * 0.5f, 200);
        }
    }

    // ------------------------------------------------------------------ 폭발, 터짐

    /** 수류탄 폭발: 섬광 심(꼭), 불덩이 셋(첫째 꼭), 바닥 충격파, 오르며 퍼지는 검은 연기 셋(덤), 바닥 그을음 */
    public void explosion(Location at) {
        noteBlast(at, 4.5);
        Location gr = ground(at, 2.5);
        ItemDisplay f = spawn(at.clone().add(0, 0.9, 0), "fx_quad", FLASH, 0, scale(3.5f), Display.Billboard.CENTER, true, 4, KEY);
        grow(f, scale(8.5f), 2);
        for (int i = 0; i < 3; i++) {
            Location c = at.clone().add(rnd.nextGaussian() * 0.45, 0.7 + rnd.nextDouble() * 0.7, rnd.nextGaussian() * 0.45);
            ItemDisplay fire = spawn(c, "fx_quad", FIRE, 0, scale(1.8f + i * 0.4f), Display.Billboard.CENTER, true, 22, i == 0 ? KEY : NORMAL);
            grow(fire, scale(6.5f + i * 1.2f), 7 + i * 2);
            drift(fire, c.clone().add(0, 0.6 + i * 0.25, 0), 18);
        }
        ItemDisplay ring = spawn(gr.clone().add(0, 0.15, 0), "fx_flat", RING, 0, scale(1.5f), Display.Billboard.FIXED, true, 11, NORMAL);
        grow(ring, scale(15f), 7);
        for (int i = 0; i < 3; i++) {
            Location c = at.clone().add(rnd.nextGaussian() * 0.7, 1.0 + i * 0.6, rnd.nextGaussian() * 0.7);
            ItemDisplay sm = spawn(c, "fx_quad", SMOKE, 1, scale(3f), Display.Billboard.CENTER, false, 72, EXTRA);
            grow(sm, scale(8.5f + i), 60);
            drift(sm, c.clone().add(rnd.nextGaussian() * 0.6, 2.6 + i * 0.5, rnd.nextGaussian() * 0.6), 59);
        }
        decal(gr, new Vector(0, 1, 0), DECAL_SCORCH, 4.6f, 300);
    }

    /** 폭발탄 작은 터짐: 섬광 심(보통), 불덩이 하나(보통), 작은 그을음 */
    public void pop(Location at) {
        noteBlast(at, 1.8);
        ItemDisplay f = spawn(at, "fx_quad", FLASH, 0, scale(0.9f), Display.Billboard.CENTER, true, 3, NORMAL);
        grow(f, scale(2.4f), 2);
        ItemDisplay fire = spawn(at, "fx_quad", FIRE, 0, scale(0.7f), Display.Billboard.CENTER, true, 14, NORMAL);
        grow(fire, scale(2.2f), 6);
        drift(fire, at.clone().add(0, 0.35, 0), 12);
        Location gr = ground(at, 1.6);
        if (gr.distanceSquared(at) < 2.6) decal(gr, new Vector(0, 1, 0), DECAL_SCORCH, 1.1f, 120);
    }

    /** 연쇄 탄 빛줄기: from 에서 to 까지 한 줄(곧 사라짐, 보통) */
    public void streak(Location from, Location to) {
        Vector way = to.toVector().subtract(from.toVector());
        double len = way.length();
        if (len < 0.3) return;
        spawn(from, "fx_tracer", TRACER, 0, along(way.multiply(1 / len), 0.22f, (float) len), Display.Billboard.FIXED, true, 4, NORMAL);
    }

    /** 불꽃 한 덩이(화염탄, 화염병, 조명탄): 크기 size 에서 1.6배로 자라며 살짝 오름, life 틱 */
    public ItemDisplay flame(Location at, float size, int life, int prio) {
        ItemDisplay fire = spawn(at, "fx_quad", FIRE, 0, scale(size), Display.Billboard.CENTER, true, life, prio);
        grow(fire, scale(size * 1.6f), Math.max(2, life - 4));
        drift(fire, at.clone().add(0, 0.3 + size * 0.2, 0), Math.max(2, life - 2));
        return fire;
    }

    /** 터짐(산성, 담즙 같은 몹 연출): 덩이 둘, 초록 충격파, 남는 초록 가스(덤), 바닥 웅덩이 */
    public void burst(Location at) {
        Location gr = ground(at, 2.5);
        ItemDisplay b = spawn(at.clone().add(0, 1.0, 0), "fx_quad", BILE, 0, scale(2f), Display.Billboard.CENTER, false, 24, KEY);
        grow(b, scale(8f), 9);
        Location c2 = at.clone().add(rnd.nextGaussian() * 0.4, 0.6, rnd.nextGaussian() * 0.4);
        ItemDisplay b2 = spawn(c2, "fx_quad", BILE, 0, scale(1.5f), Display.Billboard.CENTER, false, 22, NORMAL);
        grow(b2, scale(6f), 12);
        ItemDisplay ring = spawn(gr.clone().add(0, 0.15, 0), "fx_flat", RING, 1, scale(1.5f), Display.Billboard.FIXED, false, 11, NORMAL);
        grow(ring, scale(11f), 8);
        Location gas = at.clone().add(0, 1.2, 0);
        ItemDisplay sm = spawn(gas, "fx_quad", SMOKE, 2, scale(3f), Display.Billboard.CENTER, false, 62, EXTRA);
        grow(sm, scale(8f), 59);
        drift(sm, gas.clone().add(0, 1.5, 0), 59);
        decal(gr, new Vector(0, 1, 0), DECAL_BILE, 5f, 160);
    }

    // ------------------------------------------------------------------ 새 무기(2026-11-12): 불길, 서리, 번개, 레일 광선, 유탄 폭발, 날아가는 탄

    /**
     * 불길(V_FLAME), 서리(V_FROST) 덩이 하나: 총구 from 에서 dir 쪽 reach 블록 끝까지 날아가며 커진다(덩이마다 조금씩 흔들려 원뿔처럼 보임).
     * 사람 몫 예산(owner)과 보통 우선순위를 지킨다
     */
    public void jet(java.util.UUID owner, Location from, Vector dir, double reach, int variant) {
        int life = (int) Math.max(4, Math.min(9, Math.round(reach / 1.1)));
        Vector d = dir.clone().normalize();
        Vector side = right(d), up = side.clone().crossProduct(d).normalize();
        double jx = (rnd.nextDouble() - 0.5) * 0.4, jy = (rnd.nextDouble() - 0.5) * 0.4;
        Location start = from.clone().add(d.clone().multiply(0.35));
        Location end = from.clone().add(d.clone().multiply(reach)).add(side.multiply(jx * reach * 0.35))
            .add(up.multiply(jy * reach * 0.35 + (variant == V_FLAME ? 0.3 : -0.1)));
        float s0 = variant == V_FLAME ? 0.5f : 0.45f, s1 = variant == V_FLAME ? 2.2f : 2.1f;
        ItemDisplay q = spawn(start, "fx_quad", WEAPON, variant, scale(s0), Display.Billboard.CENTER, variant == V_FLAME, life + 2, true, NORMAL, owner);
        grow(q, scale(s1), life);
        drift(q, end, life);
    }

    /** 번개: from 에서 to 까지 가운데를 옆으로 흩은 꺾인 줄 1~3 토막(5 틱). 사람 몫 예산 */
    public void bolt(Location from, Location to, java.util.UUID owner) {
        Vector way = to.toVector().subtract(from.toVector());
        double len = way.length();
        if (len < 0.3) return;
        Vector d = way.clone().multiply(1 / len), side = right(d), up = side.clone().crossProduct(d).normalize();
        int n = len < 3 ? 1 : len < 9 ? 2 : 3;
        Location a = from.clone();
        for (int i = 1; i <= n; i++) {
            // 꺾임은 토막 길이의 8% 쯤(많아야 0.7 블록): 크게 꺾으면 가까운 번개가 화면 위로 솟아 보임(2026-11-12 게임 안 사진)
            double jag = Math.min(0.7, 0.08 * len / n);
            Location b = i == n ? to.clone()
                : from.clone().add(d.clone().multiply(len * i / n)).add(side.clone().multiply(Math.max(-1, Math.min(1, rnd.nextGaussian() * 0.5)) * jag))
                    .add(up.clone().multiply(Math.max(-1, Math.min(1, rnd.nextGaussian() * 0.5)) * jag));
            Vector seg = b.toVector().subtract(a.toVector());
            double sl = seg.length();
            if (sl > 0.05) spawn(a, "fx_tracer", WEAPON, V_BOLT, along(seg.multiply(1 / sl), 0.6f, (float) sl), Display.Billboard.FIXED, true, 5, true,
                i == 1 ? NORMAL : EXTRA, owner);
            a = b;
        }
    }

    /** 레일 광선: from 에서 to 까지 굵은 한 줄(9 틱, 꼭) */
    public void beam(Location from, Location to) {
        Vector way = to.toVector().subtract(from.toVector());
        double len = way.length();
        if (len < 0.3) return;
        spawn(from, "fx_tracer", WEAPON, V_BEAM, along(way.multiply(1 / len), 0.95f, (float) len), Display.Billboard.FIXED, true, 9, KEY);
    }

    /** 유탄 폭발(반경 r): 섬광 심(꼭), 불덩이 둘(첫째 꼭), 충격파 고리, 연기 하나(덤), 그을음. 수류탄 explosion 보다 작게 반경에 맞춤 */
    /** 폭발 연출 자리를 엔진 폭발 기록(FIRELOG, Mobs.blastsNear)에 적음(2026-11-12 sp_engine). */
    private void noteBlast(Location at, double r) {
        if (at != null && host.targets() instanceof kr.gave.mob.Mobs m) m.noteBlast(at.toVector(), r);
    }

    public void blast(Location at, float r) {
        noteBlast(at, r);
        float k = Math.max(0.5f, Math.min(1.2f, r / 5.5f));
        Location gr = ground(at, 2.5);
        ItemDisplay f = spawn(at.clone().add(0, 0.6, 0), "fx_quad", FLASH, 0, scale(2.6f * k), Display.Billboard.CENTER, true, 4, KEY);
        grow(f, scale(7.5f * k), 2);
        for (int i = 0; i < 2; i++) {
            Location c = at.clone().add(rnd.nextGaussian() * 0.35, 0.5 + rnd.nextDouble() * 0.5, rnd.nextGaussian() * 0.35);
            ItemDisplay fire = spawn(c, "fx_quad", FIRE, 0, scale((1.6f + i * 0.4f) * k), Display.Billboard.CENTER, true, 20, i == 0 ? KEY : NORMAL);
            grow(fire, scale((5.6f + i) * k), 7 + i * 2);
            drift(fire, c.clone().add(0, 0.5 + i * 0.2, 0), 16);
        }
        ItemDisplay ring = spawn(gr.clone().add(0, 0.15, 0), "fx_flat", RING, 0, scale(1.2f), Display.Billboard.FIXED, true, 10, NORMAL);
        grow(ring, scale(2.4f * r), 7);
        Location c = at.clone().add(0, 1.0, 0);
        ItemDisplay sm = spawn(c, "fx_quad", SMOKE, 1, scale(2.4f * k), Display.Billboard.CENTER, false, 64, EXTRA);
        grow(sm, scale(7f * k), 56);
        drift(sm, c.clone().add(rnd.nextGaussian() * 0.5, 2.2, rnd.nextGaussian() * 0.5), 56);
        decal(gr, new Vector(0, 1, 0), DECAL_SCORCH, 3.4f * k, 260);
    }

    /** 진행 방향 변환(아이템 디스플레이는 모델을 y 로 반 바퀴 돌려 그리므로 앞이 -Z 인 모델은 rotationTo(+Z, dir) 이면 dir 을 봄) */
    private static Matrix4f facingDir(Vector dir) {
        Vector3f v = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());
        if (v.lengthSquared() < 1e-8f) v.set(0, 0, 1);
        return new Matrix4f().rotate(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), v.normalize()));
    }

    /**
     * 날아가는 탄 몸통(유탄, 로켓): 소품 층 모드 모델(gave:&lt;model&gt;, 없으면 바닐라 그림)을 진행 방향으로. 예산 밖(Arsenal.MAX_SHELLS 가 수를 막음),
     * life 틱 뒤에는 저절로 치운다. 움직임은 {@link #moveShell}, 터지면 {@link #end}
     */
    public ItemDisplay shell(Location at, String model, Vector dir, int life) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Props.has(model) ? Key.key(Props.NS, model) : Key.key("minecraft", "fire_charge"));
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        ItemDisplay d = at.getWorld().spawn(l, ItemDisplay.class, x -> {
            x.setItemStack(it);
            x.setPersistent(false);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            x.setBillboard(Display.Billboard.FIXED);
            x.setBrightness(new Display.Brightness(13, 13));
            x.setTransformationMatrix(facingDir(dir));
            x.setViewRange(2.5f);
            x.setTeleportDuration(1);
        });
        Live lv = new Live(d, host.now() + life, false);
        lv.free = true;
        timed.add(lv);
        return d;
    }

    /** 날아가는 탄을 다음 자리로(순간 이동 보간 1 틱), 방향도 */
    public void moveShell(ItemDisplay d, Location to, Vector dir) {
        if (d == null || !d.isValid()) return;
        Location t = to.clone();
        t.setYaw(0);
        t.setPitch(0);
        d.teleport(t);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformationMatrix(facingDir(dir));
    }

    /** 이 연출을 지금 치움(날아가는 탄이 터짐). 기록은 다음 tick 이 지움 */
    public void end(ItemDisplay d) {
        if (d == null) return;
        for (Live o : timed) if (o.d == d) {
            o.until = Long.MIN_VALUE;
            break;
        }
        d.remove();
    }

    /** 로켓 꼬리: 작은 불꽃(보통)과 오르며 퍼지는 연기(덤). 사람 몫 예산 */
    public void trail(Location at, java.util.UUID owner) {
        spawn(at, "fx_quad", FIRE, 0, scale(0.55f), Display.Billboard.CENTER, true, 3, true, NORMAL, owner);
        ItemDisplay sm = spawn(at, "fx_quad", SMOKE, 0, scale(0.55f), Display.Billboard.CENTER, false, 26, true, EXTRA, owner);
        grow(sm, scale(1.7f), 24);
        drift(sm, at.clone().add(rnd.nextGaussian() * 0.15, 0.35, rnd.nextGaussian() * 0.15), 24);
    }

    /** 얼어붙은 게이브 둘레 서리 덩이(덤) */
    public void frost(Location at) {
        ItemDisplay q = spawn(at, "fx_quad", WEAPON, V_FROST, scale(0.9f), Display.Billboard.CENTER, false, 10, EXTRA);
        grow(q, scale(1.7f), 9);
    }

    /** 방전이 닿은 자리 불똥(덤) */
    public void zap(Location at) {
        spawn(at, "fx_quad", SPARK, 0, scale(0.9f), Display.Billboard.CENTER, true, 5, EXTRA);
    }

    public void tick() {
        long now = host.now();
        for (Iterator<Live> it = timed.iterator(); it.hasNext();) {
            Live o = it.next();
            // 자국은 한도 때문에 먼저 지워졌을 수 있다(isValid false): 그것도 여기서 치운다
            if (now < o.until && (!o.decal || o.d.isValid())) continue;
            o.d.remove();
            if (o.decal) {
                if (!holes.remove(o.d) && !bloods.remove(o.d)) bigs.remove(o.d);
            } else if (!o.free) {
                live--;
            }
            if (o.owner != null) ownerLive.computeIfPresent(o.owner, (k, v) -> v <= 1 ? null : v - 1);
            it.remove();
        }
    }

    public void clear() {
        for (Live o : timed) o.d.remove();
        timed.clear();
        ownerLive.clear();
        wantAt.clear();
        holes.clear();
        bloods.clear();
        bigs.clear();
        live = 0;
    }

    /** 지금 살아 있는 연출 디스플레이 수(자국 포함) */
    public int alive() {
        return timed.size();
    }

    /** 지금 살아 있는 자국 아닌 연출 수(예산 {@link #BUDGET} 과 견줌) */
    public int used() {
        return live;
    }

    /** 지금 남은 자국 수 */
    public int decals() {
        return holes.size() + bloods.size() + bigs.size();
    }

    /**
     * 시험 훅(Guns.test 가 넘김): 폭발|터짐|피 &lt;이름&gt; &lt;거리&gt; = 시선 앞 그 거리(블록에 막히면 그 앞)에 연출, 연출수, 자국시험 [탄흔 수],
     * 예산시험 [n] = 덤 탄 궤적 n 개(기본 120)를 한꺼번에 낳아 예산이 지켜지는지
     */
    public String test(Player p, String[] a) {
        if (p == null || a.length < 2) return null;
        String act = a[1];
        if (act.equals("연출수"))
            return "fx=" + timed.size() + " live=" + live + "/" + BUDGET + " dropped=" + dropped + " decals=" + decals() + " holes=" + holes.size()
                + " blood=" + bloods.size() + " big=" + bigs.size();
        if (act.equals("자국시험")) {
            // 큰 자국(그을음) 하나 뒤에 탄흔 n 개(기본 120)를 붙여도 그을음이 남는지(자국 한도가 종류마다 따로인지)
            double want = a.length > 3 ? Guns.parse(a[3]) : 120;
            int nHoles = Double.isNaN(want) || want < 0 ? 120 : (int) Math.min(400, want);
            Location base = p.getLocation();
            decal(base.clone().add(0, 0.01, 0), new Vector(0, 1, 0), DECAL_SCORCH, 2f, 300);
            ItemDisplay scorch = bigs.peekLast();
            for (int i = 0; i < nHoles; i++)
                decal(base.clone().add(rnd.nextDouble() * 4 - 2, 0.01, rnd.nextDouble() * 4 - 2), new Vector(0, 1, 0), DECAL_HOLE, 0.3f, 200);
            return "그을음 " + (scorch != null && scorch.isValid() ? "남음" : "지워짐") + " holes=" + holes.size() + " big=" + bigs.size();
        }
        if (act.equals("예산시험")) {
            double want = a.length > 3 ? Guns.parse(a[3]) : 120;
            int n = Double.isNaN(want) || want < 1 ? 120 : (int) Math.min(400, want);
            Location eye = p.getEyeLocation();
            long before = dropped;
            for (int i = 0; i < n; i++) {
                Location end = eye.clone().add(eye.getDirection().multiply(20)).add(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
                tracer(p, eye, end, i % 4 == 0);
            }
            explosion(eye.clone().add(eye.getDirection().multiply(8)));
            return "live=" + live + "/" + BUDGET + " dropped=" + (dropped - before) + " fx=" + timed.size();
        }
        if (!act.equals("폭발") && !act.equals("터짐") && !act.equals("피")) return null;
        double dist = a.length > 3 ? Guns.parse(a[3]) : 8;
        if (Double.isNaN(dist)) dist = 8;
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, look, dist, FluidCollisionMode.NEVER, true);
        Location at = r == null ? eye.clone().add(look.clone().multiply(dist)) : r.getHitPosition().toLocation(p.getWorld()).subtract(look.clone().multiply(0.4));
        switch (act) {
            case "폭발" -> explosion(ground(at, 4));
            case "터짐" -> burst(ground(at, 4));
            default -> bloodHit(at, look, true);
        }
        return String.format("%s %.1f %.1f %.1f", act, at.getX(), at.getY(), at.getZ());
    }
}
