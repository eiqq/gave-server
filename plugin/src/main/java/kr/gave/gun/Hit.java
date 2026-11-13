package kr.gave.gun;

import org.bukkit.util.Vector;

/** 광선이 맞힌 것: 대상(가짜 몹 객체), 맞은 점, 쏜 곳에서 거리. */
public record Hit(Object target, Vector pos, double dist) {
}
