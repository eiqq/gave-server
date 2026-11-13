package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G194 바닥 밑 게이브(4막, 돌격, 목표 압박). 웨이브 시작 30~60 초 뒤 트럭(없으면 중앙 원 가운데) 둘레 바로 밑에 나타나 10 초 동안 바닥 밑을 기어 다니며
 * 아래에서 두드린다(볼록 솟는 바닥이 옮겨 다님, 쏘면 절반만 들어감). 그러다 트럭 곁 사람이 가장 많은 자리 밑에 1.5 초 둥근 금 고리(지름 5)를 긋고
 * 가시 왕관부터 솟는다: 고리 선 위 사람 22 와 트럭 둘레 밖(떼 쪽)으로 날림. 솟아 있는 12 초 동안 3 초마다 가시를 접었다 펴며 반경 4 를 쓸고(16),
 * 얼굴 앞 사람을 물며(28), 트럭을 3 초마다 들이받고 트럭 상점을 닫는다(LAMP 가리기 가득). 다시 꺼져 15 초 뒤 다른 자리에서 또 솟는다.
 * 얼굴 정수리 바닥 조각(부위 체력 100)을 깨면 2 초 멍함. 가시는 탄을 막는다(피해 0, 가시 사이로 얼굴을 쏴야 함).
 *
 * <p>팩 계약(tools/gabe/sp/g194.py): 몸 gabe/g194(shrink 0.4, 상태 "", "fold", 부위 nocap), 볼록 바닥 gabe/g194_bump.
 * 소리 knock(32), rise(48), sweep(32), bite(24), ram(40), crack(32). 글귀 cause_g194, cause_g194_sweep, cause_g194_bite.
 *
 * <p>도감과 다른 점: 트럭 상점 닫기는 G14 갉기 대신 등불 가리기(LAMP, 가린 수 가득 = 상점 잠김, 등불도 어두워짐). 트럭 들이받기는 무는 피해의 4/3 배
 * (위협 5 몫). 다른 개체가 다 죽었으면 기다리지 않고 바로 나온다(웨이브가 이것만 기다리지 않게).
 */
public final class G194 extends Gabe {

    static final double HOVER = 1.5, SUNK = -5.2, RING = 2.5, RING_W = 0.95, RING_DMG = 22, SWEEP_R = 4, SWEEP_DMG = 16, BITE_R = 3.4, BITE_DMG = 28;
    static final double CAP_HP = 100, BUMP_MUL = 0.5, CRAWL = 0.12, RAM_MUL = 4.0 / 3.0;
    static final int WAIT_MIN = 600, WAIT_MAX = 1200, KNOCK_FIRST = 200, KNOCK_AGAIN = 300, TELL = 30, RISE = 4, UP = 240, SINK_T = 8, SWEEP_CD = 60,
        SWEEP_TELL = 12, BITE_CD = 40, RAM_EVERY = 60, DAZE = 40, KNOCK_EVERY = 18;

    enum Phase { WAIT, KNOCK, TELL, UP, SINK }

    private Phase phase = Phase.WAIT;
    private int phaseAt, waitFor = -1, knockLen = KNOCK_FIRST, knockAt, sweepAt, sweepTell = -1, biteAt, ramAt, dazeUntil, rises;
    private double cap = -1;
    private final Vector spot = new Vector(), wander = new Vector();
    private boolean hasWander;
    private Body.Part bump;
    private final String key;
    private int sweeps, bites, rams, flung;
    private final List<double[]> sph = new ArrayList<>(16);
    private int sphAge = -1;

    public G194(Game game, Species sp) {
        super(game, sp);
        key = "G194#" + id;
        radius = 1.5;
        hover = HOVER;
        baseSpeed = CRAWL;
        big = true;
        hpPow = Director.Tune.HP_POW_BIG;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        ragKick = 0.3;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g194", 7.5f, 5f);
        body.viewRange(3f);
        body.offset.set(0, (float) SUNK, 0);
        body.pose(0);
        bump = body.attach("gabe/g194_bump");
        if (bump != null) bump.follow = false;
        poseBump(0, false);
    }

    @Override
    protected void spawned() {
        untargetable = true;
        if (game.director.cageSpawning()) return;
        waitFor = game.director.fromDirector(this) ? WAIT_MIN + game.random.nextInt(WAIT_MAX - WAIT_MIN) : 40;
        // 트럭 둘레 바로 밑으로(숨어 기다림)
        Vector c = G141Kit.middle();
        double a = game.random.nextDouble() * Math.PI * 2;
        game.mobs.relocate(this, new Vector(c.getX() + Math.cos(a) * 6, c.getY() + HOVER, c.getZ() + Math.sin(a) * 6));
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
        if (cap < 0) cap = CAP_HP * Math.max(0.6, Math.sqrt(maxHp / Math.max(1, sp.hp)));
        switch (phase) {
            case WAIT -> waitTick();
            case KNOCK -> knock();
            case TELL -> tell();
            case UP -> up();
            case SINK -> sink();
        }
    }

    /** 다른 개체(웨이브를 붙드는 것)가 남았나. */
    private boolean othersAlive() {
        for (Gabe g : game.mobs.all()) if (g != this && !g.dead && !g.noHold && !g.persist) return true;
        return false;
    }

    private void waitTick() {
        hold();
        if (waitFor < 0) return;
        long wt = game.director.waveTicks();
        boolean due = inPhase() >= waitFor || (wt >= 0 && wt >= waitFor) || (inPhase() > 100 && !othersAlive());
        if (!due) return;
        untargetable = false;
        knockLen = KNOCK_FIRST;
        hasWander = false;
        phase(Phase.KNOCK);
        poseBump(0, true);
        game.hud.notice("notice_g194", sp, 60);
    }

    /** 바닥 밑을 기어 다니며 두드림: 볼록 솟는 바닥이 트럭 둘레를 옮겨 다님. */
    private void knock() {
        Vector c = G141Kit.middle();
        if (!hasWander || G141Kit.hdist(pos, wander) < 1.0) {
            double a = game.random.nextDouble() * Math.PI * 2, r = 4.5 + game.random.nextDouble() * 4.5;
            wander.setX(c.getX() + Math.cos(a) * r).setZ(c.getZ() + Math.sin(a) * r).setY(pos.getY());
            hasWander = true;
        }
        Vector d = wander.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l > 1e-3) {
            vel.setX(d.getX() / l * speed());
            vel.setZ(d.getZ() / l * speed());
        }
        if (age >= knockAt) {
            knockAt = age + KNOCK_EVERY + game.random.nextInt(10);
            Vector g = new Vector(pos.getX(), floor(), pos.getZ());
            G129Kit.soundAt(this, g, "knock", Sound.BLOCK_WOOD_HIT, 0.9f, 32);
            G129Kit.ring(this, g, 1.1, 8);
            if (bump != null) {
                float y = (float) (floor() - pos.getY() + 0.01);
                bump.pose(new Matrix4f().translate(0, y, 0).scale(1.15f, 1.6f, 1.15f), 2);
            }
            for (Player p : game.mobs.standing()) {
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - g.getX(), pl.getZ() - g.getZ()) < 3.5) Guns.kick(p, (game.random.nextFloat() - 0.5f) * 0.6f, -0.5f);
            }
        } else if (age == knockAt - KNOCK_EVERY + 4) poseBump(4, true);
        else if (age % 4 == 0) poseBump(4, true);
        Player p = nearest(400);
        if (p != target) mark(p);
        if (inPhase() >= knockLen) startTell();
    }

    /** 트럭 곁(R 4~9, 발자국 밖) 사람이 가장 많은 자리 밑에 금 고리. */
    private void startTell() {
        Vector c = G141Kit.middle();
        List<Player> ps = game.mobs.standing();
        double best = -1;
        Vector pick = null;
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            for (double r : new double[] {4.5, 6.5, 8.5}) {
                double x = c.getX() + Math.cos(a) * r, z = c.getZ() + Math.sin(a) * r;
                if (Truck.live() && Truck.dist(x, z) < 2.2) continue;
                double n = game.random.nextDouble() * 0.2;
                for (Player p : ps) {
                    Location l = p.getLocation();
                    double dd = Math.hypot(l.getX() - x, l.getZ() - z);
                    if (dd < 3.6) n += 1 + (3.6 - dd) / 3.6;
                }
                if (n > best) {
                    best = n;
                    pick = new Vector(x, 0, z);
                }
            }
        }
        if (pick == null) pick = pos.clone();
        spot.setX(pick.getX()).setZ(pick.getZ()).setY(game.mobs.flow.floor(pick.getX(), pick.getZ()));
        hold();
        phase(Phase.TELL);
        untargetable = true;
        game.mobs.relocate(this, new Vector(spot.getX(), spot.getY() + HOVER, spot.getZ()));
        Vector out = spot.clone().subtract(c).setY(0);
        if (out.lengthSquared() > 1e-4) G141Kit.faceFlat(this, spot.clone().add(out));
        poseBump(0, false);
        G129Kit.plate(this, "gabe/b1_warn_disc", "", spot, RING + RING_W, TELL + 4, 0);
        G129Kit.plate(this, "gabe/b1_warn_disc", "fill", spot, RING + RING_W, TELL + 4, TELL);
        G129Kit.soundAt(this, spot, "knock", Sound.BLOCK_WOOD_HIT, 1.0f, 40);
        if (game.horizon != null) game.horizon.shake(0.5, 10);
    }

    private void tell() {
        hold();
        if (inPhase() % 8 == 0) G129Kit.soundAt(this, spot, "knock", Sound.BLOCK_WOOD_HIT, 1.0f, 40);
        if (inPhase() < TELL) return;
        body.offset.set(0, 0, 0);
        body.pose(RISE);
        state("");
        untargetable = false;
        rises++;
        sound("rise", Sound.ENTITY_WARDEN_EMERGE, 0.8f, 48);
        sweepAt = age + cd(SWEEP_CD);
        ramAt = age + 20;
        biteAt = age + 20;
        Truck t = Truck.get();
        if (t != null) t.lampCover(key, Truck.LAMP_COVER_FULL);
        phase(Phase.UP);
    }

    /** 솟아 있는 12 초: 둘째 틱에 고리 위 사람을 날림, 그 뒤 쓸기, 물기, 트럭 들이받기. */
    private void up() {
        hold();
        int t = inPhase();
        if (t == 1) ringStrike();
        Truck tr = Truck.get();
        if (tr != null && age % 40 == 0) tr.lampCover(key, Truck.LAMP_COVER_FULL);
        if (age >= dazeUntil) {
            sweepTick();
            biteTick();
        }
        if (age >= ramAt) {
            ramAt = age + cd(RAM_EVERY);
            if (tr != null && Truck.dist(pos.getX(), pos.getZ()) < 10) {
                tr.hit(tr.biteDamage(sp) * RAM_MUL, this, "cause_g194");
                rams++;
                sound("ram", Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.6f, 40);
                body.squash(1.08f, 0.9f, 1.08f, 3);
            }
        }
        Player p = nearest(400);
        if (p != target) mark(p);
        if (t >= UP) {
            body.offset.set(0, (float) SUNK, 0);
            body.pose(SINK_T);
            untargetable = true;
            sweepTell = -1;
            if (tr != null) tr.lampCover(key, 0);
            sound("rise", Sound.ENTITY_WARDEN_DIG, 0.7f, 48);
            phase(Phase.SINK);
        }
    }

    private void ringStrike() {
        Vector c = G141Kit.middle();
        G129Kit.ring(this, spot, RING + 1.2, 14);
        G141Kit.dust(this, spot, 4f, 14);
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double h = Math.hypot(l.getX() - spot.getX(), l.getZ() - spot.getZ());
            if (Math.abs(h - RING) > RING_W || Math.abs(l.getY() - spot.getY()) > 3) continue;
            hit(p, RING_DMG, "cause_g194");
            Vector out = new Vector(l.getX() - c.getX(), 0, l.getZ() - c.getZ());
            if (out.lengthSquared() < 1e-4) out = new Vector(l.getX() - spot.getX(), 0, l.getZ() - spot.getZ());
            if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
            out.normalize();
            flung++;
            if (!game.players.launch(p, 2.6, out.clone().multiply(0.34), 0, this, "cause_g194")) knock(p, out, 1.2, 0.6);
        }
        G129Kit.crush(this, spot, RING + 0.6, null, 0);
    }

    /** 가시를 접었다(예고) 펴며 반경 4 를 쓸기. */
    private void sweepTick() {
        if (sweepTell < 0) {
            if (age < sweepAt) return;
            sweepTell = age;
            state("fold");
            return;
        }
        if (age - sweepTell < SWEEP_TELL) return;
        sweepTell = -1;
        sweepAt = age + cd(SWEEP_CD);
        state("");
        sweeps++;
        sound("sweep", Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 32);
        G129Kit.ring(this, spot, SWEEP_R, 10);
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double h = Math.hypot(l.getX() - spot.getX(), l.getZ() - spot.getZ());
            if (h > SWEEP_R || Math.abs(l.getY() - spot.getY()) > 3) continue;
            hit(p, SWEEP_DMG, "cause_g194_sweep");
            knock(p, new Vector(l.getX() - spot.getX(), 0, l.getZ() - spot.getZ()), 0.8, 0.3);
        }
    }

    /** 얼굴 앞 사람을 묾. */
    private void biteTick() {
        if (age < biteAt) return;
        Vector f = G141Kit.fwd(this);
        Player best = null;
        double bd = 1e9;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ(), h = Math.hypot(dx, dz);
            if (h > BITE_R || h < 0.3 || Math.abs(l.getY() - spot.getY()) > 3) continue;
            if ((dx * f.getX() + dz * f.getZ()) / h < 0.3) continue;
            if (h < bd) {
                bd = h;
                best = p;
            }
        }
        if (best == null) {
            Player p = nearest(BITE_R + 6);
            if (p != null) G141Kit.turnFlat(this, chest(p), 3f);
            return;
        }
        G141Kit.faceFlat(this, chest(best));
        biteAt = age + cd(BITE_CD);
        bites++;
        hit(best, BITE_DMG, "cause_g194_bite");
        knock(best, toward(best), 0.6, 0.2);
        sound("bite", Sound.ENTITY_RAVAGER_ATTACK, 0.8f, 24);
        body.squash(1.06f, 0.94f, 1.12f, 3);
    }

    private void sink() {
        hold();
        if (inPhase() < SINK_T + 2) return;
        state("");
        knockLen = KNOCK_AGAIN;
        hasWander = false;
        untargetable = false;
        phase(Phase.KNOCK);
        poseBump(0, true);
    }

    /** 볼록 바닥: 두드리는 동안만 땅 위(세계 축). */
    private void poseBump(int ticks, boolean show) {
        if (bump == null) return;
        boolean vis = show && phase == Phase.KNOCK;
        float y = vis ? (float) (floor() - pos.getY() + 0.01) : -12f;
        bump.pose(new Matrix4f().translate(0, y, 0), ticks);
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        if (phase == Phase.KNOCK) {
            sph.add(new double[] {pos.getX(), floor() + 0.3, pos.getZ(), 1.0});
        } else {
            double oy = body == null ? 0 : body.offset.y;
            sph.add(new double[] {pos.getX(), pos.getY() + 0.35 + oy, pos.getZ(), 1.45});       // 0 얼굴
            sph.add(new double[] {pos.getX(), pos.getY() - 0.8 + oy, pos.getZ(), 1.15});        // 1 턱
            Vector capAt = G141Kit.at(this, 0, 1.75 + oy, 0);
            sph.add(new double[] {capAt.getX(), capAt.getY(), capAt.getZ(), cap > 0 ? 0.75 : 0.01});  // 2 바닥 조각
            for (int i = 0; i < 12; i++) {                                                      // 3.. 가시
                double a = 2 * Math.PI * i / 12;
                double r = RING + 0.55;
                sph.add(new double[] {pos.getX() + Math.cos(a) * r, pos.getY() - 0.35 + oy, pos.getZ() + Math.sin(a) * r, 0.36});
            }
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return pos.clone().add(new Vector(0, body == null ? 0 : body.offset.y, 0));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (phase == Phase.KNOCK) return super.damage(d.times(BUMP_MUL));
        int s = 0;
        if (d.at() != null) {
            List<double[]> ss = spheres();
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < ss.size(); i++) {
                double[] c = ss.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < bd) {
                    bd = dd;
                    s = i;
                }
            }
        }
        boolean blast = d.type() == DamageType.EXPLOSION || d.type() == DamageType.FIRE;
        if (s >= 3 && !blast) {
            if (age % 3 == 0) sound("sweep", Sound.BLOCK_ANVIL_LAND, 1.8f, 16);
            return false;
        }
        if (s == 2 && cap > 0) {
            cap -= d.amount();
            if (cap <= 0) {
                body.look.on.add("nocap");
                dazeUntil = age + DAZE;
                sweepTell = -1;
                state("");
                sound("crack", Sound.BLOCK_STONE_BREAK, 0.8f, 32);
                G141Kit.dust(this, G141Kit.at(this, 0, 1.9, 0), 2f, 10);
            }
        }
        return super.damage(d);
    }

    private void cleanup() {
        Truck t = Truck.get();
        if (t != null) t.lampCover(key, 0);
    }

    @Override
    public void die(Damage d) {
        cleanup();
        if (body != null && body.offset.y < -2) leaveCorpse = false;
        super.die(d);
    }

    @Override
    public void remove() {
        cleanup();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " rises=" + rises + " flung=" + flung + " sweeps=" + sweeps + " bites=" + bites + " rams=" + rams
            + " cap=" + Math.round(cap) + " off=" + String.format("%.1f", body == null ? 0 : body.offset.y) + " wait=" + waitFor;
    }
}
