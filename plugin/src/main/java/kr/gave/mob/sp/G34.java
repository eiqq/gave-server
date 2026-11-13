package kr.gave.mob.sp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Band;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.Life;
import kr.gave.player.PState;

/**
 * G34 딱딱 씹는 게이브(정예, 돌격, 먹는 종). 1.2 블록 얼굴이 눈 아래에서 가로로 갈라져 위아래가 캐스터네츠처럼 맞부딪히며 통통 튀어 온다
 * (12틱에 한 번 '딱'). 배에서 '꼬르륵'이 난다.
 *
 * <p>도감: 12블록 안에서 꼬르륵을 3초(모은 시간, 조준점 아래 게이지) 들은 사람 하나를 찍는다(실눈 뜸, 그 사람 조준점 옆에 이 종 아이콘).
 * 찍은 사람만 끝까지 쫓고, 가는 길 2블록 안에 다른 종 게이브가 있으면 멈춰 3초 씹어 먹는다(먹힌 개체는 보상 없이 사라짐, 막대기 +1 최대 여섯,
 * 체력 20% 회복). 여섯째 막대기가 돋으면 마지막에 먹은 종의 성질 하나를 얻고 그 막대기 뿌리에 색 띠가 감긴다: 방패, 튼튼이를 먹었으면 앞면 막
 * (정면 탄 40%), 혹 터지는 종이나 원거리 종이면 터짐(죽을 때 3블록 6 피해), 그 밖은 빠르기 1.35배. 표적에게는 입을 크게 벌리고 달려들어
 * 물기 4 + (막대기 - 2) 피해, 문 채 0.5초마다 1.5 피해로 씹는다(붙잡힘: 본인 연타 12번, 동료 돕기로 떼어짐, 그 뒤 2초 멍함). 이 종에게 쓰러진
 * 사람은 갇힌다(Players.trap). 죽으면 갇힌 사람들이 이름 단 실뭉치(G26)로 쏟아진다. 벌어진 틈(먹거나 씹거나 달려드는 동안의 갈라진 줄)을
 * 맞히면 피해 두 배.
 *
 * <p>도감과 다른 점: 디스플레이 하나(위아래 맞부딪힘은 자세 넷으로 구움), 소리는 채소 씹기 녹음 대신 합성. 변이 2 막대기는 끔(막대기 수 =
 * 먹은 수).
 *
 * <p>팩 계약(tools/gabe/sp/g34.py): 모델 {@code gave:gabe/g34} 상태 {@code ""}, {@code "chew"}, {@code "gape"}, 부위 {@code rod3 .. rod6},
 * {@code t_burst}, {@code t_shield}, {@code t_swift}. 소리 growl(24), crunch(24), clack(16), gulp(16). 원인 글귀 {@code cause_g34}.
 */
public final class G34 extends Gabe implements Detachable {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.13, HOVER = 1.3, HOP = 0.45; // 2026-11-12 거대화(턱 1.6 배)
    /** 딱딱 한 바퀴(틱, 모델 주기와 같음). */
    static final int BEAT = 12;
    /** 꼬르륵이 들리는 반경, 찍히기까지 듣는 틱(3초), 꼬르륵 간격. */
    static final double HEAR_R = 12;
    static final int HEAR_T = 60, GROWL_EVERY = 46;
    /** 달려드는 거리, 빠르기, 틱. */
    static final double LUNGE_R = 2.9, LUNGE_V = 0.42;
    static final int LUNGE_T = 9;
    /** 물기 피해(막대기 둘일 때), 씹는 피해와 간격, 본인 연타 수, 놓친 뒤 멍한 틱. */
    static final double BITE = 4, CHEW_DMG = 2;
    static final int CHEW_EVERY = 10, ESCAPE = 12, REST = 40, REST_SHORT = 20;
    /** 먹기: 닿는 거리(가운데끼리), 씹는 틱(3초), 다음 먹기까지, 막대기 상한, 회복 비율, 먹을 수 있는 상대 최대 체력. */
    static final double EAT_R = 2.4, EAT_HEAL = 0.2, EAT_MAX_HP = 320;
    static final int EAT_T = 60, EAT_COOL = 40, MAX_RODS = 6;
    /** 벌어진 틈 배율과 높이 폭(갈라진 줄에서), 앞면 막 배율, 빠르기 배율, 터짐 반경과 피해. */
    static final double GAP_MUL = 2.0, GAP_BAND = 0.42, SPLIT_Y = -0.24, SHIELD_MUL = 0.4, SWIFT_MUL = 1.35, BURST_R = 3, BURST_DMG = 6;

    enum Phase { ROAM, HUNT, LUNGE, CHEW, EAT, REST }

    enum Trait { NONE, BURST, SHIELD, SWIFT }

    private Phase phase = Phase.ROAM;
    private int phaseAt, restFor, readyAt, nextGrowl, nextEat, nextChew, nextCrunch;
    private Player prey, held;
    private Gabe meal;
    private int rods = 2, ate;
    private Trait trait = Trait.NONE;
    private final Vector lungeDir = new Vector(0, 0, 1);
    private final Map<UUID, int[]> heard = new HashMap<>();
    private final Predicate<Gabe> edible = this::edible;

    public G34(Game game, Species sp) {
        super(game, sp);
        radius = 0.95;
        hover = HOVER;
        baseSpeed = SPEED;
        noBite = true; // 머리가 닿으면 물어뜯지 않고 씹어 먹음(종이 직접)
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g34", 5.0f, 3.4f);
        // 딱 소리와 닫히는 자세를 맞추려고 위상 0
        body.look.phase = 0;
    }

    @Override
    protected void spawned() {
        nextGrowl = age + 10 + game.random.nextInt(GROWL_EVERY);
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
        pitch = 0;
        switch (phase) {
            case ROAM -> roam();
            case HUNT -> hunt();
            case LUNGE -> lunge();
            case CHEW -> chew();
            case EAT -> eat();
            case REST -> resting();
        }
    }

    /** 통통 튐: 12틱 한 바퀴, 바닥에 닿는 순간(위아래가 닫히는 자세)에 '딱'. */
    private void hop() {
        long wt = game.worldTime();
        hover = HOVER + HOP * Math.sin(Math.PI * (wt % BEAT) / BEAT);
        if (wt % BEAT == 0) sound("clack", Sound.BLOCK_WOOD_HIT, 1.2f, 16);
    }

    private void growl() {
        if (age < nextGrowl) return;
        sound("growl", Sound.ENTITY_PLAYER_BURP, 0.5f, 24);
        nextGrowl = age + GROWL_EVERY + game.random.nextInt(12);
    }

    /** 아직 아무도 안 찍음: 흐름장을 따라 오며 꼬르륵. 12블록 안에서 3초 들은 사람을 찍는다. */
    private void roam() {
        state("");
        hop();
        follow(speed() * 0.85);
        Player near = nearest(48);
        if (near != null) {
            face(chest(near));
            pitch = 0;
        }
        growl();
        if (tryEat()) return;
        Player pick = null;
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            boolean in = dist(p) <= HEAR_R;
            int[] h = heard.get(p.getUniqueId());
            if (h == null) {
                if (!in) continue;
                h = new int[1];
                heard.put(p.getUniqueId(), h);
            }
            if (in) h[0]++;
            else h[0] = Math.max(0, h[0] - 2);
            if (h[0] > 0 && every(2)) game.hud.meter(p, "g34", Math.min(1.0, h[0] / (double) HEAR_T), 8);
            if (h[0] >= HEAR_T && pick == null) pick = p;
        }
        if (pick != null) choose(pick);
    }

    /** 찍음: 실눈을 뜨고 그 사람 화면에 표시. 이제 이 사람만 쫓는다. */
    private void choose(Player p) {
        prey = p;
        mark(p);
        heard.clear();
        game.hud.mark(p, "g34", 120);
        sound("growl", Sound.ENTITY_PLAYER_BURP, 0.5f, 24);
        nextGrowl = age + GROWL_EVERY;
        phase(Phase.HUNT);
    }

    /** 찍은 사람만 곧장 쫓음. 가는 길에 다른 종이 있으면 멈춰 씹고, 2.4블록 안이면 입을 벌리고 달려든다. */
    private void hunt() {
        if (!standing(prey)) {
            lose();
            return;
        }
        state("");
        hop();
        vel.copy(toward(prey).multiply(speed()));
        if (wallAhead(0.7)) follow(speed());
        face(chest(prey));
        pitch = 0;
        growl();
        if (every(20)) game.hud.mark(prey, "g34", 30);
        if (tryEat()) return;
        if (age >= readyAt && dist(prey) <= LUNGE_R) {
            lungeDir.copy(toward(prey));
            hover = HOVER;
            state("gape");
            phase(Phase.LUNGE);
        }
    }

    private void lose() {
        mark(null);
        prey = null;
        state("");
        phase(Phase.ROAM);
    }

    /** 입을 크게 벌리고 달려듦(벌어진 틈이 약점). 닿으면 묾. */
    private void lunge() {
        hover = HOVER;
        dash(lungeDir, LUNGE_V * speed() / baseSpeed);
        face(pos.clone().add(lungeDir));
        pitch = 0;
        if (standing(prey) && touches(prey, 0.2)) {
            bite(prey);
            return;
        }
        if (inPhase() >= LUNGE_T || wallAhead(0.6)) rest(cd(REST_SHORT));
    }

    private void bite(Player p) {
        hit(p, BITE + (rods - 2), "cause_g34");
        sound("clack", Sound.BLOCK_WOOD_HIT, 1.0f, 16);
        if (fed(p)) return;
        if (grab(p, ESCAPE)) {
            held = p;
            noPush = true;
            nextChew = age + CHEW_EVERY;
            nextCrunch = age;
            state("chew");
            phase(Phase.CHEW);
        } else rest(cd(REST_SHORT));
    }

    /** 이 종에게 쓰러진 사람은 갇힌다. 그 사람이 표적이었으면 다음 사람을 다시 꼬르륵으로 고른다. 쓰러졌으면 true. */
    private boolean fed(Player p) {
        PState s = game.players.get(p);
        if (s.standing()) return false;
        if (s.life == Life.DOWNED) {
            game.players.trap(p, this);
            sound("gulp", Sound.ENTITY_GENERIC_DRINK, 0.6f, 16);
            body.squash(1.2f, 1.15f, 1.2f, 3);
        }
        if (p == held) held = null;
        if (p == prey) {
            mark(null);
            prey = null;
        }
        rest(cd(REST));
        return true;
    }

    /** 문 채 씹음: 사람 몸통 앞에 붙어 0.5초마다 피해. 붙잡힘이 풀리면(연타, 동료 돕기) 멍하니 쉼. */
    private void chew() {
        Player q = held;
        if (q == null || !q.isOnline() || game.players.grabbedBy(q) != this) {
            held = null;
            rest(cd(REST));
            return;
        }
        hover = HOVER;
        Vector c = chest(q);
        Vector out = pos.clone().subtract(c).setY(0);
        if (out.lengthSquared() < 1e-4) out = toward(q).multiply(-1);
        out.normalize();
        Vector d = c.clone().add(out.multiply(0.75)).subtract(pos).setY(0);
        vel.copy(d.length() > 0.4 ? d.normalize().multiply(0.4) : d);
        face(c);
        pitch = 0;
        if (age >= nextCrunch) {
            sound("crunch", Sound.ENTITY_GENERIC_EAT, 0.7f, 24);
            nextCrunch = age + 14;
        }
        if (age >= nextChew) {
            nextChew = age + CHEW_EVERY;
            hit(q, CHEW_DMG, "cause_g34");
            fed(q);
        }
    }

    // ------------------------------------------------------------------ 다른 게이브 먹기

    private boolean edible(Gabe x) {
        if (x == this || x.dead || x.sp == Species.G34 || x.disguised || x.untargetable || x.noRules || x.body == null) return false;
        if (x.sp.spawn == Species.Spawn.PLACED || x.sp.spawn == Species.Spawn.DERIVED) return false;
        if (x.sp.is(Role.SWARM) || x.sp.band == Band.SKY || x.flying || Body.EMPTY.equals(x.body.model)) return false;
        if (x.maxHp > EAT_MAX_HP || Math.abs(x.pos.getY() - pos.getY()) > 2.0 || game.director.inCage(x)) return false;
        return true;
    }

    /** 2블록 안 다른 종을 물어 씹기 시작. 사람을 붙잡거나 가둔 개체는 건드리지 않는다. */
    private boolean tryEat() {
        if (age < nextEat || !every(5)) return false;
        Gabe o = game.mobs.nearest(pos, EAT_R, edible);
        if (o == null) return false;
        if (!game.players.trappedIn(o).isEmpty()) return false;
        for (Player p : game.players.grabbed()) if (game.players.grabbedBy(p) == o) return false;
        meal = o;
        hold();
        hover = HOVER;
        state("chew");
        game.mobs.lure(o, pos.clone(), 12);
        nextCrunch = age;
        phase(Phase.EAT);
        return true;
    }

    /** 3초 씹어 먹음(그동안 멈춤: 몰아 쏠 틈). 먹이는 끝까지 붙들려 있다가 사라진다. */
    private void eat() {
        Gabe o = meal;
        hold();
        hover = HOVER;
        if (o == null || o.dead || o.removed) {
            meal = null;
            nextEat = age + EAT_COOL;
            back();
            return;
        }
        face(o.pos);
        pitch = 0;
        if (age >= nextCrunch) {
            sound("crunch", Sound.ENTITY_GENERIC_EAT, 0.7f, 24);
            nextCrunch = age + 14;
        }
        if (inPhase() % 10 == 0) game.mobs.lure(o, pos.clone(), 12);
        if (inPhase() < EAT_T) return;
        if (o.pos.distance(pos) <= EAT_R + 1.5) devour(o);
        meal = null;
        nextEat = age + EAT_COOL;
        back();
    }

    private void devour(Gabe o) {
        Species what = o.sp;
        o.noReward = true;
        o.reward = 0;
        o.leaveCorpse = false;
        try {
            o.die(new Damage(Math.max(1, o.hp), DamageType.OTHER, null, o.pos.clone(), null, "eaten", 0));
        } catch (RuntimeException e) {
            o.remove();
        }
        ate++;
        if (rods < MAX_RODS) {
            rods++;
            body.look.on.add("rod" + rods);
            if (rods == MAX_RODS) gain(what);
        }
        hp = Math.min(maxHp, hp + maxHp * EAT_HEAL);
        sound("gulp", Sound.ENTITY_GENERIC_DRINK, 0.6f, 16);
        body.squash(1.2f, 1.2f, 1.2f, 3);
    }

    /** 여섯째 막대기: 마지막에 먹은 종의 성질 하나. */
    private void gain(Species what) {
        if (what.is(Role.SHIELD) || what.is(Role.TANK)) {
            trait = Trait.SHIELD;
            body.look.on.add("t_shield");
        } else if (what == Species.G05 || what.is(Role.RANGED)) {
            trait = Trait.BURST;
            body.look.on.add("t_burst");
        } else {
            trait = Trait.SWIFT;
            body.look.on.add("t_swift");
            baseSpeed *= SWIFT_MUL;
        }
    }

    // ------------------------------------------------------------------ 쉼

    private void rest(int ticks) {
        restFor = ticks;
        noPush = false;
        state("");
        phase(Phase.REST);
    }

    private void resting() {
        hover = HOVER;
        vel.multiply(0.6);
        if (inPhase() == 3) body.squash(1f, 1f, 1f, 4);
        if (inPhase() >= restFor) {
            readyAt = age + 10;
            back();
        }
    }

    private void back() {
        state("");
        noPush = false;
        if (prey != null && standing(prey)) phase(Phase.HUNT);
        else {
            if (prey != null) {
                mark(null);
                prey = null;
            }
            phase(Phase.ROAM);
        }
    }

    // ------------------------------------------------------------------ 떼어짐, 맞음, 죽음

    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != held) return;
        held = null;
        if (dead) return;
        body.squash(0.85f, 1.2f, 0.85f, 2);
        rest(cd(REST));
    }

    private Vector forward() {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        double k = 1.0;
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER)) {
            boolean open = phase == Phase.EAT || phase == Phase.CHEW || phase == Phase.LUNGE;
            if (open && Math.abs(d.at().getY() - (pos.getY() + SPLIT_Y + 0.12)) <= GAP_BAND) k = GAP_MUL;
            else if (trait == Trait.SHIELD && d.dir() != null && d.dir().clone().setY(0).dot(forward()) < -0.3) k = SHIELD_MUL;
        }
        return super.damage(k == 1.0 ? d : d.times(k));
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        Player q = held;
        held = null;
        if (q != null && game.players.grabbedBy(q) == this) game.players.release(q);
        // 감독의 웨이브 끝 정리, 시험 몹죽이기(쏜 사람 없는 OTHER)는 죽음 효과 없이: 갇힌 사람은 엔진이 바로 부활시킴
        boolean cleanup = d == null || d.type() == DamageType.OTHER && d.by() == null;
        if (!cleanup) {
            G26.spill(this);
            if (trait == Trait.BURST) burst();
        }
        super.die(d);
    }

    /** 터짐 성질: 죽을 때 3블록 안 사람에게 6 피해. */
    private void burst() {
        game.shotFx.pop(loc());
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            if (dist(p) <= BURST_R) hit(p, BURST_DMG, "cause_g34");
        }
    }

    @Override
    public void remove() {
        Player q = held;
        held = null;
        if (q != null && game.players.grabbedBy(q) == this) game.players.release(q);
        super.remove();
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    /** 시험용: 막대기 수를 바로 늘림(여섯이 되면 what 의 성질). */
    public void grow(int n, Species what) {
        while (rods < Math.min(MAX_RODS, n)) {
            rods++;
            body.look.on.add("rod" + rods);
            if (rods == MAX_RODS) gain(what == null ? Species.G01 : what);
        }
    }

    @Override
    public String toString() {
        int top = 0;
        for (int[] h : heard.values()) top = Math.max(top, h[0]);
        return super.toString() + " ph=" + phase + " rods=" + rods + " ate=" + ate + " trait=" + trait + " heard=" + top
                + (prey != null ? " prey=" + prey.getName() : "") + (held != null ? " held=" + held.getName() : "")
                + (meal != null ? " meal=" + meal.sp.name() : "") + " trapped=" + game.players.trappedIn(this).size();
    }
}
