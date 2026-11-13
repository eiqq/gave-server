package kr.gave.dex;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import kr.gave.mob.Species;

/**
 * 도감 기록: 사람(UUID)마다 만난 종. 판을 넘어 남는다(plugins/Gave/dex.yml). 메인 스레드에서만 읽고 쓰며, 파일 쓰기는 글로 굳힌 뒤
 * 비동기로 한다(늦게 굳힌 글이 먼저 쓴 글을 덮지 않게 차례 번호). 꼴:
 *
 * <pre>
 * &lt;uuid&gt;:
 *   name: Ripley
 *   seen: [G01, G02, B1]
 * </pre>
 */
final class DexStore {

    private final Path file;
    private final Logger log;
    private final Map<UUID, EnumSet<Species>> seen = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private boolean dirty;
    private long seq, written;
    /** 비동기 쓰기가 실패함(다음 저장 때 다시) */
    private volatile boolean retry;
    long savedAt;

    DexStore(File file, Logger log) {
        this.file = file.toPath();
        this.log = log;
    }

    void load() {
        seen.clear();
        names.clear();
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        int bad = 0;
        for (String k : y.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(k);
            } catch (IllegalArgumentException e) {
                bad++;
                continue;
            }
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            EnumSet<Species> set = EnumSet.noneOf(Species.class);
            for (String n : s.getStringList("seen")) {
                Species sp = Species.of(n);
                if (sp != null) set.add(sp);
                else bad++;
            }
            seen.put(id, set);
            String name = s.getString("name");
            if (name != null) names.put(id, name);
        }
        log.info("[도감] 기록 " + seen.size() + "명 읽음" + (bad > 0 ? " (모르는 칸 " + bad + ")" : ""));
    }

    boolean has(UUID id, Species sp) {
        EnumSet<Species> s = seen.get(id);
        return s != null && s.contains(sp);
    }

    Set<Species> of(UUID id) {
        EnumSet<Species> s = seen.get(id);
        return s == null ? Collections.emptySet() : Collections.unmodifiableSet(s);
    }

    /** 처음 만난 종이면 true. */
    boolean add(UUID id, String name, Species sp) {
        EnumSet<Species> s = seen.computeIfAbsent(id, k -> EnumSet.noneOf(Species.class));
        if (name != null && !name.equals(names.get(id))) {
            names.put(id, name);
            dirty = true;
        }
        if (!s.add(sp)) return false;
        dirty = true;
        return true;
    }

    void clear(UUID id) {
        if (seen.remove(id) != null) dirty = true;
    }

    int people() {
        return seen.size();
    }

    boolean dirty() {
        return dirty || retry;
    }

    /** 바뀐 것이 있으면 글로 굳혀 비동기로 쓴다(runAsync 가 다른 스레드에서 돌림). */
    void saveAsync(java.util.function.Consumer<Runnable> runAsync, long now) {
        if (retry) {
            retry = false;
            dirty = true;
        }
        if (!dirty) return;
        dirty = false;
        savedAt = now;
        String text = snapshot();
        long n = ++seq;
        runAsync.accept(() -> write(n, text));
    }

    /** 지금 바로(끌 때). */
    void saveNow() {
        if (!dirty) return;
        dirty = false;
        write(++seq, snapshot());
    }

    private String snapshot() {
        YamlConfiguration y = new YamlConfiguration();
        List<UUID> ids = new ArrayList<>(seen.keySet());
        ids.sort(null);
        for (UUID id : ids) {
            String k = id.toString();
            String name = names.get(id);
            if (name != null) y.set(k + ".name", name);
            List<String> list = new ArrayList<>();
            for (Species sp : seen.get(id)) list.add(sp.name());
            y.set(k + ".seen", list);
        }
        return y.saveToString();
    }

    private synchronized void write(long n, String text) {
        if (n <= written) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            written = n;
        } catch (IOException e) {
            retry = true;
            log.log(Level.WARNING, "[도감] 기록 저장 실패 " + file, e);
        }
    }
}
