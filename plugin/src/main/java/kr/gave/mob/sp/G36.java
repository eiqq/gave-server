package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G36 실핏줄 잇는 게이브(지원). 정수리가 열려 적갈색 실핏줄 다발이 솟은 얼굴이 떼 위 4블록에 거의 가만히 떠서, 8블록 안 같은 계열(같은 높이 띠)
 * 게이브 넷까지 뒤통수로 실핏줄을 뻗어 꽂는다(1초에 하나씩).
 *
 * <p>도감: 이어진 게이브는 이어진 동안 느려지고(빠르기 0.8배) 초당 체력 3% 회복, 하나가 받은 피해를 이어진 것들이 나눠 가진다(맞은 개체의 체력이
 * 깎인 것을 다음 틱에 나눠 돌려줌: 나눠 받는 쪽은 체력 1 아래로 내려가지 않고, 한 방에 죽는 피해는 못 나눔). 나눠질 때 박동 하나가 비명처럼
 * 늘어진다. 줄마다 어긋난 심장 박동 소리. 6블록 안(수평) 사람은 화면이 박동에 맞춰 약하게 일렁인다. 직접 공격은 없다(처음 이을 때 알림 한 번).
 * 대처: 이것부터. 줄 가운데 맥박 매듭을 쏘면 그 줄이 끊긴다(같은 게이브에는 5초 뒤 다시 이음, 그 탄은 본체에 25%만). 죽이면 이어졌던 게이브가
 * 모두 1초 기절(엔진 lure 로 제자리에 묶음).
 *
 * <p>도감과 다른 점: 화면 일렁임은 렌즈 대신 아주 작은 시점 흔들림(±0.3도, Guns.kick 을 되돌아오는 짝으로). 이음줄은 반투명 관 대신 컷아웃 핏줄
 * 판 둘을 십자로 겹친 모델(반투명 텍스처 금지). 피해 나누기는 남의 종 damage 를 가로챌 수 없어 체력 차로 다음 틱에 나눈다.
 *
 * <p>팩 계약(tools/gabe/sp/g36.py): 닻 {@code gave:gabe/g36}, 이음줄 부품 {@code gave:gabe/g36_vein}(+Z 1 블록, 20틱 박동). 소리 beat(24),
 * shriek(24), snap(16), link(16). 글귀 {@code cause_g36}, {@code notice_g36_link}.
 */
public final class G36 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.1, HOVER = 4.0;
    /** 잇는 거리, 끊기는 거리, 줄 수, 새 줄 간격(틱), 끊긴 뒤 같은 게이브에 다시 잇기까지. */
    static final double LINK_R = 8, BREAK_R = 11;
    static final int MAX_LINKS = 4, LINK_EVERY = 20, RELINK = 100;
    /** 이어진 게이브: 빠르기 배율, 1초마다 회복(최대 체력 비율). */
    static final double SLOW = 0.8, HEAL = 0.03;
    /** 화면 일렁임 반경(수평), 각도. 박동 주기(모델 주기와 같음), 줄마다 어긋남. */
    static final double SWAY_R = 6;
    static final float SWAY = 0.3f;
    static final int BEAT = 20, BEAT_OFF = 5;
    /** 매듭 구 반지름, 매듭에 맞은 탄이 본체에 주는 비율, 죽을 때 이어진 게이브 기절 틱. */
    static final double KNOT_R = 0.22, KNOT_MUL = 0.25;
    static final int STUN = 20;
    /** 정수리 높이(몸 중심에서). */
    static final double CROWN = 0.42;

    /** 이미 이어진 게이브 → 이은 개체(둘이 같은 게이브를 잇지 않게). */
    private static final Map<Gabe, G36> LINKED = new WeakHashMap<>();
    private static final Map<Game, long[]> NOTICE = new WeakHashMap<>();

    private final class Link {
        Gabe g;
        Body.Part part;
        double lastHp;
        final Vector mid = new Vector();
    }

    private final List<Link> links = new ArrayList<>(MAX_LINKS);
    private final Map<Integer, Integer> cut = new HashMap<>();
    private final List<double[]> hit = new ArrayList<>(1 + MAX_LINKS);
    private final double[] delta = new double[MAX_LINKS];
    private final Matrix4f mat = new Matrix4f();
    private final Quaternionf rot = new Quaternionf();
    private final Vector3f dir = new Vector3f();
    private static final Vector3f FRONT = new Vector3f(0, 0, 1);
    private int nextLink, shriekAt = -99;
    private boolean noticed;

    public G36(Game game, Species sp) {
        super(game, sp);
        radius = 0.42;
        hover = HOVER;
        baseSpeed = SPEED;
        for (int i = 0; i <= MAX_LINKS; i++) hit.add(new double[4]);
    }

    @Override
    protected void build() {
        // 줄이 8블록까지 뻗으므로 컬링 상자를 넓게
        body = Body.of(this, "gabe/g36", 12.0f, 9.0f);
    }

    @Override
    protected void spawned() {
        nextLink = age + 20;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        drift();
        prune();
        if (links.size() < MAX_LINKS && age >= nextLink && every(5)) seekLink();
        share();
        int n = links.size();
        long wt = game.worldTime();
        for (int i = 0; i < n; i++) {
            Link l = links.get(i);
            l.g.speedBuff *= SLOW;
            if (age % 20 == 0 && l.g.hp < l.g.maxHp) {
                l.g.hp = Math.min(l.g.maxHp, l.g.hp + l.g.maxHp * HEAL);
                l.lastHp = l.g.hp;
            }
            if ((age + i) % 2 == 0) stretch(l);
            if ((wt + i * BEAT_OFF) % BEAT == 0) sound("beat", Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 24);
        }
        if (n > 0) sway(wt);
        if (age % 200 == 0) cut.values().removeIf(t -> t <= age);
    }

    /** 이을 게이브들 위 4블록에 떠서 느리게 자리를 잡는다. 이을 것이 없으면 흐름장을 따라 천천히 온다. */
    private void drift() {
        hover = HOVER + 0.15 * Math.sin((age + id * 5) * 0.06);
        double cx = 0, cz = 0;
        int n = links.size();
        for (int i = 0; i < n; i++) {
            cx += links.get(i).g.pos.getX();
            cz += links.get(i).g.pos.getZ();
        }
        if (n > 0) {
            cx /= n;
            cz /= n;
            double dx = cx - pos.getX(), dz = cz - pos.getZ(), d = Math.hypot(dx, dz);
            if (d > 1.2) vel.setX(dx / d * speed()).setY(0).setZ(dz / d * speed());
            else hold();
        } else follow(speed() * 0.8);
        Player p = nearest(64);
        if (p != null) turnTo(chest(p), 3f);
        pitch = 0;
    }

    /** 죽었거나, 멀어졌거나, 숨은 것은 줄을 거둔다. */
    private void prune() {
        for (int i = links.size() - 1; i >= 0; i--) {
            Link l = links.get(i);
            Gabe g = l.g;
            if (g.dead || g.removed || g.disguised || g.untargetable || g.pos.distanceSquared(pos) > BREAK_R * BREAK_R) unlink(l, 0);
        }
    }

    /** 8블록 안 같은 계열 가운데 아직 아무 실핏줄도 안 꽂힌 가장 가까운 게이브에 줄 하나를 꽂는다. */
    private void seekLink() {
        Gabe best = null;
        double bd = LINK_R * LINK_R;
        for (Gabe g : kin(LINK_R)) {
            // 미리 깔려 기다리는 함정 종(G47, G55 등)에 꽂으면 그 위에 계속 떠 있게 되므로 건너뜀(2026-11-12)
            if (g instanceof G36 || g.disguised || g.untargetable || g.noRules || g.body == null || game.director.inCage(g)
                    || g.sp.spawn == Species.Spawn.PLACED) continue;
            G36 by = LINKED.get(g);
            if (by != null && !by.dead && by != this) continue;
            if (linked(g)) continue;
            Integer again = cut.get(g.id);
            if (again != null && again > age) continue;
            double d = g.pos.distanceSquared(pos);
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        if (best == null) {
            nextLink = age + 10;
            return;
        }
        Body.Part part = body.attach("gabe/g36_vein");
        if (part == null) return;
        part.follow = false;
        part.look.phase = (body.look.phase + links.size() * BEAT_OFF) % BEAT;
        Link l = new Link();
        l.g = best;
        l.part = part;
        l.lastHp = best.hp;
        links.add(l);
        LINKED.put(best, this);
        stretch(l);
        nextLink = age + LINK_EVERY;
        sound("link", Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 16);
        if (!noticed) {
            noticed = true;
            long[] last = NOTICE.computeIfAbsent(game, k -> new long[] {Long.MIN_VALUE / 2});
            if (game.now() - last[0] >= 200 || game.now() < last[0]) {
                last[0] = game.now();
                game.hud.notice("notice_g36_link", Species.G36, 60);
            }
        }
    }

    private boolean linked(Gabe g) {
        for (int i = 0; i < links.size(); i++) if (links.get(i).g == g) return true;
        return false;
    }

    private void unlink(Link l, int again) {
        if (l.part != null) l.part.remove();
        links.remove(l);
        if (LINKED.get(l.g) == this) LINKED.remove(l.g);
        if (again > 0) cut.put(l.g.id, age + again);
    }

    /** 줄 하나를 정수리에서 그 게이브 뒤통수까지 늘임(2틱마다 2틱 보간). 매듭 자리(가운데)도 기억. */
    private void stretch(Link l) {
        Gabe g = l.g;
        Vector h = g.head();
        double r = Math.toRadians(g.yaw);
        double bx = h.getX() + Math.sin(r) * g.radius * 0.7, by = h.getY() + g.radius * 0.35, bz = h.getZ() - Math.cos(r) * g.radius * 0.7;
        double x0 = pos.getX(), y0 = pos.getY() + CROWN, z0 = pos.getZ();
        dir.set((float) (bx - x0), (float) (by - y0), (float) (bz - z0));
        float len = Math.max(0.05f, dir.length());
        l.mid.setX((x0 + bx) / 2).setY((y0 + by) / 2).setZ((z0 + bz) / 2);
        dir.div(len);
        rot.rotationTo(FRONT, dir);
        mat.identity().translate(0, (float) CROWN, 0).rotate(rot).scale(1f, 1f, len);
        l.part.pose(mat, 2);
    }

    /**
     * 피해 나누기: 이어진 게이브의 체력이 지난 틱보다 깎였으면 그 몫을 이어진 것들이 나눠 갖는다(맞은 개체는 깎인 것의 (n-1)/n 을 돌려받고
     * 나머지가 1/n 씩 깎임, 체력 1 아래로는 안 내려감). 이미 죽은 것은 나누지 못한다.
     */
    private void share() {
        int n = links.size();
        if (n < 2) {
            if (n == 1) links.get(0).lastHp = links.get(0).g.hp;
            return;
        }
        boolean any = false;
        for (int i = 0; i < n; i++) {
            Link l = links.get(i);
            double d = l.lastHp - l.g.hp;
            delta[i] = d > 0.01 && !l.g.dead ? d : 0;
            any |= delta[i] > 0;
        }
        if (any) {
            for (int i = 0; i < n; i++) {
                if (delta[i] <= 0) continue;
                Link l = links.get(i);
                double each = delta[i] / n;
                l.g.hp = Math.min(l.g.maxHp, l.g.hp + each * (n - 1));
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    Gabe o = links.get(j).g;
                    if (o.dead) continue;
                    o.hp = Math.max(1, o.hp - each);
                    o.flash();
                }
            }
            if (age - shriekAt >= 10) {
                shriekAt = age;
                sound("shriek", Sound.ENTITY_WARDEN_HEARTBEAT, 1.9f, 24);
            }
        }
        for (int i = 0; i < n; i++) links.get(i).lastHp = links.get(i).g.hp;
    }

    /** 6블록 안(수평) 사람 화면이 박동에 맞춰 아주 약하게 일렁임(갔다가 되돌아오는 짝). */
    private void sway(long wt) {
        long ph = wt % BEAT;
        if (ph != 0 && ph != 3) return;
        float k = ph == 0 ? SWAY : -SWAY;
        List<Player> ps = game.mobs.standing();
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            Location l = p.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz > SWAY_R * SWAY_R) continue;
            Guns.kick(p, k, -k * 0.7f);
        }
    }

    // ------------------------------------------------------------------ 맞음, 죽음

    @Override
    public List<double[]> spheres() {
        // 얼굴 하나 + 줄마다 가운데 맥박 매듭. 배열은 다시 쓰고 줄 수만큼만 돌려줌
        double[] c = hit.get(0);
        c[0] = pos.getX();
        c[1] = pos.getY();
        c[2] = pos.getZ();
        c[3] = radius;
        int n = links.size();
        for (int i = 0; i < n; i++) {
            double[] s = hit.get(i + 1);
            Vector m = links.get(i).mid;
            s[0] = m.getX();
            s[1] = m.getY();
            s[2] = m.getZ();
            s[3] = KNOT_R;
        }
        return hit.subList(0, n + 1);
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        DamageType t = d.type();
        if (d.at() != null && (t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER) && !links.isEmpty()) {
            // 맞은 점이 얼굴보다 매듭에 가까우면 그 줄이 끊긴다
            double face = d.at().distance(pos) - radius;
            Link best = null;
            double bd = face;
            for (int i = 0; i < links.size(); i++) {
                Link l = links.get(i);
                double k = d.at().distance(l.mid) - KNOT_R;
                if (k < bd) {
                    bd = k;
                    best = l;
                }
            }
            if (best != null) {
                unlink(best, RELINK);
                nextLink = Math.max(nextLink, age + LINK_EVERY);
                sound("snap", Sound.BLOCK_HONEY_BLOCK_BREAK, 1.3f, 16);
                return super.damage(d.times(KNOT_MUL));
            }
        }
        return super.damage(d);
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 이어졌던 게이브는 모두 1초 기절, 줄은 걷음(시체에 줄이 남지 않게)
        for (int i = links.size() - 1; i >= 0; i--) {
            Link l = links.get(i);
            if (!l.g.dead) game.mobs.lure(l.g, l.g.pos.clone(), STUN);
            unlink(l, 0);
        }
        super.die(d);
    }

    @Override
    public void remove() {
        for (int i = links.size() - 1; i >= 0; i--) unlink(links.get(i), 0);
        super.remove();
    }

    /** 시험용: 이어진 수. */
    public int linkCount() {
        return links.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString()).append(" links=").append(links.size());
        for (Link l : links) sb.append(' ').append(l.g.sp.name()).append('#').append(l.g.id).append(':').append(Math.round(l.g.hp));
        return sb.toString();
    }
}
