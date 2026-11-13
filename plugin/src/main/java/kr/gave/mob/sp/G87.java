package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import kr.gave.Props;
import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.LookState;
import kr.gave.mob.Mobs;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G87 세 바람 게이브(2막, 떼, 방해). 회오리 셋이 한 조로 꼬리를 물고 지그재그로 아주 빠르게(0.42블록/틱) 와서 한 사람을 0.3초 사이에 차례로
 * 스치고 멈추지 않고 다음 사람으로 간다.
 *
 * <p>첫째(갈퀴)가 발을 걸어 0.4초 묶고, 둘째(낫)가 베어 18, 셋째(솔)가 약을 발라 그 피해를 숨긴다: 체력이 그대로이다가 6초 뒤 숨은 피해가 한꺼번에
 * 들어온다(겹쳐 쌓임, 판 갈고리 {@link Hidden}). 셋째가 없으면 그 자리에서 바로 아프다. 셋째를 잡으면 그 조의 숨은 피해가 절반이 되고, 첫째를 잡으면
 * 둘째가 절반은 빗나간다(NOMISS 깃발이면 안 빗나감). 붕대, 정화, 짜내기(CURE 갈고리)는 숨은 피해를 지운다. 약 발린 사람 뒤통수에 남들만 보는 분홍
 * 손자국(머리 칸 모델 g87_hand).
 *
 * <p>조 만들기: 놓일 때(spawned) 감독이 낸 것처럼(spawnLike) 둘을 더 놓아 한 조로 묶는다(각자 보상, 체력 배율). 맨 앞(산 것 가운데 차례가 가장 앞)이
 * 길을 고르고 뒤 둘은 앞의 4틱, 8틱 전 자리를 따라간다.
 *
 * <p>팩 계약(tools/gabe/sp/g87.py): 모델 gabe/g87, 부위 rake, sickle, brush. 머리 칸 gabe/g87_hand. 소리 whoosh, slash, burst.
 * 글귀 cause_g87, cause_g87_hidden.
 */
public final class G87 extends Gabe {

    static final String KEY = "G87";
    static final String[] PART = {"rake", "sickle", "brush"};
    /** 빠르기(블록/틱), 지나쳐 가는 거리, 곧장 꿰뚫기 시작하는 거리. */
    static final double SPEED = 0.42, OVERSHOOT = 4.5, ATTACK_R = 14;
    /** 뒤따르는 틱 간격(조 안 차례마다). */
    static final int LAG = 4;
    /** 걸기 묶음 틱, 베기 피해, 숨는 틱, 낫과 솔 사이 기다리는 틱, 한 개체가 같은 사람을 다시 스치기까지. */
    static final int TRIP = 8, HIDE = 120, PENDING = 14, RETOUCH = 20;
    static final double SLASH = 18;

    /** 한 조. 길 기록(맨 앞 자리, 틱마다)을 뒤따르는 개체가 읽는다. */
    static final class Team {
        final G87[] m = new G87[3];
        final double[] trail = new double[64 * 3];
        int trailN, trailAt;
        long trailTick = Long.MIN_VALUE;
        boolean halved;

        G87 leader() {
            for (G87 g : m) if (g != null && !g.dead) return g;
            return null;
        }

        boolean alive(int role) {
            G87 g = m[role];
            return g != null && !g.dead;
        }

        int order(G87 g) {
            int n = 0;
            for (G87 x : m) {
                if (x == null || x.dead) continue;
                if (x == g) return n;
                n++;
            }
            return 0;
        }

        void record(long now, Vector p) {
            if (now == trailTick) return;
            trailTick = now;
            int i = trailAt;
            trail[i * 3] = p.getX();
            trail[i * 3 + 1] = p.getY();
            trail[i * 3 + 2] = p.getZ();
            trailAt = (trailAt + 1) % 64;
            trailN = Math.min(64, trailN + 1);
        }

        Vector back(int ticks) {
            if (trailN == 0) return null;
            int k = Math.min(ticks, trailN - 1);
            int i = ((trailAt - 1 - k) % 64 + 64) % 64;
            return new Vector(trail[i * 3], trail[i * 3 + 1], trail[i * 3 + 2]);
        }
    }

    private static boolean forming;

    Team team;
    int role;
    private Player prey;
    private final Vector aim = new Vector();
    private boolean committed;
    private int committedAt;
    private Player lastHit;
    private int lastHitAt;
    private final Map<UUID, Integer> touchedAt = new HashMap<>();

    public G87(Game game, Species sp) {
        super(game, sp);
        radius = 0.4;
        baseSpeed = SPEED;
        noPush = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g87", 1.4f, 1.6f);
    }

    @Override
    protected void spawned() {
        body.look.phase = game.random.nextInt(8);
        if (forming) return;
        team = new Team();
        role = 0;
        team.m[0] = this;
        body.look.on.add(PART[0]);
        if (game.director.cageSpawning()) return;
        Hidden.of(game);
        game.players.addCureHook(KEY, (p, how) -> Hidden.of(game).cure(p));
        // 뒤 둘: 가운데에서 먼 쪽(뒤)으로 1.4블록씩
        Vector out = pos.clone().setY(0).subtract(new Vector(kr.gave.world.Plaza.CX + 0.5, 0, kr.gave.world.Plaza.CZ + 0.5));
        if (out.lengthSquared() < 1e-6) out = new Vector(0, 0, 1);
        out.normalize();
        forming = true;
        try {
            for (int r = 1; r < 3; r++) {
                Vector at = pos.clone().add(out.clone().multiply(1.4 * r));
                Gabe g = game.director.spawnLike(sp, at);
                if (g instanceof G87 o) {
                    o.team = team;
                    o.role = r;
                    o.yaw = yaw;
                    team.m[r] = o;
                    o.body.look.on.add(PART[r]);
                }
            }
        } finally {
            forming = false;
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (team == null) {
            team = new Team();
            team.m[0] = this;
            body.look.on.add(PART[0]);
        }
        G87 lead = team.leader();
        if (lead == this) leadTick();
        else followTick(lead);
        strikes();
    }

    /** 맨 앞: 사람 하나를 꿰뚫고 지나 다음 사람으로(멈추지 않음). */
    private void leadTick() {
        team.record(game.now(), pos);
        if (prey != null && !standing(prey)) {
            prey = null;
            committed = false;
        }
        if (!committed) {
            prey = pickPrey();
            if (prey == null) {
                follow(speed());
                zig();
                return;
            }
            if (dist(prey) <= ATTACK_R) {
                Vector to = chest(prey).subtract(pos).setY(0);
                if (to.lengthSquared() < 1e-6) to = new Vector(1, 0, 0);
                to.normalize();
                aim.copy(chest(prey).add(to.multiply(OVERSHOOT)));
                committed = true;
                committedAt = age;
                mark(prey);
            } else {
                mark(null);
                follow(speed());
                zig();
                return;
            }
        }
        // 꿰뚫기: 처음 2/3 는 사람 쪽으로 다시 겨눔(늦게 피하면 맞음), 뒤는 곧게 지나감
        Vector to = aim.clone().subtract(pos).setY(0);
        double l = to.length();
        if (prey != null && dist(prey) > 1.5 && age - committedAt < 20) {
            Vector d = chest(prey).subtract(pos).setY(0);
            if (d.lengthSquared() > 1e-6) aim.copy(chest(prey).add(d.normalize().multiply(OVERSHOOT)));
        }
        if (l < 0.8 || age - committedAt > 60) {
            committed = false;
            if (prey != null) {
                lastHit = prey;
                lastHitAt = age;
            }
            return;
        }
        dash(to, speed());
        zig();
        face(pos.clone().add(vel));
    }

    /** 다음 사람: 강제 표적 → 방금 꿰뚫은 사람이 아닌 가장 가까운 사람(혼자면 그 사람). */
    private Player pickPrey() {
        Player f = game.mobs.forcedFor(this);
        if (f != null) return f;
        Player best = null, any = null;
        double bd = Double.MAX_VALUE, ad = Double.MAX_VALUE;
        boolean fresh = age - lastHitAt < 40;
        for (Player p : game.mobs.standing()) {
            double d = dist(p);
            if (d < ad) {
                ad = d;
                any = p;
            }
            if (fresh && p == lastHit) continue;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best != null ? best : any;
    }

    /** 지그재그: 가는 쪽 옆으로 흔들림. */
    private void zig() {
        double vl = Math.hypot(vel.getX(), vel.getZ());
        if (vl < 1e-6) return;
        double s = Math.sin((age + team.hashCode() % 50) * 0.33) * 0.55 * vl;
        vel.add(new Vector(-vel.getZ() / vl * s, 0, vel.getX() / vl * s));
    }

    /** 뒤따름: 맨 앞의 (차례 x 4)틱 전 자리로. 너무 멀면 혼자 흐름장을 따라 옴. */
    private void followTick(G87 lead) {
        int k = team.order(this);
        Vector at = team.back(k * LAG);
        if (at == null || at.distanceSquared(pos) > 8 * 8) {
            follow(speed());
            zig();
            return;
        }
        Vector d = at.subtract(pos);
        double l = d.length();
        if (l < 1e-6) hold();
        else vel.copy(d.multiply(Math.min(l, speed() * 1.25) / l));
        face(pos.clone().add(vel));
        mark(lead.target);
    }

    /** 스침: 닿은 서 있는 사람마다 제 연장 하나(같은 사람은 1초에 한 번). */
    private void strikes() {
        for (Player p : game.mobs.standing()) {
            if (!touches(p, 0.25)) continue;
            UUID id = p.getUniqueId();
            Integer t = touchedAt.get(id);
            if (t != null && age - t < RETOUCH) continue;
            touchedAt.put(id, age);
            switch (role) {
                case 0 -> trip(p);
                case 1 -> slash(p);
                default -> brush(p);
            }
        }
        if (touchedAt.size() > 32) touchedAt.values().removeIf(t -> age - t > RETOUCH);
    }

    private void trip(Player p) {
        game.players.root(p, TRIP, "g87_trip");
        knock(p, vel.clone().setY(0), 0.18, 0.05);
        sound("whoosh", Sound.ENTITY_PHANTOM_SWOOP, 1.6f, 20);
    }

    private void slash(Player p) {
        sound("whoosh", Sound.ENTITY_PHANTOM_SWOOP, 1.4f, 20);
        // 첫째가 없으면 발이 안 걸려 절반은 빗나감(NOMISS 면 늘 맞음)
        if (!team.alive(0) && !game.players.noMiss(p) && game.random.nextBoolean()) return;
        if (team.alive(2)) {
            Hidden.of(game).pending(p, SLASH, team, this);
            return;
        }
        hit(p, SLASH, "cause_g87");
        sound("slash", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 16);
    }

    private void brush(Player p) {
        sound("whoosh", Sound.ENTITY_PHANTOM_SWOOP, 1.2f, 20);
        Hidden.of(game).hide(p, team, this);
    }

    @Override
    public void die(Damage d) {
        // 솔(셋째)이 죽으면 이 조의 숨은 피해 절반이 사라진다
        if (!dead && role == 2 && team != null && !team.halved) {
            team.halved = true;
            Hidden.of(game).halve(team);
        }
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " role=" + PART[Math.max(0, Math.min(2, role))] + (team != null && team.leader() == this ? " lead" : "")
            + " hidden=" + Hidden.of(game).count();
    }

    // ------------------------------------------------------------------ 판 갈고리: 숨은 피해

    /** 숨은 피해 표(FIELDTICK 키 "G87"). 낫이 벤 것은 솔을 기다리고(pending), 솔이 바르면 6초 뒤 터진다(hidden). 판이 바뀌면 비움. */
    static final class Hidden implements Mobs.Field {
        static final class Entry {
            final UUID who;
            double amount;
            long due;
            final Team team;
            final Gabe src;
            boolean hidden;

            Entry(UUID who, double amount, long due, Team team, Gabe src) {
                this.who = who;
                this.amount = amount;
                this.due = due;
                this.team = team;
                this.src = src;
            }
        }

        final Game game;
        final List<Entry> list = new ArrayList<>();
        final Set<UUID> marked = new HashSet<>();
        ItemStack hand;

        Hidden(Game game) {
            this.game = game;
        }

        static Hidden of(Game game) {
            if (game.mobs.field(KEY) instanceof Hidden h) return h;
            Hidden h = new Hidden(game);
            game.mobs.field(KEY, h);
            return h;
        }

        int count() {
            return list.size();
        }

        void pending(Player p, double amount, Team team, Gabe src) {
            list.add(new Entry(p.getUniqueId(), amount, game.now() + PENDING, team, src));
        }

        /** 솔이 닿음: 그 조가 이 사람에게 남긴 벤 것을 숨김(6초 뒤). */
        void hide(Player p, Team team, Gabe src) {
            UUID id = p.getUniqueId();
            boolean any = false;
            for (Entry e : list) {
                if (e.hidden || e.team != team || !e.who.equals(id)) continue;
                e.hidden = true;
                e.due = game.now() + HIDE;
                any = true;
            }
            if (any) mark(p, true);
        }

        void halve(Team team) {
            for (Entry e : list) if (e.team == team && e.hidden) e.amount *= 0.5;
        }

        void cure(Player p) {
            UUID id = p.getUniqueId();
            boolean any = list.removeIf(e -> e.hidden && e.who.equals(id));
            if (any) mark(p, false);
        }

        private void mark(Player p, boolean on) {
            PState s = game.players.get(p);
            UUID id = p.getUniqueId();
            if (on) {
                if (marked.contains(id) || s.headItem != null) return;
                if (hand == null) hand = Body.item("gabe/g87_hand", "", new LookState(), game.worldTime());
                game.players.attachHead(p, hand);
                marked.add(id);
            } else if (marked.remove(id)) {
                if (s.headItem != null && s.headBy == null && hand != null && s.headItem.isSimilar(hand)) game.players.attachHead(p, null);
            }
        }

        @Override
        public boolean tick(long now) {
            Set<UUID> popped = null;
            for (Iterator<Entry> it = list.iterator(); it.hasNext();) {
                Entry e = it.next();
                if (now < e.due) continue;
                it.remove();
                Player p = Bukkit.getPlayer(e.who);
                if (p == null || !p.isOnline() || !game.players.get(p).standing()) continue;
                if (e.hidden) {
                    game.players.hurt(p, e.amount, e.src, "cause_g87_hidden");
                    Props.soundTo(p, p.getLocation(), "gabe.g87.burst", Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1f, 1f, 0.7f, -1);
                    if (popped == null) popped = new HashSet<>();
                    popped.add(e.who);
                } else {
                    // 솔이 안 닿음: 그 자리에서 바로 아픔
                    game.players.hurt(p, e.amount, e.src, "cause_g87");
                }
            }
            if (popped != null) {
                for (UUID id : popped) {
                    boolean left = false;
                    for (Entry e : list) if (e.hidden && e.who.equals(id)) left = true;
                    Player p = Bukkit.getPlayer(id);
                    if (!left && p != null) mark(p, false);
                }
            }
            // 쓰러지거나 나간 사람의 손자국 정리
            if (!marked.isEmpty() && now % 20 == 0) {
                for (Iterator<UUID> it = marked.iterator(); it.hasNext();) {
                    UUID id = it.next();
                    boolean left = false;
                    for (Entry e : list) if (e.hidden && e.who.equals(id)) left = true;
                    if (left) continue;
                    Player p = Bukkit.getPlayer(id);
                    it.remove();
                    if (p != null) {
                        PState s = game.players.get(p);
                        if (s.headItem != null && s.headBy == null && hand != null && s.headItem.isSimilar(hand)) game.players.attachHead(p, null);
                    }
                }
            }
            return true;
        }

        /** 웨이브가 끝나면 숨은 피해도 끝(쉬는 시간에 터지지 않게), 손자국도 뗌. */
        @Override
        public void waveEnd(int wave) {
            list.clear();
            for (UUID id : marked) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                PState s = game.players.get(p);
                if (s.headItem != null && s.headBy == null && hand != null && s.headItem.isSimilar(hand)) game.players.attachHead(p, null);
            }
            marked.clear();
        }

        @Override
        public void reset() {
            list.clear();
            marked.clear();
        }
    }
}
