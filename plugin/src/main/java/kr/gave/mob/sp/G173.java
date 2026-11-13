package kr.gave.mob.sp;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G173 외투 게이브(함정, 돌격). 멀리서는 외투 입은 사람으로 보이는 것이 곁에 나란히 섰다가, 얼굴 박힌 날개를 펼쳐 감싸 문다.
 *
 * <p>흉내(disguised): 사람 걸음 빠르기(0.13)로 가장 가까운 사람의 오른쪽 곁 1.5블록 자리로 걸어 와 같은 쪽을 보고 나란히 선다(상태 coat, 천 스치는
 * 소리). 흘린 탄은 지나가고, 0.5초 겨눈 사격, 폭발, 불, 조명탄과 정화가 정체를 드러낸다(접힌 동안 받는 피해 x2). 곁에 선 지 2초(박자 배율) 뒤 외투를
 * 확 펼쳐(상태 "", 눈 뜸) 그 사람을 감싸 문다: 22 피해와 붙잡힘(연타 6번), 감싼 동안 0.5초마다 6, 1.5초 뒤 놓음. 감싼 동안 등 쪽에서 온 탄은 날개가
 * 절반 막고, 가슴 얼굴에 맞은 탄은 세 배로 쳐서 받은 피해가 체력의 12% 를 넘으면 놓친다(동료 사격으로 풀림).
 *
 * <p>들키면 그 자리에서 펼치고 네 발로 엎드려(상태 crawl) 빠르게 기어 온다(0.34): 2.2블록 안에서 달려들어 14 피해와 넘어짐 0.4초.
 *
 * <p>팩 계약(tools/gabe/sp/g173.py): 상태 "coat", "", "crawl". 소리 rustle(12), unfold(28), bite(16), snicker(8, 웅크려 듣기). 글귀 cause_g173.
 */
public final class G173 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double STAND_Y = 0.95, CRAWL_Y = 0.55, RADIUS = 0.45;
    static final double WALK = 0.13, CRAWL = 0.34, SIDE = 1.5;
    static final int BESIDE = 40, RUSTLE_EVERY = 14;
    /** 감싸 물기: 첫 피해, 0.5초마다, 붙잡는 틱, 탈출 연타, 놓치는 몫. */
    static final double WRAP = 22, WRAP_TICK = 6, SHAKE = 0.12;
    static final int WRAP_T = 30, CLICKS = 6;
    /** 달려들기: 거리, 빠르기, 틱, 피해, 넘어짐, 쿨다운. */
    static final double LUNGE_R = 2.2, LUNGE_V = 0.6, LUNGE = 14;
    static final int LUNGE_T = 6, LUNGE_ROOT = 8, LUNGE_CD = 30;

    enum Phase { WALK, BESIDE, WRAP, CRAWL, LUNGE }

    private Phase phase = Phase.WALK;
    private int phaseAt, nextRustle, readyAt;
    private Player victim;
    private double shaken;
    private final Vector lungeDir = new Vector();

    public G173(Game game, Species sp) {
        super(game, sp);
        radius = RADIUS;
        hover = STAND_Y;
        baseSpeed = WALK;
        disguised = true;
        noBite = true;
        state("coat");
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g173", 3.4f, 2.2f);
    }

    @Override
    protected void spawned() {
        nextRustle = age + game.random.nextInt(RUSTLE_EVERY);
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
            case WALK -> walk();
            case BESIDE -> beside();
            case WRAP -> wrap();
            case CRAWL -> crawl();
            case LUNGE -> lunge();
        }
    }

    /** 몸 오른쪽(그 사람이 보는 쪽 기준)으로 1.5블록 곁 자리. */
    private static Vector besideSpot(Player p) {
        double r = Math.toRadians(p.getLocation().getYaw());
        // 사람 오른쪽 = -(왼쪽), 왼쪽 = (cos, 0, sin)
        Vector right = new Vector(-Math.cos(r), 0, -Math.sin(r));
        return p.getLocation().toVector().add(right.multiply(SIDE));
    }

    private void walk() {
        Player p = nearest(400);
        if (p == null) {
            follow(WALK);
            rustle();
            return;
        }
        victim = p;
        Vector spot = besideSpot(p);
        double d = Math.hypot(spot.getX() - pos.getX(), spot.getZ() - pos.getZ());
        if (d > 24) follow(speed());
        else seek(spot.setY(pos.getY()), Math.min(speed(), d * 0.5));
        if (d > 3) face(pos.clone().add(vel.clone().setY(0).lengthSquared() > 1e-6 ? vel.clone().setY(0) : toward(p)));
        else yaw = p.getLocation().getYaw();
        pitch = 12; // 고개를 숙인 채
        rustle();
        if (d < 0.7 && standing(p)) {
            hold();
            phase(Phase.BESIDE);
        }
    }

    private void rustle() {
        if (age < nextRustle || vel.lengthSquared() < 1e-4) return;
        sound("rustle", Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 12);
        nextRustle = age + RUSTLE_EVERY + game.random.nextInt(6);
    }

    /** 곁에 나란히 서서 같은 쪽을 봄. 그 사람이 움직이면 다시 따라감. */
    private void beside() {
        Player p = victim;
        if (!standing(p)) {
            phase(Phase.WALK);
            return;
        }
        Vector spot = besideSpot(p);
        double d = Math.hypot(spot.getX() - pos.getX(), spot.getZ() - pos.getZ());
        if (d > 2.5) {
            phase(Phase.WALK);
            return;
        }
        seek(spot.setY(pos.getY()), Math.min(WALK, d * 0.5));
        yaw = p.getLocation().getYaw();
        pitch = 12;
        if (inPhase() >= cd(BESIDE)) unfold(p);
    }

    /** 외투를 확 펼쳐 감싸 묾. */
    private void unfold(Player p) {
        disguised = false;
        state("");
        pitch = 0;
        mark(p);
        sound("unfold", Sound.ENTITY_PHANTOM_FLAP, 0.5f, 28);
        hit(p, WRAP, "cause_g173");
        sound("bite", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 16);
        if (standing(p) && grab(p, CLICKS)) {
            victim = p;
            shaken = 0;
            noPush = true;
            phase(Phase.WRAP);
        } else toCrawl();
    }

    /** 감싼 채(마주 보고 날개로 감쌈): 0.5초마다 6, 1.5초 뒤 놓음. */
    private void wrap() {
        Player p = victim;
        if (p == null || !p.isOnline() || game.players.grabbedBy(p) != this || inPhase() >= WRAP_T) {
            letGo();
            toCrawl();
            return;
        }
        // 그 사람 코앞에서 마주 보고 날개로 감쌈(당한 사람 눈앞에 안쪽 얼굴 비늘과 가슴 얼굴이 가득)
        double r = Math.toRadians(p.getLocation().getYaw());
        Vector f = new Vector(-Math.sin(r), 0, Math.cos(r));
        Vector want = p.getLocation().toVector().add(f.clone().multiply(0.6)).setY(pos.getY());
        vel.copy(want.subtract(pos).setY(0));
        yaw = p.getLocation().getYaw() + 180f;
        if (inPhase() > 0 && inPhase() % 10 == 0) hit(p, WRAP_TICK, "cause_g173");
    }

    private void letGo() {
        Player p = victim;
        noPush = false;
        if (p != null && p.isOnline() && game.players.grabbedBy(p) == this) game.players.release(p);
    }

    private void toCrawl() {
        disguised = false;
        state("crawl");
        hover = CRAWL_Y;
        baseSpeed = CRAWL;
        pitch = 0;
        readyAt = age + cd(LUNGE_CD);
        phase(Phase.CRAWL);
    }

    private void crawl() {
        Player p = nearest(400);
        follow(speed());
        if (p == null) return;
        face(chest(p));
        pitch = 0;
        if (target != p && dist(p) < 16) mark(p);
        if (age >= readyAt && dist(p) <= LUNGE_R && sees(p)) {
            lungeDir.copy(toward(p));
            victim = p;
            phase(Phase.LUNGE);
        }
    }

    private void lunge() {
        dash(lungeDir, LUNGE_V);
        Player p = victim;
        if (standing(p) && touches(p, 0.2)) {
            hit(p, LUNGE, "cause_g173");
            game.players.root(p, LUNGE_ROOT, "g173");
            sound("bite", Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 16);
            hold();
            readyAt = age + cd(LUNGE_CD);
            phase(Phase.CRAWL);
            return;
        }
        if (inPhase() >= LUNGE_T || wallAhead(0.6)) {
            hold();
            readyAt = age + cd(LUNGE_CD) / 2;
            phase(Phase.CRAWL);
        }
    }

    // ------------------------------------------------------------------ 맞음, 드러남

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        if (disguised) {
            // 흘린 탄은 지나감. 겨눈 사격, 폭발, 불이면 드러나 기어 옴(접힌 동안 맞은 것은 x2)
            if (gun && !d.aimed()) return false;
            Damage dd = d.times(2);
            reveal();
            return super.damage(dd);
        }
        if (phase == Phase.WRAP) {
            double fx, fz;
            double r = Math.toRadians(yaw);
            fx = -Math.sin(r);
            fz = Math.cos(r);
            Damage use = d;
            Vector dir = d.dir();
            if (dir != null && dir.getX() * fx + dir.getZ() * fz > 0.3) use = d.times(0.5); // 등 쪽에서 온 탄: 날개가 절반 막음
            boolean chestHit = d.at() != null && d.at().distanceSquared(pos.clone().add(new Vector(fx * 0.2, 0.12, fz * 0.2))) < 0.5 * 0.5;
            double before = hp;
            boolean res = super.damage(use);
            shaken += Math.max(0, before - hp) * (chestHit ? 3 : 1);
            if (!dead && shaken >= SHAKE * maxHp) {
                letGo();
                toCrawl();
            }
            return res;
        }
        return super.damage(d);
    }

    /** 조명탄, 정화, 겨눈 사격: 그 자리에서 펼치고 엎드려 기어 옴. */
    @Override
    public void reveal() {
        if (!disguised) return;
        disguised = false;
        sound("unfold", Sound.ENTITY_PHANTOM_FLAP, 0.5f, 28);
        Player p = nearest(400);
        if (p != null) mark(p);
        toCrawl();
    }

    @Override
    public String listenSound() {
        return disguised ? "gave:gabe.g173.snicker" : null;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        letGo();
        super.die(d);
    }

    @Override
    public void remove() {
        letGo();
        super.remove();
    }

    /** 시험용. */
    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (disguised ? " disg" : "");
    }
}
