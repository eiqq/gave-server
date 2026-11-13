package kr.gave.gun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.player.PState;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/**
 * 소모품(상점이 팔고 핫바 3번 칸 하나에 한 종류씩): 수류탄, 조명탄, 화염병, 딸랑이, 정화, 붕대 + 입는 비옷.
 * 쓰기: 3번 칸을 들고 우클릭, 또는 F(수류탄이면 Grenades 가 받음). 던지는 것(조명탄, 화염병, 딸랑이)은 수류탄처럼 날아가 튕긴다.
 * <ul>
 * <li>조명탄: 20초 동안 반경 12 에 빛(그 칸에 빛 블록 15), 10틱마다 Mobs.reveal(흉내 종 정체 드러남). 빛 피하는 종은 {@link #inFlare} 로 묻는다.</li>
 * <li>화염병: 처음 닿는 곳에서 깨져 반경 3 바닥 불 5초(10틱마다 FIRE 7.5 = 초당 15, 셰이더 불꽃 연출, 그을음 자국).</li>
 * <li>딸랑이: 멈춘 자리로 12블록 안 게이브 주의를 5초 끎(Mobs.lure, 20틱마다 다시 걸어 늦게 온 것도).</li>
 * <li>정화: 자기 잠복, 덮기, 느려짐 지움(Players.purify).</li>
 * <li>붕대: 3초(3번 칸을 든 채, 서 있는 동안) 뒤 체력 40. 진행은 칸 아이템 재사용 대기 그림으로 보인다.</li>
 * <li>비옷: 사면 바로 입음(PState.raincoat, 노란 가죽 옷이 남에게 보임). 붙기나 잠복, 덮기를 한 번 막고 찢어짐(Players.coat 가 줄임,
 * 옷 벗김과 찢어지는 소리는 여기서).</li>
 * </ul>
 * 칸 아이템은 이름 없음(핫바 이름 팝업 없음), 아이템 모델 gave:shop/&lt;종류&gt;(수류탄은 소품 gave:grenade).
 * 던진 것은 판 전체 {@link #MAX_TOSSES} 개까지(차면 던지지 않고 소모품은 남음). 조명탄 빛 블록은 같은 칸을 쓰는 조명탄 수를 세고
 * 놓은 자리를 플러그인 폴더 flare_lights.txt 에 적어 두어, 서버가 비정상으로 꺼져도 다음에 켤 때 지운다.
 * 주머니 수류탄({@link #pocket}, 주머니 증강)은 칸 종류와 따로 Arms.grenades 에 들어 F 로 던지고, 칸이 비면 칸에 보인다.
 */
public final class Supplies {

    /** 칸에 드는 종류(차례 = 상점 칸 차례) */
    public static final List<String> KINDS = List.of("grenade", "flare", "molotov", "rattle", "cleanse", "bandage");
    /** 종류마다 칸 한도 */
    public static final Map<String, Integer> MAX = new LinkedHashMap<>();
    /** 한국어 이름, 한 줄 */
    public static final Map<String, String> NAME = new LinkedHashMap<>(), LINE = new LinkedHashMap<>();

    static {
        put("grenade", 3, "수류탄", "F 로도 던짐");
        put("flare", 2, "조명탄", "흉내 종이 드러남");
        put("molotov", 2, "화염병", "바닥 불 5초");
        put("rattle", 2, "딸랑이", "게이브를 끎");
        put("cleanse", 2, "정화", "잠복, 덮기, 느려짐 지움");
        put("bandage", 3, "붕대", "3초 뒤 체력 40");
        put("raincoat", 1, "글귀 적힌 비옷", "붙기나 잠복을 한 번 막음");
    }

    private static void put(String k, int max, String name, String line) {
        MAX.put(k, max);
        NAME.put(k, name);
        LINE.put(k, line);
    }

    public static final int FLARE_TICKS = 400, FIRE_TICKS = 100, RATTLE_TICKS = 100, BANDAGE_TICKS = 60;
    /** 판 전체에 한 번에 있을 수 있는 던진 것(날거나 멈춘 조명탄, 화염병, 딸랑이 디스플레이). 차면 던지지 않음(소모품은 남음) */
    public static final int MAX_TOSSES = 12;
    /** 멈춘 딸랑이 흔들림 자세를 보내는 틱 간격(보간 길이도 같음) */
    private static final int WOBBLE_EVERY = 5;
    public static final double FLARE_R = 12, FIRE_R = 3, RATTLE_R = 12, FIRE_DPS = 15, BANDAGE_HP = 40;
    private static final double SPEED = 1.0, LIFT = 0.2, GRAVITY = 0.06, DRAG = 0.99;

    private final Game game;
    private final Random rnd = new Random();

    private static final class Toss {
        String kind;
        ItemDisplay d;
        Vector pos, vel, axis = new Vector(1, 0, 0);
        Player owner;
        long thrown, until, nextFx, nextLure;
        boolean resting;
        int bounces;
        float spin;
        Block light;
    }

    private static final class Fire {
        Location at;
        Player owner;
        long until, next, nextFx;
    }

    private final List<Toss> tosses = new ArrayList<>();
    private final List<Fire> fires = new ArrayList<>();
    private final Map<UUID, Long> usedAt = new HashMap<>(), bandageUntil = new HashMap<>();
    private final Map<UUID, String> sent = new HashMap<>();
    private final Set<UUID> raincoats = new HashSet<>();
    /** 조명탄이 놓은 빛 블록(같은 칸을 쓰는 켜진 조명탄 수). 마지막 것이 꺼질 때 지움 */
    private final Map<Block, Integer> lights = new HashMap<>();
    /** 놓은 빛 블록 자리 기록(서버가 비정상으로 꺼져도 다음에 켤 때 지우게) */
    private final java.io.File lightLog;
    private String lastLog = "없음";

    public Supplies(Game game) {
        this.game = game;
        this.lightLog = new java.io.File(game.plugin.getDataFolder(), "flare_lights.txt");
        sweepLights();
    }

    /** 지난번에 지우지 못한 조명탄 빛 블록을 지움(켤 때 한 번) */
    private void sweepLights() {
        if (!lightLog.isFile()) return;
        int n = 0;
        try {
            for (String line : java.nio.file.Files.readAllLines(lightLog.toPath())) {
                String[] f = line.trim().split(" ");
                if (f.length != 4) continue;
                World w = Bukkit.getWorld(f[0]);
                if (w == null) continue;
                Block b = w.getBlockAt(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
                if (b.getType() == Material.LIGHT) {
                    b.setType(Material.AIR, false);
                    n++;
                }
            }
            java.nio.file.Files.deleteIfExists(lightLog.toPath());
        } catch (java.io.IOException | RuntimeException ex) {
            game.plugin.getLogger().warning("[총] 조명탄 빛 기록 읽기 실패: " + ex);
        }
        if (n > 0) game.plugin.getLogger().info("[총] 남은 조명탄 빛 블록 " + n + "개 지움");
    }

    private void saveLights() {
        try {
            if (lights.isEmpty()) {
                java.nio.file.Files.deleteIfExists(lightLog.toPath());
                return;
            }
            List<String> out = new ArrayList<>();
            for (Block b : lights.keySet()) out.add(b.getWorld().getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ());
            lightLog.getParentFile().mkdirs();
            java.nio.file.Files.write(lightLog.toPath(), out);
        } catch (java.io.IOException ex) {
            game.plugin.getLogger().warning("[총] 조명탄 빛 기록 쓰기 실패: " + ex);
        }
    }

    // ------------------------------------------------------------------ 아이템

    /** 상점, 칸 그림 아이템 모델 키(gave:shop/flare 등, 수류탄은 소품 grenade) */
    public static Key model(String kind) {
        if (kind.equals("grenade") && Props.has("grenade")) return Key.key(Props.NS, "grenade");
        return Key.key(Props.NS, "shop/" + kind);
    }

    /** 칸 아이템(이름 없음, 툴팁 숨김) n 개 */
    public static ItemStack stack(String kind, int n) {
        ItemStack it = ItemStack.of(Material.PAPER, Math.max(1, Math.min(64, n)));
        it.setData(DataComponentTypes.ITEM_MODEL, model(kind));
        it.setData(DataComponentTypes.ITEM_NAME, Component.empty());
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return it;
    }

    private static ItemStack coat() {
        ItemStack it = ItemStack.of(Material.LEATHER_CHESTPLATE);
        it.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(0xF2C43A)));
        it.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        it.setData(DataComponentTypes.ITEM_NAME, Component.empty());
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return it;
    }

    /** 지금 칸 종류(없으면 null) */
    public String kind(Player p) {
        Guns.Arms a = game.guns.arms(p);
        return a == null ? null : a.item;
    }

    /** 이 종류를 몇 개 가졌나(비옷은 입었으면 1) */
    public int count(Player p, String kind) {
        if (kind.equals("raincoat")) return raincoat(p) ? 1 : 0;
        Guns.Arms a = game.guns.arms(p);
        if (a == null) return 0;
        if (kind.equals("grenade")) return a.grenades;
        return kind.equals(a.item) ? a.itemCount : 0;
    }

    /** 더 받을 수 있나(한도 안) */
    public boolean canTake(Player p, String kind) {
        return game.guns.arms(p) != null && count(p, kind) < MAX.getOrDefault(kind, 1);
    }

    /**
     * 소모품 n 개를 줌. 다른 종류를 들고 있었으면 그것을 버리고 바꾼다(한 종류씩). 한도까지만. 늘었으면 true. 비옷은 입힘
     */
    public boolean give(Player p, String kind, int n) {
        if (kind.equals("raincoat")) return giveRaincoat(p);
        Guns.Arms a = game.guns.arms(p);
        if (a == null || !MAX.containsKey(kind) || n <= 0) return false;
        int max = MAX.get(kind);
        int have = count(p, kind);
        if (have >= max) return false;
        if (!kind.equals(a.item)) {
            // 바꿈: 전 종류는 버림
            if ("grenade".equals(a.item)) a.grenades = 0;
            a.itemCount = 0;
            a.item = kind;
        }
        int now = Math.min(max, have + n);
        if (kind.equals("grenade")) a.grenades = now;
        else a.itemCount = now;
        sync(p, a);
        return true;
    }

    /** 이 종류를 사면 3번 칸의 다른 소모품을 버리나: 버릴 종류 키(수 &gt; 0), 아니면 null. 상점이 확인을 받는다 */
    public String wouldDrop(Player p, String kind) {
        Guns.Arms a = game.guns.arms(p);
        if (a == null || kind.equals("raincoat") || a.item == null || a.item.equals(kind) || a.items() <= 0) return null;
        return a.item;
    }

    /**
     * 주머니 수류탄(주머니 증강): 3번 칸 종류는 바꾸지 않고 Arms.grenades 에 n 개 더함(한도까지). F 로 던지고, 칸이 비면 칸에 수류탄이 보인다.
     * 칸이 비었으면 바로 수류탄 칸. 늘었으면 true
     */
    public boolean pocket(Player p, int n) {
        Guns.Arms a = game.guns.arms(p);
        if (a == null || n <= 0) return false;
        int max = MAX.get("grenade");
        if (a.grenades >= max) return false;
        a.grenades = Math.min(max, a.grenades + n);
        sync(p, a);
        return true;
    }

    // ------------------------------------------------------------------ 쓰기

    /** 3번 칸 소모품을 씀(우클릭, F). 쓴 게 있으면 true. 판 밖, 쓰러짐, 붙잡힘이면 안 씀 */
    public boolean use(Player p) {
        Guns.Arms a = game.guns.arms(p);
        if (a == null || !game.running() || game.blocked(p)) return false;
        if (a.item == null && a.grenades > 0) a.item = "grenade";
        if (a.item == null || a.items() <= 0) return false;
        long now = game.now();
        Long last = usedAt.get(p.getUniqueId());
        if (last != null && now - last < 8) return false;
        if (bandageUntil.containsKey(p.getUniqueId())) return false;
        usedAt.put(p.getUniqueId(), now);
        String k = a.item;
        switch (k) {
            case "grenade" -> {
                if (a.grenades <= 0) return false;
                game.grenades.throwFrom(p);
            }
            case "flare", "molotov", "rattle" -> {
                if (tosses.size() >= MAX_TOSSES) {
                    // 판에 던진 것이 꽉 참: 던지지 않고 소모품은 남김
                    usedAt.remove(p.getUniqueId());
                    Props.sound(p, null, Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1f, 1.2f);
                    lastLog = "던진 것 꽉 참 " + tosses.size();
                    return false;
                }
                throwIt(p, k);
                a.itemCount--;
            }
            case "cleanse" -> {
                game.players.purify(p);
                a.itemCount--;
                Props.soundAround(p.getLocation(), null, Sound.BLOCK_BREWING_STAND_BREW, 0.9f, 1f, 1.5f, 12, -1);
                Props.sound(p, null, Sound.ENTITY_GENERIC_DRINK, 0.8f, 1f, 1.2f);
                lastLog = "정화 " + p.getName();
            }
            case "bandage" -> {
                // trip_aug: 붕대 솜씨(걸리는 시간 배율)
                bandageUntil.put(p.getUniqueId(), now + Math.max(1, Math.round(BANDAGE_TICKS * game.augments.bandageTime(p))));
                ItemStack it = p.getInventory().getItem(Guns.SLOT_ITEM);
                if (it != null) p.setCooldown(it, BANDAGE_TICKS);
                Props.soundAround(p.getLocation(), null, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1f, 1f, 10, -1);
                return true;
            }
            default -> {
                return false;
            }
        }
        if (a.items() <= 0) a.item = null;
        sync(p, a);
        return true;
    }

    private void throwIt(Player p, String kind) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Toss t = new Toss();
        t.kind = kind;
        t.owner = p;
        Vector start = eye.toVector().add(dir.clone().multiply(0.5)).add(right.clone().multiply(0.2)).add(new Vector(0, -0.12, 0));
        RayTraceResult block = p.getWorld().rayTraceBlocks(eye, start.clone().subtract(eye.toVector()).normalize(), 0.75, FluidCollisionMode.NEVER, true);
        t.pos = block == null ? start : eye.toVector();
        t.vel = dir.clone().multiply(SPEED).add(new Vector(0, LIFT, 0)).add(p.getVelocity().clone().multiply(0.5).setY(0));
        t.axis = right.clone();
        t.thrown = game.now();
        t.until = kind.equals("flare") ? t.thrown + FLARE_TICKS : t.thrown + 200;
        ItemStack look = stack(kind, 1);
        boolean glow = kind.equals("flare");
        t.d = p.getWorld().spawn(t.pos.toLocation(p.getWorld()), ItemDisplay.class, d -> {
            d.setItemStack(look);
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setPersistent(false);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            if (glow) d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.55f), new AxisAngle4f()));
        });
        tosses.add(t);
        Props.soundAround(eye, null, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 1f, 0.7f, 16, -1);
        if (glow) Props.soundAround(eye, null, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1f, 1.4f, 16, -1);
        lastLog = "던짐 " + kind + " " + p.getName();
    }

    // ------------------------------------------------------------------ 틱

    public void tick() {
        long now = game.now();
        tossTick(now);
        fireTick(now);
        bandageTick(now);
        if (now % 5 == 0) for (Player p : game.players.inPlay()) {
            Guns.Arms a = game.guns.arms(p);
            if (a != null) sync(p, a);
        }
        for (Player p : game.players.inPlay()) coatTick(p, game.players.get(p));
    }

    private void tossTick(long now) {
        for (Iterator<Toss> it = tosses.iterator(); it.hasNext();) {
            Toss t = it.next();
            if (!t.d.isValid() || now >= t.until) {
                end(t);
                it.remove();
                continue;
            }
            World w = t.d.getWorld();
            if (!t.resting) {
                t.vel.setY(t.vel.getY() - GRAVITY);
                t.vel.multiply(DRAG);
                double len = t.vel.length();
                if (len > 1e-4) {
                    RayTraceResult r = w.rayTraceBlocks(t.pos.toLocation(w), t.vel.clone().multiply(1 / len), len, FluidCollisionMode.NEVER, true);
                    if (r != null && r.getHitBlockFace() != null) {
                        Vector nrm = r.getHitBlockFace().getDirection();
                        if (t.kind.equals("molotov")) {
                            shatter(t, r.getHitPosition().add(nrm.clone().multiply(0.1)));
                            t.d.remove();
                            it.remove();
                            continue;
                        }
                        double vn = t.vel.dot(nrm);
                        t.vel.subtract(nrm.clone().multiply(vn * 1.35)).multiply(0.5);
                        boolean floor = nrm.getY() > 0.7;
                        t.pos = r.getHitPosition().add(nrm.clone().multiply(floor ? 0.06 : 0.12));
                        if (t.bounces++ < 4 && Math.abs(vn) > 0.12)
                            Props.soundAround(t.pos.toLocation(w), null, t.kind.equals("rattle") ? Sound.BLOCK_AMETHYST_BLOCK_HIT : Sound.BLOCK_WOOD_HIT,
                                (float) Math.min(1, 0.3 + Math.abs(vn)), 1f, 1.3f, 16, -1);
                        if (floor && t.vel.lengthSquared() < 0.01) rest(t, now);
                    } else {
                        t.pos.add(t.vel);
                    }
                }
                if (!t.resting && now - t.thrown > 60) {
                    if (t.kind.equals("molotov")) {
                        shatter(t, t.pos.clone());
                        t.d.remove();
                        it.remove();
                        continue;
                    }
                    rest(t, now);
                }
                Vector h = t.vel.clone().setY(0);
                if (h.lengthSquared() > 1e-4) t.axis = h.normalize().crossProduct(new Vector(0, 1, 0)).multiply(-1);
                t.spin += 0.4f;
                Quaternionf q = new Quaternionf().rotateAxis(t.spin, (float) t.axis.getX(), (float) t.axis.getY(), (float) t.axis.getZ());
                t.d.setInterpolationDelay(0);
                t.d.setInterpolationDuration(1);
                t.d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(0.55f), new Quaternionf()));
                t.d.teleport(t.pos.toLocation(w));
            }
            Location at = t.pos.toLocation(w);
            switch (t.kind) {
                case "flare" -> {
                    if (now >= t.nextFx) {
                        t.nextFx = now + 12;
                        game.shotFx.flame(at.clone().add(0, 0.15, 0), 0.32f, 14, ShotFx.NORMAL);
                    }
                    if (t.resting && now >= t.nextLure) {
                        t.nextLure = now + 10;
                        game.mobs.reveal(t.pos.clone(), FLARE_R);
                    }
                    if ((now - t.thrown) % 40 == 0) Props.soundAround(at, null, Sound.BLOCK_FIRE_AMBIENT, 0.6f, 1f, 1.3f, 12, -1);
                }
                case "rattle" -> {
                    if (!t.resting) break;
                    if (now >= t.nextLure) {
                        t.nextLure = now + 20;
                        game.mobs.lure(t.pos.clone(), RATTLE_R, (int) Math.max(1, t.until - now));
                    }
                    if ((now - t.thrown) % 6 == 0) Props.soundAround(at, null, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.9f + rnd.nextFloat() * 0.2f, 1.6f, 24, -1);
                    // 흔들림: 옆으로 까딱(5 틱마다 반대쪽으로, 보간이 그사이를 채움: 보는 사람마다 초당 4 패킷)
                    if ((now - t.thrown) % WOBBLE_EVERY != 0) break;
                    float wob = ((now - t.thrown) / WOBBLE_EVERY % 2 == 0 ? 1 : -1) * 0.45f;
                    t.d.setInterpolationDelay(0);
                    t.d.setInterpolationDuration(WOBBLE_EVERY);
                    t.d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) (Math.PI / 2)).rotateY(wob),
                        new Vector3f(0.55f), new Quaternionf()));
                }
                default -> {
                }
            }
        }
    }

    /** 멈춤: 바닥에 눕히고 조명탄은 빛 블록, 딸랑이는 끌기 시작(5초 남김) */
    private void rest(Toss t, long now) {
        t.resting = true;
        t.vel.zero();
        World w = t.d.getWorld();
        t.d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) (Math.PI / 2)), new Vector3f(0.55f), new Quaternionf()));
        t.d.teleport(t.pos.toLocation(w).add(0, 0.02, 0));
        if (t.kind.equals("flare")) {
            Block b = w.getBlockAt(t.pos.getBlockX(), (int) Math.floor(t.pos.getY() + 0.3), t.pos.getBlockZ());
            if (lights.containsKey(b) && b.getType() == Material.LIGHT) {
                // 같은 칸에 먼저 켜진 조명탄이 있음: 그 빛을 함께 씀(먼저 것이 꺼져도 남음)
                lights.merge(b, 1, Integer::sum);
                t.light = b;
            } else if (b.getType().isAir()) {
                b.setType(Material.LIGHT, false);
                if (b.getBlockData() instanceof Light l) {
                    l.setLevel(15);
                    b.setBlockData(l, false);
                }
                t.light = b;
                lights.put(b, 1);
                saveLights();
            }
            game.mobs.reveal(t.pos.clone(), FLARE_R);
            lastLog = "조명탄 켬 " + fmt(t.pos);
        } else if (t.kind.equals("rattle")) {
            t.until = now + RATTLE_TICKS;
            t.nextLure = now;
            lastLog = "딸랑이 " + fmt(t.pos);
        }
    }

    private void end(Toss t) {
        if (t.light != null) {
            int left = lights.getOrDefault(t.light, 1) - 1;
            if (left > 0) lights.put(t.light, left);
            else {
                lights.remove(t.light);
                if (t.light.getType() == Material.LIGHT) t.light.setType(Material.AIR, false);
                saveLights();
            }
        }
        t.light = null;
        if (t.d.isValid()) t.d.remove();
    }

    /** 화염병 깨짐: 바닥 불(반경 3, 5초), 깨지는 소리, 섬광, 큰 그을음 */
    private void shatter(Toss t, Vector hit) {
        World w = t.d.getWorld();
        Location at = ShotFx.ground(hit.toLocation(w), 2.5);
        Fire f = new Fire();
        f.at = at;
        f.owner = t.owner;
        f.until = game.now() + FIRE_TICKS;
        f.next = game.now();
        fires.add(f);
        Props.soundAround(at, null, Sound.ENTITY_SPLASH_POTION_BREAK, 1f, 1f, 0.9f, 24, -1);
        Props.soundAround(at, null, Sound.ITEM_FIRECHARGE_USE, 1f, 1f, 0.7f, 24, -1);
        game.shotFx.pop(at.clone().add(0, 0.3, 0));
        game.shotFx.decal(at, new Vector(0, 1, 0), ShotFx.DECAL_SCORCH, (float) (FIRE_R * 2), FIRE_TICKS + 60);
        lastLog = "화염병 " + fmt(at.toVector());
    }

    private void fireTick(long now) {
        for (Iterator<Fire> it = fires.iterator(); it.hasNext();) {
            Fire f = it.next();
            if (now >= f.until) {
                it.remove();
                continue;
            }
            if (now >= f.nextFx) {
                f.nextFx = now + 6;
                for (int i = 0; i < 2; i++) {
                    double r = Math.sqrt(rnd.nextDouble()) * (FIRE_R - 0.4), ang = rnd.nextDouble() * Math.PI * 2;
                    Location c = f.at.clone().add(Math.cos(ang) * r, 0.35, Math.sin(ang) * r);
                    game.shotFx.flame(c, 0.8f + rnd.nextFloat() * 0.5f, 20, i == 0 ? ShotFx.NORMAL : ShotFx.EXTRA);
                }
            }
            if (now < f.next) continue;
            f.next = now + 10;
            HitTargets t = game.targets();
            if (t == null) continue;
            double dmg = FIRE_DPS * 0.5;
            Guns.Arms a = f.owner == null ? null : game.guns.arms(f.owner);
            for (Object o : t.inRadius(f.at, FIRE_R + 1.5)) {
                Vector c = t.center(o);
                if (c == null) continue;
                double dx = c.getX() - f.at.getX(), dz = c.getZ() - f.at.getZ(), dy = c.getY() - f.at.getY();
                if (dx * dx + dz * dz > FIRE_R * FIRE_R || dy > 2.5 || dy < -1) continue;
                boolean died = t.damage(o, new Damage(dmg, DamageType.FIRE, f.owner, c.clone(), new Vector(0, 1, 0), "molotov", 0));
                if (died && a != null) {
                    a.kills++;
                    a.killAt = now;
                }
                if (f.owner != null) game.guns.hooks.onHit(f.owner, o, dmg, died, null);
            }
        }
    }

    private void bandageTick(long now) {
        for (Iterator<Map.Entry<UUID, Long>> it = bandageUntil.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Guns.Arms a = p == null ? null : game.guns.arms(p);
            if (p == null || a == null || !"bandage".equals(a.item) || a.itemCount <= 0 || !game.players.get(p).standing()
                || p.getInventory().getHeldItemSlot() != Guns.SLOT_ITEM) {
                if (p != null) {
                    ItemStack it2 = p.getInventory().getItem(Guns.SLOT_ITEM);
                    if (it2 != null) p.setCooldown(it2, 0);
                }
                it.remove();
                continue;
            }
            if (now < e.getValue()) continue;
            it.remove();
            a.itemCount--;
            if (a.itemCount <= 0) a.item = null;
            game.players.heal(p, BANDAGE_HP * game.augments.bandageHeal(p));
            // 낫기 갈고리(CURE, 2026-11-12 sp_engine): 종의 지연 피해 같은 것을 붕대가 지움
            game.players.cured(p, "bandage");
            Props.soundAround(p.getLocation(), null, Sound.BLOCK_WOOL_PLACE, 0.9f, 1f, 1.2f, 10, -1);
            lastLog = "붕대 " + p.getName() + " hp=" + Math.round(game.players.get(p).hp);
            sync(p, a);
        }
    }

    /** 칸 3 아이템을 지금 수에 맞춤(바뀔 때만 보냄). 칸 종류를 다 썼고 주머니 수류탄이 있으면 수류탄 칸으로 */
    private void sync(Player p, Guns.Arms a) {
        if (a.item != null && a.items() <= 0) a.item = null;
        if (a.item == null && a.grenades > 0) a.item = "grenade";
        String sig = a.item == null ? "" : a.item + ":" + a.items();
        ItemStack cur = p.getInventory().getItem(Guns.SLOT_ITEM);
        boolean ok = sig.isEmpty() ? cur == null || cur.getType().isAir()
            : cur != null && cur.getAmount() == a.items() && model(a.item).equals(cur.getData(DataComponentTypes.ITEM_MODEL));
        if (sig.equals(sent.get(p.getUniqueId())) && ok) return;
        sent.put(p.getUniqueId(), sig);
        p.getInventory().setItem(Guns.SLOT_ITEM, sig.isEmpty() ? null : stack(a.item, a.items()));
    }

    // ------------------------------------------------------------------ 비옷

    /** 비옷을 입었나(PState.raincoat > 0) */
    public boolean raincoat(Player p) {
        return game.players.get(p).raincoat > 0;
    }

    /** 비옷을 입힘(PState.raincoat 1, 이미 입었으면 false). 노란 옷은 {@link #coatTick} 이 맞춤 */
    public boolean giveRaincoat(Player p) {
        PState s = game.players.get(p);
        if (s.raincoat >= MAX.get("raincoat")) return false;
        s.raincoat++;
        coatTick(p, s);
        Props.soundAround(p.getLocation(), null, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.9f, 1f, 1f, 10, -1);
        return true;
    }

    /**
     * 붙기, 잠복, 덮기를 막을 때: 비옷이 있으면 찢어지며 true(그 일은 막힘). Players.coat 가 하나 줄이고(같은 틱 다음 것도 막음),
     * 찢어지는 소리와 옷 벗김은 coatTick 이 바로 함
     */
    public boolean tearRaincoat(Player p) {
        PState s = game.players.get(p);
        boolean torn = game.players.coat(p, s);
        if (torn) coatTick(p, s);
        return torn;
    }

    /**
     * 노란 비옷 옷 칸을 PState.raincoat 에 맞춤. 입었다가 없어졌으면 찢어지는 소리, 천 조각 연출. 입은 사람의 옷 칸이 비었으면(나갔다 들어와
     * 총 칸을 다시 만들며 인벤토리가 비워짐 등) 5 틱마다 다시 입힘
     */
    private void coatTick(Player p, PState s) {
        boolean want = s.raincoat > 0, has = raincoats.contains(p.getUniqueId());
        if (want && has && game.now() % 5 == 0) {
            ItemStack chest = p.getInventory().getItem(EquipmentSlot.CHEST);
            if (chest == null || chest.getType() != Material.LEATHER_CHESTPLATE) p.getInventory().setItem(EquipmentSlot.CHEST, coat());
            return;
        }
        if (want == has) return;
        if (want) {
            raincoats.add(p.getUniqueId());
            p.getInventory().setItem(EquipmentSlot.CHEST, coat());
            return;
        }
        raincoats.remove(p.getUniqueId());
        ItemStack chest = p.getInventory().getItem(EquipmentSlot.CHEST);
        if (chest != null && chest.getType() == Material.LEATHER_CHESTPLATE) p.getInventory().setItem(EquipmentSlot.CHEST, null);
        if (s.life == kr.gave.player.Life.LOBBY) return;
        Location at = p.getLocation().add(0, 1.1, 0);
        Props.soundAround(at, null, Sound.ENTITY_SHEEP_SHEAR, 1f, 1f, 0.8f, 16, -1);
        game.shotFx.spawn(at, "fx_quad", ShotFx.DUST, 3, ShotFx.scale(0.7f), Display.Billboard.CENTER, false, 14, ShotFx.NORMAL);
        lastLog = "비옷 찢어짐 " + p.getName();
    }

    // ------------------------------------------------------------------ 빛, 묻기

    /** 이 점이 켜진 조명탄 빛(반경 FLARE_R + extra) 안인가. 빛 피하는 종(지네 등)이 읽음 */
    public boolean inFlare(Vector at, double extra) {
        double r = FLARE_R + extra;
        for (Toss t : tosses) if (t.kind.equals("flare") && t.resting && t.pos.distanceSquared(at) <= r * r) return true;
        return false;
    }

    /** 이 점에서 r 블록 안에 켜진 조명탄이 있나(지네처럼 '조명탄 3블록 안에서 물러남' 같은 종) */
    public boolean nearFlare(Vector at, double r) {
        for (Toss t : tosses) if (t.kind.equals("flare") && t.resting && t.pos.distanceSquared(at) <= r * r) return true;
        return false;
    }

    /** 켜진(멈춘) 조명탄 자리들 */
    public List<Vector> flares() {
        List<Vector> out = new ArrayList<>();
        for (Toss t : tosses) if (t.kind.equals("flare") && t.resting) out.add(t.pos.clone());
        return out;
    }

    /** 불타는 화염병 바닥 자리들(가운데) */
    public List<Vector> fires() {
        List<Vector> out = new ArrayList<>();
        for (Fire f : fires) out.add(f.at.toVector());
        return out;
    }

    /** 이 점이 화염병 바닥 불(반경 3) 위인가(불 계열을 꺼리는 종) */
    public boolean inFire(Vector at) {
        for (Fire f : fires) {
            double dx = at.getX() - f.at.getX(), dz = at.getZ() - f.at.getZ();
            if (dx * dx + dz * dz <= FIRE_R * FIRE_R && Math.abs(at.getY() - f.at.getY()) < 2.5) return true;
        }
        return false;
    }

    public void forget(UUID id) {
        sent.remove(id);
        usedAt.remove(id);
        bandageUntil.remove(id);
        raincoats.remove(id);
    }

    public void clear() {
        for (Toss t : tosses) end(t);
        tosses.clear();
        for (Block b : lights.keySet()) if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
        lights.clear();
        saveLights();
        fires.clear();
        for (UUID id : raincoats) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.getInventory().setItem(EquipmentSlot.CHEST, null);
        }
        raincoats.clear();
        bandageUntil.clear();
        usedAt.clear();
        sent.clear();
    }

    private static String fmt(Vector v) {
        return String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", v.getX(), v.getY(), v.getZ());
    }

    /**
     * 시험 훅(Shop.test 가 넘김): 소모품 &lt;이름&gt; &lt;종류&gt; [수] = 칸에 넣기, 쓰기 &lt;이름&gt; = 우클릭처럼 씀, 소모품상태 &lt;이름&gt;, 비옷찢기 &lt;이름&gt;,
     * 던진것끄기 &lt;이름&gt;(가장 먼저 던진 것을 끝냄)
     */
    public String test(Player p, String[] a) {
        if (a.length < 2) return null;
        switch (a[1]) {
            case "소모품" -> {
                if (p == null || a.length < 4) return "소모품 <이름> grenade|flare|molotov|rattle|cleanse|bandage|raincoat [수]";
                int n = a.length > 4 ? (int) Guns.parse(a[4]) : 1;
                boolean ok = give(p, a[3], Math.max(1, n));
                return (ok ? "ok " : "못 줌 ") + status(p);
            }
            case "쓰기" -> {
                if (p == null) return "없음";
                if (a.length > 3 && a[3].equals("들고")) p.getInventory().setHeldItemSlot(Guns.SLOT_ITEM);
                usedAt.remove(p.getUniqueId());
                boolean ok = use(p);
                return (ok ? "썼음 " : "못 씀 ") + status(p) + " | " + lastLog;
            }
            case "소모품상태" -> {
                return p == null ? "없음" : status(p) + " | " + lastLog;
            }
            case "비옷찢기" -> {
                return p == null ? "없음" : "torn=" + tearRaincoat(p);
            }
            case "던진것끄기" -> {
                // 시험: 가장 먼저 던진 것을 끝냄(조명탄 빛 블록 나눠 쓰기 확인)
                if (tosses.isEmpty()) return "없음";
                Toss t = tosses.remove(0);
                end(t);
                return "끔 " + t.kind + " | " + (p == null ? "" : status(p));
            }
            default -> {
                return null;
            }
        }
    }

    /** 시험 글: 칸 종류와 수, 던진 것, 불, 붕대, 비옷, 체력 */
    public String status(Player p) {
        Guns.Arms a = game.guns.arms(p);
        PState s = game.players.get(p);
        int flares = 0, rattles = 0, lit = 0;
        for (Toss t : tosses) {
            if (t.kind.equals("flare")) flares++;
            if (t.kind.equals("rattle")) rattles++;
            if (t.light != null) lit++;
        }
        ItemStack slot = p.getInventory().getItem(Guns.SLOT_ITEM);
        int lightOk = 0;
        for (Block b : lights.keySet()) if (b.getType() == Material.LIGHT) lightOk++;
        ItemStack chest = p.getInventory().getItem(EquipmentSlot.CHEST);
        return "item=" + (a == null ? "-" : a.item + "x" + a.items()) + " grenades=" + (a == null ? 0 : a.grenades) + " chest="
            + (chest == null ? "-" : chest.getType()) + " lightOk=" + lightOk + " slot3="
            + (slot == null ? "-" : String.valueOf(slot.getData(DataComponentTypes.ITEM_MODEL)) + "x" + slot.getAmount()) + " tosses=" + tosses.size()
            + " flares=" + flares + " lit=" + lit + " lightBlocks=" + lights.size() + " rattles=" + rattles + " fires=" + fires.size() + " bandaging=" + bandageUntil.containsKey(p.getUniqueId())
            + " raincoat=" + raincoat(p) + " hp=" + Math.round(s.hp) + "/" + Math.round(s.maxHp);
    }
}
