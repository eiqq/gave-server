package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G58 비명 횡대 게이브(떼, 돌격). 막대기 둘을 쳐든 비명 얼굴들이 어깨를 맞대고 한 줄로 서서, 1초 멈춰 비명을 지른 뒤 줄째 일직선으로 질주한다.
 *
 * <p>줄 묶기는 G56 과 같은 꼴: spawned() 에서 '열린 횡대'(생긴 지 JOIN 틱 안, 사람에게서 멀거나 새 자리 곁)에 붙고, 없으면 새 횡대를 연다.
 * 횡대는 보이지 않는 닻(anchor)을 갖고 개체마다 닻 기준 자리(옆으로 SPACING, 가운데가 BOW 만큼 앞선 활 꼴)를 쫓는다.
 *
 * <p>차례: COME(닻이 사람 쪽으로, 사람에게서 SHOW_R 안에 들면) → FORM(그 순간 사람 무리 쪽으로 방향을 정하고 줄을 맞춤, 비명, 줄 앞 흙먼지 띠,
 * 상태 halt, HALT 틱) → RUN(정한 방향으로 RUN 빠르기 고정 질주, 방향을 안 바꿈. 닿은 사람은 박치기 DMG + 진행 방향 밀침, 한 사람에게 한 번.
 * 줄 끝 둘은 빗겨 가며 DMG_EDGE) → 사람 무리를 PAST 블록 지나치면 CALM(멈춰 돌아서고 비명이 뚝 끊기며 웃음, 상태 calm) → 다시 FORM.
 * 정면의 한두 마리를 잡아 틈을 내면 줄이 그 사람을 그냥 지나친다(방향을 못 틈).
 *
 * <p>팩 계약(tools/gabe/sp/g58.py): 상태 "", "halt", "calm", 소리 scream(48), bump(16), 글귀 cause_g58.
 */
public final class G58 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double HOVER = 0.93, RADIUS = 0.38;
    /** 다가오는 빠르기, 질주 빠르기(블록/틱, 감독 배율 곱함). */
    static final double SPEED = 0.16, RUN = 0.42;
    /** 줄 간격, 가운데가 앞서는 정도. */
    static final double SPACING = 0.95, BOW = 0.7;
    /** 이 거리 안에 들면 멈춰 줄을 맞춤, 사람 무리를 지나쳐 더 달리는 거리, 질주 최대 틱. */
    static final double SHOW_R = 40, PAST = 16;
    static final int RUN_MAX = 260;
    /** 멈춰 비명(출발 1초 전, 박자 배율 곱함), 돌아서 웃는 틱. */
    static final int HALT = 20, CALM = 30;
    /** 피해와 밀침. */
    static final double DMG = 10, DMG_EDGE = 5, PUSH = 0.7, PUSH_UP = 0.3;
    /** 줄 묶기. */
    static final int JOIN = 100, MAX_RANK = 16;
    static final double FAR = 36, NEAR_JOIN = 8;

    enum Phase { COME, FORM, RUN, CALM }

    /** 한 횡대. */
    static final class Rank {
        final Game game;
        final long t0;
        final List<G58> members = new ArrayList<>();
        final Vector anchor = new Vector(), heading = new Vector(0, 0, 1);
        final Set<UUID> struck = new HashSet<>();
        Phase phase = Phase.COME;
        long phaseAt, beatTick = Long.MIN_VALUE;
        double runGone, runLen;

        Rank(Game game, long t0, Vector at) {
            this.game = game;
            this.t0 = t0;
            anchor.copy(at);
        }
    }

    private static final List<Rank> OPEN = new ArrayList<>();

    private Rank rank;
    /** 줄 안 자리 번호(왼쪽부터, FORM 마다 다시 매김). */
    private int slot;

    public G58(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    // ------------------------------------------------------------------ 놓임, 줄 묶기

    @Override
    protected void spawned() {
        long now = game.now();
        Rank join = null;
        if (!game.director.cageSpawning()) {
            for (Iterator<Rank> it = OPEN.iterator(); it.hasNext();) {
                Rank r = it.next();
                r.members.removeIf(m -> m.dead);
                if (r.game != game || now < r.t0 || now - r.t0 > JOIN || r.members.isEmpty() || r.phase != Phase.COME
                        || r.members.size() >= MAX_RANK) {
                    it.remove();
                    continue;
                }
                if (join != null) continue;
                boolean far = game.mobs.nearestPlayer(r.anchor, FAR) == null && game.mobs.nearestPlayer(pos, FAR) == null;
                if (far || r.anchor.distance(pos) <= NEAR_JOIN) join = r;
            }
        }
        if (join == null) {
            rank = new Rank(game, now, pos);
            rank.members.add(this);
            rank.phaseAt = now;
            Vector d = game.mobs.flow.dir(pos);
            if (d.lengthSquared() > 1e-6) rank.heading.copy(d.setY(0).normalize());
            if (!game.director.cageSpawning()) OPEN.add(rank);
            return;
        }
        rank = join;
        join.members.add(this);
        slot = join.members.size() - 1;
        Vector at = slotPoint(slot, join.members.size());
        if (game.mobs.flow.walkable(at.getX(), at.getZ())) {
            pos.setX(at.getX());
            pos.setZ(at.getZ());
            pos.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER);
        }
    }

    /** 닻 기준 i 번째(n 마리 가운데) 자리: 옆으로 나란히, 가운데가 앞선 활 꼴. */
    private Vector slotPoint(int i, int n) {
        Vector h = rank.heading;
        Vector right = new Vector(-h.getZ(), 0, h.getX());
        double c = (n - 1) / 2.0, u = n <= 1 ? 0 : (i - c) / Math.max(1, c);
        return rank.anchor.clone().add(right.multiply((i - c) * SPACING)).add(h.clone().multiply(BOW * (1 - u * u)));
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        long now = now();
        if (rank.beatTick != now) beat(now);
        switch (rank.phase) {
            case COME -> {
                Vector at = slotPoint(slot, rank.members.size());
                if (at.distanceSquared(pos) > 0.04) seek(at.setY(pos.getY()), speed() * 1.4);
                else hold();
                face(pos.clone().add(rank.heading));
                pitch = 0;
                state("");
            }
            case FORM -> {
                Vector at = slotPoint(slot, rank.members.size());
                if (at.distanceSquared(pos) > 0.01) seek(at.setY(pos.getY()), Math.min(0.35, at.distance(pos) * 0.35 + 0.05));
                else hold();
                face(pos.clone().add(rank.heading));
                pitch = 0;
                state("halt");
            }
            case RUN -> run();
            case CALM -> {
                vel.multiply(0.6);
                turnTo(pos.clone().add(rank.heading), 12f);
                state("calm");
            }
        }
    }

    /** 횡대 박자(한 틱에 한 번): 닻 옮기기와 단계 넘기기. */
    private void beat(long now) {
        Rank r = rank;
        r.beatTick = now;
        r.members.removeIf(m -> m.dead);
        if (r.members.isEmpty()) return;
        int inPhase = (int) (now - r.phaseAt);
        G58 lead = r.members.get(0);
        switch (r.phase) {
            case COME -> {
                Player p = game.mobs.nearestPlayer(r.anchor, 400);
                Vector dir;
                if (p != null && flatDist(r.anchor, p) <= 24) dir = p.getLocation().toVector().subtract(r.anchor).setY(0);
                else dir = game.mobs.flow.dir(r.anchor).setY(0);
                if (dir.lengthSquared() > 1e-6) {
                    dir.normalize();
                    // 닻 방향은 천천히 돎(줄이 휙 돌지 않게)
                    r.heading.multiply(0.9).add(dir.clone().multiply(0.1)).setY(0);
                    if (r.heading.lengthSquared() > 1e-6) r.heading.normalize();
                    r.anchor.add(dir.multiply(lead.speed()));
                    r.anchor.setY(game.mobs.flow.floor(r.anchor.getX(), r.anchor.getZ()) + HOVER);
                }
                if (p != null && flatDist(r.anchor, p) <= SHOW_R) form(r, now);
            }
            case FORM -> {
                if (inPhase >= lead.cd(HALT)) {
                    r.phase = Phase.RUN;
                    r.phaseAt = now;
                    r.runGone = 0;
                    r.struck.clear();
                }
            }
            case RUN -> {
                r.runGone += RUN * lead.speed() / lead.baseSpeed;
                if (r.runGone >= r.runLen || inPhase >= RUN_MAX) {
                    r.phase = Phase.CALM;
                    r.phaseAt = now;
                    // 닻을 줄 가운데로 다시
                    Vector c = new Vector();
                    for (G58 m : r.members) c.add(m.pos);
                    r.anchor.copy(c.multiply(1.0 / r.members.size()));
                    r.heading.multiply(-1);
                }
            }
            case CALM -> {
                if (inPhase >= CALM) {
                    Player p = game.mobs.nearestPlayer(r.anchor, 400);
                    if (p != null && flatDist(r.anchor, p) <= SHOW_R + 12) form(r, now);
                    else {
                        r.phase = Phase.COME;
                        r.phaseAt = now;
                    }
                }
            }
        }
    }

    /** 줄을 맞추고 비명: 그 순간 가장 가까운 사람 둘레 무리의 가운데 쪽으로 방향을 정함(끝까지 안 바꿈). */
    private void form(Rank r, long now) {
        Player p = game.mobs.nearestPlayer(r.anchor, 400);
        if (p == null) return;
        Vector aim = new Vector();
        int n = 0;
        Vector pp = p.getLocation().toVector();
        for (Player q : game.mobs.standing()) {
            Vector v = q.getLocation().toVector();
            if (v.distanceSquared(pp) <= 16 * 16) {
                aim.add(v);
                n++;
            }
        }
        aim.multiply(1.0 / Math.max(1, n));
        Vector c = new Vector();
        for (G58 m : r.members) c.add(m.pos);
        c.multiply(1.0 / r.members.size());
        r.anchor.copy(c);
        Vector h = aim.clone().subtract(c).setY(0);
        if (h.lengthSquared() < 1e-6) h = r.heading.clone();
        r.runLen = h.length() + PAST;
        r.heading.copy(h.normalize());
        // 줄 안 자리를 지금 옆 순서대로 다시 매김(가로지르지 않게)
        Vector right = new Vector(-r.heading.getZ(), 0, r.heading.getX());
        r.members.sort((a, b) -> Double.compare(a.pos.clone().subtract(c).dot(right), b.pos.clone().subtract(c).dot(right)));
        for (int i = 0; i < r.members.size(); i++) r.members.get(i).slot = i;
        r.phase = Phase.FORM;
        r.phaseAt = now;
        G58 mid = r.members.get(r.members.size() / 2);
        mid.sound("scream", Sound.ENTITY_GHAST_SCREAM, 1.3f, 48);
        mid.dust(right);
    }

    /** 질주: 정한 방향 고정, 닿은 사람은 한 번만 박음. */
    private void run() {
        state("");
        dash(rank.heading, RUN * speed() / baseSpeed);
        face(pos.clone().add(rank.heading));
        pitch = 0;
        boolean edge = slot == 0 || slot == rank.members.size() - 1;
        for (Player p : game.mobs.standing()) {
            if (rank.struck.contains(p.getUniqueId()) || !touches(p, 0.05)) continue;
            rank.struck.add(p.getUniqueId());
            hit(p, edge && rank.members.size() > 2 ? DMG_EDGE : DMG, "cause_g58");
            knock(p, rank.heading, PUSH, PUSH_UP);
            sound("bump", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 16);
        }
    }

    /** 출발 1초 전 줄 앞 바닥에 일어나는 흙먼지 띠. */
    private void dust(Vector right) {
        BlockData bd = floorBlock();
        if (bd == null) return;
        int n = rank.members.size();
        double half = (n - 1) / 2.0 * SPACING + 0.6;
        for (double s = -half; s <= half; s += 0.9) {
            Vector at = rank.anchor.clone().add(right.clone().multiply(s)).add(rank.heading.clone().multiply(BOW + 1.2));
            Location l = new Location(game.world, at.getX(), game.mobs.flow.floor(at.getX(), at.getZ()) + 0.1, at.getZ());
            game.world.spawnParticle(Particle.BLOCK, l, 6, 0.3, 0.08, 0.3, 0.05, bd);
        }
    }

    private BlockData floorBlock() {
        try {
            return game.world.getBlockAt((int) Math.floor(pos.getX()), (int) Math.floor(floor() - 0.5), (int) Math.floor(pos.getZ())).getBlockData();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 시험용. */
    public String rankInfo() {
        return rank == null ? "-" : rank.members.size() + " " + rank.phase + " slot" + slot;
    }

    @Override
    public String toString() {
        return super.toString() + " rank=" + rankInfo();
    }

    /** 수평 거리. */
    private static double flatDist(Vector a, Player p) {
        double dx = p.getLocation().getX() - a.getX(), dz = p.getLocation().getZ() - a.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
