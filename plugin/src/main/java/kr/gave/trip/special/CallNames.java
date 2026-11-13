package kr.gave.trip.special;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.trip.Rounds;
import kr.gave.trip.Trip;
import kr.gave.world.Plaza;

/**
 * 특수 14 이름 부르는 밤(3막 재의 땅, specials_draft callnames): 짙은 안개와 밤, 벌판 여기저기 쓰러진 동료가 누워 있다. 거의 모두 엎드린 척(G35:
 * 이름표, 바닥 원, 남은 초까지 진짜 쓰러짐과 같은 꼴, 일으키려 하면 얼굴로 튀어 오른다)이고, 판 중에 진짜로 쓰러진 사람 곁에는 가짜가 하나 더 눕는다.
 * 안개 속 가짜 자리에서 사람마다 따로 속삭임(이름을 부르는 소리)이 들린다. 웅크려 들으면 가짜는 킥킥대고, 조명탄 빛에 얼굴이 드러난다(G35 규칙).
 *
 * <p>이름: G35 가 참가자 이름표를 단다(게임 안 글자로만). 이 클래스는 이름을 읽거나 기록에 남기지 않는다(사용자 지시: 접속자 이름은 게임 안에서만).
 * 끝: 감독 떼를 다 잡고 가짜를 모두 잡음.
 */
public final class CallNames extends Late {

    private final List<Gabe> fakes = new ArrayList<>();
    private final Set<UUID> downedSeen = new HashSet<>();
    private int total, killed, made, whispers;
    private long nextMore, nextWhisper;

    public CallNames(Game game, Trip trip, Rounds rounds) {
        super(game, trip, rounds);
    }

    @Override
    protected String icon() {
        return "ti_goal_hunt";
    }

    @Override
    protected boolean begin() {
        if (!Species.G35.implemented()) return false;
        fakes.clear();
        downedSeen.clear();
        total = killed = made = whispers = 0;
        for (Player p : game.players.inPlay()) if (game.players.get(p).life == Life.DOWNED) downedSeen.add(p.getUniqueId());
        int n = (int) Math.min(LateTune.CALL_MAX, Math.round(LateTune.CALL_BASE + LateTune.CALL_PER * trip.people()));
        double base = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < n; i++) {
            double a = base + Math.PI * 2 * i / n + (rnd.nextDouble() - 0.5) * 0.5;
            double r = LateTune.CALL_RMIN + rnd.nextDouble() * (LateTune.CALL_RMAX - LateTune.CALL_RMIN);
            lay(polar(r, a));
        }
        if (made == 0) return false;
        fog(LateTune.CALL_FOG);
        darken(0.6);
        nextMore = game.now() + LateTune.CALL_MORE_EVERY;
        nextWhisper = game.now() + 60;
        return true;
    }

    private void lay(Vector at) {
        Gabe g = rounds.spawnAt(Species.G35, at);
        if (g == null) return;
        fakes.add(g);
        made++;
        total++;
    }

    @Override
    protected void end() {
        fakes.clear();
        downedSeen.clear();
    }

    @Override
    public void plan(Director.Plan p) {
        scaleCounts(p, LateTune.CALL_UNITS);
    }

    @Override
    protected double mulFor(String k) {
        return k.equals("dmg") ? LateTune.CALL_DMG : 1;
    }

    @Override
    protected boolean extraDone() {
        return alive() == 0;
    }

    private int alive() {
        int n = 0;
        for (Gabe g : fakes) if (!g.dead && !g.removed) n++;
        return n;
    }

    @Override
    protected double frac() {
        return total <= 0 ? -1 : (double) killed / total;
    }

    @Override
    protected String text() {
        return killed + "/" + total;
    }

    @Override
    protected void step(long t) {
        long now = game.now();
        // 잡힌 가짜 셈(사람이 잡은 것만, 정리는 셈에서 뺌)
        for (Gabe g : fakes) {
            if (g.dead && g.hp <= 0 && !g.noReward && !counted.contains(g)) {
                counted.add(g);
                killed++;
            }
        }
        fakes.removeIf(g -> g.removed && !g.dead);
        // 더 눕는다
        if (now >= nextMore && t < LateTune.CALL_MORE_UNTIL * 20L) {
            nextMore = now + LateTune.CALL_MORE_EVERY;
            for (int i = 0; i < LateTune.CALL_MORE; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = LateTune.CALL_RMIN + rnd.nextDouble() * (LateTune.CALL_RMAX - LateTune.CALL_RMIN);
                lay(polar(r, a));
            }
        }
        // 진짜로 쓰러진 사람 곁에 가짜 하나
        for (Player p : game.players.inPlay()) {
            PState s = game.players.get(p);
            UUID id = p.getUniqueId();
            if (s.life != Life.DOWNED) {
                downedSeen.remove(id);
                continue;
            }
            if (!downedSeen.add(id)) continue;
            if (alive() >= LateTune.CALL_MAX * 1.5) continue;
            Location l = p.getLocation();
            double a = rnd.nextDouble() * Math.PI * 2, r = 2.5 + rnd.nextDouble() * (LateTune.CALL_NEAR - 2.5);
            lay(new Vector(l.getX() + Math.cos(a) * r, Plaza.FLOOR_Y, l.getZ() + Math.sin(a) * r));
        }
        // 속삭임: 사람마다 따로, 살아 있는 가짜 자리에서(그 사람만 듣는다)
        if (now >= nextWhisper) {
            nextWhisper = now + LateTune.CALL_WHISPER_MIN + rnd.nextInt(Math.max(1, LateTune.CALL_WHISPER_MAX - LateTune.CALL_WHISPER_MIN));
            List<Gabe> live = new ArrayList<>();
            for (Gabe g : fakes) if (!g.dead && !g.removed) live.add(g);
            if (!live.isEmpty()) {
                for (Player p : game.players.standing()) {
                    if (rnd.nextDouble() < 0.4) continue;
                    Gabe g = live.get(rnd.nextInt(live.size()));
                    Location at = new Location(game.world, g.pos.getX(), Plaza.FLOOR_Y + 0.5, g.pos.getZ());
                    Props.soundTo(p, at, "director.murmur", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.9f, 0.7f + rnd.nextFloat() * 0.3f, 0.5f, -1);
                    whispers++;
                }
            }
        }
    }

    private final Set<Gabe> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    @Override
    protected String statusMore() {
        return String.format(Locale.ROOT, "fakes=%d/%d killed=%d whispers=%d", alive(), total, killed, whispers);
    }

    @Override
    protected String testMore(Player p, String sub, String[] a) {
        if (sub.equals("가짜") && p != null) {
            for (Gabe g : fakes) {
                if (g.dead || g.removed) continue;
                p.teleport(new Location(game.world, g.pos.getX() + 2.2, Plaza.FLOOR_Y + 0.1, g.pos.getZ(), 90, 30));
                return "가짜 곁으로 " + statusMore();
            }
            return "가짜 없음";
        }
        if (sub.equals("잡기")) {
            for (Gabe g : fakes) {
                if (g.dead || g.removed) continue;
                g.noReward = false;
                g.hp = 0;
                g.die(new kr.gave.mob.Damage(1e6, kr.gave.mob.DamageType.OTHER, null, g.pos.clone(), null, "test", 0));
                return "가짜 하나 잡음 " + statusMore();
            }
            return "가짜 없음";
        }
        return null;
    }
}
