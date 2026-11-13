package kr.gave.gun;

import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.mob.Damage;

/** 총, 수류탄이 맞힐 대상(가짜 몹). kr.gave.mob.Mobs 가 구현한다. */
public interface HitTargets {

    /** origin 에서 dir(단위)로 maxDist 안 가장 가까운 대상. exclude 는 관통으로 이미 맞힌 것. */
    Hit cast(Player shooter, Vector origin, Vector dir, double maxDist, Set<Object> exclude);

    /** 피해를 준다. 이번에 죽었으면 true. */
    boolean damage(Object target, Damage d);

    /** 반경 안 대상(수류탄). */
    List<Object> inRadius(Location c, double r);

    /** 대상 중심(시야 검사). */
    Vector center(Object target);
}
