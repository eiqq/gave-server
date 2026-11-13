package kr.gave;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;

/**
 * 리소스팩을 HTTP 로 내보내고 접속하는 사람에게 보낸다(좀비런 PackServer 를 옮긴 것). 팩은 둘까지(tools/build_pack.py):
 * 모습 팩 Gave-pack.zip(모델, 텍스처, 셰이더, 글꼴, 언어, 아이템 정의, music.json 같은 데이터)과, 같은 폴더에 있으면 소리 팩
 * Gave-sound.zip(모든 이름공간의 sounds.json 과 소리 파일). 둘 다 있으면 한 요청에 모습 다음 소리로(아이디도 따로) 보내고,
 * 소리 팩이 없으면 모습 팩 하나만 보낸다(소리까지 든 옛 꼴의 팩 하나도 그대로 돈다). 모습 팩이 없으면 아무것도 보내지 않는다.
 * <p>
 * 시스템 속성: gave.packDir(두 팩이 있는 폴더, 기본: 플러그인 폴더에서 세 단계 위의 build), gave.packHost(기본: 플레이어가 접속할 때 친 주소),
 * gave.packPort(기본 8290, 이 PC 주소 /pack.zip 과 /sound.zip), gave.packRepo(깃허브 저장소, 있으면 팩마다 릴리스
 * pack-&lt;그 팩 sha1 앞 12자리&gt;/&lt;파일 이름&gt; 을 받아 확인하고 맞는 팩만 그 주소로, 나머지 팩은 이 PC 주소로 보냄).
 * 접속 전 구성 단계에서 보내고 모든 팩의 끝 상태가 올 때까지 기다린다. 하나라도 못 붙으면(거절 빼고) 들어온 뒤 한 번 다시 보낸다
 * (깃허브에서 못 받은 팩은 그때 이 PC 주소로).
 * 팩을 읽을 때마다(켤 때, /gave 팩) 내보내는 바로 그 zip 들의 assets/gave/sounds.json 을 합쳐 울릴 소리 사건을 다시 채운다(Props.packSounds).
 * 시험 도구가 로그의 "팩 상태 &lt;이름&gt; &lt;상태&gt;" 를 찾으므로 그 글은 바꾸지 않는다. 팩이 둘이면 그 줄은 모두 끝났을 때 한 번
 * (모두 붙었으면 SUCCESSFULLY_LOADED, 아니면 실패한 상태) 남기고, 팩마다 줄은 "팩 상태 &lt;이름&gt; 모습|소리 &lt;상태&gt;" 로 따로 남긴다.
 */
public final class PackServer {
    private static final String SOUNDS_JSON = "assets/" + Props.NS + "/sounds.json";

    /** 읽은 팩 한 벌(바이트, sha1, 확인된 깃허브 주소). 다시 읽거나 확인이 끝나면 통째로 바꿔 끼운다. */
    private record Data(byte[] bytes, String sha1, URI remote) {
    }

    /** 팩 하나: 파일, 이 PC 주소의 길, 아이디, 지금 읽은 것. */
    private static final class Pack {
        final String label, fileName, route;
        final UUID id;
        final Path file;
        volatile Data data;

        Pack(Path dir, String label, String fileName, String route, String idName) {
            this.label = label;
            this.fileName = fileName;
            this.route = route;
            this.id = UUID.nameUUIDFromBytes(idName.getBytes(StandardCharsets.UTF_8));
            this.file = dir.resolve(fileName);
        }
    }

    /** 이번에 보내는 팩 하나와 그 주소(깃허브 릴리스 주소면 remote). */
    private record Item(Pack pack, URI uri, String sha1, boolean remote) {
    }

    private final JavaPlugin plugin;
    private final Pack look, sound;
    private final int port;
    private HttpServer http;
    private final String repo = System.getProperty("gave.packRepo");
    /** 구성 단계에서 팩을 다 못 붙이고 들어오는 사람 → 다시 보낼 때 이 PC 주소로 보낼 팩 아이디(깃허브에서 못 받은 팩). */
    private final Map<UUID, Set<UUID>> retry = new ConcurrentHashMap<>();

    public PackServer(JavaPlugin plugin) {
        this.plugin = plugin;
        String d = System.getProperty("gave.packDir");
        Path data = plugin.getDataFolder().toPath().toAbsolutePath();
        Path dir = d != null ? Path.of(d) : data.getParent().getParent().getParent().resolve("build");
        // 모습 팩 아이디는 팩이 하나이던 때와 같다(gave:main)
        this.look = new Pack(dir, "모습", "Gave-pack.zip", "/pack.zip", "gave:main");
        this.sound = new Pack(dir, "소리", "Gave-sound.zip", "/sound.zip", "gave:sound");
        this.port = Integer.getInteger("gave.packPort", 8290);
    }

    public void start() {
        reload();
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            // 한 사람이 두 팩을 함께 받으므로 두 사람이 같이 받을 만큼
            http.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "Gave-pack-http");
                t.setDaemon(true);
                return t;
            }));
            for (Pack k : List.of(look, sound)) http.createContext(k.route, ex -> serve(ex, k));
            http.start();
            plugin.getLogger().info("팩 서빙: 포트 " + port + " (" + look.file + (sending().size() > 1 ? ", " + sound.file : "") + ")");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "팩 서빙 포트 " + port + " 를 열지 못했다", e);
        }
        // 구성 단계에서 못 붙은 팩은 들어온 뒤 한 번 다시
        plugin.getServer().getPluginManager().registerEvent(PlayerJoinEvent.class, new Listener() {
        }, EventPriority.MONITOR, (l, e) -> {
            if (e instanceof PlayerJoinEvent j) retryAfterJoin(j.getPlayer());
        }, plugin);
    }

    private static void serve(HttpExchange ex, Pack k) throws IOException {
        Data d = k.data;
        if (d == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        ex.sendResponseHeaders(200, d.bytes().length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(d.bytes());
        }
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    public boolean reload() {
        return reload(null);
    }

    /**
     * 지금 내보내는 모습 팩 zip 바이트(클라가 받는 바로 그것, music.json 과 gabe_looks.json 이 든 팩). 읽기만 하고 고치지 말 것.
     * 팩이 없으면 null. 다시 읽으면(/gave 팩) 새 배열. 소리 사건 목록은 soundEvents().
     */
    public byte[] bytes() {
        Data d = look.data;
        return d == null ? null : d.bytes();
    }

    /** 지금 모습 팩 sha1(16진, 없으면 null). */
    public String sha1() {
        Data d = look.data;
        return d == null ? null : d.sha1();
    }

    /** 읽는 모습 팩 파일 경로(gave.packDir/Gave-pack.zip, 없으면 서버 폴더 옆 build/). 소리 팩은 같은 폴더의 Gave-sound.zip. */
    public Path file() {
        return look.file;
    }

    /**
     * 지금 보내는 팩들의 assets/gave/sounds.json 을 합친 소리 사건 이름(gave: 뺀 것, 파일 차례). 팩이 없거나 못 읽으면 빈 집합.
     * 팩을 나눈 뒤로 sounds.json 은 소리 팩(Gave-sound.zip)에 있어 Gave-pack.zip 만 열면 보이지 않는다. 부를 때마다 zip 을 읽으므로 자주 부르지 말 것.
     */
    public Set<String> soundEvents() {
        Set<String> out = new LinkedHashSet<>();
        byte[] j = mergedSounds(false);
        if (j == null) return out;
        try {
            for (String k : JsonParser.parseString(new String(j, StandardCharsets.UTF_8)).getAsJsonObject().keySet())
                out.add(k.startsWith(Props.NS + ":") ? k.substring(Props.NS.length() + 1) : k);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(SOUNDS_JSON + " 을 읽지 못했다: " + e);
        }
        return out;
    }

    /**
     * 두 팩 파일을 다시 읽는다. gave.packRepo(깃허브 저장소 이름)가 있으면 팩마다 그 저장소 릴리스 pack-&lt;그 팩 sha1 앞 12자리&gt;에
     * 같은 파일이 올라가 있는지 받아서 확인하고, 맞으면 그 주소를 보낸다. 없거나 다르면 그 팩만 이 PC 포트로 보낸다.
     * 어느 쪽이든 클라가 받는 것은 sha1 이 이 PC 파일과 같은 zip 이라, 소리 사건은 readLocal 이 읽은 바이트로 채운 것이 맞다.
     * after 는 확인이 끝난 뒤 주 스레드에서 부른다.
     */
    public boolean reload(Runnable after) {
        for (Pack k : List.of(look, sound)) {
            Data d = k.data;
            if (d != null && d.remote() != null) k.data = new Data(d.bytes(), d.sha1(), null);
        }
        boolean ok = readLocal();
        kr.gave.mob.Look.load(bytes(), plugin.getLogger()); // 게이브 모델 틴트 역할표(모습 팩 assets/gave/gabe_looks.json, 팩 담당), 모델 수와 경고를 남김
        List<Pack> check = sending();
        if (!ok || repo == null || repo.isBlank() || check.isEmpty()) {
            if (after != null) after.run();
            return ok;
        }
        boolean two = check.size() > 1;
        Thread.ofVirtual().name("Gave-pack-check").start(() -> {
            for (Pack k : check) {
                Data d = k.data;
                if (d == null) continue;
                String tag = "pack-" + d.sha1().substring(0, 12);
                URI uri = URI.create("https://github.com/" + repo + "/releases/download/" + tag + "/" + k.fileName);
                String got = remoteSha1(uri);
                if (d.sha1().equals(got)) {
                    if (setRemote(k, d, uri)) plugin.getLogger().info((two ? k.label + " " : "") + "팩은 깃허브에서 받게 한다: " + uri);
                } else {
                    String what = tag + (two ? "(" + k.fileName + ")" : "");
                    plugin.getLogger().warning((got == null ? "깃허브 " + repo + " 에 " + what + " 가 아직 없다."
                            : "깃허브 " + what + " 파일이 이 팩과 다르다(" + got + ").")
                            + " 그동안 " + (two ? k.label + " 팩은 " : "") + "이 PC 포트 " + port + " 로 보낸다.");
                }
            }
            if (after != null && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, after);
        });
        return true;
    }

    /** 확인하는 동안 팩을 다시 읽지 않았으면(같은 d) 깃허브 주소를 붙인다. */
    private static synchronized boolean setRemote(Pack k, Data d, URI uri) {
        if (k.data != d) return false;
        k.data = new Data(d.bytes(), d.sha1(), uri);
        return true;
    }

    private boolean readLocal() {
        if (!Files.isRegularFile(look.file)) {
            plugin.getLogger().warning("팩 파일이 없다: " + look.file + " (tools/build_pack.py)");
            look.data = null;
            sound.data = null;
            Props.packSounds(null);
            return false;
        }
        Data ld;
        try {
            byte[] b = Files.readAllBytes(look.file);
            ld = new Data(b, sha1(b), null);
            look.data = ld;
            plugin.getLogger().info("팩 " + b.length + " 바이트, sha1 " + ld.sha1());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "팩을 읽지 못했다", e);
            // 읽기 전 팩을 그대로 내보내면 소리 사건도 그대로, 내보낼 팩이 없으면 모두 바닐라
            if (look.data == null) Props.packSounds(null);
            return false;
        }
        if (!Files.isRegularFile(sound.file)) {
            sound.data = null;
        } else {
            try {
                byte[] b = Files.readAllBytes(sound.file);
                Data sd = new Data(b, sha1(b), null);
                sound.data = sd;
                plugin.getLogger().info("소리 팩 " + b.length + " 바이트, sha1 " + sd.sha1());
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "소리 팩을 읽지 못했다(읽기 전 소리 팩이 있으면 그것을 그대로 보냄)", e);
            }
        }
        // 클라가 받을 바로 이 바이트들의 sounds.json 으로: jar 의 props.json 만 보면 낡은 팩에서 새 사건이 무음이 됨
        Props.packSounds(sound.data == null ? ld.bytes() : zipOf(SOUNDS_JSON, mergedSounds(true)));
        return true;
    }

    /** 지금 보낼 팩: 모습 팩이 있으면 모습, 소리 팩도 있으면 그 다음 소리. */
    private List<Pack> sending() {
        if (look.data == null) return List.of();
        return sound.data == null ? List.of(look) : List.of(look, sound);
    }

    /**
     * 보내는 팩들의 assets/gave/sounds.json 을 클라처럼 합친 것(모습 팩이 아래, 소리 팩이 위: 같은 사건은 sounds 를 이어 붙이고 위 팩이
     * replace 면 위 팩 것만). 한 팩만 가졌으면 그 바이트 그대로, 아무도 없으면 null.
     */
    private byte[] mergedSounds(boolean warn) {
        List<byte[]> js = new ArrayList<>(2);
        for (Pack k : sending()) {
            Data d = k.data;
            byte[] j = d == null ? null : entry(d.bytes(), SOUNDS_JSON);
            if (j != null) js.add(j);
        }
        if (js.isEmpty()) return null;
        if (js.size() == 1) return js.get(0);
        if (warn) plugin.getLogger().warning("모습 팩과 소리 팩 둘 다 " + SOUNDS_JSON + " 을 가졌다(겹침, tools/build_pack.py 로 다시 묶을 것): 클라처럼 합쳐 읽음");
        try {
            return merge(js);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("두 팩의 " + SOUNDS_JSON + " 을 합치지 못했다(소리 팩 것만 씀): " + e);
            return js.get(js.size() - 1);
        }
    }

    private static byte[] merge(List<byte[]> js) {
        JsonObject out = new JsonObject();
        for (byte[] j : js) {
            JsonObject o = JsonParser.parseString(new String(j, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                JsonElement old = out.get(e.getKey()), v = e.getValue();
                if (old != null && old.isJsonObject() && v.isJsonObject() && !replaces(v.getAsJsonObject())) {
                    JsonObject m = old.getAsJsonObject().deepCopy(), add = v.getAsJsonObject();
                    JsonArray list = m.has("sounds") && m.get("sounds").isJsonArray() ? m.getAsJsonArray("sounds") : new JsonArray();
                    if (add.has("sounds") && add.get("sounds").isJsonArray()) list.addAll(add.getAsJsonArray("sounds"));
                    m.add("sounds", list);
                    if (add.has("subtitle")) m.add("subtitle", add.get("subtitle"));
                    out.add(e.getKey(), m);
                } else {
                    out.add(e.getKey(), v);
                }
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean replaces(JsonObject o) {
        JsonElement r = o.get("replace");
        return r != null && r.isJsonPrimitive() && r.getAsBoolean();
    }

    /** zip 바이트에서 그 이름 항목의 바이트(없거나 못 읽으면 null). */
    private static byte[] entry(byte[] zip, String name) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().equals(name)) return z.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** 항목 하나만 든 zip 바이트(data 가 null 이면 빈 zip). Props.packSounds 에 합친 sounds.json 을 넘기려고. */
    private static byte[] zipOf(String name, byte[] data) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            if (data != null) {
                z.putNextEntry(new ZipEntry(name));
                z.write(data);
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bo.toByteArray();
    }

    private static String sha1(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 주소에서 파일을 받아 sha1 을 낸다. 못 받으면 null. */
    private String remoteSha1(URI uri) {
        try (HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 ? sha1(r.body()) : null;
        } catch (IOException e) {
            plugin.getLogger().warning("깃허브 팩 확인 실패: " + e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 들어와 있는 사람에게 보낸다(/gave 팩). 끝 상태는 기록에 "(들어온 뒤)" 로 남는다. */
    public void send(Player p) {
        send(p, Set.of(), "들어온 뒤");
    }

    private void send(Player p, Set<UUID> local, String when) {
        List<Item> items = items(p.getVirtualHost(), local);
        if (items.isEmpty()) return;
        p.sendResourcePacks(request(items).callback(watch(p.getName(), when, items, new ConcurrentHashMap<>(), new CompletableFuture<>())).build());
    }

    /** 구성 단계에서 못 붙은 팩이 있던 사람이 들어오면 2초 뒤(월드를 받는 동안은 피해) 한 번 다시 보낸다. */
    private void retryAfterJoin(Player p) {
        Set<UUID> local = retry.remove(p.getUniqueId());
        if (local == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) send(p, local, "들어온 뒤 다시 보냄");
        }, 40);
    }

    /** 구성 단계(비동기)에서 보내고 모든 팩의 끝 상태가 올 때까지(최대 3분) 붙잡는다. 하나라도 못 붙으면 들어온 뒤 한 번 다시 보낸다. */
    public void sendBeforeJoin(PlayerConfigurationConnection c) {
        List<Item> items = items(c.getVirtualHost(), Set.of());
        if (items.isEmpty()) return;
        String name = c.getProfile().getName();
        UUID who = c.getProfile().getId();
        if (who != null) retry.remove(who);
        Map<UUID, ResourcePackStatus> got = new ConcurrentHashMap<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        c.getAudience().sendResourcePacks(request(items).callback(watch(name, "접속 전", items, got, done)).build());
        long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (!done.isDone() && c.isConnected() && System.nanoTime() < until) {
            try {
                done.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // 1초마다 연결을 다시 본다
            }
        }
        if (who == null || !c.isConnected()) return;
        // 못 붙은 팩(끝 상태가 안 온 것 포함, 거절은 빼고)이 있으면 들어온 뒤 한 번 다시. 깃허브 주소에서 못 받은 팩은 그때 이 PC 주소로
        Set<UUID> local = new HashSet<>();
        List<String> bad = new ArrayList<>();
        for (Item it : items) {
            ResourcePackStatus st = got.get(it.pack().id);
            if (st == ResourcePackStatus.SUCCESSFULLY_LOADED || st == ResourcePackStatus.DECLINED) continue;
            bad.add((items.size() > 1 ? it.pack().label + " " : "") + (st == null ? "끝 상태 없음" : st.name()));
            if (it.remote() && (st == null || st == ResourcePackStatus.FAILED_DOWNLOAD || st == ResourcePackStatus.INVALID_URL)) local.add(it.pack().id);
        }
        if (bad.isEmpty()) return;
        retry.put(who, local);
        plugin.getLogger().warning("팩을 다 붙이지 못하고 들어옴: " + name + " (" + String.join(", ", bad) + "). 들어온 뒤 한 번 다시 보낸다"
                + (local.isEmpty() ? "" : "(깃허브에서 못 받은 팩은 이 PC 포트 " + port + " 로)"));
    }

    /** 지금 보낼 팩과 주소. local 에 든 팩은 깃허브 주소가 있어도 이 PC 주소로. 모습 팩이 없으면 빈 목록. */
    private List<Item> items(InetSocketAddress virtualHost, Set<UUID> local) {
        List<Item> out = new ArrayList<>(2);
        for (Pack k : sending()) {
            Data d = k.data;
            if (d == null) {
                if (k == look) return List.of();
                continue;
            }
            URI uri = local.contains(k.id) ? null : d.remote();
            boolean remote = uri != null;
            if (uri == null) uri = URI.create("http://" + host(virtualHost) + ":" + port + k.route + "?v=" + d.sha1().substring(0, 12));
            out.add(new Item(k, uri, d.sha1(), remote));
        }
        return out;
    }

    private static String host(InetSocketAddress virtualHost) {
        String host = System.getProperty("gave.packHost");
        if (host == null) host = virtualHost == null || virtualHost.getHostString().isBlank() ? "127.0.0.1" : virtualHost.getHostString();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return host;
    }

    private static ResourcePackRequest.Builder request(List<Item> items) {
        List<ResourcePackInfo> infos = new ArrayList<>(items.size());
        for (Item it : items) infos.add(ResourcePackInfo.resourcePackInfo(it.pack().id, it.uri(), it.sha1()));
        return ResourcePackRequest.resourcePackRequest()
                .packs(infos)
                .required(true)
                .replace(true)
                .prompt(Component.text("게이브 서버 팩: 총, 게이브, HUD 그림과 소리"));
    }

    /**
     * 팩마다 끝 상태를 모은다. 팩이 둘이면 팩마다 "팩 상태 &lt;이름&gt; 모습|소리 &lt;상태&gt; (때)" 를 남기고, 모두 끝나면 도구가 읽는
     * "팩 상태 &lt;이름&gt; &lt;상태&gt; (때[, 팩 2개])" 한 줄을 남기고 done 을 채운다(팩이 하나면 그 한 줄만, 예전과 같은 글).
     */
    private ResourcePackCallback watch(String name, String when, List<Item> items, Map<UUID, ResourcePackStatus> got, CompletableFuture<Void> done) {
        boolean two = items.size() > 1;
        return (pid, st, aud) -> {
            if (st.intermediate()) return;
            Item it = null;
            for (Item i : items) if (i.pack().id.equals(pid)) it = i;
            if (it == null) return;
            got.put(pid, st);
            if (two) plugin.getLogger().info("팩 상태 " + name + " " + it.pack().label + " " + st.name() + " (" + when + ")");
            if (got.size() < items.size()) return;
            if (done.complete(null))
                plugin.getLogger().info("팩 상태 " + name + " " + overall(items, got).name() + " (" + when + (two ? ", 팩 " + items.size() + "개" : "") + ")");
        };
    }

    /** 모두 붙었으면 SUCCESSFULLY_LOADED, 아니면 앞 팩부터 처음 실패한 상태(한 팩을 못 받아 함께 버려진 DISCARDED 보다 그 원인을 먼저). */
    private static ResourcePackStatus overall(List<Item> items, Map<UUID, ResourcePackStatus> got) {
        ResourcePackStatus all = ResourcePackStatus.SUCCESSFULLY_LOADED;
        for (Item i : items) {
            ResourcePackStatus s = got.get(i.pack().id);
            if (s == null || s == ResourcePackStatus.SUCCESSFULLY_LOADED) continue;
            if (s != ResourcePackStatus.DISCARDED) return s;
            all = s;
        }
        return all;
    }
}
