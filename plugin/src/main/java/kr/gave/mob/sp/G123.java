package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Corpse;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G123 벌레 구름 게이브(떼, 하늘). 수백의 벌레 얼굴이 폭 8블록 구름째 날아와 한 사람을 감싸 5초 뜯고 다음 사람으로 옮긴다.
 *
 * <p>도감: 구름 속(반경 3.5) 사람은 세 명까지 초당 9 피해, 화면에 벌레 덮개(g123_bugs), 옷이 뜯겨 4초 동안 받는 피해 25% 더(방어 20% 감소).
 * 총알은 구멍만 뚫는다(BULLET, SNIPER 20%, PELLET 70%, 폭발과 불 150%). 화염병 불이나 조명탄 곁에 들어가면 흩어져 5초 물러난다.
 * 지나간 자리(4블록)의 사체는 뜯겨 사라진다. 체력이 줄수록 겹친 덩이(부품)를 하나씩 끈다.
 *
 * <p>몸: 닻(점묘 덩이 + 가장자리 날개 조각, 계속 돎) + 부품 덩이 둘(상태 b, c, 따로 돎). 바닥 재화는 엔진에 없어 사체만 뜯는다.
 *
 * <p>팩 계약(tools/gabe/sp/g123.py): 모델 gave:gabe/g123 상태 "", "b", "c", 덮개 g123_bugs, 소리 buzz(48), chew(16), 글귀 cause_g123.
 */
public final class G123 extends Gabe {

    static final double SPEED = 0.2, R = 3.5, DPS = 6, ARMOR_K = 1.25;
    static final int MAX_HELD = 3, HOLD = 100, HIT_CD = 20, FLEE = 100, STRIP = 80, SPIN = 5;
    static final double FIRE_R = 4.5;

    /** 사람마다 옷이 뜯긴 끝 틱(판 시계). */
    private static final Map<UUID, Long> STRIPPED = new HashMap<>();

    private Body.Part partB, partC;
    private Player prey;
    private int holdAt = -1, hitAt, fleeUntil = -1, buzzAt, chewAt;
    private final List<Player> held = new ArrayList<>(MAX_HELD);
    private Player last;
    private final Vector fleeFrom = new Vector();
    private float spinB, spinC;

    public G123(Game game, Species sp) {
        super(game, sp);
        radius = R;
        baseSpeed = SPEED;
        noPush = true;
        noBite = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g123", 9f, 5f);
    }

    @Override
    protected void spawned() {
        partB = body.attach("gabe/g123");
        if (partB != null) partB.state("b");
        partC = body.attach("gabe/g123");
        if (partC != null) partC.state("c");
        yaw = game.random.nextFloat() * 360f;
    }

    @Override
    public void tick() {
        spin();
        thin();
        if (age % 10 == 0) eatCorpses();
        if (age >= buzzAt && game.mobs.nearestPlayer(pos, 48) != null) {
            buzzAt = age + 50 + game.random.nextInt(20);
            sound("buzz", Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 0.7f, 48);
        }
        if (fleeUntil > age) {
            letGo();
            Vector away = pos.clone().subtract(fleeFrom).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            away.normalize().multiply(speed() * 1.5);
            vel.copy(away.setY(0.08));
            return;
        }
        Vector fire = fireNear();
        if (fire != null) {
            fleeUntil = age + FLEE;
            fleeFrom.copy(fire);
            return;
        }
        if (holdAt >= 0) {
            envelop();
            return;
        }
        Player p = choose();
        if (p != target) mark(p);
        if (p == null) {
            follow(speed());
            return;
        }
        Vector c = chest(p).add(new Vector(0, 0.4, 0));
        double d = c.distance(pos);
        seek(c, d > 12 ? speed() * 1.6 : speed());
        if (d < 2.5) {
            prey = p;
            holdAt = age;
            hitAt = age;
        }
    }

    /** 다음 사람: 방금 뜯은 사람은 다른 사람이 있으면 건너뜀. */
    private Player choose() {
        Player best = null, fallback = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            double d = chest(p).distanceSquared(pos);
            if (p == last) {
                fallback = p;
                continue;
            }
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best != null ? best : fallback;
    }

    /** 한 사람을 감싸 5초 뜯음(곁의 사람 세 명까지). */
    private void envelop() {
        Player p = prey;
        if (!standing(p) || age - holdAt >= HOLD) {
            last = p;
            letGo();
            return;
        }
        seek(chest(p).add(new Vector(0, 0.4, 0)), 0.6);
        if (age < hitAt) return;
        hitAt = age + cd(HIT_CD);
        held.clear();
        for (Player q : game.mobs.standing()) {
            if (held.size() >= MAX_HELD) break;
            if (chest(q).distance(pos) <= R) held.add(q);
        }
        for (Player q : held) {
            hit(q, DPS, "cause_g123");
            game.players.cover(q, "g123_bugs", HIT_CD + 10);
            strip(q);
        }
        if (age >= chewAt) {
            chewAt = age + 30;
            sound("chew", Sound.ENTITY_SILVERFISH_AMBIENT, 1.3f, 16);
        }
    }

    private void letGo() {
        for (Player q : held) {
            if (q.isOnline() && "g123_bugs".equals(game.players.get(q).cover)) game.players.uncover(q);
        }
        held.clear();
        prey = null;
        holdAt = -1;
    }

    /** 옷이 뜯김: 4초 동안 받는 피해 x1.25(PState.armor). 끝나면 그동안 다른 것이 armor 를 안 바꿨을 때만 되돌린다. */
    private void strip(Player p) {
        long now = now();
        UUID id = p.getUniqueId();
        Long until = STRIPPED.get(id);
        if (until != null && until > now) return;
        PState s = game.players.get(p);
        double before = s.armor, after = before * ARMOR_K;
        s.armor = after;
        STRIPPED.put(id, now + STRIP);
        Bukkit.getScheduler().runTaskLater(game.plugin, () -> {
            STRIPPED.remove(id);
            if (Math.abs(s.armor - after) < 1e-9) s.armor = before;
        }, STRIP);
    }

    /** 곁에 화염병 불이나 조명탄이 있으면 그 자리(없으면 null). */
    private Vector fireNear() {
        if (game.shop.inFire(pos) || game.shop.nearFlare(pos, FIRE_R)) return pos.clone().add(new Vector(0.5, 0, 0.5));
        Vector below = new Vector(pos.getX(), floor() + 0.5, pos.getZ());
        if (game.shop.inFire(below)) return below;
        return null;
    }

    /** 지나간 자리의 사체를 뜯음. */
    private void eatCorpses() {
        for (Corpse c : game.mobs.corpses()) {
            if (c.gone()) continue;
            double dx = c.pos.getX() - pos.getX(), dz = c.pos.getZ() - pos.getZ();
            if (dx * dx + dz * dz < 16 && pos.getY() - c.pos.getY() < 6) {
                game.mobs.eat(c);
                return;
            }
        }
    }

    /** 닻은 계속 돌고(날개 조각이 가장자리를 돎), 부품 덩이는 서로 반대로 천천히. */
    private void spin() {
        yaw = (yaw + SPIN) % 360f;
        if (age % 20 == 0) {
            spinB += 70f;
            spinC -= 95f;
            if (partB != null) partB.pose(new Matrix4f().rotateY((float) Math.toRadians(spinB)).rotateX(0.25f), 20);
            if (partC != null) partC.pose(new Matrix4f().rotateY((float) Math.toRadians(spinC)).rotateZ(-0.2f), 20);
        }
    }

    /** 체력이 줄면 덩이를 하나씩 끔. */
    private void thin() {
        double f = hpFrac();
        if (partC != null && f < 0.66) {
            partC.remove();
            partC = null;
        }
        if (partB != null && f < 0.33) {
            partB.remove();
            partB = null;
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        double k = switch (d.type()) {
            case BULLET, SNIPER -> 0.2;
            case PELLET -> 0.7;
            case FIRE, EXPLOSION -> 1.5;
            default -> 1.0;
        };
        if (d.type() == DamageType.FIRE && fleeUntil < age) {
            fleeUntil = age + FLEE;
            fleeFrom.copy(d.at() != null ? d.at() : pos.clone().add(new Vector(0.5, 0, 0.5)));
        }
        return super.damage(k == 1.0 ? d : d.times(k));
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        letGo();
        Location at = loc();
        for (int i = 0; i < 3; i++) {
            Location c = at.clone().add(game.random.nextGaussian() * 1.2, game.random.nextGaussian() * 0.6, game.random.nextGaussian() * 1.2);
            var fx = game.shotFx.spawn(c, "fx_quad", kr.gave.gun.ShotFx.SMOKE, 1, kr.gave.gun.ShotFx.scale(2.5f),
                org.bukkit.entity.Display.Billboard.CENTER, false, 40, kr.gave.gun.ShotFx.EXTRA);
            game.shotFx.grow(fx, kr.gave.gun.ShotFx.scale(6f), 30);
        }
        super.die(d);
    }

    @Override
    public void remove() {
        letGo();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + (holdAt >= 0 ? " hold=" + (prey == null ? "-" : prey.getName()) + " held=" + held.size() : "")
            + (fleeUntil > age ? " flee" : "") + " parts=" + ((partB != null ? 1 : 0) + (partC != null ? 1 : 0));
    }
}
