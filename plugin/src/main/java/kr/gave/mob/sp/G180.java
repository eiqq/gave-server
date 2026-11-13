package kr.gave.mob.sp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Director;
import kr.gave.game.Game;
import kr.gave.gun.ShotFx;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G180 송곳 게이브(3막, 함정, 방해). 땅속을 헤엄쳐 다니다(흙 두둑만 보임, 안 맞음) 5 초마다 한 사람을 골라 그 사람이 지금 가는 방향과 빠르기로
 * 1.5 초 뒤에 있을 자리를 읽어 그 밑에서 높이 7 블록 송곳으로 솟는다. 1.5 초 전 땅 밑 우르릉과 그 자리 나선 금(예고), 솟는 송곳에 꿰이면 30 과
 * 위로 4 블록 날림(떨어져 8), 반경 2 안 다른 사람은 12 와 밀림. 솟은 뒤 2.5 초 동안 밑동 얼굴이 드러나 그때만 맞고, 허물을 벗어 두고 다시 땅속으로.
 * 허물 기둥(같은 종의 다른 꼴 개체, 체력 150, 총알은 거의 막고 폭발에 무너짐)은 웨이브 끝까지 남아 길과 쏠 길을 막는다(여덟까지, 넘으면 오래된 것부터 무너짐).
 *
 * <p>팩 계약(tools/gabe/sp/g180.py): 송곳 gabe/g180(shrink 0.35, 상태 "", 4 자세 돎), 허물 gabe/g180_husk, 흙 두둑 gabe/g180_mound,
 * 예고 판 gabe/g180_crack. 소리 rumble(48), drill(48), shed(32), crumble(32). 글귀 cause_g180, cause_g180_near.
 *
 * <p>도감과 다른 점: 높이 6 에서 7 블록(드릴 날 포함). 허물 기둥은 Mobs.Shootable 대신 같은 종의 다른 꼴 개체(보상 없음, 웨이브를 붙들지 않음):
 * 총알은 15% 만, 폭발과 불은 그대로 받아 무너진다. 사람은 허물 둘레 1.1 블록 밖으로 밀린다(몹은 지나감).
 */
public final class G180 extends Gabe {

    static final double HOVER = 3.0, SINK = -8.2;
    static final double SWIM = 0.3, STRIKE_R = 12, IMPALE_R = 1.45, NEAR_R = 2.2, IMPALE_DMG = 30, NEAR_DMG = 12, LAUNCH_H = 4, LAND_DMG = 8;
    static final int CYCLE = 100, TELL = 30, RISE = 4, EXPOSED = 50, SINK_T = 6, HUSK_MAX = 8, LOOK = 10, LEAD = 30;
    static final double FACE_MUL = 1.5, BODY_MUL = 0.6, MAX_LEAD = 9;
    static final double HUSK_HP = 150, HUSK_R = 1.1, HUSK_BULLET = 0.15;

    /** 허물 꼴로 놓을 때 잠깐 켜는 표시(같은 틱 안에서만, Mobs.spawn 이 생성자를 부르는 동안). */
    private static boolean nextHusk;

    enum Phase { SWIM, TELL, RISE, EXPOSED, SINK }

    private final boolean husk;
    private Phase phase = Phase.SWIM;
    private int phaseAt, nextStrike, strikeAt, strikes, impaled;
    private Player prey, lastPrey;
    private final Deque<double[]> track = new ArrayDeque<>(16);
    private final Vector spot = new Vector();
    private Body.Part mound;
    private float moundYaw = Float.NaN;
    private final Deque<G180> husks = new ArrayDeque<>();
    private final List<double[]> sph = new ArrayList<>(8);
    private int sphAge = -1;

    public G180(Game game, Species sp) {
        super(game, sp);
        husk = nextHusk;
        nextHusk = false;
        radius = 1.2;
        hover = HOVER;
        baseSpeed = SWIM;
        big = true;
        noMutate = true;
        noPush = true;
        noBite = true;
        ghost = true;
        if (husk) {
            noRules = true;
            noReward = true;
            reward = 0;
            noHold = true;
            leaveCorpse = false;
            baseSpeed = 0;
        } else {
            hpPow = Director.Tune.HP_POW_BIG;
            ragKick = 0.25;
        }
    }

    @Override
    protected void build() {
        if (husk) {
            body = Body.of(this, "gabe/g180_husk", 3.2f, 8f);
            body.viewRange(2.6f);
            return;
        }
        body = Body.of(this, "gabe/g180", 3.4f, 9f);
        body.viewRange(3f);
        body.offset.set(0, (float) SINK, 0);
        body.pose(0);
        mound = body.attach("gabe/g180_mound");
        if (mound != null) mound.follow = false;
        poseMound(0, true);
    }

    @Override
    protected void spawned() {
        if (husk) return;
        untargetable = true;
        if (game.director.cageSpawning()) return;
        nextStrike = age + 60;
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
        if (husk) {
            huskTick();
            return;
        }
        trackPrey();
        switch (phase) {
            case SWIM -> swim();
            case TELL -> tell();
            case RISE -> rise();
            case EXPOSED -> exposed();
            case SINK -> sink();
        }
        husks.removeIf(h -> h.dead || h.removed);
    }

    /** 표적 사람의 최근 자리(속도 읽기). */
    private void trackPrey() {
        if (prey != null && (!prey.isOnline() || !standing(prey))) {
            prey = null;
            track.clear();
        }
        if (prey == null) return;
        Location l = prey.getLocation();
        if (track.size() > LOOK) track.removeFirst();
        track.addLast(new double[] {l.getX(), l.getZ()});
    }

    /** 서 있는 사람 가운데 무작위(같은 사람 연속은 피함). */
    private void pickPrey() {
        List<Player> ps = game.mobs.standing();
        if (ps.isEmpty()) {
            prey = null;
            return;
        }
        List<Player> c = new ArrayList<>(ps);
        if (c.size() > 1 && lastPrey != null) c.remove(lastPrey);
        prey = c.get(game.random.nextInt(c.size()));
        track.clear();
    }

    /** 땅속을 헤엄쳐 표적 쪽으로(흙 두둑만 보임). 가까우면 갈 자리를 읽어 예고. */
    private void swim() {
        if (prey == null) pickPrey();
        if (prey == null) {
            follow(speed());
            poseMound(2, false);
            return;
        }
        Location l = prey.getLocation();
        Vector d = new Vector(l.getX() - pos.getX(), 0, l.getZ() - pos.getZ());
        double dl = d.length();
        if (dl > 2.0) {
            double v = speed() * (dl > 30 ? 1.6 : 1.0);
            vel.setX(d.getX() / dl * v);
            vel.setZ(d.getZ() / dl * v);
            yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        } else hold();
        poseMound(2, false);
        if (age >= nextStrike && dl <= STRIKE_R && track.size() >= LOOK) startTell();
    }

    /** 갈 자리 읽기: 최근 10 틱 차로 빠르기를 내어 30 틱 뒤 자리(박자 배율 안 탐). 그 밑으로 옮겨 금과 우르릉. */
    private void startTell() {
        double[] a = track.peekFirst(), b = track.peekLast();
        Location l = prey.getLocation();
        double vx = (b[0] - a[0]) / (track.size() - 1), vz = (b[1] - a[1]) / (track.size() - 1);
        double lx = vx * LEAD, lz = vz * LEAD, ll = Math.hypot(lx, lz);
        if (ll > MAX_LEAD) {
            lx *= MAX_LEAD / ll;
            lz *= MAX_LEAD / ll;
        }
        spot.setX(l.getX() + lx).setZ(l.getZ() + lz);
        spot.setY(game.mobs.flow.floor(spot.getX(), spot.getZ()));
        hold();
        phase(Phase.TELL);
        strikeAt = age;
        game.mobs.relocate(this, new Vector(spot.getX(), spot.getY() + HOVER, spot.getZ()));
        poseMound(0, true);
        G129Kit.plate(this, "gabe/g180_crack", "", spot, 1.9, TELL + 6, TELL - 6);
        sound("rumble", Sound.ENTITY_WARDEN_DIG, 0.6f, 48);
        if (game.horizon != null) game.horizon.shake(0.4, 10);
        lastPrey = prey;
    }

    private void tell() {
        hold();
        if (inPhase() == TELL / 2) G129Kit.ring(this, spot, 1.6, 10);
        if (inPhase() < TELL) return;
        body.offset.set(0, 0, 0);
        body.pose(RISE);
        sound("drill", Sound.BLOCK_GRINDSTONE_USE, 0.5f, 48);
        phase(Phase.RISE);
    }

    /** 솟음: 둘째 틱에 꿰기와 밀기. */
    private void rise() {
        hold();
        if (inPhase() == 1) strike();
        if (inPhase() < RISE) return;
        untargetable = false;
        phase(Phase.EXPOSED);
    }

    private void strike() {
        strikes++;
        G129Kit.ring(this, spot, NEAR_R + 0.5, 12);
        G141Kit.dust(this, spot, 3.5f, 14);
        if (game.horizon != null) game.horizon.shake(0.6, 8);
        Player hitOne = null;
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double h = Math.hypot(l.getX() - spot.getX(), l.getZ() - spot.getZ());
            if (h > NEAR_R || Math.abs(l.getY() - spot.getY()) > 3) continue;
            if (h <= IMPALE_R) {
                impaled++;
                hit(p, IMPALE_DMG, "cause_g180");
                Vector side = new Vector(l.getX() - spot.getX(), 0, l.getZ() - spot.getZ());
                if (side.lengthSquared() < 1e-4) side = new Vector(game.random.nextDouble() - 0.5, 0, game.random.nextDouble() - 0.5);
                side.normalize().multiply(0.18);
                if (!game.players.launch(p, LAUNCH_H, side, LAND_DMG, this, "cause_g180")) knock(p, side, 0.5, 0.9);
                if (hitOne == null) hitOne = p;
            } else {
                hit(p, NEAR_DMG, "cause_g180_near");
                knock(p, new Vector(l.getX() - spot.getX(), 0, l.getZ() - spot.getZ()), 0.9, 0.35);
            }
        }
        G129Kit.crush(this, spot, IMPALE_R, null, 0);
        mark(hitOne != null ? hitOne : prey);
    }

    /** 드러남 2.5 초: 밑동 얼굴이 보는 사람을 봄. 송곳 둘레 사람은 밖으로 밀림. 끝에 허물을 벗고 다시 땅속으로. */
    private void exposed() {
        hold();
        if (target != null && standing(target)) G141Kit.faceFlat(this, chest(target));
        shove(pos, 1.6);
        if (inPhase() < EXPOSED) return;
        untargetable = true;
        mark(null);
        shed();
        body.offset.set(0, (float) SINK, 0);
        body.pose(SINK_T);
        phase(Phase.SINK);
    }

    private void sink() {
        hold();
        if (inPhase() < SINK_T + 2) return;
        nextStrike = strikeAt + cd(CYCLE);
        pickPrey();
        phase(Phase.SWIM);
        poseMound(0, true);
    }

    /** 허물 기둥을 그 자리에 남김(여덟 넘으면 가장 오래된 것부터 무너짐). */
    private void shed() {
        while (husks.size() >= HUSK_MAX) {
            G180 old = husks.pollFirst();
            if (old != null && !old.dead) old.crumble();
        }
        nextHusk = true;
        Gabe c;
        try {
            c = child(sp, new Vector(spot.getX(), spot.getY() + HOVER, spot.getZ()));
        } finally {
            nextHusk = false;
        }
        if (c instanceof G180 h && h.husk) {
            double k = Math.max(1, Math.sqrt(maxHp / Math.max(1, sp.hp)));
            h.maxHp = h.hp = HUSK_HP * k;
            h.yaw = yaw;
            husks.addLast(h);
            sound("shed", Sound.BLOCK_STONE_BREAK, 0.6f, 32);
        }
    }

    /** 사람을 c 둘레 r 밖으로(넘어뜨리지 않고 밈). */
    private void shove(Vector c, double r) {
        for (Player p : game.mobs.standing()) {
            Location l = p.getLocation();
            double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ(), h = Math.hypot(dx, dz);
            if (h > r || Math.abs(l.getY() - (c.getY() - HOVER)) > 4) continue;
            if (h < 1e-3) {
                dx = 1;
                h = 1;
            }
            game.players.push(p, new Vector(dx / h, 0, dz / h).multiply(0.22 * (1.2 - h / r)));
        }
    }

    /** 흙 두둑: 땅속에 있을 때만 땅 위(세계 축, 나아가는 쪽으로 길쭉). */
    private void poseMound(int ticks, boolean force) {
        if (mound == null) return;
        boolean show = phase == Phase.SWIM;
        if (!force && show && !Float.isNaN(moundYaw) && Math.abs(G141Kit.wrap(yaw - moundYaw)) < 8 && age % 10 != 0) return;
        moundYaw = yaw;
        float y = show ? (float) (game.mobs.flow.floor(pos.getX(), pos.getZ()) - pos.getY() + 0.02) : -14f;
        mound.pose(new Matrix4f().translate(0, y, 0).rotateY((float) Math.toRadians(-yaw)), ticks);
    }

    // ------------------------------------------------------------------ 허물

    private void huskTick() {
        hold();
        shove(pos, HUSK_R);
    }

    /** 오래된 허물이 무너짐(보상 없음). */
    void crumble() {
        if (dead) return;
        G141Kit.dust(this, pos.clone().setY(pos.getY() - HOVER), 3f, 16);
        if (!game.director.silent())
            kr.gave.Props.soundAround(loc(), "gabe.g180.crumble", Sound.BLOCK_STONE_BREAK, 1f, 0.9f, 0.6f, 32, -1);
        noReward = true;
        die(Damage.of(hp + 1, DamageType.OTHER));
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public List<double[]> spheres() {
        if (sphAge == age && !sph.isEmpty()) return sph;
        sph.clear();
        double yo = husk ? 0 : body == null ? 0 : body.offset.y;
        double f = Math.toRadians(yaw);
        double fx = -Math.sin(f), fz = Math.cos(f);
        sph.add(new double[] {pos.getX(), pos.getY() - 1.9 + yo, pos.getZ(), 1.25});
        sph.add(new double[] {pos.getX(), pos.getY() - 0.3 + yo, pos.getZ(), 1.0});
        sph.add(new double[] {pos.getX(), pos.getY() + 1.3 + yo, pos.getZ(), 0.7});
        sph.add(new double[] {pos.getX(), pos.getY() + 2.9 + yo, pos.getZ(), 0.45});
        if (!husk) sph.add(new double[] {pos.getX() + fx * 1.35, pos.getY() - 2.1 + yo, pos.getZ() + fz * 1.35, 0.75});
        sphAge = age;
        return sph;
    }

    @Override
    public Vector head() {
        double f = Math.toRadians(yaw);
        return pos.clone().add(new Vector(-Math.sin(f) * 1.35, -2.1, Math.cos(f) * 1.35));
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable) return false;
        if (husk) {
            boolean blast = d.type() == DamageType.EXPLOSION || d.type() == DamageType.FIRE;
            return super.damage(blast ? d : d.times(HUSK_BULLET));
        }
        boolean face = false;
        if (d.at() != null) {
            List<double[]> s = spheres();
            double[] fc = s.get(s.size() - 1);
            double best = Double.MAX_VALUE, fd = 0;
            for (int i = 0; i < s.size(); i++) {
                double[] c = s.get(i);
                double dd = Math.sqrt(Math.pow(d.at().getX() - c[0], 2) + Math.pow(d.at().getY() - c[1], 2) + Math.pow(d.at().getZ() - c[2], 2)) - c[3];
                if (dd < best) best = dd;
                if (c == fc) fd = dd;
            }
            face = fd <= best + 1e-6;
        }
        return super.damage(d.times(face ? FACE_MUL : BODY_MUL));
    }

    @Override
    public void die(Damage d) {
        if (!husk && game.shotFx.room(ShotFx.NORMAL) && body != null && body.offset.y > -1)
            game.shotFx.bloodHit(new Location(game.world, pos.getX(), pos.getY() - 1.5, pos.getZ()), new Vector(0, 1, 0), true, ShotFx.BLOOD_PINK);
        if (!husk && body != null && body.offset.y < -1) leaveCorpse = false;
        super.die(d);
    }

    @Override
    public String toString() {
        if (husk) return super.toString() + " husk";
        return super.toString() + " ph=" + phase + " prey=" + (prey == null ? "-" : prey.getName()) + " strikes=" + strikes + " impaled=" + impaled
            + " husks=" + husks.size() + " off=" + String.format("%.1f", body == null ? 0 : body.offset.y);
    }
}
