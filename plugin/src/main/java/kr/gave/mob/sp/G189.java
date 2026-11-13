package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;

/**
 * G189 꼭 맞는 구멍 게이브(4막 32라운드, 함정 + 방해). 서 있는 사람 넷에 하나꼴로 임자를 정해(구멍 없는 사람 먼저) 임자에게서 6블록 떨어진 땅에 열리고,
 * 임자를 천천히 따라 기어 온다(0.05). 임자는 줄곧 구멍 쪽으로 살짝 끌리고(PUSH 0.02), 4초마다 1초 세게 끌린다(부름 0.12, 화면이 구멍 쪽으로 조금 돎,
 * 0.5초 전 입술이 오므라듦). 임자가 0.8블록 안에 들면 빠짐: 못 걷고 못 쏘며 0.5초마다 6, 20초 뒤 삼켜짐(갇힘: 구멍을 잡으면 풀려남).
 * 동료는 돕기로 끌어낼 수 있다(붙잡힘 떼기). 임자의 탄은 빨려 들어간다(피해 0): 남이 쏴 줘야 한다. 임자가 아닌 사람이 밟으면 5 와 0.5초 묶임.
 * 낮은 웅웅 소리는 임자에게만 크게(VOLUME), 임자 조준점 옆에 이 종 표시와 구멍 쪽 금색 화살촉.
 *
 * <p>팩 계약(tools/gabe/sp/g189.py): gabe/g189(상태 "", "call", "bite", 부위 lean). 소리 call(24), gulp(16), snap(16).
 * 글귀 cause_g189, cause_g189_bite.
 *
 * <p>도감과 다른 점: 감독이 하나만 깔면 첫 구멍이 넷에 하나꼴이 되게 나머지를 spawnLike 로 놓는다(8까지). 끌어내기는 돕기 한 번(1.5초 채널 없음).
 * 임자가 쓰러지거나 죽으면 다른 사람을 임자로 고른다(빠진 사람이 삼켜진 뒤에는 닫힌 채 그 자리에 남는다).
 */
public final class G189 extends Gabe implements Detachable {

    static final double HOVER = 0.02, CRAWL = 0.05, PULL = 0.02, CALL_PULL = 0.12, OPEN_R = 6, FALL_R = 0.8, STEP_R = 0.75, SINK = 1.35;
    static final double SUNK_DMG = 6, STEP_DMG = 5, LEAN_R = 4;
    static final int CALL_EVERY = 80, CALL_T = 20, TELL = 10, SWALLOW = 400, STEP_ROOT = 10, STEP_CD = 20, REGRAB = 60, MAX_HOLES = 8;

    enum Phase { FOLLOW, CALL, SUNK, SHUT }

    private static boolean filling;

    private Phase phase = Phase.FOLLOW;
    private int phaseAt, nextCall, regrabAt, falls, calls;
    private Player owner;
    private final Map<UUID, Integer> stepAt = new HashMap<>();
    private long hintAt;

    public G189(Game game, Species sp) {
        super(game, sp);
        radius = 0.7;
        hover = HOVER;
        baseSpeed = CRAWL;
        ghost = true;
        noPush = true;
        noBite = true;
        noMutate = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g189", 1.8f, 0.8f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        boolean first = !filling && others() == 0;
        pickOwner();
        placeNear();
        nextCall = age + cd(CALL_EVERY);
        if (!first) return;
        int want = Math.min(MAX_HOLES, Math.max(1, (int) Math.ceil(game.mobs.standing().size() / 4.0)));
        filling = true;
        try {
            for (int i = 1; i < want && others() + 1 < want; i++) {
                if (game.director.spawnLike(sp, pos.clone()) == null) break;
            }
        } finally {
            filling = false;
        }
    }

    private int others() {
        int n = 0;
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && g instanceof G189) n++;
        return n;
    }

    /** 구멍 없는 서 있는 사람 가운데 가장 가까운 사람(모두 있으면 가장 가까운 사람). */
    private void pickOwner() {
        Player best = null, any = null;
        double bd = Double.MAX_VALUE, ad = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            double d = p.getLocation().toVector().distanceSquared(pos);
            if (d < ad) {
                ad = d;
                any = p;
            }
            if (owned(p.getUniqueId())) continue;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        owner = best != null ? best : any;
        mark(owner);
    }

    private boolean owned(UUID id) {
        for (Gabe g : game.mobs.all()) {
            if (g == this || g.dead || !(g instanceof G189 o)) continue;
            if (o.owner != null && o.owner.getUniqueId().equals(id) && o.phase != Phase.SHUT) return true;
        }
        return false;
    }

    /** 임자에게서 6블록 떨어진 걷는 땅으로 옮김. */
    private void placeNear() {
        if (owner == null) return;
        Location l = owner.getLocation();
        for (int t = 0; t < 16; t++) {
            double a = game.random.nextDouble() * Math.PI * 2;
            double x = l.getX() + Math.cos(a) * OPEN_R, z = l.getZ() + Math.sin(a) * OPEN_R;
            if (!game.mobs.flow.walkable(x, z) || Plaza.radius(x, z) > Plaza.R_ROAM - 1) continue;
            game.mobs.relocate(this, new Vector(x, game.mobs.flow.floor(x, z) + HOVER, z));
            return;
        }
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        stepped();
        if (phase == Phase.SHUT) {
            hold();
            return;
        }
        if (phase == Phase.SUNK) {
            sunk();
            return;
        }
        if (owner == null || !standing(owner)) {
            pickOwner();
            if (owner == null) {
                follow(speed());
                return;
            }
        }
        mark(owner);
        Location ol = owner.getLocation();
        Vector feet = ol.toVector();
        double h = Math.hypot(feet.getX() - pos.getX(), feet.getZ() - pos.getZ());
        face(feet.clone().setY(pos.getY()));
        if (h > 0.35) seek(feet.clone().setY(pos.getY()), speed());
        else hold();
        if (h < LEAN_R) body.look.on.add("lean");
        else body.look.on.remove("lean");
        // 끌림(PUSH)
        Vector in = new Vector(pos.getX() - feet.getX(), 0, pos.getZ() - feet.getZ());
        if (in.lengthSquared() > 1e-4) {
            in.normalize();
            game.players.push(owner, in.clone().multiply(phase == Phase.CALL ? CALL_PULL : PULL));
        }
        // 부름: 0.5초 전 입술이 오므라듦, 1초 세게
        if (phase == Phase.FOLLOW) {
            state(age >= nextCall - TELL ? "call" : "");
            if (age >= nextCall) {
                phase(Phase.CALL);
                calls++;
                soundFor(owner, "call", Sound.BLOCK_BEACON_AMBIENT, 1f);
            } else if (age % 30 == 0) {
                soundFor(owner, "call", Sound.BLOCK_BEACON_AMBIENT, (float) (0.35 + 0.55 * Math.max(0, 1 - h / 18)));
            }
        } else if (phase == Phase.CALL) {
            state("call");
            if (inPhase() % 4 == 0) turnView(owner);
            if (inPhase() >= CALL_T) {
                phase(Phase.FOLLOW);
                nextCall = age + cd(CALL_EVERY);
            }
        }
        if (game.now() - hintAt >= 40) {
            hintAt = game.now();
            game.hud.point(owner, pos.clone().add(new Vector(0, 0.3, 0)), 45);
            game.hud.mark(owner, "g189", 45);
        }
        if (h < FALL_R && age >= regrabAt && Math.abs(ol.getY() - pos.getY()) < 1.2) fall(owner);
    }

    /** 부름 동안 임자 화면을 구멍 쪽으로 조금 돌림. */
    private void turnView(Player p) {
        Location l = p.getLocation();
        double want = Math.toDegrees(Math.atan2(-(pos.getX() - l.getX()), pos.getZ() - l.getZ()));
        double d = ((want - l.getYaw()) % 360 + 540) % 360 - 180;
        if (Math.abs(d) < 4) return;
        Guns.kick(p, (float) Math.signum(d) * (float) Math.min(3.0, Math.abs(d) * 0.15), 0.6f);
    }

    private void fall(Player p) {
        if (!grab(p, 60)) return;
        PState s = game.players.get(p);
        s.grabPin = true;
        s.grabDetach = true;
        phase(Phase.SUNK);
        state("bite");
        falls++;
        sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.6f, 16);
        body.squash(1.15f, 1.6f, 1.15f, 3);
    }

    private void sunk() {
        hold();
        if (owner == null || !owner.isOnline()) {
            reopen();
            return;
        }
        PState s = game.players.get(owner);
        if (s.life == Life.TRAPPED && s.eater == this) {
            phase(Phase.SHUT);
            mark(null);
            return;
        }
        if (game.players.grabbedBy(owner) != this) {
            reopen();
            return;
        }
        double fy = game.mobs.flow.floor(pos.getX(), pos.getZ());
        game.players.carry(owner, new Vector(pos.getX(), fy - SINK + 0.6, pos.getZ()));
        if (every(10)) hit(owner, SUNK_DMG, "cause_g189");
        if (inPhase() >= SWALLOW && standing(owner)) {
            sound("gulp", Sound.ENTITY_GENERIC_EAT, 0.4f, 16);
            game.players.trap(owner, this);
            phase(Phase.SHUT);
            mark(null);
        } else if (!standing(owner) && game.players.get(owner).life != Life.TRAPPED) {
            reopen();
        }
    }

    private void reopen() {
        phase(Phase.FOLLOW);
        state("");
        regrabAt = age + REGRAB;
        nextCall = age + cd(CALL_EVERY);
    }

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != owner || phase != Phase.SUNK) return;
        reopen();
        Vector out = victim.getLocation().toVector().subtract(pos).setY(0);
        if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
        knock(victim, out, 0.6, 0.45);
    }

    /** 임자가 아닌 사람이 밟으면 발을 묾. */
    private void stepped() {
        if (phase == Phase.SUNK || phase == Phase.SHUT) return;
        for (Player q : game.mobs.standing()) {
            if (q == owner) continue;
            Location l = q.getLocation();
            if (Math.abs(l.getY() - pos.getY()) > 1.0) continue;
            if (Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) > STEP_R) continue;
            Integer at = stepAt.get(q.getUniqueId());
            if (at != null && age - at < STEP_CD) continue;
            stepAt.put(q.getUniqueId(), age);
            hit(q, STEP_DMG, "cause_g189_bite");
            game.players.root(q, STEP_ROOT, "g189");
            sound("snap", Sound.ENTITY_EVOKER_FANGS_ATTACK, 1f, 16);
            body.squash(0.9f, 1.4f, 0.9f, 2);
        }
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (d.by() != null && owner != null && d.by().getUniqueId().equals(owner.getUniqueId())) {
            // 임자의 탄은 빨려 들어감
            body.squash(1.08f, 0.8f, 1.08f, 2);
            return false;
        }
        return super.damage(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " owner=" + (owner == null ? "-" : owner.getName()) + " falls=" + falls + " calls=" + calls;
    }
}
