package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Band;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Role;
import kr.gave.mob.Species;
import kr.gave.trip.Truck;

/**
 * G146 고리 문 게이브(4막, 목표 압박, 지원, 미리 깔림). 지름 3.8 블록 고리 둘이 한 짝: 먼저 바깥 고리가 트럭에서 R 36~44 땅에서 입술부터
 * 솟고, 3초 뒤 안쪽 고리가 트럭에서 R 6~9 에 솟는다(재난문자 꼴 알림, 금색 화살촉). 움직이지 않는다.
 *
 * <ul>
 * <li>바깥 고리 곁을 지나던 게이브(큰 몸, 보스, 하늘 종 빼고)를 끌어들여 1초에 하나씩 안쪽 고리 앞으로 옮김(공용 RELOCATE). 넘어오기 직전
 *   안쪽 막이 불룩해지고 심장 박동이 한 번. 나온 것은 0.5초 휘청한 뒤 트럭(노림으로 찍음)과 가장 가까운 사람에게.</li>
 * <li>안쪽 고리 앞 4 블록 안 사람은 막 쪽으로 끌리고, 막에 닿으면 12 와 함께 바깥 고리 앞(떼 한복판)으로 떨어진다. 테에 닿으면 물려 10.</li>
 * <li>약점: 안쪽 고리 테의 실눈 얼굴 여덟(하나 = 최대 체력의 1/8). 맞아 감기면 바늘땀 감긴 얼굴. 여덟을 다 감기면 두 고리가 함께 닫히며
 *   넘어오던 것이 잘린다(죽음). 폭발은 가까운 얼굴 둘. 테, 바깥 고리는 맞아도 안 다침.</li>
 * </ul>
 *
 * <p>팩 계약(tools/gabe/sp/g146.py): 고리 gabe/g146(shrink 0.6, 부위 e0..e7, 얼굴 i 의 각 = 2pi(i + 0.5)/8, 몸 왼쪽 +X 에서 반시계),
 * 막 gabe/g146_veil. 디스플레이 넷: 닻 = 안쪽 고리, 부품 = 바깥 고리(follow false), 안쪽 막, 바깥 막. 소리 open(64), beat(48), shut(64),
 * bite(24), pass(32), drag(32). 글귀 cause_g146, cause_g146_bite, notice_g146_open.
 *
 * <p>도감과 다른 점: 막 일렁임은 텍스처 애니메이션이 아니라 막 판의 자세 넷. 사람을 바깥 고리로 보내는 것은 Player.teleport.
 */
public final class G146 extends Gabe {

    static final double HOVER = 2.0, R = 1.6;
    static final double OUTER_MIN = 36, OUTER_MAX = 44, INNER_MIN = 6, INNER_MAX = 9;
    static final int RISE_T = 60, PASS_EVERY = 20, BULGE_T = 10;
    static final double CATCH_R = 8, LURE_R = 13, MAX_PASS_R = 1.2;
    static final double PULL_R = 4, PULL_V = 0.07, VEIL_R = 1.15, RIM_R = 2.3, DRAG_DMG = 12, RIM_DMG = 10;
    static final int PLAYER_CD = 40;
    static final double EYE_FRAC = 0.125, EYE_R = 0.45;

    private final Vector outer = new Vector();
    private float outerYaw;
    private Body.Part outerRing, innerVeil, outerVeil;
    private int risenAt = -1, passAt, bulgeAt = -1, announced;
    private Gabe passing;
    private final double[] eyeHp = new double[8];
    private int closed;
    private final Map<Player, Integer> touched = new IdentityHashMap<>();
    private final List<double[]> sph = new ArrayList<>(16);
    private int sphAge = -1;

    public G146(Game game, Species sp) {
        super(game, sp);
        radius = 2.0;
        hover = HOVER;
        baseSpeed = 0;
        big = true;
        noPush = true;
        noMutate = true;
        noBite = true;
        ghost = true;
        leaveCorpse = false;
        java.util.Arrays.fill(eyeHp, -1);
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g146", 96f, 6f);
        body.viewRange(3f);
        outerRing = body.attach("gabe/g146");
        innerVeil = body.attach("gabe/g146_veil");
        outerVeil = body.attach("gabe/g146_veil");
        for (Body.Part p : new Body.Part[] {outerRing, outerVeil}) if (p != null) p.follow = false;
    }

    @Override
    protected void spawned() {
        if (game.director.cageSpawning()) return;
        Vector c = G141Kit.middle();
        Vector d = pos.clone().subtract(c).setY(0);
        if (d.lengthSquared() < 1e-6) d = new Vector(0, 0, 1);
        d.normalize();
        // 바깥 고리: 같은 방향 R 36~44, 안쪽 고리(닻): R 6~9 이면서 트럭 발자국에서 4 블록 밖
        double ro = OUTER_MIN + game.random.nextDouble() * (OUTER_MAX - OUTER_MIN);
        outer.copy(c).add(d.clone().multiply(ro));
        outer.setY(game.mobs.flow.floor(outer.getX(), outer.getZ()) + HOVER);
        double ri = INNER_MIN + game.random.nextDouble() * (INNER_MAX - INNER_MIN);
        Vector in = c.clone().add(d.clone().multiply(ri));
        for (int i = 0; i < 6 && Truck.live() && Truck.dist(in.getX(), in.getZ()) < 4; i++) in.add(d.clone().multiply(1));
        in.setY(game.mobs.flow.floor(in.getX(), in.getZ()) + HOVER);
        game.mobs.relocate(this, in);
        // 안쪽 고리는 막이 트럭을 봄, 바깥 고리는 막이 바깥(떼)을 봄
        G141Kit.faceFlat(this, c);
        outerYaw = G141Kit.yawOf(d);
        body.pose(0);
        body.scale(0.01f, 0);
        poseOuter(0.01f, 0);
        poseInnerVeil(0.01f, 0);
        risenAt = age;
        sound("open", Sound.BLOCK_IRON_DOOR_OPEN, 0.5f, 64);
        mark(nearest(400));
    }

    private void poseOuter(float s, int ticks) {
        Matrix4f m = new Matrix4f().translate((float) (outer.getX() - pos.getX()), (float) (outer.getY() - pos.getY()), (float) (outer.getZ() - pos.getZ()))
            .rotateY((float) Math.toRadians(-outerYaw)).scale(1, s, 1);
        if (outerRing != null) outerRing.pose(m, ticks);
        if (outerVeil != null) outerVeil.pose(new Matrix4f(m).scale(s), ticks);
    }

    private void poseInnerVeil(float s, int ticks) {
        if (innerVeil != null) innerVeil.pose(new Matrix4f().scale(s, s, s), ticks);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        hold();
        if (eyeHp[0] < 0) java.util.Arrays.fill(eyeHp, maxHp * EYE_FRAC);
        if (target == null || !standing(target)) mark(nearest(400));
        int t = risenAt < 0 ? 999 : age - risenAt;
        // 솟아오름: 바깥 고리 먼저(0..20틱), 안쪽 고리 3초 뒤(60..80틱)
        if (t == 1) poseOuter(1f, 20);
        if (t == RISE_T) {
            body.scale(1f, 20);
            poseInnerVeil(1f, 20);
            sound("open", Sound.BLOCK_IRON_DOOR_OPEN, 0.5f, 64);
            game.hud.alert("notice_g146_open", 4, 100);
            game.hud.point(null, pos.clone(), 120);
            announced = 1;
        }
        if (t < RISE_T + 20) return;
        // 큰 연결: 바깥 고리로 끌어들임과 넘김
        if (age % 10 == 0) lureOuter();
        pass();
        players();
    }

    private boolean passable(Gabe g) {
        if (g == this || g.dead || g.removed || g.big || g.radius > MAX_PASS_R || g.flying || g.sp.is(Role.BOSS)) return false;
        if (g.sp.band == Band.SKY || g.sp.spawn == Species.Spawn.PLACED || g.untargetable || g.noRules) return false;
        return !(g instanceof G146);
    }

    private void lureOuter() {
        Vector o = outer.clone();
        o.setY(o.getY() - HOVER + 0.5);
        for (Gabe g : game.mobs.near(o, LURE_R, this::passable)) {
            if (g == passing) continue;
            game.mobs.lure(g, outer.clone().setY(g.pos.getY()), 14);
        }
    }

    private void pass() {
        if (passing != null) {
            if (passing.dead || passing.removed) {
                passing = null;
                bulgeAt = -1;
                poseInnerVeil(1f, 4);
                return;
            }
            if (age - bulgeAt >= BULGE_T) {
                Vector at = G141Kit.at(this, 0, 0, 1.4);
                at.setY(game.mobs.flow.floor(at.getX(), at.getZ()) + passing.hover);
                if (game.mobs.relocate(passing, at)) {
                    passing.yaw = yaw;
                    game.mobs.lure(passing, at.clone().add(G141Kit.fwd(this).multiply(0.6)), 10);
                    Truck tr = game.trip == null ? null : game.trip.truck();
                    if (tr != null) tr.mark(passing);
                    sound("pass", Sound.ENTITY_SLIME_SQUISH, 0.7f, 32);
                    if (game.shotFx.room(ShotFx.EXTRA))
                        game.shotFx.bloodHit(new Location(game.world, at.getX(), pos.getY(), at.getZ()), G141Kit.fwd(this), false, ShotFx.BLOOD_PINK);
                }
                passing = null;
                bulgeAt = -1;
                poseInnerVeil(1f, 4);
                passAt = age + cd(PASS_EVERY);
            }
            return;
        }
        if (age < passAt) return;
        Vector o = outer.clone();
        o.setY(o.getY() - HOVER + 0.5);
        Gabe g = game.mobs.nearest(o, CATCH_R, this::passable);
        if (g == null) return;
        passing = g;
        bulgeAt = age;
        // 넘어오기 직전: 바깥 고리 쪽에서 붙듦, 안쪽 막이 불룩, 심장 박동
        game.mobs.lure(g, outer.clone().setY(g.pos.getY()), BULGE_T + 2);
        if (innerVeil != null) innerVeil.pose(new Matrix4f().scale(1.05f, 1.05f, 3.2f), BULGE_T);
        sound("beat", Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 48);
    }

    /** 안쪽 고리 앞 사람: 끌림, 막에 닿으면 바깥 고리로, 테에 닿으면 물림. */
    private void players() {
        Vector f = G141Kit.fwd(this);
        Vector base = pos.clone();
        base.setY(pos.getY() - HOVER);
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - base.getX(), dz = l.getZ() - base.getZ();
            double fw = dx * f.getX() + dz * f.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > PULL_R + 1 || Math.abs(l.getY() - base.getY()) > 3) continue;
            Integer last = touched.get(p);
            boolean ready = last == null || age - last >= PLAYER_CD;
            double lat = Math.abs(dx * f.getZ() - dz * f.getX());
            if (fw > -0.6 && fw < 0.9 && lat < VEIL_R) {
                if (!ready) continue;
                touched.put(p, age);
                drag(p);
                continue;
            }
            if (d < RIM_R && Math.abs(fw) < 0.9) {
                if (!ready) continue;
                touched.put(p, age);
                hit(p, RIM_DMG, "cause_g146_bite");
                sound("bite", Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 24);
                knock(p, new Vector(dx, 0, dz), 0.6, 0.3);
                continue;
            }
            if (fw > 0 && d <= PULL_R) {
                Vector to = new Vector(-dx, 0, -dz);
                if (to.lengthSquared() > 1e-6) game.players.push(p, to.normalize().multiply(PULL_V));
                if (age % 30 == 0) sound("drag", Sound.ITEM_ELYTRA_FLYING, 0.8f, 32);
            }
        }
    }

    /** 막에 닿은 사람: 12 와 함께 바깥 고리 앞(떼 쪽)으로. */
    private void drag(Player p) {
        hit(p, DRAG_DMG, "cause_g146");
        if (!standing(p)) return;
        Vector away = new Vector(-Math.sin(Math.toRadians(outerYaw)), 0, Math.cos(Math.toRadians(outerYaw)));
        Vector at = outer.clone().add(away.multiply(2.2));
        double y = game.mobs.flow.floor(at.getX(), at.getZ());
        Location to = new Location(game.world, at.getX(), y, at.getZ(), outerYaw + 180f, 0f);
        sound("drag", Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 32);
        p.teleport(to);
        if (!game.director.silent())
            kr.gave.Props.soundAround(to, "gabe.g146.pass", Sound.ENTITY_SLIME_SQUISH, 1f, 0.8f, 0.7f, 32, -1);
    }

    // ------------------------------------------------------------------ 맞음

    /** 안쪽 고리 얼굴 i 의 세계 자리. */
    private Vector eyeAt(int i) {
        double a = 2 * Math.PI * (i + 0.5) / 8;
        return G141Kit.at(this, Math.cos(a) * R, Math.sin(a) * R, 0.25);
    }

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        for (int i = 0; i < 8; i++) {
            Vector e = eyeAt(i);
            sph.add(new double[] {e.getX(), e.getY(), e.getZ(), EYE_R});
            double a = 2 * Math.PI * i / 8;
            Vector r = G141Kit.at(this, Math.cos(a) * R, Math.sin(a) * R, 0);
            sph.add(new double[] {r.getX(), r.getY(), r.getZ(), 0.38});
        }
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        return pos.clone();
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || risenAt < 0 || age - risenAt < RISE_T) return false;
        if (eyeHp[0] < 0) java.util.Arrays.fill(eyeHp, maxHp * EYE_FRAC);
        if (d.at() == null) return false;
        boolean blast = d.type() == DamageType.EXPLOSION;
        // 맞은 점에서 가까운 얼굴(폭발은 둘)
        int a = -1, b = -1;
        double da = 1e9, db = 1e9;
        for (int i = 0; i < 8; i++) {
            if (eyeHp[i] <= 0) continue;
            double dd = d.at().distance(eyeAt(i));
            if (dd < da) {
                db = da;
                b = a;
                da = dd;
                a = i;
            } else if (dd < db) {
                db = dd;
                b = i;
            }
        }
        if (a < 0) return false;
        boolean any = false;
        if (blast) {
            if (da <= 3.5) any |= hurtEye(a, d);
            if (b >= 0 && db <= 3.5 && !dead) any |= hurtEye(b, d);
        } else if (da <= EYE_R + 0.4) any = hurtEye(a, d);
        return any;
    }

    private boolean hurtEye(int i, Damage d) {
        double took = Math.min(eyeHp[i], d.amount());
        if (took <= 0) return false;
        eyeHp[i] -= took;
        if (eyeHp[i] <= 0) {
            body.look.on.add("e" + i);
            closed++;
            sound("bite", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.6f, 24);
        }
        // 체력 막대와 기여: 깎인 몫만큼 본체에서(같은 쏜 사람), 여덟을 다 감기면 남은 체력을 다
        double amt = closed >= 8 ? Math.max(took, hp + 1) : Math.min(took, Math.max(0, hp - 1));
        super.damage(new Damage(amt, d.type(), d.by(), d.at(), d.dir(), d.how(), d.aimTicks()));
        return true;
    }

    /** 죽음 = 두 고리가 함께 닫힘(넘어오던 것이 잘림). */
    @Override
    public void die(Damage d) {
        if (passing != null && !passing.dead) {
            Gabe g = passing;
            passing = null;
            g.noReward = true;
            g.reward = 0;
            g.leaveCorpse = false;
            try {
                g.die(new Damage(Math.max(1, g.hp), DamageType.OTHER, null, g.pos.clone(), null, "cut", 0));
            } catch (RuntimeException e) {
                g.remove();
            }
        }
        sound("shut", Sound.BLOCK_IRON_DOOR_CLOSE, 0.5f, 64);
        for (Vector v : new Vector[] {pos.clone(), outer.clone()}) {
            if (game.shotFx.room(ShotFx.NORMAL))
                game.shotFx.bloodHit(new Location(game.world, v.getX(), v.getY(), v.getZ()), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
            G141Kit.ring(this, v.clone().setY(v.getY() - HOVER), 2.4, 12);
        }
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " closed=" + closed + " outer=" + Math.round(outer.getX()) + "," + Math.round(outer.getZ()) + (passing != null ? " passing" : "");
    }
}
