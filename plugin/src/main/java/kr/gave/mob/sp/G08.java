package kr.gave.mob.sp;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.world.Plaza;

/**
 * G08 금빛 홍조 게이브(보상 종, 웨이브에 드물게 하나). 홍조만 금빛(팔레트 비트), 몸보다 긴 막대기 둘을 꼬리처럼 끌며 싸움에 끼지 않고 사람이 갈 수
 * 있는 끝 안쪽 고리를 따라 크게 돌며 도망친다. 나올 때 맵 전체에 '딸랑' 한 번과 알림, 그쪽을 가리키는 금색 화살촉. 사람이 다가오면 더 빨라지고
 * (앞을 막으면 돌아섬), 도는 고리에 닿은 뒤 25초가 지나면 바깥으로 빠져나가 사라진다. 지나간 자리에 금빛 가루가 반짝인다. 사람이 잡으면(빠져나가는
 * 중이어도) 판에 든 모두에게 증강 뽑기(Augments.goldReward), 재화 보상은 없다. 실눈은 끝까지 뜨지 않는다(누구도 찍지 않음).
 *
 * <p>황야 맵(구조물 없음): 가장자리 고리(안개 너머)에서 나오므로 먼저 보이는 고리(가운데에서 28~42블록, 사람이 갈 수 있는 끝 48 안쪽)까지 서둘러
 * 들어온 뒤 돈다. 25초는 고리에 닿은 때부터 센다. 오래 살면 변함 규칙에서 빠지고(noMutate), 변이나 갈라짐으로 생긴 사본(noReward)은 딸랑, 알림,
 * 증강 뽑기를 주지 않는다.
 *
 * <p>팩 계약(tools/gabe/sp/g08.py): 모델 {@code gave:gabe/g08} 상태 {@code ""}, {@code "rush"}. 소리 jingle(반경 160). 글귀 notice_g08,
 * notice_g08_got, notice_g08_gone.
 */
public final class G08 extends Gabe {

    static final double SPEED = 0.17, RUSH_MUL = 2.2, NOTICE_R = 14, BLOCK_R = 9;
    /** 사는 틱(도는 고리에 닿은 뒤 25초), 빠져나가는 틱, 고리까지 오는 데 주는 틱(넘으면 닿지 못해도 25초를 세기 시작). 맵 전체 소리 반경. */
    static final int LIFE = 500, ESCAPE = 40, ARRIVE_MAX = 300;
    static final double JINGLE_R = 160;
    /** 도는 반지름(가운데 기준): 중앙 원(R_SAFE) 밖, 사람이 갈 수 있는 끝(48) 안쪽. 고리에 닿았다고 보는 거리. */
    static final double R_MIN = Plaza.R_SAFE + 16, R_MAX = Plaza.R_SAFE + 30, ARRIVE_R = 4;
    static final int DUST_EVERY = 5, POINT_TICKS = 80;

    private int dir;
    private double orbitR;
    private int escapeAt = -1, arrivedAt = -1, stuck, nextDust;
    private final Vector last = new Vector();

    public G08(Game game, Species sp) {
        super(game, sp);
        radius = 0.35;
        hover = 1.0;
        baseSpeed = SPEED;
        noBite = true;
        // 25초만 사는 보상 종: 오래 살면 변함(입 찢어짐, 빨라짐, 둘로 갈라짐)에서 뺌
        noMutate = true;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g08", 1.6f, 1.2f);
        body.look.palette |= Look.PAL_GOLD;
    }

    @Override
    protected void spawned() {
        dir = game.random.nextBoolean() ? 1 : -1;
        double d0 = Math.hypot(pos.getX() - Plaza.CX, pos.getZ() - Plaza.CZ);
        // 가장자리(안개 너머)에서 나왔으면 보이는 고리 바깥쪽 어딘가로 들어와 돌고, 고리 안팎에서 나왔으면 그 자리 반지름으로
        orbitR = d0 > R_MAX ? R_MAX - game.random.nextDouble() * 8 : Math.max(R_MIN, d0);
        last.copy(pos);
        // 우리 개체, 변이나 갈라짐으로 생긴 사본(보상 0)은 사람 쪽 신호를 내지 않음
        if (game.director.cageSpawning() || noRules || noReward) return;
        // 나올 때 맵 전체에 '딸랑' 한 번과 알림, 그쪽을 가리키는 금색 화살촉
        sound("jingle", Sound.BLOCK_BELL_USE, 1.9f, JINGLE_R);
        game.hud.notice("notice_g08", sp, 80);
        game.hud.point(null, pos.clone(), POINT_TICKS);
    }

    @Override
    public void tick() {
        if (escapeAt >= 0) {
            escape();
            return;
        }
        double d0 = Math.hypot(pos.getX() - Plaza.CX, pos.getZ() - Plaza.CZ);
        if (arrivedAt < 0 && (Math.abs(d0 - orbitR) <= ARRIVE_R || age >= ARRIVE_MAX)) arrivedAt = age;
        if (arrivedAt >= 0 && age - arrivedAt >= LIFE) {
            escapeAt = age;
            state("rush");
            return;
        }
        Player p = nearest(NOTICE_R);
        // 고리로 들어오는 길과 사람이 다가올 때는 서두름
        boolean rush = p != null || arrivedAt < 0;
        Vector c = new Vector(Plaza.CX, pos.getY(), Plaza.CZ);
        // 앞을 막은 사람이 있으면 돌아섬
        if (p != null) {
            Vector tang = tangent();
            Vector to = chest(p).subtract(pos).setY(0);
            if (to.length() < BLOCK_R && to.normalize().dot(tang) > 0.55) dir = -dir;
        }
        double v = speed() * (rush ? RUSH_MUL : 1.0);
        circle(c, orbitR, v, dir);
        vel.setY(0);
        state(rush ? "rush" : "");
        Vector ahead = pos.clone().add(vel);
        face(ahead.setY(pos.getY()));
        // 막혀서 못 가면 반지름을 바꾸거나 돌아섬
        if (pos.distanceSquared(last) < 0.0004) {
            if (++stuck > 8) {
                stuck = 0;
                orbitR = orbitR + (game.random.nextBoolean() ? 3 : -3);
                orbitR = Math.max(R_MIN, Math.min(R_MAX, orbitR));
                if (game.random.nextInt(3) == 0) dir = -dir;
            }
        } else stuck = 0;
        last.copy(pos);
        dust();
    }

    /** 원 둘레 진행 방향(수평 단위). */
    private Vector tangent() {
        double dx = pos.getX() - Plaza.CX, dz = pos.getZ() - Plaza.CZ, d = Math.max(1e-4, Math.hypot(dx, dz));
        return new Vector(-dz / d * dir, 0, dx / d * dir);
    }

    /** 바깥으로 빠져나가 사라짐(사라지면 보상 없음, 그 전에 잡히면 보상). */
    private void escape() {
        int t = age - escapeAt;
        double dx = pos.getX() - Plaza.CX, dz = pos.getZ() - Plaza.CZ, d = Math.max(1e-4, Math.hypot(dx, dz));
        Vector out = new Vector(dx / d, 0, dz / d);
        ghost = true;
        vel.copy(out.multiply(speed() * RUSH_MUL)).add(tangent().multiply(speed()));
        face(pos.clone().add(vel));
        if (t == ESCAPE - 10) body.scale(0.05f, 10);
        dust();
        if (t >= ESCAPE) {
            if (!game.director.silent()) game.hud.notice("notice_g08_gone", sp, 60);
            remove();
        }
    }

    private void dust() {
        if (age < nextDust) return;
        nextDust = age + DUST_EVERY;
        Location at = new Location(game.world, pos.getX(), floor() + 0.25, pos.getZ());
        game.shotFx.spawn(at, "fx_quad", ShotFx.SPARK, 0, ShotFx.scale(0.55f), Display.Billboard.CENTER, true, 10, ShotFx.EXTRA);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 빠져나가는 중에 잡아도 보상(사라지기 전까지는 잡을 수 있음). 변이나 갈라짐으로 생긴 사본(보상 0)은 뺌
        boolean byPlayer = d != null && d.by() != null && !noReward;
        super.die(d);
        if (byPlayer) {
            // 판에 든 모두에게 증강 뽑기
            game.augments.goldReward();
            game.hud.notice("notice_g08_got", sp, 100);
            sound("jingle", Sound.BLOCK_BELL_USE, 2f, JINGLE_R);
        }
    }

    @Override
    public String toString() {
        return super.toString() + (escapeAt >= 0 ? " escape" : arrivedAt < 0 ? " arriving" : " left=" + (LIFE - (age - arrivedAt)))
                + String.format(java.util.Locale.ROOT, " r=%.0f dir=%d", orbitR, dir);
    }
}
