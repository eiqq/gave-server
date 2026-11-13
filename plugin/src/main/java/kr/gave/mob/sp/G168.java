package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G168 꿰매는 게이브(2막 떼, 방해). 바늘(엔드 막대기)을 문 0.4 블록 얼굴 여섯에서 열이 함께 하늘에서 내려와(감독이 낸 하나가 첫 틱에 다섯에서 아홉을
 * 더 데려옴) 재봉틀처럼 위아래로 까딱이며 날아온다(0.24블록/틱). 하나가 한 사람을 찌르고(6) 곧바로 6 블록 안의 다른 사람에게 날아가 찌르면 두 사람이
 * 분홍 살 실로 이어지고, 그 바늘 얼굴은 실 한가운데에 매달려 남는다.
 * <ul>
 * <li>실로 이어진 두 사람은 4 블록 넘게 못 벌어진다(넘으면 서로 쪽으로 되끌림, Players.push).</li>
 * <li>한 사람이 받는 피해의 절반을 이어진 사람도 받는다(실마다 따로). HURTHOOK: Players.addHurtHook("G168") 으로 체력이 깎인 직후 통지를 받아 이웃에게
 * 나눠 줌(갈고리 안의 hurt 는 다시 통지되지 않아 되먹임 없음).</li>
 * <li>한 사람에게 실이 셋 걸리면 1초 동안 실들이 팽팽해지며 떨다가(예고) 이어진 사람 모두가 그 사람 쪽으로 끌려와 1초 엉켜 못 걷고 8 피해.</li>
 * <li>실 상한은 사람마다 넷, 판 전체 열둘. 실 한가운데 바늘 얼굴(체력 18)을 쏘면 그 실이 풀린다. 이어진 사람이 쓰러지거나 나가면 실이 끊기고 바늘은 다시 난다.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g168.py): 모델 gave:gabe/g168 상태 ""(날아옴), "hang"(실에 매달림). 실 부품 gave:gabe/g168_thread 상태 "", "taut"
 * (+Z 로 길이 1, Java 가 두 사람 사이에 늘임). 소리 stitch(20), pull(24), snap(16). 글귀 cause_g168, cause_g168_pull, cause_g168_share.
 */
public final class G168 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.24, STAB = 6, SECOND_R = 6, MAX_GAP = 4, PULL_DMG = 8, SHARE = 0.5;
    static final int FOLLOW_MIN = 5, FOLLOW_MAX = 9, PER_PERSON = 4, MAX_THREADS = 12, PULL_AT = 3, PULL_WARN = 20, PULL_ROOT = 20,
        PULL_COOL = 160, STAB_CD = 20;
    /** 실 끝(사람 발 위 높이)과 바늘이 매달리는 높이(실 가운데 위). */
    static final double END_Y = 1.15, HANG_UP = 0.3;
    static final String HOOK = "G168";

    /** 지금 실을 걸고 매달린 바늘들(판 하나, 죽거나 치워진 것은 쓸 때 거름). */
    static final List<G168> HUNG = new ArrayList<>();
    /** 실 셋이 걸린 사람: 당기기 예고 시작 틱, 당긴 뒤 다시 당길 수 있는 틱. */
    static final Map<UUID, Long> PULL_AT_TICK = new HashMap<>(), PULL_COOL_UNTIL = new HashMap<>(), PULL_DONE = new HashMap<>();
    /** 시험 보기: 나눠 준 피해 횟수, 당긴 횟수(판 하나, toString 이 보임). */
    static int shared, pulls;

    enum Phase { FLY, HANG }

    private boolean follower, init;
    private Phase phase = Phase.FLY;
    /** 첫째로 찌른 사람, 둘째로 노리는 사람, 실의 두 끝. */
    private Player first, aim, a, b;
    private int readyAt;
    private Body.Part thread;
    private final Matrix4f mat = new Matrix4f();
    private boolean taut;

    public G168(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        baseSpeed = SPEED;
        flying = true;
        noPush = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g168", 1.0f, 0.8f);
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        game.players.addHurtHook(HOOK, (p, dealt, src, cause) -> share(game, p, dealt));
    }

    /** 감독이 낸 하나(첫 틱): 다섯에서 아홉을 더 데려옴(체력, 보상은 이 개체와 같게). */
    private void formFlock() {
        init = true;
        if (follower) return;
        int n = FOLLOW_MIN + game.random.nextInt(FOLLOW_MAX - FOLLOW_MIN + 1);
        for (int i = 0; i < n; i++) {
            if (!game.mobs.budgetFor(1)) break;
            G168 f = new G168(game, sp);
            f.follower = true;
            f.init = true;
            f.reward = reward;
            f.noReward = noReward;
            double ang = game.random.nextDouble() * Math.PI * 2, r = 1.2 + game.random.nextDouble() * 2.5;
            Vector at = pos.clone().add(new Vector(Math.cos(ang) * r, (game.random.nextDouble() - 0.5) * 2, Math.sin(ang) * r));
            if (game.mobs.place(f, at) == null) continue;
            f.maxHp = f.hp = maxHp;
            f.readyAt = game.random.nextInt(20);
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) formFlock();
        if (phase == Phase.HANG) hang();
        else fly();
    }

    /** 재봉틀처럼 위아래로 까딱이며 곧게 날아와 찌름. */
    private void fly() {
        state("");
        body.offset.set(0, (float) (Math.abs(Math.sin((age + id * 7) * 0.45)) * 0.18 - 0.09), 0);
        if (first != null && !standing(first)) first = null;
        Player p = aim != null && standing(aim) ? aim : null;
        if (p == null) {
            p = first == null ? nearest(400) : second(first);
            if (p != null && p != aim) {
                aim = p;
                mark(p);
            }
        }
        if (p == null) {
            follow(speed());
            face(pos.clone().add(vel));
            return;
        }
        Vector to = p.getLocation().toVector().add(new Vector(0, END_Y + 0.1, 0));
        seek(to, speed());
        face(to);
        if (age >= readyAt && touches(p, 0.15)) stab(p);
    }

    /** 첫째 곁 6 블록 안의 다른 서 있는 사람(없으면 첫째 말고 가장 가까운 사람, 그것도 없으면 첫째를 다시). */
    private Player second(Player one) {
        Vector at = one.getLocation().toVector();
        Player best = null, any = null;
        double bd = Double.MAX_VALUE, ad = Double.MAX_VALUE;
        for (Player q : game.mobs.standing()) {
            if (q == one) continue;
            double d = q.getLocation().toVector().distance(at);
            if (d <= SECOND_R && d < bd) {
                bd = d;
                best = q;
            }
            double dm = dist(q);
            if (dm < ad) {
                ad = dm;
                any = q;
            }
        }
        return best != null ? best : any != null ? any : one;
    }

    private void stab(Player p) {
        hit(p, STAB, "cause_g168");
        sound("stitch", Sound.ITEM_SHEARS_SNIP, 1.4f, 20);
        body.squash(0.85f, 0.85f, 1.25f, 2);
        readyAt = age + cd(STAB_CD);
        aim = null;
        if (first == null || first == p) {
            first = p;
            return;
        }
        // 둘째를 찔렀다: 실을 걸 수 있으면 두 사람을 잇고 가운데에 매달림, 못 걸면 둘째를 첫째로 삼아 계속 꿰어 다님
        if (canTie(first, p)) tie(first, p);
        else first = p;
    }

    private static int threadsOf(Player p) {
        int n = 0;
        for (G168 g : HUNG) if (!g.dead && !g.removed && (g.a == p || g.b == p)) n++;
        return n;
    }

    private static int live() {
        HUNG.removeIf(g -> g.dead || g.removed || g.phase != Phase.HANG);
        return HUNG.size();
    }

    private boolean canTie(Player x, Player y) {
        return x != y && standing(x) && standing(y) && live() < MAX_THREADS && threadsOf(x) < PER_PERSON && threadsOf(y) < PER_PERSON;
    }

    private void tie(Player x, Player y) {
        a = x;
        b = y;
        first = null;
        phase = Phase.HANG;
        HUNG.add(this);
        state("hang");
        body.offset.set(0, 0, 0);
        mark(x);
        if (thread == null) {
            thread = body.attach("gabe/g168_thread");
            if (thread != null) {
                thread.follow = false;
                if (thread.d != null) {
                    thread.d.setDisplayWidth(0f);
                    thread.d.setDisplayHeight(0f);
                }
            }
        }
        poseThread(0);
    }

    /** 실을 풂(바늘은 다시 날거나 죽음). */
    private void untie() {
        if (thread != null) {
            thread.remove();
            thread = null;
        }
        HUNG.remove(this);
        a = b = null;
        taut = false;
        phase = Phase.FLY;
        state("");
        mark(null);
        readyAt = age + cd(STAB_CD);
    }

    private static Vector end(Player p) {
        return p.getLocation().toVector().add(new Vector(0, END_Y, 0));
    }

    /** 실 한가운데에 매달려 흔들림. 4 블록 넘게 벌어지면 되끌림, 실 셋이 걸린 사람 쪽으로 당기기. */
    private void hang() {
        if (a == null || b == null || !standing(a) || !standing(b)) {
            sound("snap", Sound.ENTITY_ITEM_BREAK, 1.2f, 16);
            untie();
            return;
        }
        Vector ea = end(a), eb = end(b);
        Vector mid = ea.clone().add(eb).multiply(0.5);
        double sway = Math.sin((age + id * 5) * 0.2) * 0.12;
        Vector want = mid.clone().add(new Vector(sway, HANG_UP, 0));
        vel.copy(want.subtract(pos).multiply(0.5));
        face(pos.clone().add(new Vector(eb.getZ() - ea.getZ(), 0, ea.getX() - eb.getX())));
        // 되끌림: 두 사람이 4 블록 넘게 벌어지면 서로 쪽으로
        Vector ab = eb.clone().subtract(ea).setY(0);
        double d = ab.length();
        if (d > MAX_GAP && d > 1e-6) {
            double k = Math.min(0.5, 0.1 + 0.22 * (d - MAX_GAP));
            ab.multiply(1 / d);
            game.players.push(a, ab.clone().multiply(k));
            game.players.push(b, ab.clone().multiply(-k));
        }
        pullTick();
        if (every(2)) poseThread(2);
        if (every(30)) sound("stitch", Sound.ITEM_SHEARS_SNIP, 1.6f, 20);
    }

    /** 실 셋이 걸린 사람(바퀴통): 1초 예고(실이 팽팽하게 떪) 뒤 이어진 사람들을 그 사람 쪽으로 끌어 엉키게 함. */
    private void pullTick() {
        long now = game.now();
        Player hub = null;
        if (threadsOf(a) >= PULL_AT) hub = a;
        else if (threadsOf(b) >= PULL_AT) hub = b;
        if (hub == null) {
            setTaut(false);
            return;
        }
        UUID id = hub.getUniqueId();
        // 같은 틱에 먼저 당긴 실이 있으면 이 실도 마저 당김(바퀴통 사람 피해는 한 번)
        Long done = PULL_DONE.get(id);
        if (done != null && done == now) {
            yank(hub);
            return;
        }
        Long cool = PULL_COOL_UNTIL.get(id);
        if (cool != null && cool > now) {
            setTaut(false);
            return;
        }
        Long at = PULL_AT_TICK.get(id);
        if (at == null || now - at > PULL_WARN + 5) {
            PULL_AT_TICK.put(id, now);
            at = now;
            sound("pull", Sound.BLOCK_WOOL_PLACE, 0.6f, 24);
        }
        setTaut(true);
        if (now - at < PULL_WARN) return;
        yank(hub);
        // 바퀴통 사람은 한 번만
        PULL_DONE.put(id, now);
        PULL_COOL_UNTIL.put(id, now + cd(PULL_COOL));
        PULL_AT_TICK.remove(id);
        hit(hub, PULL_DMG, "cause_g168_pull");
        game.players.root(hub, PULL_ROOT, "g168_pull");
        sound("pull", Sound.ENTITY_ITEM_BREAK, 0.5f, 24);
    }

    /** 이 실의 다른 끝을 바퀴통 쪽으로 홱: 1초 엉켜 못 걷고 8. */
    private void yank(Player hub) {
        Player other = hub == a ? b : a;
        setTaut(false);
        if (other == null || !standing(other)) return;
        Vector to = hub.getLocation().toVector().subtract(other.getLocation().toVector()).setY(0);
        double dd = to.length();
        if (dd > 0.8) knock(other, to, Math.min(1.1, 0.25 + dd * 0.18), 0.25);
        hit(other, PULL_DMG, "cause_g168_pull");
        game.players.root(other, PULL_ROOT, "g168_pull");
        pulls++;
    }

    private void setTaut(boolean on) {
        if (taut == on) return;
        taut = on;
        if (thread != null) thread.state(on ? "taut" : "");
    }

    /** 실 부품: 몸(바늘) 자리에서 본 a 끝에서 b 끝까지 늘임(세계 축). */
    private void poseThread(int ticks) {
        if (thread == null || a == null || b == null) return;
        Vector ea = end(a), eb = end(b);
        Vector dir = eb.clone().subtract(ea);
        double len = dir.length();
        if (len < 1e-3) return;
        dir.multiply(1 / len);
        mat.identity().translate((float) (ea.getX() - pos.getX()), (float) (ea.getY() - pos.getY()), (float) (ea.getZ() - pos.getZ()))
            .rotate(new Quaternionf().rotationTo(0, 0, 1, (float) dir.getX(), (float) dir.getY(), (float) dir.getZ()))
            .scale(1f, 1f, (float) len);
        thread.pose(mat, ticks);
    }

    // ------------------------------------------------------------------ 피해 나누기(HURTHOOK)

    /** 사람 p 가 dealt 만큼 깎였다: 그 사람과 실로 이어진 사람마다 절반을 나눠 줌(실마다 따로). */
    static void share(Game game, Player p, double dealt) {
        if (HUNG.isEmpty() || dealt <= 0) return;
        for (int i = 0; i < HUNG.size(); i++) {
            G168 g = HUNG.get(i);
            if (g.dead || g.removed || g.phase != Phase.HANG) continue;
            Player other = g.a == p ? g.b : g.b == p ? g.a : null;
            if (other == null || other == p) continue;
            game.players.hurt(other, dealt * SHARE, null, "cause_g168_share");
            shared++;
        }
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (phase == Phase.HANG) sound("snap", Sound.ENTITY_ITEM_BREAK, 1.2f, 16);
        HUNG.remove(this);
        if (thread != null) {
            thread.remove();
            thread = null;
        }
        super.die(d);
    }

    @Override
    public void remove() {
        HUNG.remove(this);
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " " + phase + (a != null ? " a=" + a.getName() : "") + (b != null ? " b=" + b.getName() : "")
            + (first != null ? " first=" + first.getName() : "") + " threads=" + HUNG.size() + " shared=" + shared + " pulls=" + pulls;
    }
}
