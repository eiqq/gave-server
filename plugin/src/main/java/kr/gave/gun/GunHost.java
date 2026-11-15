package kr.gave.gun;

import java.util.Collection;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** 총 쪽이 판에 묻는 것. kr.gave.game.Game 이 구현한다. */
public interface GunHost {

    JavaPlugin plugin();

    World world();

    /** 판 시계(늘 흐름). */
    long now();

    /** 셰이더 시간(world.getGameTime()). */
    long worldTime();

    /** 쏠 수 있는 사람들(서 있는 사람). */
    Collection<Player> shooters();

    /** 웨이브나 쉬는 시간이면 true(대기실, 끝난 판은 false). */
    boolean running();

    /** 붙잡힘, 쓰러짐, 묶임, 돕는 중, 아군 탄 끊김으로 쏘기와 재장전 시작을 못 하면 true(이미 하던 재장전은 끝까지 간다). */
    boolean blocked(Player p);

    HitTargets targets();

    /** 이 사람의 총 배율. */
    GunMods mods(Player p);

    /** 우클릭을 다른 것이 먼저 쓰면 true(상점 열기 등, 그 누름은 쏘지 않음). */
    boolean tryUse(Player p);

    /** 한 발 쏜 뒤(소리 듣는 종, 기록). */
    void onShot(Player p, String gunKey);

    /** 좌클릭(돕기). */
    void onHelp(Player p);

    /** 방금 맞힌 발이 치명타(HUD 적중 표시를 금색으로 하는 데 쓴다). 기본은 아무 일 없음. */
    default void crit(Player p) {
    }
}
