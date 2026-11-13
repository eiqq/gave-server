package kr.gave.mob.sp;

import java.util.EnumMap;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * 보고 배우는 게이브(G51)의 배움 하나(판 전체에 하나만): 그 웨이브에 본 피해 종류 집계와 배운 저항.
 *
 * <p>엔진의 피해 배율 훅({@code Mobs.scale}, 모든 게이브가 받는 모든 피해에 걸림)을 쓴다:
 * <ul>
 * <li>집계: 지켜보는 G51 이 살아 있는 동안 게이브가 받은 피해를 종류별로 더한다(총, 산탄, 저격, 수류탄, 폭발탄, 화염탄, 화염병).</li>
 * <li>저항: 배운 종류의 피해에 0.7 을 곱한다(정확히 30%, 수류탄과 불에도 걸림).</li>
 * </ul>
 * 배우는 종류는 도감대로 산탄, 저격, 폭발, 불 넷(보통 탄은 배우지 않음). 저항은 늘 하나, 새로 배우면 바뀌고 G51 이 사람에게 잡히면 지워진다.
 */
final class G51Lesson {

    /** 배운 종류에 곱하는 배율(30% 저항). */
    static final double RESIST = 0.7;
    /** 이마 눈 상한. */
    static final int MAX_EYES = 4;
    /** 배우려면 그 종류 피해가 이만큼은 들어와야 한다. */
    static final double MIN_SEEN = 20;
    static final DamageType[] LEARNABLE = {DamageType.PELLET, DamageType.SNIPER, DamageType.EXPLOSION, DamageType.FIRE};

    private static G51Lesson inst;

    final Game game;
    private int round = -1, wave = -1;
    /** 지금 배운 저항(없으면 null), 이번 판에 이어서 배운 횟수(이마 눈). */
    DamageType learned;
    int lessons;
    private int learnedWave = -1;
    private final EnumMap<DamageType, Double> seen = new EnumMap<>(DamageType.class);

    /** 지켜보는 G51 이 마지막으로 산 틱. */
    private long watchTick = -10;

    private G51Lesson(Game game) {
        this.game = game;
        game.mobs.scale(G51Lesson.class, this::scale);
    }

    /** 이 판의 배움(처음 부를 때 엔진 피해 배율 훅을 건다). */
    static G51Lesson of(Game game) {
        if (inst == null || inst.game != game) inst = new G51Lesson(game);
        inst.sync();
        return inst;
    }

    /** 판이 바뀌면 모두 지우고, 웨이브가 바뀌면 집계만 지운다. */
    private void sync() {
        if (game.round != round) {
            round = game.round;
            wave = -1;
            learned = null;
            lessons = 0;
            learnedWave = -1;
        }
        if (game.wave != wave) {
            wave = game.wave;
            seen.clear();
        }
    }

    // ------------------------------------------------------------------ G51 이 부르는 것

    /** 지켜보는 G51 이 매 틱 부른다: 이 틱부터 2틱 동안 들어온 피해를 센다. */
    void observe() {
        sync();
        watchTick = game.now();
    }

    /** 엔진 피해 배율 훅: 지켜보는 동안 집계하고, 배운 종류면 저항. */
    private double scale(Gabe target, Damage d) {
        // 보스는 배움 저항도 집계도 안 함(보스 B4 가 따로 배움, docs/모듈/보스.md 11.8)
        if (target != null && target.sp.is(kr.gave.mob.Role.BOSS)) return 1;
        sync();
        DamageType t = d.type();
        if (d.amount() > 0 && game.now() - watchTick <= 2 && learnable(t)) seen.merge(t, d.amount(), Double::sum);
        return t != null && t == learned ? RESIST : 1;
    }

    static boolean learnable(DamageType t) {
        for (DamageType x : LEARNABLE) if (x == t) return true;
        return false;
    }

    /** 지금까지 본 그 종류 피해. */
    double seen(DamageType t) {
        return seen.getOrDefault(t, 0.0);
    }

    /** 살아남은 G51 이 웨이브 끝에 부른다: 가장 많이 본 종류를 배운다(웨이브마다 한 번). 배웠으면 그 종류, 없으면 null. */
    DamageType learn() {
        sync();
        if (learnedWave == game.wave) return null;
        DamageType best = null;
        double most = MIN_SEEN;
        for (DamageType t : LEARNABLE) {
            double v = seen(t);
            if (v > most) {
                most = v;
                best = t;
            }
        }
        learnedWave = game.wave;
        seen.clear();
        if (best == null) return null;
        learned = best;
        lessons = Math.min(MAX_EYES, lessons + 1);
        game.hud.notice("notice_g51_" + best.name().toLowerCase(java.util.Locale.ROOT), icon(best), 100);
        game.plugin.getLogger().info("[G51] 배움 " + best + " 횟수 " + lessons + " 웨이브 " + game.wave);
        return best;
    }

    /** G51 이 사람에게 잡힘: 배운 저항이 지워진다. */
    void forget() {
        sync();
        if (learned == null) return;
        game.plugin.getLogger().info("[G51] 잊음 " + learned);
        learned = null;
        lessons = 0;
        game.hud.notice("notice_g51_forgot", Species.G51, 80);
    }

    static String icon(DamageType t) {
        return switch (t) {
            case PELLET -> "gun_m_shotgun";
            case SNIPER -> "gun_m_sniper";
            case EXPLOSION -> "it_grenade";
            case FIRE -> "it_molotov";
            default -> "g51";
        };
    }

    String status() {
        return "learned=" + (learned == null ? "-" : learned.name()) + " lessons=" + lessons + " seen=" + seen;
    }
}
