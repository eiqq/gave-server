package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G135 일곱 게이브(떼, 정예). 젖은 얼굴이 걸린 장대 일곱이 한 줄로 콩콩 찍으며 온다.
 *
 * <p>도감: 맨 앞(지팡이 짚고 눈 뜬 것)을 잡으면 그 자리는 다시 안 채워지고 다음 것이 지팡이를 받는다. 맨 앞이 아닌 것을 잡으면 5초 뒤
 * 줄 끝 땅에서 젖은 새것이 솟아 수가 그대로다. 여럿을 한꺼번에 잡아도 맨 앞 하나만 줄어든다. 솟는 중(1초)인 것은 한 발에 죽지만 그것도 다시 솟는다.
 * 사람을 쓰러뜨리면 끝에서 하나가 더 솟는다(일곱까지). 장대를 숙여 젖은 얼굴로 후려쳐 13 피해와 젖음(3초 느려짐 20%),
 * 둘러싸면 차례로 내려쳐 1초에 세 번.
 *
 * <p>줄({@link Row}): 감독이 낸 개체는 40틱 안에 생긴 같은 판의 줄에 끼고(줄 끝으로 옮겨 섬), 모자란 수는 그 뒤 보상 0 개체로 채운다.
 * 보상은 줄이 모아 두었다가 맨 앞을 잡을 때마다 남은 수로 나눠 준다(뒤의 것, 다시 솟은 것은 0).
 *
 * <p>팩 계약(tools/gabe/sp/g135.py): 모델 gave:gabe/g135 상태 "", 부위 "staff"(맨 앞), 소리 tap(24), slap(16), rise(24), 글귀 cause_g135.
 */
public final class G135 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int SEVEN = 7;
    static final double SPEED = 0.18, HOVER = 1.3, GAP = 1.2;
    /** 후려치기: 닿는 거리(수평), 피해, 젖음. */
    static final double REACH = 2.4, DAMAGE = 13, WET_SLOW = 0.2;
    static final int WET = 60;
    /** 준비(뒤로 젖힘), 내려친 뒤 되돌아오는 틱, 개체 쿨다운, 줄 전체에서 내려치기 사이 최소 틱(1초에 세 번). */
    static final int WINDUP = 8, RECOVER = 8, STRIKE_CD = 40, ROW_GAP = 7;
    /** 줄 모으는 틱, 다시 솟기까지, 솟는 틱, 솟을 때 땅 밑 깊이. */
    static final int FORM = 40, RESPAWN = 100, RISE = 20;
    static final double RISE_DEPTH = 2.7;
    /** 콩콩: 한 번 뛰는 틱, 한 바퀴 틱, 뛰는 높이, 줄 차례마다 늦는 틱. */
    static final int HOP = 8, HOP_PERIOD = 28, HOP_LAG = 4;
    static final double HOP_H = 0.3;
    /** 둘러쌀 때 사람에게서 거리, 맨 앞이 사람에게 이 안으로 오면 둘러싼다. */
    static final double SURROUND_R = 1.9, SURROUND_AT = 6;

    /** 줄 하나(무리 표). 줄 차례 = line 차례, 맨 앞 = line[0]. 남은 수 left = 줄에 선 수 + 솟기를 기다리는 수. */
    static final class Row {
        final Game game;
        final int wave;
        final long born;
        final List<G135> line = new ArrayList<>(SEVEN);
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        final Vector lastPos = new Vector();
        int left = SEVEN, pool, fails;
        double unitHp = -1, ringA;
        long lastStrike = Long.MIN_VALUE / 2, tickAt = -1;
        boolean filled;
        Player prey;

        Row(Game game) {
            this.game = game;
            this.wave = game.wave;
            this.born = game.now();
        }

        G135 front() {
            return line.isEmpty() ? null : line.get(0);
        }

        boolean live() {
            return game.state == Game.State.WAVE && game.wave == wave;
        }

        /** 줄 끝 다음 자리(가운데에서 바깥쪽으로 한 칸 뒤). */
        Vector tail() {
            if (line.isEmpty()) return lastPos.clone();
            G135 last = line.get(line.size() - 1);
            double dx = last.pos.getX() - (Plaza.CX + 0.5), dz = last.pos.getZ() - (Plaza.CZ + 0.5), l = Math.hypot(dx, dz);
            if (l < 1e-6) return last.pos.clone();
            return new Vector(last.pos.getX() + dx / l * GAP, last.pos.getY(), last.pos.getZ() + dz / l * GAP);
        }

        /** 줄의 틱(한 판 틱에 한 번): 모자란 수 채우기, 기다리던 것 솟기. */
        void tick(G135 by) {
            long now = game.now();
            if (tickAt == now) return;
            tickAt = now;
            if (prey != null && !by.standing(prey)) prey = null;
            if (!filled && now - born >= FORM) {
                filled = true;
                int missing = left - line.size() - pending.size();
                for (int i = 0; i < missing; i++) pending.add(now + i * 3L);
            }
            while (!pending.isEmpty() && pending.peek() <= now) {
                pending.poll();
                if (!rise(by)) break;
            }
        }

        /** 줄 끝 땅에서 새것 하나가 솟음. 못 만들었으면(예산) 잠깐 뒤 다시, 여러 번 안 되면 그 자리를 버린다. */
        boolean rise(G135 by) {
            if (!live()) {
                left = Math.max(line.size(), left - 1);
                return true;
            }
            Vector at = tail();
            at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + HOVER - RISE_DEPTH);
            handoff = this;
            Gabe c;
            try {
                c = by.child(by.sp, at);
            } finally {
                handoff = null;
            }
            if (c != null) {
                fails = 0;
                return true;
            }
            if (++fails >= 5) {
                fails = 0;
                left = Math.max(line.size(), left - 1);
                return true;
            }
            pending.addFirst(game.now() + 40);
            return false;
        }

        /** 사람이 쓰러짐: 끝에서 하나 더(일곱까지). */
        void grow() {
            if (left >= SEVEN) return;
            left++;
            pending.add(game.now() + 20);
        }

        boolean strikeGate() {
            long now = game.now();
            if (now - lastStrike < ROW_GAP) return false;
            lastStrike = now;
            return true;
        }

        /** 줄의 개체가 죽음(super.die 앞). 맨 앞이면 남은 수가 줄고 모아 둔 보상의 몫을 준다. 아니면 5초 뒤 다시 솟는다. */
        void died(G135 g, Damage d) {
            boolean front = front() == g;
            lastPos.copy(g.pos);
            line.remove(g);
            if (!live() || cleanup(d)) return;
            if (front) {
                int share = left > 0 ? pool / left : 0;
                pool -= share;
                left = Math.max(0, left - 1);
                g.noReward = share <= 0;
                g.reward = share;
            } else {
                g.noReward = true;
                g.reward = 0;
                pending.add(game.now() + RESPAWN);
            }
            // 한꺼번에 다 잡혔으면 기다리던 것 하나가 바로 솟는다(줄이 끊긴 사이에 웨이브가 끝나지 않게)
            if (line.isEmpty() && !pending.isEmpty()) {
                pending.poll();
                if (!rise(g)) {
                    pending.clear();
                    left = 0;
                }
            }
        }
    }

    private static final Map<Game, List<Row>> ROWS = new WeakHashMap<>();
    /** 줄이 새것을 솟게 할 때 그 개체의 spawned() 에 줄을 넘겨주는 칸(한 스레드). */
    private static Row handoff;

    /** 웨이브 끝 정리, 시험 치우기 같은 죽음(다시 솟지 않는다). */
    static boolean cleanup(Damage d) {
        if (d == null) return true;
        if (d.by() != null || d.type() != DamageType.OTHER) return false;
        String how = d.how();
        return "test".equals(how) || "wave_end".equals(how) || "stall".equals(how);
    }

    private static Row forming(Game game) {
        List<Row> rows = ROWS.computeIfAbsent(game, g -> new ArrayList<>());
        long now = game.now();
        Row found = null;
        for (Iterator<Row> it = rows.iterator(); it.hasNext();) {
            Row r = it.next();
            if (!r.live() || (r.line.isEmpty() && r.pending.isEmpty())) {
                it.remove();
                continue;
            }
            if (found == null && !r.filled && now - r.born < FORM && r.line.size() < SEVEN) found = r;
        }
        if (found == null) {
            found = new Row(game);
            rows.add(found);
        }
        return found;
    }

    private Row row;
    private boolean init, fresh;
    private int riseUntil, strikeAt = -1, readyAt;
    private Player victim;

    public G135(Game game, Species sp) {
        super(game, sp);
        radius = 0.4;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        ragKick = 0.7;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g135", 1.2f, 2.8f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Row r = handoff;
        if (r != null) {
            // 줄이 솟게 한 새것: 땅 밑에서 올라온다
            fresh = true;
            riseUntil = RISE;
            hover = HOVER - RISE_DEPTH;
            sound("rise", Sound.BLOCK_MUD_STEP, 0.6f, 24);
        } else {
            r = forming(game);
            if (!r.line.isEmpty()) {
                Vector t = r.tail();
                pos.setX(t.getX()).setZ(t.getZ());
                pos.setY(floor() + hover);
            }
        }
        row = r;
        r.line.add(this);
        yaw = (float) Math.toDegrees(Math.atan2(pos.getX() - (Plaza.CX + 0.5), (Plaza.CZ + 0.5) - pos.getZ()));
    }

    private void init() {
        init = true;
        if (!fresh) {
            // 감독이 낸 개체: 체력 배율을 줄에 적고 보상은 줄이 모아 둔다(맨 앞을 잡을 때마다 나눠 줌)
            if (row.unitHp < 0) row.unitHp = maxHp;
            if (!noReward) row.pool += reward;
            reward = 0;
        } else if (row.unitHp > 0) {
            maxHp = hp = row.unitHp;
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (row == null) {
            follow(speed());
            return;
        }
        if (!init) init();
        row.tick(this);
        if (age < riseUntil) {
            hover = HOVER - RISE_DEPTH * (1.0 - (double) (age + 1) / RISE);
            hold();
            return;
        }
        boolean front = row.front() == this;
        lead(front);
        if (strikeAt >= 0) {
            hover = HOVER;
            strike();
            return;
        }
        hop();
        move(front);
        tryStrike();
    }

    /** 맨 앞만 지팡이를 짚고 눈을 뜬다. */
    private void lead(boolean front) {
        if (front) {
            body.look.on.add("staff");
            Player p = nearest(400);
            if (p != target) mark(p);
        } else {
            body.look.on.remove("staff");
            if (target != null) mark(null);
        }
    }

    /** 장대 밑동을 땅에 콩 찍음(줄 차례대로 늦게: 일곱 번). */
    private void hop() {
        int i = Math.max(0, row.line.indexOf(this));
        int ph = Math.floorMod((int) ((now() - row.born) % HOP_PERIOD) - i * HOP_LAG, HOP_PERIOD);
        if (ph < HOP) hover = HOVER + HOP_H * Math.sin(Math.PI * ph / HOP);
        else hover = HOVER;
        if (ph == HOP && game.mobs.nearestPlayer(pos, 30) != null) sound("tap", Sound.BLOCK_WOOD_HIT, 0.6f, 24);
    }

    private double flat(Player p) {
        return Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ());
    }

    private void faceAlong() {
        if (Math.hypot(vel.getX(), vel.getZ()) > 1e-4) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        pitch = 0;
    }

    private void move(boolean front) {
        if (front) {
            Player p = target;
            if (p != null && standing(p)) {
                double d = flat(p);
                if (d > REACH - 0.7) follow(speed());
                else hold();
                face(chest(p));
                pitch = 0;
                if (d < SURROUND_AT) {
                    row.prey = p;
                    row.ringA = Math.atan2(pos.getZ() - p.getLocation().getZ(), pos.getX() - p.getLocation().getX());
                } else row.prey = null;
            } else {
                row.prey = null;
                follow(speed());
                faceAlong();
            }
            return;
        }
        Player q = row.prey;
        if (q != null && standing(q)) {
            // 둘러싸기: 맨 앞이 선 자리부터 고르게 돌아가며
            int n = Math.max(1, row.line.size()), i = Math.max(0, row.line.indexOf(this));
            double a = row.ringA + i * (Math.PI * 2 / n);
            double tx = q.getLocation().getX() + Math.cos(a) * SURROUND_R, tz = q.getLocation().getZ() + Math.sin(a) * SURROUND_R;
            step(tx, tz, speed() * 1.35, 0.15);
            face(chest(q));
            pitch = 0;
            return;
        }
        int i = row.line.indexOf(this);
        G135 prev = i > 0 ? row.line.get(i - 1) : null;
        if (prev == null) {
            follow(speed());
            faceAlong();
            return;
        }
        step(prev.pos.getX(), prev.pos.getZ(), speed() * 1.4, GAP);
        yaw = (float) Math.toDegrees(Math.atan2(-(prev.pos.getX() - pos.getX()), prev.pos.getZ() - pos.getZ()));
        pitch = 0;
    }

    /** (x, z) 쪽으로 keep 블록 앞까지 걸음. */
    private void step(double x, double z, double v, double keep) {
        double dx = x - pos.getX(), dz = z - pos.getZ(), d = Math.hypot(dx, dz);
        if (d <= keep) {
            hold();
            return;
        }
        double go = Math.min(v, d - keep);
        vel.setX(dx / d * go).setY(0).setZ(dz / d * go);
    }

    private void tryStrike() {
        if (age < readyAt) return;
        Player q = nearest(REACH + 1.5);
        if (q == null || flat(q) > REACH) return;
        if (!row.strikeGate()) return;
        victim = q;
        strikeAt = age;
    }

    /** 장대를 뒤로 젖혔다가 숙여 젖은 얼굴로 후려침. */
    private void strike() {
        int t = age - strikeAt, wind = cd(WINDUP);
        hold();
        if (victim != null && victim.isOnline() && t <= wind) face(chest(victim));
        if (t < wind) {
            pitch = -16;
        } else if (t == wind) {
            pitch = 64;
            body.pose(2);
            sound("slap", Sound.ENTITY_SLIME_ATTACK, 0.8f, 16);
            if (standing(victim) && flat(victim) <= REACH + 0.6) {
                hit(victim, DAMAGE, "cause_g135");
                if (standing(victim)) game.players.slow(victim, WET_SLOW, WET, "g135_wet");
                else row.grow();
            }
        } else if (t >= wind + RECOVER) {
            pitch = 0;
            body.pose(4);
            strikeAt = -1;
            victim = null;
            readyAt = age + cd(STRIKE_CD);
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        List<double[]> out = new ArrayList<>(3);
        out.add(new double[] {pos.getX() + fx * 0.14, pos.getY() + 0.72, pos.getZ() + fz * 0.14, 0.38});
        out.add(new double[] {pos.getX(), pos.getY() - 0.05, pos.getZ(), 0.3});
        out.add(new double[] {pos.getX(), pos.getY() - 0.75, pos.getZ(), 0.26});
        return out;
    }

    @Override
    public Vector head() {
        return new Vector(pos.getX(), pos.getY() + 0.72, pos.getZ());
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        // 솟는 중인 것(맨 앞이 아닐 때)은 한 발에 죽는다(그것도 다시 솟는다)
        if (age < riseUntil && row != null && row.front() != this) hp = Math.min(hp, 0.01);
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (row != null) row.died(this, d);
        super.die(d);
    }

    @Override
    public void remove() {
        if (row != null) row.line.remove(this);
        super.remove();
    }

    /** 시험용: 줄 안 차례, 줄에 선 수, 남은 수, 솟기를 기다리는 수. */
    @Override
    public String toString() {
        if (row == null) return super.toString();
        return super.toString() + " row=" + row.line.indexOf(this) + "/" + row.line.size() + " left=" + row.left + " pend=" + row.pending.size()
                + " pool=" + row.pool + (age < riseUntil ? " rising" : "") + (strikeAt >= 0 ? " strike" : "");
    }
}
