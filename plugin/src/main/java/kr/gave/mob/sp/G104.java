package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Mobs;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.PState;

/**
 * G104 딱지 게이브(2막, 떼, 방해). 손바닥만 한 종이쪽 얼굴이 나뭇잎처럼 불규칙하게 팔랑이며 와서 사람 등에 찰싹 붙는다(붙을 때 3).
 *
 * <p>붙은 동안: 검은 깨알 얼굴 떼(부품 g104_dots)가 등을 뜯어 딱지 한 장마다 초당 6(세 장까지 겹침), 12블록 안 게이브가 그 사람만 노린다(FORCE).
 * 붙은 사람은 제 등의 딱지를 못 본다(그 사람에게서만 몸을 숨김), 화면 표시(hud.mark)와 찰싹, 사각사각 소리만. 딱지는 20초 뒤 삭는다.
 * 동료가 등 뒤에서 돕기로 짜내면(STICK 칸: 정화, 짜내기) 떨어진다. 떼어 낸 사람이 3초 안에 다른 게이브를 보고 돕기를 한 번 더 누르면(HELPHOOK)
 * 딱지가 그 게이브에 붙어 점 떼가 그쪽을 뜯는다.
 *
 * <p>팩 계약(tools/gabe/sp/g104.py): 모델 gabe/g104(상태 "" 팔랑임, "stuck"), 점 떼 부품 gabe/g104_dots. 소리 flutter, slap, gnaw. 글귀 cause_g104.
 *
 * <p>도감과 다른 점: 떼기는 엔진 짜내기(등 뒤 돕기 3초, 도감은 1초). 한 사람의 딱지 여럿은 붙음 칸 하나를 나눠 써서 짜내면 모두 떨어지고 20초도
 * 첫 딱지 기준으로 함께 삭는다. 다른 잠복이 이미 붙은 사람에게는 붙지 못하고 튕긴다(떼어 낼 길이 없어서). 지평선에서 흘러오는 줄은 딱지 뒤로
 * 3블록 흘러드는 깨알 줄로 줄였다.
 */
public final class G104 extends Gabe {

    static final String KEY = "G104", KIND = "g104";
    /** 팔랑이며 나는 빠르기(블록/틱), 날 때 바닥 위 높이. */
    static final double SPEED = 0.17, FLY_Y = 1.7;
    /** 붙는 거리(사람 몸통에서), 붙을 때 피해, 점 떼 피해(사람, 옮겨 붙은 게이브), 간격. */
    static final double STICK_R = 0.95, STICK_DMG = 3, GNAW = 6, GNAW_GABE = 10;
    static final int GNAW_EVERY = 20;
    /** 붙어 있는 틱, 떼어 낸 뒤 옮겨 붙일 수 있는 틱, 한 사람 최대 장 수, 강제 표적 반경. */
    static final int LIFE = 400, HOLD = 60, MAX_TAGS = 3;
    static final double FORCE_R = 12;

    enum Phase { FLY, BOUNCE, STUCK, HELD, ON_GABE }

    private Phase phase = Phase.FLY;
    private int phaseAt, nextJit, nextFlap, nextGnaw;
    private final Vector jit = new Vector();
    /** 붙은 사람, 떼어 낸 사람, 옮겨 붙은 게이브, 피할 사람(튕긴 뒤 잠깐). */
    Player victim, holder;
    private Gabe host;
    private Player shun;
    private int shunUntil;
    private long lifeUntil;
    private Body.Part dots;

    public G104(Game game, Species sp) {
        super(game, sp);
        radius = 0.28;
        flying = true;
        noPush = true;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g104", 0.8f, 0.8f);
    }

    @Override
    protected void spawned() {
        nextFlap = age + game.random.nextInt(30);
        body.look.phase = game.random.nextInt(12);
        if (game.director.cageSpawning()) return;
        Table.of(game);
        game.players.addHelpHook(KEY, (helper, mate) -> Table.of(game).help(helper));
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
        switch (phase) {
            case FLY -> fly();
            case BOUNCE -> bounce();
            case STUCK -> stuck();
            case HELD -> held();
            case ON_GABE -> onGabe();
        }
    }

    private Player pickPrey() {
        Player f = game.mobs.forcedFor(this);
        if (f != null && f != avoid() && Table.of(game).count(f) < MAX_TAGS) return f;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            if (p == avoid() || Table.of(game).count(p) >= MAX_TAGS) continue;
            double d = dist(p);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private Player avoid() {
        return age < shunUntil ? shun : null;
    }

    /** 나뭇잎처럼 팔랑이며 사람 등 쪽으로. */
    private void fly() {
        state("");
        Player p = pickPrey();
        if (age >= nextFlap) {
            sound("flutter", Sound.ITEM_BOOK_PAGE_TURN, 1.4f, 16);
            nextFlap = age + 25 + game.random.nextInt(30);
        }
        if (p == null) {
            follow(speed());
            return;
        }
        Vector back = backPoint(p, 0);
        double h = Math.hypot(back.getX() - pos.getX(), back.getZ() - pos.getZ());
        Vector to = back.clone();
        if (h > 6) to.setY(floor() + FLY_Y + Math.sin((age + id) * 0.15) * 0.4);
        else if (h > 1.2) {
            // 앞에서 오면 옆으로 돌아 등 쪽으로
            Location l = p.getLocation();
            Vector f = l.getDirection().setY(0);
            if (f.lengthSquared() > 1e-6) {
                f.normalize();
                Vector rel = pos.clone().subtract(l.toVector()).setY(0);
                if (rel.dot(f) > 0) {
                    Vector side = new Vector(-f.getZ(), 0, f.getX());
                    if (rel.dot(side) < 0) side.multiply(-1);
                    to = l.toVector().add(side.multiply(1.6)).setY(back.getY());
                }
            }
        }
        flutter(to, speed());
        if (h < 8) mark(p);
        if (pos.distanceSquared(chest(p)) <= STICK_R * STICK_R) tryStick(p);
    }

    /** 바람에 날리듯: 목표 쪽 + 6틱마다 바뀌는 흔들림 + 출렁. */
    private void flutter(Vector to, double v) {
        if (age >= nextJit) {
            nextJit = age + 4 + game.random.nextInt(5);
            jit.setX(game.random.nextGaussian()).setY(game.random.nextGaussian() * 0.6).setZ(game.random.nextGaussian());
        }
        Vector d = to.clone().subtract(pos);
        double l = d.length();
        if (l > 1e-6) d.multiply(Math.min(v, l) / l);
        double near = Math.min(1, l / 2.5);
        vel.copy(d).add(jit.clone().multiply(v * 0.8 * near)).add(new Vector(0, Math.sin((age + id) * 0.4) * v * 0.4 * near, 0));
        face(pos.clone().add(new Vector(d.getX(), 0, d.getZ())));
    }

    /** 사람 등 위쪽(딱지 i 번째 자리, 세 장이면 옆으로 나란히). */
    private Vector backPoint(Player p, int i) {
        Location l = p.getLocation();
        double r = Math.toRadians(l.getYaw());
        double fx = -Math.sin(r), fz = Math.cos(r);
        double side = i == 0 ? 0 : i == 1 ? 0.17 : -0.17;
        return new Vector(l.getX() - fx * 0.36 + fz * side, l.getY() + 1.2 + (i == 0 ? 0.08 : -0.05), l.getZ() - fz * 0.36 - fx * side);
    }

    private void tryStick(Player p) {
        Table t = Table.of(game);
        List<G104> on = t.on(p);
        if (on.size() >= MAX_TAGS || !standing(p)) {
            bounceOff(p);
            return;
        }
        if (on.isEmpty()) {
            PState s = game.players.get(p);
            if (s.latent != null || !game.players.stick(p, KIND, LIFE, q -> Table.of(game).pop(q), q -> Table.of(game).cured(q))) {
                hit(p, STICK_DMG, "cause_g104");
                bounceOff(p);
                return;
            }
        }
        on.add(this);
        victim = p;
        lifeUntil = game.now() + LIFE;
        noHold = true;
        untargetable = true;
        mark(p);
        state("stuck");
        body.hide(p, true);
        if (dots == null) dots = body.attach("gabe/g104_dots");
        hit(p, STICK_DMG, "cause_g104");
        sound("slap", Sound.ENTITY_SLIME_ATTACK, 1.6f, 16);
        nextGnaw = age + cd(GNAW_EVERY);
        phase(Phase.STUCK);
    }

    private void bounceOff(Player p) {
        shun = p;
        shunUntil = age + 100;
        mark(null);
        phase(Phase.BOUNCE);
    }

    private void bounce() {
        state("");
        Vector up = pos.clone().add(new Vector(Math.sin(id) * 3, 0, Math.cos(id) * 3));
        up.setY(floor() + FLY_Y + 1.2);
        flutter(up, speed());
        if (inPhase() >= cd(30)) phase(Phase.FLY);
    }

    /** 사람 등에 붙어 따라감, 점 떼가 뜯음, 둘레 게이브가 이 사람만 노림. */
    private void stuck() {
        if (victim == null || !standing(victim)) {
            decay(null);
            return;
        }
        List<G104> on = Table.of(game).on(victim);
        int i = Math.max(0, on.indexOf(this));
        Vector want = backPoint(victim, i);
        vel.copy(want.subtract(pos));
        yaw = victim.getLocation().getYaw() + 180f;
        pitch = 0;
        if (age % 20 == 0) {
            game.mobs.force(this, victim, FORCE_R, 30);
            game.hud.mark(victim, "g104", 30);
        }
        if (age >= nextGnaw) {
            nextGnaw = age + cd(GNAW_EVERY);
            hit(victim, GNAW, "cause_g104");
            if (i == 0) soundFor(victim, "gnaw", Sound.ENTITY_SILVERFISH_STEP, 0.7f);
        }
    }

    /** 떼어 냄: 떼어 낸 사람 손에 3초 들려 있다(그동안 게이브를 보고 돕기를 한 번 더 누르면 옮겨 붙음). */
    void hold(Player helper) {
        unstickBody();
        holder = helper;
        noHold = true;
        untargetable = true;
        state("");
        phase(Phase.HELD);
    }

    private void held() {
        if (holder == null || !holder.isOnline() || inPhase() >= HOLD) {
            decay(holder);
            return;
        }
        Location e = holder.getEyeLocation();
        Vector at = e.toVector().add(e.getDirection().multiply(0.7)).add(new Vector(0, -0.35, 0));
        vel.copy(at.subtract(pos));
        yaw = e.getYaw() + 180f;
    }

    /** 다른 게이브에 옮겨 붙음: 점 떼가 그 게이브를 뜯는다(떼어 낸 사람 몫). */
    void stickTo(Gabe g) {
        host = g;
        lifeUntil = Math.max(lifeUntil, game.now() + LIFE / 2);
        state("stuck");
        if (dots == null) dots = body.attach("gabe/g104_dots");
        sound("slap", Sound.ENTITY_SLIME_ATTACK, 1.6f, 16);
        nextGnaw = age + 10;
        phase(Phase.ON_GABE);
    }

    private void onGabe() {
        if (host == null || host.dead || game.now() >= lifeUntil) {
            decay(holder);
            return;
        }
        Vector want = host.pos.clone().add(new Vector(0, Math.max(0.3, host.radius * 0.8), 0));
        vel.copy(want.subtract(pos));
        if (age >= nextGnaw) {
            nextGnaw = age + cd(GNAW_EVERY);
            game.mobs.damage(host, new Damage(GNAW_GABE, DamageType.OTHER, holder != null && holder.isOnline() ? holder : null, host.pos.clone(), null, KIND, 0));
            sound("gnaw", Sound.ENTITY_SILVERFISH_STEP, 1.4f, 12);
        }
    }

    /** 몸을 사람 등에서 뗌(숨김 풂, 점 떼 치움, 강제 표적 뗌). 칸 정리는 Table 이. */
    private void unstickBody() {
        if (victim != null) {
            if (body != null) body.hide(victim, false);
            game.mobs.unforce(this);
        }
        if (dots != null) {
            dots.remove();
            dots = null;
        }
        victim = null;
        mark(null);
    }

    /** 삭음, 떨어짐: credit 이 있으면 그 사람이 잡은 것으로(보상), 없으면 보상 없이. */
    void decay(Player credit) {
        if (dead) return;
        Table.of(game).release(this);
        unstickBody();
        untargetable = false;
        if (credit != null && credit.isOnline()) {
            game.mobs.damage(this, new Damage(hp + 1, DamageType.OTHER, credit, pos.clone(), null, KIND, 0));
            if (!dead) die(Damage.of(hp + 1, DamageType.OTHER));
        } else {
            noReward = true;
            die(Damage.of(hp + 1, DamageType.OTHER));
        }
    }

    @Override
    public void die(Damage d) {
        if (!dead) {
            Table.of(game).release(this);
            unstickBody();
        }
        super.die(d);
    }

    @Override
    public void remove() {
        if (!dead) {
            Table.of(game).release(this);
            unstickBody();
        }
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (victim != null ? " on=" + victim.getName() : "") + (host != null ? " host=" + host.sp.key() : "");
    }

    // ------------------------------------------------------------------ 판 갈고리: 사람마다 붙은 딱지

    /** 사람마다 붙은 딱지(FIELDTICK 키 "G104": 판이 바뀌면 비움). 붙음 칸 하나를 딱지 여럿이 나눠 쓴다. */
    static final class Table implements Mobs.Field {
        final Game game;
        final Map<UUID, List<G104>> on = new HashMap<>();
        boolean quiet;

        Table(Game game) {
            this.game = game;
        }

        static Table of(Game game) {
            if (game.mobs.field(KEY) instanceof Table t) return t;
            Table t = new Table(game);
            game.mobs.field(KEY, t);
            return t;
        }

        List<G104> on(Player p) {
            return on.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>(3));
        }

        int count(Player p) {
            List<G104> l = on.get(p.getUniqueId());
            return l == null ? 0 : l.size();
        }

        /** 딱지 하나가 사람에게서 빠짐: 마지막이면 붙음 칸도 비움(조용히). */
        void release(G104 g) {
            Player p = g.victim;
            if (p == null) return;
            List<G104> l = on.get(p.getUniqueId());
            if (l == null) return;
            l.remove(g);
            if (l.isEmpty()) {
                on.remove(p.getUniqueId());
                PState s = game.players.get(p);
                if (KIND.equals(s.latent)) {
                    quiet = true;
                    try {
                        game.players.cure(p);
                    } finally {
                        quiet = false;
                    }
                }
            }
        }

        /** 20초 지나 삭음(붙음 칸이 터짐). */
        void pop(Player p) {
            List<G104> l = on.remove(p.getUniqueId());
            if (l == null) return;
            for (G104 g : new ArrayList<>(l)) {
                g.victim = p;
                g.unstickBody();
                g.noReward = true;
                g.die(Damage.of(g.hp + 1, DamageType.OTHER));
            }
        }

        /** 정화나 짜내기로 떨어짐: 곁의 동료(짜낸 사람) 손에 들림, 없으면 그 자리에서 찢어짐(정화한 사람 몫). */
        void cured(Player p) {
            if (quiet) return;
            List<G104> l = on.remove(p.getUniqueId());
            if (l == null) return;
            Player helper = null;
            double bd = 3.5 * 3.5;
            Location pl = p.getLocation();
            for (Player q : game.mobs.standing()) {
                if (q == p) continue;
                double d = q.getLocation().distanceSquared(pl);
                if (d < bd) {
                    bd = d;
                    helper = q;
                }
            }
            for (G104 g : new ArrayList<>(l)) {
                if (g.dead) continue;
                if (helper != null) g.hold(helper);
                else {
                    g.victim = p;
                    g.unstickBody();
                    g.decay(p);
                }
            }
        }

        /** 돕기 갈고리: 들고 있는 딱지가 있고 게이브를 보고 있으면 그 게이브에 붙인다. */
        boolean help(Player helper) {
            G104 tag = null;
            for (Gabe g : game.mobs.all()) {
                if (g instanceof G104 t && !t.dead && t.phase == Phase.HELD && t.holder == helper) {
                    tag = t;
                    break;
                }
            }
            if (tag == null) return false;
            Gabe target = game.mobs.looked(helper, 10);
            if (target == null || target instanceof G104 || target.dead || target.sp.is(Role.BOSS)) return false;
            tag.stickTo(target);
            return true;
        }

        @Override
        public boolean tick(long now) {
            return true;
        }

        @Override
        public void reset() {
            on.clear();
        }
    }
}
