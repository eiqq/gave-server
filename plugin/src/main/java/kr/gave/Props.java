package kr.gave;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 모드에서 가져온 소품(tools/props.json → jar 의 props.json): 아이템 모델 키, 장비 키, 소리 사건(좀비런 Props 를 옮김, 이름공간 gave).
 * 없는 키는 바닐라 그림, 바닐라 소리로 대신한다(소품 층이 아직 없을 때도 판이 돌게). props.json 이 아예 없어도 된다.
 * 소리 사건은 서버가 내보내는 팩 zip 의 assets/gave/sounds.json 으로 다시 채운다(PackServer 가 팩을 읽을 때마다 packSounds):
 * 클라가 실제로 가진 사건만 모드 소리로 울리고, 팩에 없는 사건은 경고 한 줄을 남기고 바닐라 대체 소리로 떨어진다
 * (jar 의 props.json 만 믿으면 낡은 팩을 내보낼 때 새 사건이 무음이 됨).
 * 멀리 들려야 하는 소리(총소리, 폭발)는 듣는 사람마다 따로 보낸다: 서버가 한꺼번에 뿌리면 16 블록(음량 1) 안에만 가고,
 * 클라는 sounds.json attenuation_distance 까지 거리에 따라 줄여 들려 준다.
 * <p>
 * 높이 계약(sound, soundAround, soundTo, soundOn 모두 같음): jitter 는 흔들림(1 둘레 무작위, 없으면 1), fallbackPitch 는 바닐라 대체 소리에
 * 맞춰 고른 높이. 팩에 그 gave 사건이 있으면 pitch = jitter 만 보낸다(들리는 높이는 sounds.json 의 pitch 가 정하고,
 * 클라가 sounds.json pitch x 보낸 pitch 를 0.5~2.0 으로 자름). 없으면 바닐라 대체 소리를 fallbackPitch x jitter 로 울린다.
 * 그래서 모드 소리 높이를 바꾸려면 sounds.json 만 고치고, Java 호출 값(fallbackPitch)은 바닐라 대체 소리용으로만 고른다.
 */
public final class Props {
    private Props() {
    }

    public static final String NS = "gave";
    private static final String SOUNDS_JSON = "assets/" + NS + "/sounds.json";
    public static final Set<String> ITEMS = new HashSet<>(), EQUIPMENT = new HashSet<>();
    private static final Random RND = new Random();
    private static Logger log;
    /** jar 의 props.json 이 적은 소리 사건(내보내는 팩이 낡았는지 견줄 기준) */
    private static Set<String> listed = Set.of();
    /** 지금 울릴 모드 소리 사건 → 변형 수(sounds.json 의 sounds 개수, 같은 차례). 팩을 읽을 때마다 통째로 바꿔 끼운다 */
    private static volatile Map<String, Integer> sounds = Map.of();
    /** 바닐라로 떨어진다고 이미 알린 사건(같은 경고를 되풀이하지 않게) */
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    public static void load(InputStream in, Logger logger) {
        log = logger;
        ITEMS.clear();
        EQUIPMENT.clear();
        Map<String, Integer> found = new HashMap<>();
        if (in == null) {
            if (logger != null) logger.info("props.json 없음: 바닐라 그림, 소리로 대신함");
        } else {
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject o = new Gson().fromJson(r, JsonObject.class);
                if (o != null && o.has("items")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("items").entrySet()) ITEMS.add(e.getKey());
                if (o != null && o.has("equipment")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("equipment").entrySet()) EQUIPMENT.add(e.getKey());
                if (o != null && o.has("sounds")) {
                    JsonElement s = o.get("sounds");
                    if (s.isJsonArray()) for (JsonElement e : s.getAsJsonArray()) found.put(strip(e.getAsString()), 1);
                    else if (s.isJsonObject()) for (Map.Entry<String, JsonElement> e : s.getAsJsonObject().entrySet()) {
                        JsonElement v = e.getValue();
                        boolean files = v.isJsonObject() && v.getAsJsonObject().has("files");
                        found.put(strip(e.getKey()), files ? Math.max(1, v.getAsJsonObject().getAsJsonArray("files").size()) : 1);
                    }
                }
                if (logger != null) logger.info("소품 모델 " + ITEMS.size() + ", 장비 " + EQUIPMENT.size() + ", 소리 " + found.size() + "(props.json)");
            } catch (Exception e) {
                if (logger != null) logger.warning("props.json 읽기 실패: " + e);
            }
        }
        listed = Set.copyOf(found.keySet());
        // 팩을 읽기 전까지만(PackServer.start 가 바로 packSounds 를 부름) props.json 값으로
        sounds = Map.copyOf(found);
        warned.clear();
    }

    /**
     * 내보내는 팩 zip(PackServer 가 읽어 내보내는 바이트 그대로)의 assets/gave/sounds.json 으로 소리 사건과 변형 수를 다시 채운다.
     * zip 이 null(팩 없음)이거나 sounds.json 이 없으면 모든 소리를 바닐라로. props.json 에 있는데 팩에 없는 사건은 경고 한 줄로 알린다.
     */
    public static void packSounds(byte[] zip) {
        Map<String, Integer> found = new HashMap<>();
        String why = null;
        if (zip == null) {
            why = "팩 파일 없음";
        } else {
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                    if (!e.getName().equals(SOUNDS_JSON)) continue;
                    JsonObject o = new Gson().fromJson(new String(z.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                    if (o != null) for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                        JsonElement v = en.getValue();
                        JsonElement list = v.isJsonObject() ? v.getAsJsonObject().get("sounds") : null;
                        int n = list != null && list.isJsonArray() ? list.getAsJsonArray().size() : 0;
                        if (n > 0) found.put(strip(en.getKey()), n);
                    }
                    break;
                }
                if (found.isEmpty()) why = SOUNDS_JSON + " 이 없거나 비었음";
            } catch (Exception ex) {
                found.clear();
                why = SOUNDS_JSON + " 읽기 실패: " + ex;
            }
        }
        sounds = Map.copyOf(found);
        Set<String> missing = new TreeSet<>(listed);
        missing.removeAll(found.keySet());
        warned.clear();
        warned.addAll(missing);
        if (log == null) return;
        int files = 0;
        for (int n : found.values()) files += n;
        log.info("팩 소리 사건 " + found.size() + "(변형 " + files + ")" + (why == null ? "" : ": " + why));
        if (found.isEmpty() && !missing.isEmpty()) log.warning("팩에 gave 소리가 없어 소리 사건 " + missing.size() + "개 모두 바닐라 대체 소리로 울림");
        else if (!missing.isEmpty())
            log.warning("팩 sounds.json 에 없는 소리 사건 " + missing.size() + "개는 바닐라 대체 소리로 울림(팩을 다시 묶을 것): " + String.join(", ", missing));
    }

    private static String strip(String s) {
        return s.startsWith(NS + ":") ? s.substring(NS.length() + 1) : s;
    }

    /** props.json 에 그 아이템 모델 키가 있는가(있으면 gave:&lt;key&gt; 모델을 씀) */
    public static boolean has(String key) {
        return ITEMS.contains(key);
    }

    /** 내보내는 팩에 그 소리 사건이 있는가 */
    public static boolean hasSound(String event) {
        return event != null && sounds.containsKey(event);
    }

    /** 지금 울릴 수 있는 모드 소리 사건 수 */
    public static int soundCount() {
        return sounds.size();
    }

    /** 모드 사건으로 울릴까. 팩에 없는 사건이면 처음 한 번만 경고(팩을 읽을 때 이미 알린 사건은 빼고)하고 false */
    private static boolean modSound(String event, Sound fallback) {
        if (event == null) return false;
        if (sounds.containsKey(event)) return true;
        if (warned.add(event) && log != null)
            log.warning("소리 사건 " + NS + ":" + event + " 이 팩에 없음: " + (fallback == null ? "울리지 않음" : "바닐라 대체 소리로"));
        return false;
    }

    /** 이름 붙은 아이템. 모드 모델이 있으면 그 모델(gave:&lt;key&gt;), 없으면 바닐라 재료 그림. 툴팁은 숨김 */
    public static ItemStack item(Material base, String key, Component name) {
        ItemStack it = ItemStack.of(base);
        if (key != null && has(key)) it.setData(DataComponentTypes.ITEM_MODEL, Key.key(NS, key));
        it.setData(DataComponentTypes.ITEM_NAME, name.decoration(TextDecoration.ITALIC, false));
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return it;
    }

    /** 모드 모델이 없으면 바닐라 아이템 모델(minecraft:&lt;vanillaModel&gt;)을 쓰되 재료는 base(행동이 없는 것) */
    public static ItemStack vanillaLook(Material base, String vanillaModel, String key, Component name) {
        ItemStack it = item(base, key, name);
        if (key == null || !has(key)) it.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft", vanillaModel));
        return it;
    }

    // ------------------------------------------------------------------ 소리(높이 계약은 머리 주석)

    /** 소리: 서버가 16 x 음량 블록 안에 뿌림. event 가 null 이면 바닐라 소리만 */
    public static void sound(Location at, String event, Sound fallback, float volume, float jitter, float fallbackPitch) {
        if (modSound(event, fallback)) at.getWorld().playSound(at, NS + ":" + event, SoundCategory.PLAYERS, volume, jitter);
        else if (fallback != null) at.getWorld().playSound(at, fallback, SoundCategory.PLAYERS, volume, fallbackPitch * jitter);
    }

    /** 한 사람에게만, 그 사람 자리에서 나는 소리 */
    public static void sound(Player to, String event, Sound fallback, float volume, float jitter, float fallbackPitch) {
        if (modSound(event, fallback)) to.playSound(to.getLocation(), NS + ":" + event, SoundCategory.PLAYERS, volume, jitter);
        else if (fallback != null) to.playSound(to.getLocation(), fallback, SoundCategory.PLAYERS, volume, fallbackPitch * jitter);
    }

    /**
     * 멀리 들리는 소리: range 블록 안 사람마다 따로 보낸다(모두 같은 변형). 모드 소리는 음량 그대로(클라가 attenuation_distance 로 줄임),
     * 바닐라 대체 소리는 들리는 거리가 16 x 음량이라 range / 16 으로 늘린다. variant >= 0 이면 그 변형(sounds.json sounds 차례)을 고른다.
     */
    public static void soundAround(Location at, String event, Sound fallback, float volume, float jitter, float fallbackPitch, double range,
        int variant) {
        boolean mod = modSound(event, fallback);
        if (!mod && fallback == null) return;
        long seed = seedFor(mod ? event : null, variant);
        double r2 = range * range;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > r2) continue;
            if (mod) p.playSound(at, NS + ":" + event, SoundCategory.PLAYERS, volume, jitter, seed);
            else p.playSound(at, fallback, SoundCategory.PLAYERS, Math.max(volume, (float) (range / 16.0)), fallbackPitch * jitter, seed);
        }
    }

    /** 한 사람에게만, 자리 at 에서 나는 소리 */
    public static void soundTo(Player to, Location at, String event, Sound fallback, float volume, float jitter, float fallbackPitch, int variant) {
        if (modSound(event, fallback)) to.playSound(at, NS + ":" + event, SoundCategory.PLAYERS, volume, jitter, seedFor(event, variant));
        else if (fallback != null) to.playSound(at, fallback, SoundCategory.PLAYERS, volume, fallbackPitch * jitter, RND.nextLong());
    }

    /** 한 사람에게만, 그 사람 자리에서 소리 갈래 cat 으로(모드 사건만, 팩에 없으면 안 울림: 보탠 먼 저음 층이 주변/환경 막대로 씀) */
    public static void sound(Player to, String event, float volume, float jitter, SoundCategory cat) {
        if (modSound(event, null)) to.playSound(to.getLocation(), NS + ":" + event, cat, volume, jitter, RND.nextLong());
    }

    /** 한 사람에게만, 자리 at 에서 소리 갈래 cat 으로(모드 사건만, 팩에 없으면 안 울림) */
    public static void soundTo(Player to, Location at, String event, float volume, float jitter, SoundCategory cat) {
        if (modSound(event, null)) to.playSound(at, NS + ":" + event, cat, volume, jitter, RND.nextLong());
    }

    /** 몸에 붙여 울린 소리 하나(끊을 때 씀): 모드 사건 이름(gave:...) 또는 바닐라 소리 */
    public record Voice(String event, Sound vanilla) {
        void stop(Player p) {
            if (event != null) p.stopSound(event, SoundCategory.PLAYERS);
            else if (vanilla != null) p.stopSound(vanilla, SoundCategory.PLAYERS);
        }
    }

    /**
     * 몸에 붙는 소리(World.playSound(Entity, ...), 바닐라 대체 소리도 같은 식): 그 몸을 추적하는 사람(몸이 사람이면 본인도)에게 가고
     * 클라에서 몸을 따라 움직인다. 울린 것을 돌려줌(stopAround 로 끊음), 아무것도 안 울렸으면 null
     */
    public static Voice soundOn(Entity e, String event, Sound fallback, float volume, float jitter, float fallbackPitch) {
        if (modSound(event, fallback)) {
            e.getWorld().playSound(e, NS + ":" + event, SoundCategory.PLAYERS, volume, jitter);
            return new Voice(NS + ":" + event, null);
        }
        if (fallback == null) return null;
        e.getWorld().playSound(e, fallback, SoundCategory.PLAYERS, volume, fallbackPitch * jitter);
        return new Voice(null, fallback);
    }

    /** 몸에 붙여 울린 소리를 at 둘레 range 블록 안 사람에게서 끊는다(그 사람에게서 같은 이름 소리는 모두 멈춤) */
    public static void stopAround(Location at, Voice v, double range) {
        if (v == null) return;
        double r2 = range * range;
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distanceSquared(at) <= r2) v.stop(p);
    }

    /**
     * 클라가 소리 변형을 고르는 난수(RandomSource.create(seed) 의 첫 nextInt(변형 수), java.util.Random 과 같은 식)가 variant 가 되는 씨앗.
     * 변형마다 소리 앞 빈 시간이 달라(산탄 넣는 소리 0.06 초 / 0.65 초) 박자를 맞춰야 할 때 쓴다. 변형 수는 내보내는 팩의 sounds.json 기준
     */
    public static long seedFor(String event, int variant) {
        int n = event == null ? 1 : sounds.getOrDefault(event, 1);
        long seed = RND.nextLong();
        if (variant < 0 || n <= 1 || variant >= n) return seed;
        for (int i = 0; i < 64 && new Random(seed).nextInt(n) != variant; i++) seed = RND.nextLong();
        return seed;
    }
}
