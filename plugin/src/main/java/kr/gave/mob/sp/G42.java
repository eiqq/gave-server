package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G42 알주머니 게이브(지원, 정예).
 *
 * <p>도감: 1.6블록 얼굴 아래 막대기 넷이 거미줄 날개막을 받치고 그 밑에 반투명 알주머니. 8~10블록 높이를 느리게 활공하며 사람 위에 자리를 잡고,
 * 6초마다 '꿀렁' 하고 새끼 한 마리를 떨구거나 날개막을 2초 펼쳐 8마리를 쏟는다(디스플레이 예산이 차면 미룸). 반투명한 동안은 총알이 지나가고,
 * 떨군 직후 1.5초와 날개막을 펴는 2초만 불투명해 맞는다. 불투명할 때 알주머니를 쏘면 매달린 알이 함께 죽고(알 수 = 남은 횟수),
 * 알주머니가 아닌 곳을 맞혀 죽이면 남은 알 몇이 쏟아진다. 다 뱉으면 쪼그라든다.
 *
 * <p>바꾼 것: 반투명은 컷아웃으로 못 그려 성긴 점으로 비쳐 보이게 했다(팩 g42.py). 맵이 평지라 '지붕 없는 곳 고르기', '지붕에 튕김'은 없다.
 * 다 뱉으면 쪼그라든 채 낮게 내려와 늘 맞는다(영영 안 맞는 개체가 남아 웨이브가 멈추지 않게). 새끼는 {@link G41Baby}(종 G42, 보상 0).
 *
 * <p>몸: 닻 {@code gave:gabe/g42}(상태 {@code ""} 비침, {@code "solid"}, {@code "spread"}) + 부품 {@code gave:gabe/g42_sac}(세계 축, 상태
 * {@code ""}, {@code "solid"}, 부위 d1..d4 = 쓴 알 묶음). 소리 gulp(32), pour(32), giggle(16), peck(12), shrivel(24). 원인 글귀 {@code cause_g42}.
 */
public final class G42 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    static final double SPEED = 0.07;
    /** 활공 높이(바닥 위) 8~10, 다 뱉은 뒤 높이. */
    static final double ALT_LO = 8, ALT_HI = 10, SPENT_ALT = 3.6;
    static final double HEAD_R = 1.35, SAC_R = 1.2, SAC_DOWN = 3.0; // 2026-11-12 거대화(1.6 배)
    /** 알 수(= 남은 횟수), 떨구는 간격(6초), 떨군 뒤 불투명 틱(1.5초), 펼치는 틱(2초), 한 번에 쏟는 수. */
    static final int EGGS = 14, EVERY = 120, SOLID_T = 30, SPREAD_T = 40, POUR = 8;
    /** 자리를 잡았다고 보는 수평 거리. */
    static final double OVER_R = 5;
    /** 새끼: 피해, 체력, 수명. */
    static final double BABY_DMG = 3, BABY_HP = 6;
    static final int BABY_LIFE = 400;
    /** 알주머니 피해 배율, 알 하나가 터지는 알주머니 피해(최대 체력의 몫). */
    static final double SAC_MUL = 1.5, EGG_HP = 0.06;
    static final float PITCH = 36f;

    enum Phase { GLIDE, DROP, SPREAD, SPENT }

    private Phase phase = Phase.GLIDE;
    private int phaseAt, nextAt, eggs = EGGS, drops, poured, nextWobble;
    private double alt, sacHurt;
    private boolean solid, lastSac;
    private Player prey;
    private Body.Part sac;
    private float sacScale = 1f;

    public G42(Game game, Species sp) {
        super(game, sp);
        radius = HEAD_R;
        flying = true;
        noPush = true;
        baseSpeed = SPEED;
        alt = ALT_LO + game.random.nextDouble() * (ALT_HI - ALT_LO);
        hover = alt;
        pitch = PITCH;
        untargetable = true; // 비치는 동안 탄이 지나감
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g42", 10.5f, 11f);
        sac = body.attach("gabe/g42_sac");
        if (sac != null) {
            sac.follow = false;
            sac.pose(sacPose(1f, 1f), 0);
        }
    }

    @Override
    protected void spawned() {
        nextAt = age + 60 + game.random.nextInt(60);
        // 놓인 높이가 낮으면(걸어 나오는 종 자리) 활공 높이로 올라간다
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private Matrix4f sacPose(float wide, float tall) {
        return new Matrix4f().translate(0f, (float) -SAC_DOWN + (1f - sacScale) * 1.1f, 0f).scale(sacScale * wide, sacScale * tall, sacScale * wide);
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (prey != null && !standing(prey)) prey = null;
        switch (phase) {
            case GLIDE -> glide();
            case DROP -> dropTick();
            case SPREAD -> spread();
            case SPENT -> spent();
        }
        pitch = PITCH;
        untargetable = !solid;
        wobble();
    }

    /** 수평으로 goal 쪽, 높이는 바닥 + h 로(느리게). */
    private void fly(Vector goal, double h, double v) {
        Vector d = goal.clone().subtract(pos).setY(0);
        double l = d.length();
        if (l < 0.05) d.zero();
        else d.multiply(Math.min(v, l) / l);
        double dy = floor() + h - pos.getY();
        d.setY(Math.max(-0.07, Math.min(0.07, dy * 0.1)));
        vel.copy(d);
        if (l > 0.4) yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    /** 활공: 가장 가까운 사람 위로 가서 자리를 잡는다. 때가 되면 떨구거나 쏟는다. */
    private void glide() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) {
            vel.zero();
            return;
        }
        fly(p.getLocation().toVector(), alt, speed());
        if (age < nextAt || flat(p) > OVER_R) return;
        // 디스플레이 예산이 차면 미룸
        boolean pour = eggs >= POUR && drops >= 2 && game.mobs.budgetFor(POUR);
        if (!pour && !game.mobs.budgetFor(1)) {
            nextAt = age + 20;
            return;
        }
        prey = p;
        mark(p);
        solid = true;
        if (pour) {
            poured = 0;
            drops = 0;
            state("spread");
            if (sac != null) sac.state("solid");
            sound("pour", Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.7f, 32);
            phase(Phase.SPREAD);
        } else {
            drops++;
            state("solid");
            if (sac != null) {
                sac.state("solid");
                sac.pose(sacPose(1.22f, 0.8f), 3);
            }
            sound("gulp", Sound.ENTITY_SLIME_SQUISH, 0.5f, 32);
            baby();
            phase(Phase.DROP);
        }
    }

    /** '꿀렁' 뒤 1.5초: 불투명(맞는 틈). */
    private void dropTick() {
        vel.multiply(0.5);
        if (inPhase() == 5 && sac != null) sac.pose(sacPose(1f, 1f), 6);
        if (inPhase() >= SOLID_T) calm();
    }

    /** 날개막을 2초 펼쳐 8마리를 쏟음: 불투명(맞는 틈). */
    private void spread() {
        vel.zero();
        int t = inPhase();
        if (t % 5 == 2 && poured < POUR && eggs > 0) {
            if (game.mobs.budgetFor(1)) baby();
            poured++;
            if (sac != null) sac.pose(sacPose(t % 10 == 2 ? 1.15f : 0.92f, t % 10 == 2 ? 0.88f : 1.08f), 4);
        }
        if (t == 20) sound("giggle", Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.8f, 16);
        if (t >= SPREAD_T) calm();
    }

    private void calm() {
        mark(null);
        prey = null;
        if (eggs <= 0) {
            shrivel();
            return;
        }
        solid = false;
        state("");
        if (sac != null) {
            sac.state("");
            sac.pose(sacPose(1f, 1f), 6);
        }
        nextAt = age + cd(EVERY);
        phase(Phase.GLIDE);
    }

    /** 다 뱉음: 쪼그라들어 낮게 내려오고 늘 맞는다. */
    private void shrivel() {
        solid = true;
        state("solid");
        sacScale = 0.42f;
        if (sac != null) {
            sac.state("solid");
            sac.pose(sacPose(1f, 0.8f), 12);
        }
        sound("shrivel", Sound.ENTITY_SLIME_SQUISH, 0.5f, 24);
        phase(Phase.SPENT);
    }

    private void spent() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) {
            vel.zero();
            return;
        }
        // 사람 둘레를 느리게 맴돎(낮은 높이)
        Vector c = p.getLocation().toVector();
        Vector off = pos.clone().subtract(c).setY(0);
        if (off.lengthSquared() < 1) off = new Vector(1, 0, 0);
        off.normalize().multiply(5).rotateAroundY(0.35);
        fly(c.add(off), SPENT_ALT, speed() * 1.2);
    }

    /** 알 하나를 새끼로 떨굼. */
    private void baby() {
        if (eggs <= 0) return;
        eggs--;
        eggParts();
        double a = game.random.nextDouble() * Math.PI * 2, r = 0.25 + game.random.nextDouble() * 0.35;
        Vector at = new Vector(pos.getX() + Math.cos(a) * r, pos.getY() - SAC_DOWN - 1.0, pos.getZ() + Math.sin(a) * r);
        G41Baby b = new G41Baby(game, Species.G42, "gabe/g42_baby", "cause_g42", BABY_DMG, prey, BABY_LIFE,
                BABY_HP * Math.sqrt(Math.max(1.0, game.director.hpMul())), true);
        b.yaw = yaw + (float) Math.toDegrees(a);
        game.mobs.place(b, at);
    }

    /** 남은 알 수만큼 알 묶음을 보임(14, 10, 6, 3 아래로 한 묶음씩 사라짐). */
    private void eggParts() {
        if (sac == null) return;
        if (eggs <= 10) sac.look.on.add("d4");
        if (eggs <= 6) sac.look.on.add("d3");
        if (eggs <= 3) sac.look.on.add("d2");
        if (eggs <= 0) sac.look.on.add("d1");
        // 알이 줄수록 주머니도 조금씩 줄어듦
        if (phase != Phase.SPENT) sacScale = 0.72f + 0.28f * eggs / EGGS;
    }

    /** 알주머니 출렁임. */
    private void wobble() {
        if (sac == null || age < nextWobble || phase == Phase.DROP || phase == Phase.SPREAD) return;
        nextWobble = age + 12;
        boolean a = (age / 12) % 2 == 0;
        sac.pose(sacPose(a ? 1.05f : 0.96f, a ? 0.95f : 1.05f), 12);
    }

    // ------------------------------------------------------------------ 기하, 맞음, 죽음

    private double flat(Player p) {
        Location l = p.getLocation();
        return Math.hypot(l.getX() - pos.getX(), l.getZ() - pos.getZ());
    }

    private Vector sacPos() {
        return new Vector(pos.getX(), pos.getY() - SAC_DOWN, pos.getZ());
    }

    @Override
    public List<double[]> spheres() {
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY(), pos.getZ(), HEAD_R});
        out.add(new double[] {pos.getX(), pos.getY() - SAC_DOWN, pos.getZ(), SAC_R * sacScale});
        return out;
    }

    @Override
    public boolean damage(Damage d) {
        if (dead || untargetable || !solid) return false;
        DamageType t = d.type();
        boolean gun = t == DamageType.BULLET || t == DamageType.PELLET || t == DamageType.SNIPER;
        boolean onSac = gun && d.at() != null && d.at().distanceSquared(sacPos()) < d.at().distanceSquared(pos);
        lastSac = onSac || t == DamageType.EXPLOSION || t == DamageType.FIRE;
        if (!onSac) return super.damage(d);
        // 알주머니: 피해가 더 크고 매달린 알이 함께 터진다
        double amt = d.amount() * SAC_MUL, before = hp;
        hp -= amt;
        sacHurt += amt;
        while (eggs > 0 && sacHurt >= maxHp * EGG_HP) {
            sacHurt -= maxHp * EGG_HP;
            eggs--;
        }
        eggParts();
        game.mobs.onHurt(this, d, Math.min(before, amt));
        if (sac != null && !dead) sac.pose(sacPose(0.88f, 1.1f), 2);
        if (hp <= 0) die(d);
        return true;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        boolean cleanup = d == null || d.type() == DamageType.OTHER && d.by() == null;
        // 알주머니가 아닌 곳을 맞혀 죽이면 남은 알 몇이 쏟아진다(정리로 치워질 때는 아님)
        if (!cleanup && !lastSac && eggs > 0) {
            int n = Math.min(4, eggs);
            for (int i = 0; i < n && game.mobs.budgetFor(1); i++) baby();
        }
        super.die(d);
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + " eggs=" + eggs + (solid ? " solid" : " ghost");
    }
}
