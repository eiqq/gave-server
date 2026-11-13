package kr.gave.mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

/**
 * 무리 닻 도우미(떼 종: G02, G04, G11, G26, G40, G45, G53 ...). 빈 닻 디스플레이 하나({@link Body#carrier})에 마리마다 디스플레이 하나를
 * 승객으로 태운다. 무리 전체가 하나의 {@link Gabe}(위치 = 닻, 체력 = 마리들)이고, 마리는 닻 기준 오프셋(세계 축, 블록)으로만 움직인다.
 *
 * <p>만들기: 종의 build() 에서 {@code swarm = game.mobs.swarm(this, "gabe/g02", n);} (body 가 없으면 빈 닻을 만들어 붙임).
 * 마리 자리: {@link Member#home} 에 몸 기준 대형 오프셋(x 옆, y 위, z 앞)을 넣고 {@link #heading(float)} 로 돌리면 15도 넘게 꺾일 때
 * 모든 마리가 같은 틱에 휙 돈다. 흩어짐은 {@link Member#moveTo(Vector, int)}(translation 보간, 서버도 같은 직선으로 따라가 판정이 맞음).
 *
 * <p>무리 LOD: {@link #lodDist} 블록 밖 사람에게는 마리들을 숨기고 덩어리 디스플레이 하나(같은 모델, 상태 {@link #lodState})만 보인다.
 * 사람마다 따로(Player#hideEntity), 5틱마다 다시 재고 1블록 문턱을 둔다. 닻은 누구에게나 보인다(숨기면 승객이 클라에서 제자리에 멈춤).
 */
public final class Swarm {

    public final Gabe owner;
    public final Body body;
    public final String model;
    public final List<Member> members = new ArrayList<>();
    /** 덩어리 디스플레이(사람이 멀 때). null 이면 LOD 없음. */
    public ItemDisplay lod;
    public String lodState = "lod";
    public double lodDist = 15;
    /** 마리 모델 크기 배율, 덩어리 크기 배율. */
    public float memberScale = 1f, lodScale = 1f;
    /** 휙 돌 때 보간 틱. */
    public int turnTicks = 2;
    /** 무리 방향(도, Gabe.yaw 와 같은 약속). */
    public float heading;
    /** 마리 맞는 구 반지름(탄 판정은 여기에 0.25 를 더 얹음). */
    public double memberRadius = 0.18;

    private float sentHeading;
    private final Map<UUID, Boolean> far = new HashMap<>();
    private long lodAt = Long.MIN_VALUE / 2;
    private long flinchUntil;
    private boolean flinched;
    private long gazeStamp = -1;
    private LookState lodSentLook;
    private boolean lodSentFreeze;

    Swarm(Gabe owner, Body body, String model) {
        this.owner = owner;
        this.body = body;
        this.model = model;
        this.heading = this.sentHeading = owner.yaw;
    }

    // ------------------------------------------------------------------ 마리

    /** 마리 하나. 오프셋은 닻(owner.pos) 기준 세계 축 블록. */
    public final class Member {
        public final int index;
        public ItemDisplay d;
        /** 몸 기준 대형 자리(x 옆, y 위, z 앞). {@link #formation} 이 heading 으로 돌려 쓴다. */
        public final Vector home = new Vector();
        public float yaw, pitch, roll;
        public float scale = 1f;
        public double hp = -1;
        public boolean dead;
        public final LookState look = new LookState();
        String st = "";
        private final Vector from = new Vector(), to = new Vector();
        private long t0;
        private int dur;
        private boolean poseDirty, itemDirty;
        private int poseTicks = 2;
        private String sentState = "";
        private LookState sentLook;
        private boolean sentFreeze;

        Member(int index) {
            this.index = index;
        }

        /** 지금 오프셋(서버가 클라 보간과 같은 직선으로 따라감). */
        public Vector off() {
            long now = owner.game.now();
            if (dur <= 0 || now >= t0 + dur) return to.clone();
            double t = (double) (now - t0) / dur;
            return from.clone().add(to.clone().subtract(from).multiply(t));
        }

        /** 세계 위치. */
        public Vector pos() {
            return owner.pos.clone().add(off());
        }

        /** 오프셋 off(세계 축)로 ticks 동안 옮김. */
        public void moveTo(Vector off, int ticks) {
            from.copy(off());
            to.copy(off);
            t0 = owner.game.now();
            dur = Math.max(0, ticks);
            poseTicks = dur;
            poseDirty = true;
        }

        /** 방향(도). 다음 sync 에 ticks 보간. */
        public void face(float yaw, float pitch, float roll, int ticks) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            poseTicks = Math.max(0, ticks);
            poseDirty = true;
        }

        /** 세계 점 at 을 보게. */
        public void lookAt(Vector at, int ticks) {
            Vector p = pos();
            double dx = at.getX() - p.getX(), dy = at.getY() - p.getY(), dz = at.getZ() - p.getZ();
            face((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz))), roll, ticks);
        }

        public void state(String s) {
            st = s == null ? "" : s;
            itemDirty = true;
        }

        public String stateName() {
            return st;
        }

        /** 이 마리의 틀(닻 자리 기준, 모델을 반 바퀴 돌리기 전). 래그돌이 조각으로 넘겨받을 때도 쓴다. */
        Matrix4f rig() {
            Vector o = to;
            float s = memberScale * scale * (flinched ? 0.75f : 1f) * Look.scale(model);
            return new Matrix4f().translate((float) o.getX(), (float) o.getY(), (float) o.getZ()).rotateY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(roll)).scale(s);
        }

        Matrix4f matrix() {
            return rig().rotateY((float) Math.PI);
        }

        /** 오프셋이 바뀌는 빠르기(블록/틱, 옮기는 중이 아니면 0). 래그돌이 이어받는다. */
        Vector velocity() {
            long now = owner.game.now();
            if (dur <= 0 || now >= t0 + dur) return new Vector();
            return to.clone().subtract(from).multiply(1.0 / dur);
        }

        void sync(long now, long worldTime) {
            if (d == null || !d.isValid()) return;
            if (poseDirty) {
                // 옮기는 중이면 남은 틱으로 보간(움찔, 돌아보기가 마리를 목적지로 튀게 하지 않음: 서버 off() 와 같은 직선)
                long rem = t0 + dur - now;
                int t = rem > 0 ? (int) rem : poseTicks;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(t);
                d.setTransformationMatrix(matrix());
                poseDirty = false;
            }
            boolean freeze = body.forceFreeze;
            boolean settled = Look.settle(look, worldTime);
            if (settled || itemDirty || !st.equals(sentState) || !look.equals(sentLook) || freeze != sentFreeze) {
                d.setItemStack(Body.item(model, st, frozen(look, freeze), worldTime));
                sentState = st;
                sentLook = look.copy();
                sentFreeze = freeze;
                itemDirty = false;
            }
        }
    }

    /** 마리 하나 더(닻 승객). 대형 자리 home(몸 기준), 상태 state. 멀리 있는 사람에게는 숨긴 채로 낳는다. */
    public Member add(Vector home, String state) {
        Member m = new Member(members.size());
        m.home.copy(home);
        m.st = state == null ? "" : state;
        m.look.phase = body.look.phase;
        m.yaw = heading;
        Vector off = rotate(home, heading);
        m.to.copy(off);
        m.from.copy(off);
        ItemStack it = Body.item(model, m.st, m.look, owner.game.worldTime());
        m.sentState = m.st;
        m.sentLook = m.look.copy();
        Matrix4f mx = m.matrix();
        Location at = body.anchor.getLocation();
        m.d = at.getWorld().spawn(at, ItemDisplay.class, d -> Body.setup(d, it, mx, 1.2f, 0, 0));
        body.anchor.addPassenger(m.d);
        for (Map.Entry<UUID, Boolean> e : far.entrySet()) {
            if (!e.getValue()) continue;
            Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideEntity(owner.game.plugin, m.d);
        }
        for (Player p : body.hiddenFrom) p.hideEntity(owner.game.plugin, m.d);
        members.add(m);
        return m;
    }

    /** 덩어리 디스플레이를 만든다(Mobs.swarm 이 부름). */
    void makeLod() {
        if (lod != null || body.anchor == null) return;
        Matrix4f mx = lodMatrix();
        lodSentLook = body.look.copy();
        lodSentFreeze = body.forceFreeze;
        ItemStack it = Body.item(model, lodState, frozen(body.look, lodSentFreeze), owner.game.worldTime());
        Location at = body.anchor.getLocation();
        lod = at.getWorld().spawn(at, ItemDisplay.class, d -> Body.setup(d, it, mx, 2.0f, 0, 0));
        body.anchor.addPassenger(lod);
    }

    private Matrix4f lodMatrix() {
        return new Matrix4f().rotateY((float) Math.toRadians(-heading)).scale(lodScale * Look.scale(model)).rotateY((float) Math.PI);
    }

    /** 소리 없는 웨이브(엔진 forceFreeze)면 freeze 를 켠 사본, 아니면 그대로. */
    static LookState frozen(LookState ls, boolean force) {
        if (!force || ls.freeze) return ls;
        LookState c = ls.copy();
        c.freeze = true;
        return c;
    }

    /** 몸 기준 v(x 옆, y 위, z 앞)를 yaw 방향 세계 축으로. */
    public static Vector rotate(Vector v, float yaw) {
        double a = Math.toRadians(-yaw), c = Math.cos(a), s = Math.sin(a);
        // JOML rotateY(a): x' = x c + z s, z' = -x s + z c
        return new Vector(v.getX() * c + v.getZ() * s, v.getY(), -v.getX() * s + v.getZ() * c);
    }

    // ------------------------------------------------------------------ 무리 움직임

    /** 모든 산 마리를 대형 자리(home 을 heading 으로 돌린 것)로, 방향도 heading 으로(기울기 0) ticks 동안. */
    public void formation(float yaw, int ticks) {
        heading = yaw;
        sentHeading = yaw;
        for (Member m : members) {
            if (m.dead) continue;
            m.moveTo(rotate(m.home, yaw), ticks);
            m.face(yaw, 0, 0, ticks);
        }
        if (lod != null) {
            lod.setInterpolationDelay(0);
            lod.setInterpolationDuration(ticks);
            lod.setTransformationMatrix(lodMatrix());
        }
    }

    /** 무리 방향을 바꾼다. 마지막 보낸 것과 15도 넘게 다르면 모든 마리가 같은 틱에 휙 돈다(true). */
    public boolean heading(float yaw) {
        heading = yaw;
        float d = (yaw - sentHeading) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        if (Math.abs(d) <= Body.TURN_DEG) return false;
        formation(yaw, turnTicks);
        return true;
    }

    /** 모든 산 마리가 같이 움찔(0.5초에 한 번까지). */
    public void flinch() {
        long now = owner.game.now();
        if (now < flinchUntil + 6) return;
        flinchUntil = now + 3;
        flinched = true;
        for (Member m : members) {
            if (m.dead) continue;
            m.poseTicks = 1;
            m.poseDirty = true;
        }
    }

    void flash(long until) {
        // 무리는 맞은 마리만 번쩍이게 종이 직접 고른다(전부 다시 실으면 패킷이 많음)
    }

    /** 산 마리 수. */
    public int alive() {
        int n = 0;
        for (Member m : members) if (!m.dead) n++;
        return n;
    }

    /** at 에 가장 가까운 산 마리(없으면 null). at 이 null 이면 아무 산 마리. */
    public Member nearest(Vector at) {
        Member best = null;
        double bd = Double.MAX_VALUE;
        for (Member m : members) {
            if (m.dead) continue;
            if (at == null) return m;
            double d = m.pos().distanceSquared(at);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    /** 반경 안 산 마리들. */
    public List<Member> within(Vector at, double r) {
        List<Member> out = new ArrayList<>();
        double r2 = r * r;
        for (Member m : members) if (!m.dead && m.pos().distanceSquared(at) <= r2) out.add(m);
        return out;
    }

    /** 반경 안에 산 마리가 하나라도 있나. */
    public boolean anyWithin(Vector at, double r) {
        double r2 = r * r, ox = owner.pos.getX() - at.getX(), oy = owner.pos.getY() - at.getY(), oz = owner.pos.getZ() - at.getZ();
        for (Member m : members) {
            if (m.dead) continue;
            Vector o = m.off();
            double dx = ox + o.getX(), dy = oy + o.getY(), dz = oz + o.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    /** 산 마리들의 가운데(세계 좌표). 산 마리가 없으면 닻 자리. */
    public Vector centroid() {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (Member m : members) {
            if (m.dead) continue;
            Vector o = m.off();
            x += o.getX();
            y += o.getY();
            z += o.getZ();
            n++;
        }
        Vector c = owner.pos.clone();
        return n == 0 ? c : c.add(new Vector(x / n, y / n, z / n));
    }

    /** 맞는 구들(마리마다 하나, 반지름 memberRadius). Gabe.spheres() 에서 그대로 돌려주면 된다. */
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(members.size());
        double ox = owner.pos.getX(), oy = owner.pos.getY(), oz = owner.pos.getZ();
        for (Member m : members) {
            if (m.dead) continue;
            Vector o = m.off();
            out.add(new double[] {ox + o.getX(), oy + o.getY(), oz + o.getZ(), memberRadius});
        }
        return out;
    }

    /**
     * 마리 하나 죽음: 닻에서 내려 작은 래그돌 사체 하나가 된다(맞은 쪽으로 튕겨 구름, 사슬 없음. {@link Ragdoll#startMember}). 래그돌
     * 예산이 없으면 예전처럼 그 자리 바닥으로 납작해지며 시체 풀로(짧게). 보상, 무리 죽음은 종이 정한다(산 마리가 0 이면 owner.die).
     */
    public void kill(Member m) {
        if (m.dead) return;
        m.dead = true;
        if (m.d == null || !m.d.isValid()) return;
        Vector wp = m.pos();
        body.anchor.removePassenger(m.d);
        if (owner.game.mobs.ragdoll.startMember(this, m, wp)) {
            m.d = null;
            return;
        }
        double fl = owner.game.mobs.flow.floor(wp.getX(), wp.getZ());
        Vector o = m.off();
        float y = (float) (fl + 0.04 - body.anchor.getLocation().getY());
        float k = memberScale * Look.scale(model);
        Matrix4f flat = new Matrix4f().translate((float) o.getX(), y, (float) o.getZ()).rotateY((float) Math.toRadians(-m.yaw))
                .scale(k * 1.2f, k * 0.12f, k * 1.2f).rotateY((float) Math.PI);
        LookState ls = m.look.copy();
        ls.freeze = true;
        ls.open = false;
        m.d.setItemStack(Body.item(model, m.st, ls, owner.game.worldTime()));
        m.d.setInterpolationDelay(0);
        m.d.setInterpolationDuration(6);
        m.d.setTransformationMatrix(flat);
        owner.game.mobs.addCorpse(owner.sp, wp, m.d, 60);
        m.d = null;
    }

    /** 남은 마리 모두 시체로(무리 죽음). */
    void dieAll() {
        for (Member m : members) kill(m);
        if (lod != null) lod.remove();
        lod = null;
    }

    void removeAll() {
        for (Member m : members) {
            if (m.d != null) m.d.remove();
            m.d = null;
            m.dead = true;
        }
        if (lod != null) lod.remove();
        lod = null;
    }

    /** 실제 살아 있는 디스플레이 수(산 마리 + 덩어리). 닻은 Body 가 센다. */
    public int displays() {
        int n = lod != null ? 1 : 0;
        for (Member m : members) if (!m.dead && m.d != null) n++;
        return n;
    }

    void entities(List<Entity> out) {
        for (Member m : members) if (m.d != null) out.add(m.d);
        if (lod != null) out.add(lod);
    }

    // ------------------------------------------------------------------ 엔진(Body.move 가 부름)

    void sync() {
        long now = owner.game.now();
        if (flinched && now >= flinchUntil) {
            flinched = false;
            for (Member m : members) {
                if (m.dead) continue;
                m.poseTicks = 3;
                m.poseDirty = true;
            }
        }
        Player gz = owner.game.mobs.gazeTarget(owner);
        if (gz != null && (gazeStamp < 0 || now - gazeStamp >= 10)) {
            gazeStamp = now;
            Vector c = Mobs.chest(gz);
            for (Member m : members) if (!m.dead) m.lookAt(c, 1);
        } else if (gz == null && gazeStamp >= 0) {
            gazeStamp = -1;
            for (Member m : members) if (!m.dead) m.face(heading, 0, 0, 3);
        }
        long wt = owner.game.worldTime();
        for (Member m : members) if (!m.dead) m.sync(now, wt);
        if (lod != null && lod.isValid() && (!body.look.equals(lodSentLook) || body.forceFreeze != lodSentFreeze)) {
            // 덩어리는 닻 겉모습(실눈 뜸, 무리 번쩍임, 소리 없는 웨이브 멈춤)을 따름
            lodSentLook = body.look.copy();
            lodSentFreeze = body.forceFreeze;
            lod.setItemStack(Body.item(model, lodState, frozen(body.look, lodSentFreeze), wt));
        }
        if (lod != null && now - lodAt >= 5) {
            lodAt = now;
            lodCheck();
        }
    }

    private void lodCheck() {
        Vector c = owner.pos;
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (Player p : owner.game.world.getPlayers()) {
            UUID id = p.getUniqueId();
            seen.add(id);
            if (body.hiddenFrom.contains(p)) continue;
            Location e = p.getEyeLocation();
            double dx = e.getX() - c.getX(), dy = e.getY() - c.getY(), dz = e.getZ() - c.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            Boolean was = far.get(id);
            boolean isFar = was == null ? d > lodDist : was ? d > lodDist - 1 : d > lodDist + 1;
            if (was != null && was == isFar) continue;
            far.put(id, isFar);
            apply(p, isFar);
        }
        for (Iterator<UUID> it = far.keySet().iterator(); it.hasNext();) if (!seen.contains(it.next())) it.remove();
    }

    private void apply(Player p, boolean isFar) {
        var pl = owner.game.plugin;
        for (Member m : members) {
            if (m.dead || m.d == null) continue;
            if (isFar) p.hideEntity(pl, m.d);
            else p.showEntity(pl, m.d);
        }
        if (lod != null) {
            if (isFar) p.showEntity(pl, lod);
            else p.hideEntity(pl, lod);
        }
    }

    /** Body.hide(p, false) 뒤: 그 사람의 LOD 상태를 다시 잼. */
    void reshow(Player p) {
        far.remove(p.getUniqueId());
        lodAt = Long.MIN_VALUE / 2;
    }

    /** 이 사람에게 지금 덩어리로 보이나. */
    public boolean farFor(Player p) {
        return Boolean.TRUE.equals(far.get(p.getUniqueId()));
    }
}
