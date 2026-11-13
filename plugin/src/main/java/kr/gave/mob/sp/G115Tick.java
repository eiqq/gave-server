package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G115 진드기 덮인 거인에게서 떨어진 얼굴 진드기(0.3블록 얼굴 + 막대기 다리 넷, 체력 3, 보상 0). 같은 종(G115)으로 센다.
 * 가장 가까운 사람에게 기어 가 물고(3) 등에 붙어 2초 동안 매달려(15% 느려짐, 한 사람에 셋까지 겹침, 1초마다 다시 묾) 떨어진 뒤 다시 쫓는다.
 * 거인이 죽어도 남는다. 사체 없이 분홍 피만 튄다.
 */
public final class G115Tick extends Gabe {

    static final double HP = 3, HOVER = 0.2, SPEED = 0.21, BITE = 3, SLOW = 0.15;
    static final int HANG = 40, BITE_CD = 20, REST = 20, MAX_ON = 3;

    private Player on;
    private int onAt, slot, biteAt, restUntil, bites;
    private final Vector off = new Vector();

    G115Tick(Game game, Species sp) {
        super(game, sp);
        radius = 0.25;
        hover = HOVER;
        baseSpeed = SPEED;
        noMutate = true;
        noBite = true;
        leaveCorpse = false;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g115_tick", 0.7f, 0.6f);
    }

    @Override
    public void tick() {
        if (on != null) {
            hang();
            return;
        }
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        mark(p);
        if (age < restUntil) {
            follow(speed() * 0.5);
            return;
        }
        Vector to = p.getLocation().toVector().setY(pos.getY());
        if (dist(p) > 2.5) follow(speed());
        else seek(to, speed());
        face(chest(p));
        if (G129Kit.flat(p.getLocation(), pos) <= 0.75 && Math.abs(p.getLocation().getY() - pos.getY()) < 1.6) latch(p);
    }

    /** 사람 등에 붙음(한 사람에 셋까지, 넘으면 물고 튕김). */
    private void latch(Player p) {
        bite(p);
        List<Gabe> others = game.mobs.near(p.getLocation().toVector(), 3, g -> g instanceof G115Tick t && t != this && t.on == p);
        if (others.size() >= MAX_ON) {
            restUntil = age + REST;
            return;
        }
        on = p;
        onAt = age;
        slot = others.size();
        noPush = true;
        flying = true;
        double a = game.random.nextDouble() * Math.PI * 2;
        off.setX(Math.cos(a) * 0.32).setY(0.7 + game.random.nextDouble() * 0.6).setZ(Math.sin(a) * 0.32);
        game.players.slow(p, SLOW, HANG, "g115_" + slot);
    }

    private void hang() {
        Player p = on;
        if (p == null || !p.isOnline() || !standing(p) || age - onAt >= HANG) {
            drop();
            return;
        }
        Location l = p.getLocation();
        vel.zero();
        pos.setX(l.getX() + off.getX()).setY(l.getY() + off.getY()).setZ(l.getZ() + off.getZ());
        if (age >= biteAt) bite(p);
    }

    private void bite(Player p) {
        hit(p, BITE, "cause_g115_tick");
        biteAt = age + cd(BITE_CD);
        bites++;
        sound("bite", Sound.ENTITY_SILVERFISH_HURT, 1.4f, 12);
    }

    private void drop() {
        on = null;
        noPush = false;
        flying = false;
        restUntil = age + REST;
    }

    @Override
    public void die(Damage d) {
        if (game.shotFx.room(ShotFx.EXTRA)) game.shotFx.bloodHit(loc(), d == null || d.dir() == null ? new Vector(0, 1, 0) : d.dir(), false, ShotFx.BLOOD_PINK);
        on = null;
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " on=" + (on == null ? "-" : on.getName()) + " bites=" + bites;
    }
}
