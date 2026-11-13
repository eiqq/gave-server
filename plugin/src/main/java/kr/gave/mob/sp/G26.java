package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.mob.Swarm;

/**
 * G26 실뭉치 게이브(떼, 파생). 먹은 몹(겹얼굴 G27 등)이 죽으면 그 안에 갇힌 사람이 이 무리로 쏟아진다. 목말 탑(G25) 꼭대기를 잡아도
 * 이름 없는 무리 하나가 나온다.
 *
 * <p>도감: 바랜 분홍 흰 줄무늬 실타래(작은 얼굴 다섯이 엉킴) 셋이 바닥을 빠르게 기어 발에 감긴다. 감긴 수만큼 느려지고 약한 지속 피해,
 * 둘 넘게 감기면 화면 아래에 흰 실이 차오른다(덮개 g26_thread). 위에 먹힌 동료 이름이 흐린 회색으로 뜨고, 듣기(웅크리기)면 낮은 웅얼거림.
 * 한 방이면 실타래 하나가 죽는다(산탄, 범위로 쓸기). 무리를 다 잡으면 엔진이 그 동료를 상점 앞에서 부활시킨다(갇힘의 먹은 개체가 이 무리).
 *
 * <p>사람에게 거는 것: 느려짐은 5틱마다 12틱씩 이어 걸어(무리가 죽으면 0.6초 안에 끝남), 흰 실 덮개는 이 무리가 죽거나 치워질 때 바로 걷는다.
 * 실눈은 발에 감길 만큼 다가왔을 때(8블록 안)만 뜬다.
 *
 * <p>팩 계약(tools/gabe/sp/g26.py): 모델 {@code gave:gabe/g26}, 마리 상태 {@code "a"}, {@code "b"}, 덩어리 {@code "lod"}. 소리 wring(16),
 * murmur(8, 듣기), squash(16), die(24). 덮개 {@code g26_thread}. 원인 글귀 {@code cause_g26}.
 */
public final class G26 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final int MEMBERS = 3;
    static final double SPEED = 0.2;
    /** 이 수평 거리 안에서 발로 감겨 듦. 마리가 발에서 이 안이면 감긴 것. */
    static final double WRAP_START = 1.3, WRAP_R = 0.6;
    /** 이 거리 안으로 다가오면 그 사람을 찍음(실눈 뜸). */
    static final double MARK_R = 8;
    /** 감긴 마리 하나당 느려짐(Players 가 계열 합 50% 까지), 지속 피해, 피해 간격. */
    static final double SLOW_EACH = 0.15, DOT = 0.5;
    static final int DOT_EVERY = 20, COVER_T = 40;
    static final String COVER = "g26_thread";
    /** 폭발, 불에 함께 죽는 반경. 마리 사이 거리. */
    static final double SPLASH = 2.5, SPREAD = 0.42;

    private Swarm swarm;
    private boolean init;
    private G26Tag tag;
    private Player prey, covered;
    private boolean eyes;
    private float heading;
    private int nextWring, nextDot, nextName;

    public G26(Game game, Species sp) {
        super(game, sp);
        radius = 0.55;
        noPush = true;
        baseSpeed = SPEED;
        hover = 0.12;
    }

    @Override
    protected void build() {
        swarm = game.mobs.swarm(this, "gabe/g26", MEMBERS);
        swarm.memberRadius = 0.26;
        for (Swarm.Member m : swarm.members) {
            double a = Math.PI * 2 * m.index / MEMBERS;
            m.home.copy(new Vector(Math.sin(a) * SPREAD, 0, Math.cos(a) * SPREAD));
            m.state(m.index % 2 == 0 ? "a" : "b");
        }
        heading = yaw;
        swarm.formation(heading, 0);
        tag = new G26Tag(this, 0.9f);
    }

    @Override
    protected void spawned() {
        nextWring = age + game.random.nextInt(20);
    }

    private void init() {
        init = true;
        for (Swarm.Member m : swarm.members) m.hp = 1;
        maxHp = hp = swarm.alive();
    }

    // ------------------------------------------------------------------ 바깥에서(먹은 몹이 죽을 때)

    /**
     * 먹은 개체 eater 안에 갇힌 사람들을 이름 단 실뭉치 무리로 쏟는다(eater 의 die 에서 super.die 전에 부를 것). 갇힌 사람이 없거나 예산이
     * 차면 null(그때는 엔진이 eater 죽음에 그 사람들을 바로 부활시킴). 여럿이면 한 무리에 이름을 줄로 쌓는다(사람별 옮기기 API 가 없음).
     */
    public static G26 spill(Gabe eater) {
        if (eater == null || !Species.G26.implemented()) return null;
        Game game = eater.game;
        List<Player> vic = game.players.trappedIn(eater);
        if (vic.isEmpty()) return null;
        G26 s = skein(game, eater.pos);
        if (s == null) return null;
        game.players.transferTrapped(eater, s);
        s.tag.set(vic);
        s.nextName = s.age + 20;
        return s;
    }

    /** 이름 없는 실뭉치 무리 하나(보상 0). 예산이 차면 null. */
    public static G26 skein(Game game, Vector near) {
        if (!Species.G26.implemented()) return null;
        Vector at = near.clone();
        at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + 0.12);
        Gabe g = game.mobs.spawn(Species.G26, at, true);
        return g instanceof G26 s ? s : null;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) init();
        if (age >= nextName) {
            List<Player> vic = game.players.trappedIn(this);
            if (!vic.isEmpty() || !tag.text().isEmpty()) tag.set(vic);
            nextName = age + 20;
        }
        // 실뭉치는 먼 곳에서 죽은 개체 자리에도 생긴다(멀리서 잡힌 목말 탑 등). 전에는 32블록 안의 사람만 찾아서 그런 무리가
        // 제자리에서 꼬물대기만 하고 웨이브 끝을 막았다. 반경 제한 없이 찾아 흐름장을 따라 온다(2026-11-11).
        Player p = nearest(400);
        prey = p;
        if (p == null) {
            eyes(null);
            hold();
            wriggle();
            return;
        }
        Location fl = p.getLocation();
        Vector feet = new Vector(fl.getX(), pos.getY(), fl.getZ());
        double flat = Math.hypot(feet.getX() - pos.getX(), feet.getZ() - pos.getZ());
        eyes(flat <= MARK_R ? p : null);
        if (flat > WRAP_START) {
            follow(speed());
            double vl = Math.hypot(vel.getX(), vel.getZ());
            if (vl > 1e-6) {
                // 꿈틀꿈틀: 옆으로 흔들며
                double w = Math.sin((age + id * 7) * 0.5) * 0.5 * speed();
                vel.add(new Vector(-vel.getZ() / vl, 0, vel.getX() / vl).multiply(w));
                heading = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
                yaw = heading;
            }
            wriggle();
        } else {
            // 발에 감김: 닻은 발로, 마리는 발 둘레를 돌며 감음
            Vector d = feet.clone().subtract(pos).setY(0);
            double l = d.length();
            vel.copy(l < 1e-6 ? d : d.multiply(Math.min(0.3, l) / l));
            if (every(2)) {
                for (Swarm.Member m : swarm.members) {
                    if (m.dead) continue;
                    double a = Math.PI * 2 * m.index / MEMBERS + age * 0.09;
                    Vector off = feet.clone().subtract(pos).add(new Vector(Math.sin(a) * 0.3, 0, Math.cos(a) * 0.3)).setY(0);
                    m.moveTo(off, 2);
                    m.lookAt(chest(p), 2);
                }
            }
        }
        wrapEffects(p);
        if (age >= nextWring) {
            sound("wring", Sound.BLOCK_HONEY_BLOCK_SLIDE, 1.2f, 16);
            nextWring = age + 22 + game.random.nextInt(14);
        }
    }

    /** 대형 자리 둘레로 꼬물(4틱마다 조금씩 다른 자리, 방향). */
    private void wriggle() {
        if (!every(4)) return;
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            Vector off = Swarm.rotate(m.home, heading).add(new Vector(game.random.nextGaussian() * 0.06, 0, game.random.nextGaussian() * 0.06));
            m.moveTo(off, 4);
            m.face(heading + (float) game.random.nextGaussian() * 25f, 0, (float) game.random.nextGaussian() * 10f, 4);
        }
    }

    /** 감긴 마리 수만큼 느려짐, 지속 피해, 둘 넘게 감기면 흰 실 덮개. */
    private void wrapEffects(Player p) {
        if (!standing(p)) return;
        Location fl = p.getLocation();
        int n = 0;
        for (Swarm.Member m : swarm.members) {
            if (m.dead) continue;
            Vector mp = m.pos();
            if (Math.hypot(mp.getX() - fl.getX(), mp.getZ() - fl.getZ()) <= WRAP_R && Math.abs(mp.getY() - fl.getY()) < 1.0) n++;
        }
        if (n == 0) return;
        if (every(5)) game.players.slow(p, SLOW_EACH * n, 12, "g26");
        if (age >= nextDot) {
            hit(p, DOT * n, "cause_g26");
            nextDot = age + DOT_EVERY;
        }
        if (n >= 2 && game.players.cover(p, COVER, COVER_T)) covered = p;
    }

    /** 찍은 사람(실눈 뜸). null 이면 실눈으로 돌아감. */
    private void eyes(Player p) {
        if (p != target) mark(p);
        boolean on = p != null;
        if (on == eyes) return;
        eyes = on;
        for (Swarm.Member m : swarm.members) m.look.open = on;
    }

    /** 이 무리가 걸어 둔 흰 실 덮개를 걷음(죽거나 치워질 때). 다른 무리가 아직 감고 있으면 그쪽이 다음 틱에 다시 건다. */
    private void uncover() {
        Player p = covered;
        covered = null;
        if (p == null || !p.isOnline()) return;
        if (COVER.equals(game.players.get(p).cover)) game.players.uncover(p);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        return swarm.spheres();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (!init) init();
        DamageType t = d.type();
        if (t == DamageType.BITE) return false; // 실뭉치는 물어뜯겨도 실만 짓이겨짐
        double before = hp;
        int killed = 0;
        if (t == DamageType.EXPLOSION || t == DamageType.FIRE) {
            for (Swarm.Member m : swarm.within(d.at() != null ? d.at() : pos, SPLASH)) {
                kill(m);
                killed++;
            }
        } else {
            Swarm.Member m = swarm.nearest(d.at());
            if (m != null) {
                kill(m);
                killed++;
            }
        }
        hp = swarm.alive();
        if (killed > 0) sound("squash", Sound.BLOCK_HONEY_BLOCK_BREAK, 0.9f, 16);
        game.mobs.onHurt(this, d, before - hp);
        if (swarm.alive() == 0) die(d);
        return true;
    }

    private void kill(Swarm.Member m) {
        m.look.hurtUntil = game.worldTime() + 3;
        swarm.kill(m);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        if (tag != null) tag.remove();
        uncover();
        super.die(d);
    }

    @Override
    public void remove() {
        if (tag != null) tag.remove();
        uncover();
        super.remove();
    }

    @Override
    public String listenSound() {
        return game.players.trappedIn(this).isEmpty() ? "gave:gabe.g26.wring" : "gave:gabe.g26.murmur";
    }

    @Override
    public String toString() {
        return super.toString() + " alive=" + (swarm == null ? 0 : swarm.alive()) + (tag != null && !tag.text().isEmpty() ? " name=" + tag.text() : "");
    }
}
