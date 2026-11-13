package kr.gave.gun;

import java.util.Collection;
import java.util.List;

import org.bukkit.entity.Player;

/**
 * 총이 맞힐 때 판 쪽에 묻고 알리는 것(증강의 대상 조건 피해, 처치 회복, 아군 사격). kr.gave.game.Augments 가 구현해 Guns.hooks 에 단다.
 * 기본은 아무 일 없음(Guns 만 따로 시험할 때).
 */
public interface GunHooks {

    GunHooks NONE = new GunHooks() {
    };

    /** 이 사람이 이 대상에 주는 피해 배율(띠, 역할, 체력 조건 증강). 치명, 거리 감쇠 전에 곱함 */
    default double hitMul(Player shooter, Object target, Guns.Spec spec) {
        return 1;
    }

    /** 한 번 맞힘(died = 이번에 죽음). gun 은 쏜 총(굴린 총 특성 흡혈 등), 수류탄이나 화염 지속 피해면 null 일 수 있음 */
    default void onHit(Player shooter, Object target, double amount, boolean died, Gun gun) {
    }

    /** 아군 탄이 서 있거나 쓰러진 동료에게 닿음(피해 없음, 한 발에 한 사람 한 번). Players.friendlyHit 로 넘긴다 */
    default void friendly(Player shooter, Player victim) {
    }

    /** 쓰러진 동료(아군 사격 광선이 좌석 자리 낮은 몸 상자로 검사한다) */
    default Collection<Player> downedMates() {
        return List.of();
    }

    /** target 이 victim 에게 붙은 것(붙잡은 게이브, 머리에 붙은 게이브)이면 true: 탄이 그 동료 몸에 막히지 않고 target 을 맞힌다 */
    default boolean attached(Player victim, Object target) {
        return false;
    }

    /** 시험 총정보 끝에 붙일 글(사람 스탯) */
    default String info(Player p) {
        return "";
    }

    // ------------------------------------------------------------------ 2026-11-12 trip_aug: 무기 종류에 얽힌 증강 고리(기본은 아무 일 없음)

    /** 이 사람이 종류 k 를 쏠 때의 배율(base = host.mods(p) 에 그 종류만의 증강을 더한 것). 바뀌면 {@link #modsStamp} 도 바뀌어야 한다 */
    default GunMods kindMods(Player p, Guns.Kind k, GunMods base) {
        return base;
    }

    /** kindMods 가 바뀌었는지 보는 도장(값이 달라지면 Guns 가 총 값을 다시 만든다) */
    default long modsStamp(Player p) {
        return 0;
    }

    /** 점사 한 번의 발 수(base = Mode.count) */
    default int burstCount(Player p, Guns.Kind k, int base) {
        return base;
    }

    /** 모아 쏘는 시간 배율(레일건 과충전) */
    default double chargeMul(Player p, Guns.Kind k) {
        return 1;
    }

    /** 방아쇠를 놓아도 미니건 총열이 안 식나 */
    default boolean keepSpin(Player p) {
        return false;
    }

    /** 움직일 때, 뛸 때 더해지는 퍼짐의 배율 */
    default double moveSpread(Player p, Guns.Kind k) {
        return 1;
    }

    /** 탄창도 예비탄도 없을 때 한 발을 대신 낼 수 있나(냈으면 true: 그 한 발이 장전된다) */
    default boolean dryFire(Player p, Guns.Spec s) {
        return false;
    }

    /** 폭발 반경 배율(유탄, 로켓) */
    default double blastMul(Player p, Guns.Kind k) {
        return 1;
    }

    /** 불붙음 길이 배율 */
    default double burnMul(Player p) {
        return 1;
    }

    /** 번개가 더 번지는 수 */
    default int chainExtra(Player p) {
        return 0;
    }

    /**
     * 날아가는 탄 규칙: null 이면 보통. {쪼개지는 수, 쪼개진 것 피해 몫, 지뢰로 기다리는 틱(0 = 지뢰 아님), 한 사람 지뢰 한도, 지뢰가 터지는 거리}
     */
    default double[] shellRule(Player p, Guns.Kind k) {
        return null;
    }

    /** 불길, 냉기 한 번의 끝 자리(불 벽) */
    default void streamEnd(Player p, Guns.Kind k, org.bukkit.Location end) {
    }
}
