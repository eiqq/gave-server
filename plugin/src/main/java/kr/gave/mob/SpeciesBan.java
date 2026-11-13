package kr.gave.mob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 판에 안 나오게 막은 종(설정 bannedSpecies, 2026-11-12 사용자 "시야 각도를 바꾸는 게이브는 등장하지 않게 해": 사람 시점에 회전을 보내는 종 40, 목록 근거는
 * build/noview/list.md). 보스(Role.BOSS)는 목록에 있어도 막지 않는다.
 *
 * <p>{@link #listed} 는 고르는 길(감독 묶음과 계획, 원정 막 덱, 라운드와 특수의 종 고르기)이 쓰고, {@link #blocks} 는 Mobs.spawn 의 마지막 문이 쓴다.
 * 마지막 문은 /gave 명령 안({@link #open}: /gave 소환, 시험 훅이 바로 놓는 것), 시험 훅 '새종'이 연 종({@link #free}), 이미 살아 있는 같은 종의 분열과 짝은
 * 들여보낸다. 빈 목록 [] 이면 모두 나온다. 설정은 플러그인을 켤 때(처음 물을 때) 읽는다.
 */
public final class SpeciesBan {

    /** 설정 칸이 없을 때(옛 config.yml) 쓰는 기본 목록 = config.yml 기본값. */
    public static final List<String> DEFAULT = List.of(
        // 직접 회전 22
        "G21", "G23", "G28", "G30", "G36", "G39", "G57", "G61", "G74", "G80", "G81", "G85", "G120", "G122", "G134", "G146", "G151", "G159", "G163", "G188", "G189",
        "G194");
        // 거인 걸음 화면 끄덕 18종은 끄덕(Guns.kick)을 빼서 되살림(2026-11-12)

    private static Set<Species> list;
    private static final Set<Species> freed = EnumSet.noneOf(Species.class);
    /** /gave 명령을 처리하는 중(깊이). 그동안 마지막 문이 열린다. */
    public static int open;

    private SpeciesBan() {
    }

    /** 설정에서 다시 읽는다(c 가 null 이면 기본 목록). 시험 훅 '새종'이 연 종도 지운다. */
    public static synchronized void load(FileConfiguration c) {
        List<String> names = c != null && c.isList("bannedSpecies") ? c.getStringList("bannedSpecies") : DEFAULT;
        EnumSet<Species> s = EnumSet.noneOf(Species.class);
        for (String n : names) {
            Species sp = n == null ? null : Species.of(n.trim());
            if (sp != null && !sp.is(Role.BOSS)) s.add(sp);
        }
        list = s;
        freed.clear();
    }

    private static Set<Species> set() {
        Set<Species> s = list;
        if (s != null) return s;
        FileConfiguration c = null;
        try {
            c = JavaPlugin.getProvidingPlugin(SpeciesBan.class).getConfig();
        } catch (RuntimeException e) {
            // 플러그인 밖(모의, 단위 시험): 기본 목록
        }
        load(c);
        return list;
    }

    /** 설정이 막은 종인가(보스 빼고). 고르는 길은 이것으로 뺀다. */
    public static boolean listed(Species sp) {
        return sp != null && set().contains(sp);
    }

    /** 마지막 문(Mobs.spawn): 막은 종이고 /gave 명령 안이 아니고 시험 훅이 열지 않았으면 true. 같은 종이 살아 있는지는 Mobs 가 본다. */
    public static boolean blocks(Species sp) {
        return listed(sp) && open <= 0 && !freed.contains(sp);
    }

    /** 시험 훅이 이 종을 이 서버가 켜져 있는 동안 연다('새종'처럼 계획에 넣어 나중 틱에 놓는 것). */
    public static void free(Species sp) {
        if (sp != null) freed.add(sp);
    }

    /** 막은 종(Species 차례). */
    public static List<Species> all() {
        List<Species> out = new ArrayList<>(set());
        Collections.sort(out);
        return out;
    }

    /** 시험 훅 글: 수와 번호, 시험 훅이 연 종. */
    public static String describe() {
        StringBuilder sb = new StringBuilder("막은 종 " + set().size() + ":");
        for (Species sp : all()) sb.append(' ').append(sp.name());
        if (!freed.isEmpty()) {
            sb.append(" | 시험으로 연 종:");
            for (Species sp : freed) sb.append(' ').append(sp.name());
        }
        return sb.toString();
    }
}
