package kr.gave.mob.sp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G15 박수 게이브(지원). 무리 뒤 10~15블록에서 멈춰 막대기를 앞으로 접어 박자를 친다.
 *
 * <p>도감: 반경 12블록 같은 계열(같은 높이 띠) 게이브 빠르기 30% 증가, 박수 게이브가 셋 이상 모이면 박자가 빨라져(20틱에 두 번) 더 빠르게
 * (45%). 누가 쓰러지면 맵의 박수 게이브 전부가 그쪽으로 고개를 돌려 일제히 박수(4초). 박자는 월드 시각 전역 박자: 모든 개체가 위상 0 이라
 * 맵 전체가 한 박자로 치고, 짝 소리는 모델의 맞닿는 자세와 같은 틱(월드 시각 % 20 == 0, 빠르면 % 10)에 난다. 직접 공격 없음.
 *
 * <p>팩 계약(tools/gabe/sp/g15.py): 모델 gave:gabe/g15, 상태 "" 박자, "fast" 두 배 박자. 소리 clap(짝, 반경 48), applause(갈채, 64).
 * 글귀 notice_g15_applause(cause_g15 는 피해가 없어 쓰이지 않음).
 */
public final class G15 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.12, HOVER = 2.0;
    /** 사람과 두는 거리(무리 뒤). */
    static final double KEEP = 12.5, KEEP_MIN = 10, KEEP_MAX = 15;
    /** 버프 반경, 배율(셋 이상이면 더). */
    static final double BUFF_R = 12, BUFF = 1.3, BUFF_FAST = 1.45;
    static final int FAST_COUNT = 3;
    /** 박자 주기(틱, 모델 주기와 같음), 갈채 틱. */
    static final int BEAT = 20, APPLAUSE = 80;

    private static long lastNotice = -1;
    private final Set<UUID> seenDown = new HashSet<>(), downNow = new HashSet<>();
    private Player cheer;
    private int cheerUntil = -1, buffed, clappers = 1;
    /** 무리 가운데(5틱마다 다시 잼, 없으면 null). */
    private Vector group;

    public G15(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g15", 1.9f, 1.4f);
        // 전역 박자: 위상 0(모든 박수 게이브가 같은 틱에 맞닿음)
        body.look.phase = 0;
    }

    @Override
    protected void spawned() {
        for (Player p : game.players.downed()) seenDown.add(p.getUniqueId());
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        watchDowns();
        // 둘레 세기(박수 게이브 수, 무리 가운데)는 5틱마다, 개체마다 어긋나게
        if (every(5) || age <= 1) {
            clappers = countClappers();
            group = groupCenter();
        }
        int near = clappers;
        boolean cheering = cheer != null && cheer.isOnline() && age < cheerUntil;
        boolean fast = near >= FAST_COUNT || cheering;
        state(fast ? "fast" : "");
        if (cheering) {
            hold();
            face(chest(cheer));
        } else {
            cheer = null;
            position();
        }
        buff(near >= FAST_COUNT ? BUFF_FAST : BUFF);
        long wt = game.worldTime();
        int beat = fast ? BEAT / 2 : BEAT;
        if (wt % beat == 0) sound("clap", Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.6f, 48);
    }

    /** 무리 뒤 10~15블록: 가장 가까운 사람에서 무리 가운데 쪽으로 12.5블록 자리. */
    private void position() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) {
            hold();
            return;
        }
        Location pl = p.getLocation();
        Vector from = new Vector(pl.getX(), 0, pl.getZ());
        Vector dir = (group != null ? group.clone() : pos.clone()).setY(0).subtract(from);
        if (dir.lengthSquared() < 1e-4) dir = pos.clone().setY(0).subtract(from);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
        dir.normalize();
        Vector want = from.clone().add(dir.multiply(KEEP));
        double d = flat(p);
        double off = Math.hypot(want.getX() - pos.getX(), want.getZ() - pos.getZ());
        if (d > KEEP_MAX + 8) follow(speed());
        else if (d < KEEP_MIN || off > 1.5) {
            Vector v = want.subtract(pos).setY(0);
            double l = v.length();
            vel.copy(l < 1e-4 ? new Vector() : v.multiply(Math.min(speed(), l) / l));
            if (wallAhead(0.7)) follow(speed() * (d < KEEP_MIN ? -1 : 1));
        } else hold();
        face(chest(p));
    }

    /** 같은 띠 다른 게이브들(박수 게이브 빼고)의 가운데. 없으면 null. */
    private Vector groupCenter() {
        List<Gabe> ks = game.mobs.near(pos, 30, g -> g != this && !g.dead && !(g instanceof G15) && !g.disguised);
        if (ks.isEmpty()) return null;
        Vector c = new Vector();
        for (Gabe g : ks) c.add(g.pos);
        return c.multiply(1.0 / ks.size());
    }

    private int countClappers() {
        return game.mobs.near(pos, BUFF_R, g -> g instanceof G15).size();
    }

    /** 반경 안 같은 계열(같은 높이 띠) 게이브에 이번 틱 빠르기 배율(지원 종은 매 틱 다시 건다). */
    private void buff(double mul) {
        buffed = 0;
        for (Gabe g : kin(BUFF_R)) {
            if (g instanceof G15) continue;
            g.speedBuff = Math.max(g.speedBuff, mul);
            buffed++;
        }
    }

    /** 누가 새로 쓰러지면 그쪽으로 고개를 돌려 갈채(맵의 박수 게이브 전부가 같은 틱에, 알림은 한 번). */
    private void watchDowns() {
        Set<UUID> now = downNow;
        now.clear();
        for (Player p : game.players.downed()) {
            UUID id = p.getUniqueId();
            now.add(id);
            if (seenDown.add(id)) {
                cheer = p;
                cheerUntil = age + APPLAUSE;
                sound("applause", Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.3f, 64);
                if (lastNotice != game.now()) {
                    lastNotice = game.now();
                    game.hud.notice("notice_g15_applause", Species.G15, 60);
                }
            }
        }
        seenDown.retainAll(now);
        if (cheer != null && !now.contains(cheer.getUniqueId())) cheerUntil = Math.min(cheerUntil, age + 10);
    }

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    @Override
    public String toString() {
        return super.toString() + " buffed=" + buffed + " clappers=" + clappers + (cheer != null && age < cheerUntil ? " cheer" : "");
    }
}
