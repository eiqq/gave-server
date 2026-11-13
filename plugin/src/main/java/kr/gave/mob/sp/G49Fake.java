package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * G49 축복이 끝난 자리에 생기는 가짜 동료(이름표 단 AI 게이브, 보상 0). 축복받았던 사람의 스킨과 이름표를 단 마네킹(G47Doll)이 걸어와
 * 동료를 때린다(4 피해, 1.25초마다). 등에 엔드 막대기 끝 둘이 삐져나와 있다. 실제 채팅은 쓰지 않고, 시스템이 지어낸 말만 머리 위에
 * 분홍 글씨와 앞머리 글리프(◇)로 가끔 띄운다. '내 얼굴, 내 이름 쓰지 않기'인 사람이면 기본 스킨에 이름표 없음.
 *
 * <p>종은 G49 로 센다(죽은 이유 글귀 cause_g49_fake). 몸은 빈 닻 하나, 맞는 구는 몸통과 머리.
 */
final class G49Fake extends Gabe {

    static final double SPEED = 0.19, HP = 60, DAMAGE = 4, REACH = 0.35;
    static final int HIT_CD = 25, SAY_EVERY = 110, SAY_T = 50;
    static final TextColor PINK = TextColor.color(0xFF8FB8);
    /** 시스템이 지어낸 말(분홍, 앞머리 글리프). */
    static final String WORDS = "◇ 같이 가자";

    private final Player like;
    private G47Doll doll;
    private TextDisplay say;
    private int nextHit, nextSay, sayUntil;

    G49Fake(Game game, Species sp, Player like) {
        super(game, sp);
        this.like = like;
        radius = 0.4;
        hover = 0.9;
        baseSpeed = SPEED;
        noReward = true;
        reward = 0;
        noMutate = true;
        leaveCorpse = false;
        maxHp = hp = HP * Math.max(1.0, game.director.hpMul());
    }

    @Override
    protected void build() {
        body = Body.carrier(this);
        double fl = game.mobs.flow.floor(pos.getX(), pos.getZ());
        doll = new G47Doll(game, new Location(game.world, pos.getX(), fl, pos.getZ(), yaw, 0), like, true);
    }

    @Override
    protected void spawned() {
        nextSay = age + 20;
        nextHit = age + 20;
        if (!game.director.cageSpawning()) sound("wake", Sound.ENTITY_SLIME_SQUISH, 0.6f, 24);
    }

    @Override
    public void tick() {
        Player p = nearest(400); // 게이브는 72~84블록에서 나오므로 반경을 넉넉히(전에는 멀면 사람을 못 찾아 외곽에서 멈춤, 2026-11-11)
        if (p == null) {
            hold();
            mark(null);
        } else {
            mark(p);
            follow(speed());
            if (dist(p) < 3) face(chest(p));
            else if (vel.lengthSquared() > 1e-6) yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
            pitch = 0;
            if (age >= nextHit && touches(p, REACH)) {
                hit(p, DAMAGE, "cause_g49_fake");
                knock(p, toward(p), 0.3, 0.1);
                if (doll != null) doll.swing();
                nextHit = age + HIT_CD;
            }
        }
        if (doll != null) doll.move(pos.getX(), floor(), pos.getZ(), yaw);
        talk();
    }

    /** 가끔 머리 위에 분홍 글씨(시스템이 지어낸 말). */
    private void talk() {
        if (say != null && age >= sayUntil) {
            say.remove();
            say = null;
        }
        if (age < nextSay || doll == null || !doll.valid()) return;
        nextSay = age + SAY_EVERY + game.random.nextInt(40);
        sayUntil = age + SAY_T;
        if (say == null) {
            say = tag(doll.man.getLocation(), 0.62f, 1.0f);
            doll.man.addPassenger(say);
        }
        say.text(Component.text(WORDS, PINK));
    }

    /** 분홍 글씨용 글 디스플레이(영속 안 함, 배경 없음). 부르는 쪽이 승객으로 태우거나 옮긴다. */
    static TextDisplay tag(Location at, float up, float scale) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        return l.getWorld().spawn(l, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(true);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setViewRange(0.5f);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTeleportDuration(2);
            t.setTransformation(new Transformation(new Vector3f(0, up, 0), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    @Override
    public List<double[]> spheres() {
        return List.of(new double[] {pos.getX(), pos.getY(), pos.getZ(), 0.42}, new double[] {pos.getX(), pos.getY() + 0.75, pos.getZ(), 0.3});
    }

    @Override
    public Vector head() {
        return new Vector(pos.getX(), pos.getY() + 0.75, pos.getZ());
    }

    private void cleanup() {
        if (say != null) say.remove();
        say = null;
        if (doll != null) doll.remove();
        doll = null;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        cleanup();
        super.die(d);
    }

    @Override
    public void remove() {
        cleanup();
        super.remove();
    }

    @Override
    public String toString() {
        return super.toString() + " fake" + (like != null ? " like=" + like.getName() : "");
    }
}
