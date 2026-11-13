package kr.gave.trip;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.world.Plaza;
import net.kyori.adventure.key.Key;

/**
 * 스테이지마다 다른 충돌 없는 소품 묶음(원정.md 3.5절): 아이템 디스플레이 {@link #MAX} 개까지, 중앙 원 밖 R {@link #R_IN}..{@link #R_OUT} 에 놓는다.
 * 사람과 게이브는 그냥 지나간다(충돌, 걷기 격자, 흐름장과 무관). 묶음(카드의 props)과 놓는 법은 stages.json scenery.
 *
 * <p>모델은 모드에서 옮긴 것만: tools/trip/route.py 가 구운 길가 소품(아이템 모델 gave:trip/&lt;이름&gt;)과 소품 층 키(gave:&lt;이름&gt;).
 * 둘 다 '월드 소품' 꼴이다(모델 (8,8,8) = 엔티티 자리, 바닥 = 엔티티 y - 0.5, fixed 표시 변환). 내보내는 팩에 그 아이템 정의가 없으면 그 소품은 건너뛴다.
 */
public final class Scenery {

    /** 한 스테이지 소품 수 상한(판 규칙 '고정 20' 안), 놓는 반지름 띠(중앙 원 밖, 안개 끝 안팎). */
    public static final int MAX = 16;
    public static final double R_IN = 14, R_OUT = 58;
    /** 길 폭의 절반(길 한 줄은 폭 5): 길가 소품은 이 밖에 선다. */
    public static final double ROAD_HALF = 4.5; // 2026-11-12 도로 폭 1.8배에 맞춤

    private final Game game;
    private final StageData data;
    private final List<ItemDisplay> live = new ArrayList<>();
    private String key = "none";

    Scenery(Game game, StageData data) {
        this.game = game;
        this.data = data;
    }

    /** 지금 놓인 소품 수. */
    public int displays() {
        return live.size();
    }

    /** 지금 묶음 키. */
    public String key() {
        return key;
    }

    /** 다 치운다. */
    public void clear() {
        for (ItemDisplay d : live) if (d != null && d.isValid()) d.remove();
        live.clear();
        key = "none";
    }

    /** 그 스테이지의 소품을 놓는다(앞 것은 치움). 같은 판, 같은 스테이지면 같은 자리(rnd 는 판 씨앗에서). 놓은 수. */
    public int set(Stage st, Random rnd) {
        clear();
        if (st == null) return 0;
        key = st.props();
        StageData.Props p = data.scenery.get(key);
        if (p == null) return 0;
        List<String> models = new ArrayList<>();
        for (String m : p.models) if (has(game, m)) models.add(m);
        if (models.isEmpty()) return 0;
        int n = Math.min(MAX, p.count);
        switch (p.layout) {
            case "road" -> road(p, models, n);
            case "jam" -> jam(models, n, rnd);
            case "cluster" -> cluster(p, models, n, rnd);
            default -> scatter(models, n, rnd);
        }
        return live.size();
    }

    /** 길 양옆에 줄지어(길은 남북으로 가운데를 꿰뚫음): 앞뒤, 동서 네 자리씩 gap 마다. 모델은 차례로 돌려 쓴다(첫 모델이 드문 것: 가로등). */
    private void road(StageData.Props p, List<String> models, int n) {
        double gap = Math.max(3, p.gap), side = ROAD_HALF + 1.5;
        int k = 0;
        for (double z = R_IN + 2; z <= R_OUT && live.size() < n; z += gap) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                for (int east = -1; east <= 1 && live.size() < n; east += 2) {
                    // 길가 소품은 길 쪽을 본다(동쪽에 선 것은 서쪽을)
                    put(models.get(k++ % models.size()), Plaza.CX + 0.5 + east * side, Plaza.CZ + 0.5 + sgn * z, east > 0 ? 90f : -90f, 1f);
                }
            }
        }
    }

    /** 길 위에 흩어진 것(멈춘 차 자리): 길 폭 안, 앞뒤 R_IN 밖. */
    private void jam(List<String> models, int n, Random rnd) {
        for (int i = 0; i < n; i++) {
            double z = (R_IN + 2 + rnd.nextDouble() * (R_OUT - R_IN - 8)) * (i % 2 == 0 ? -1 : 1);
            double x = (rnd.nextDouble() * 2 - 1) * (ROAD_HALF + 1.0);
            put(models.get(i % models.size()), Plaza.CX + 0.5 + x, Plaza.CZ + 0.5 + z, rnd.nextFloat() * 360f, 1f);
        }
    }

    /** 몇 무더기: 자리마다 둥글게 모아 놓음. */
    private void cluster(StageData.Props p, List<String> models, int n, Random rnd) {
        int spots = Math.max(1, p.spots);
        for (int s = 0; s < spots; s++) {
            double ang = rnd.nextDouble() * Math.PI * 2, rad = R_IN + 4 + rnd.nextDouble() * 24;
            double cx = Plaza.CX + 0.5 + Math.cos(ang) * rad, cz = Plaza.CZ + 0.5 + Math.sin(ang) * rad;
            int each = (n + spots - 1 - s) / spots;
            for (int i = 0; i < each && live.size() < n; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = 0.6 + Math.sqrt(rnd.nextDouble()) * 2.6;
                put(models.get(rnd.nextInt(models.size())), cx + Math.cos(a) * r, cz + Math.sin(a) * r, rnd.nextFloat() * 360f, 1f);
            }
        }
    }

    /** 흩뿌림: R_IN..R_OUT 고르게(넓이 기준), 길 위는 피함. */
    private void scatter(List<String> models, int n, Random rnd) {
        for (int i = 0, tries = 0; i < n && tries < n * 8; tries++) {
            double ang = rnd.nextDouble() * Math.PI * 2, rad = Math.sqrt(R_IN * R_IN + rnd.nextDouble() * (R_OUT * R_OUT - R_IN * R_IN));
            double x = Math.cos(ang) * rad, z = Math.sin(ang) * rad;
            if (Math.abs(x) < ROAD_HALF + 1) continue;
            put(models.get(rnd.nextInt(models.size())), Plaza.CX + 0.5 + x, Plaza.CZ + 0.5 + z, rnd.nextFloat() * 360f, 1f);
            i++;
        }
    }

    private void put(String model, double x, double z, float yaw, float scale) {
        ItemDisplay d = spawn(game, model, new Location(game.world, x, Plaza.FLOOR_Y + 0.5 * scale, z), yaw, scale, 0.9f);
        if (d != null) live.add(d);
    }

    // ------------------------------------------------------------------ 월드 소품 디스플레이(표지판, 흐름 소품도 같이 씀)

    /** 월드 소품 꼴 모델의 아이템(PAPER + item_model). 키는 "trip/lamp" 처럼 이름공간 없이. */
    public static ItemStack item(String model) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, model));
        return it;
    }

    /** 월드 소품 하나(자리 = 모델 가운데, 바닥은 그 0.5 x scale 아래). yaw 는 모델 앞(북)이 도는 각도(도). 밝기는 세상 빛 그대로. */
    public static ItemDisplay spawn(Game game, String model, Location at, float yaw, float scale, float viewRange) {
        ItemStack it = item(model);
        Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(-yaw)).scale(scale);
        return game.world.spawn(at, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setItemStack(it);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            x.setBillboard(Display.Billboard.FIXED);
            x.setViewRange(viewRange);
            x.setTransformationMatrix(m);
        });
    }

    // ------------------------------------------------------------------ 팩에 그 모델이 있나

    private static byte[] seen;
    private static final Set<String> ITEMS = new HashSet<>();

    /** 내보내는 팩(PackServer.bytes())에 아이템 정의 gave:&lt;model&gt; 이 있나. 팩이 바뀌면 다시 읽는다. 팩이 없으면 모두 없음. */
    public static synchronized boolean has(Game game, String model) {
        byte[] b = game.plugin.pack == null ? null : game.plugin.pack.bytes();
        if (b != seen) {
            seen = b;
            ITEMS.clear();
            if (b != null) {
                String pre = "assets/" + Props.NS + "/items/";
                try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
                    for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                        String n = e.getName();
                        if (n.startsWith(pre) && n.endsWith(".json")) ITEMS.add(n.substring(pre.length(), n.length() - 5));
                    }
                } catch (Exception ex) {
                    game.plugin.getLogger().warning("[길] 팩에서 아이템 정의 목록을 못 읽음: " + ex);
                }
            }
        }
        return ITEMS.contains(model);
    }
}
