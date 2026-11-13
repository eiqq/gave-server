package kr.gave.mob;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 게이브가 받는 피해 한 번.
 *
 * @param amount  피해량(증강, 거리 감쇠를 이미 곱한 값)
 * @param type    종류
 * @param by      쏜 사람(없으면 null)
 * @param at      맞은 점(세계 좌표, 없으면 null)
 * @param dir     탄 방향(단위 벡터, 없으면 null)
 * @param how     무엇으로(총 키 pistol, smg ... 또는 grenade, bite)
 * @param aimTicks 쏘기 전에 조준선이 이 개체에 머문 틱(흉내 종은 10틱 넘는 조준 사격에만 정체를 드러냄)
 */
public record Damage(double amount, DamageType type, Player by, Vector at, Vector dir, String how, int aimTicks) {

    public static Damage of(double amount, DamageType type) {
        return new Damage(amount, type, null, null, null, type.name().toLowerCase(java.util.Locale.ROOT), 0);
    }

    public Damage times(double k) {
        return new Damage(amount * k, type, by, at, dir, how, aimTicks);
    }

    /** 흉내 종이 반응하는 '겨눈 사격'(조준선 0.5초 이상). */
    public boolean aimed() {
        return aimTicks >= 10;
    }
}
