package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * 진짜 바닥 재화(팀 공용 웃돈). 재화 흉내 게이브(G07)가 깔릴 때 같이 놓이는 진짜 금화다. 공용 코드에 바닥 재화가 생기기 전까지 G07 이 직접 깐다.
 *
 * <p>가짜와 같은 모델(상태 "real": 감긴 막대기가 움찔하지 않음), 같은 회전({@link G07#SPIN})과 둥실거림. 쳐다봐도 멈추지 않고, 굴러오지 않고,
 * 숨소리가 없고, 그림자가 작다. 사람이 걸어가 닿으면 판에 든 모두가 {@link #VALUE}원을 받는다. 조준선을 0.5초 대고 쏘면 반으로 깨져
 * 값이 반이 된다(도감 공통 규칙: 진짜를 겨눠 쏘면 대가). 흘린 탄, 폭발, 불, 조명탄, 딸랑이에는 아무 일 없다.
 *
 * <p>엔진에는 보상 없는 흉내 개체로 든다(맞는 구 없음, 판 규칙에서 빠짐, 표적이 없어 웨이브 끝을 막지 않음, 웨이브 끝에 조용히 사라짐).
 */
public final class G07Coin extends Gabe {

    /** 주우면 판에 든 사람마다 받는 원, 줍는 거리(수평). */
    static final int VALUE = 4;
    static final double PICK = 0.9;

    private final G07Aim aim = new G07Aim();
    private int value = VALUE;
    private boolean halved;

    public G07Coin(Game game, Species sp) {
        super(game, sp);
        radius = 0.3;
        hover = G07.HOVER;
        disguised = true;
        noReward = true;
        reward = 0;
        noRules = true;
        noMutate = true;
        noPush = true;
        leaveCorpse = false;
        state("real");
    }

    /**
     * 가짜 하나(owner) 몫의 진짜 금화 n 개를 깐다(디스플레이 예산 안에서만). 자리는 중앙에서 14~42블록 걷는 칸(사람에게서 8블록, 다른 재화에서
     * 6블록 넘게). 사람 코앞(10블록 안)에 놓인 가짜면(시험 소환) 그 사람이 볼 때 가짜 오른쪽 3블록에 놓는다.
     */
    static void scatter(G07 owner, int n) {
        Game game = owner.game;
        for (int i = 0; i < n; i++) {
            if (!game.mobs.budgetFor(1)) return;
            Vector at = spot(owner, i);
            if (at != null) game.mobs.place(new G07Coin(game, owner.sp), at);
        }
    }

    private static Vector spot(G07 owner, int i) {
        Game game = owner.game;
        var flow = game.mobs.flow;
        Player near = game.mobs.nearestPlayer(owner.pos, 10);
        if (near != null) {
            Vector d = owner.pos.clone().subtract(near.getLocation().toVector()).setY(0);
            if (d.lengthSquared() < 1e-4) d = new Vector(0, 0, 1);
            d.normalize();
            double x = owner.pos.getX() - d.getZ() * 3 * (i + 1), z = owner.pos.getZ() + d.getX() * 3 * (i + 1);
            return flow.walkable(x, z) ? new Vector(x, flow.floor(x, z) + G07.HOVER, z) : null;
        }
        Location c = kr.gave.world.Plaza.center(game.world);
        for (int tries = 0; tries < 30; tries++) {
            double a = game.random.nextDouble() * Math.PI * 2, r = 14 + game.random.nextDouble() * 28;
            double x = c.getX() + Math.cos(a) * r, z = c.getZ() + Math.sin(a) * r;
            if (!flow.walkable(x, z)) continue;
            Vector at = new Vector(x, flow.floor(x, z) + G07.HOVER, z);
            if (game.mobs.nearestPlayer(at, 8) != null) continue;
            boolean crowded = false;
            for (Gabe g : game.mobs.all()) {
                if (!g.dead && g.sp == owner.sp && g.pos.distanceSquared(at) < 36) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) return at;
        }
        return null;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g07", 0.8f, 0.8f);
        body.turnTicks = 2;
        body.anchor.setShadowRadius(G07.SHADOW_REAL);
        body.anchor.setShadowStrength(0.9f);
    }

    @Override
    protected void spawned() {
        yaw = game.random.nextFloat() * 360f;
    }

    @Override
    public void tick() {
        hold();
        hover = G07.HOVER + Math.sin((age + id * 7) * 0.12) * G07.BOB;
        yaw += G07.SPIN;
        pitch = 0;
        if (!halved && aim.tick(this) != null) crack();
        Player p = nearest(2.0);
        if (p != null) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ()) <= PICK) pick();
        }
    }

    /** 겨눠 쏜 진짜 재화: 반으로 깨진다(값 반). */
    private void crack() {
        halved = true;
        value = Math.max(1, value / 2);
        state("half");
        sound("crack", Sound.BLOCK_GLASS_BREAK, 1.6f, 16);
    }

    /** 주움: 판에 든 모두에게(팀 공용 웃돈). */
    private void pick() {
        game.players.teamMoney(value);
        sound("pickup", Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f, 12);
        remove();
    }

    @Override
    public List<double[]> spheres() {
        return List.of();
    }

    @Override
    public boolean damage(Damage d) {
        return false;
    }

    /** 웨이브 끝 정리, 시험 죽이기: 소리와 시체 없이 사라진다. */
    @Override
    public void die(Damage d) {
        remove();
    }

    @Override
    public void reveal() {
    }

    @Override
    public void lure(Vector at, int ticks) {
    }

    public boolean halved() {
        return halved;
    }

    @Override
    public String toString() {
        return super.toString() + " real value=" + value + (halved ? " half" : "");
    }
}
