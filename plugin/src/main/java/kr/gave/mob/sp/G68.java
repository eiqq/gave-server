package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G68 오뚝이 게이브(1막 보상, 돌격). 좌우로 뒤뚱거리며 굴러와 한 사람 앞 2블록에 서서 손을 내민다(조준점 아래 값 게이지). 2초 안에 돈을 안 주면
 * 뒤로 젖혔다 박는다(14 피해와 밀침, 쓰러졌다 서는 반동으로 연달아 두 번). 돕기 키로 값을 내면 끄덕이고 물러났다가 12초 뒤 값을 두 배로 올려 다시 온다
 * (10, 20, 40원). 받을 때마다 배가 부풀고 배 속에 동전이 비친다. 네 번째로 찾아오면 손을 안 내밀고 온몸으로 깔아뭉갠다(30 피해와 1초 묶임).
 *
 * <p>잡으면 뜯긴 돈의 1.5배를 낸 사람마다 돌려받는다(안 뜯겼으면 보통 보상 8원). 맞으면 크게 기울었다 벌떡 선다.
 *
 * <p>팩 계약(tools/gabe/sp/g68.py): 모델 gabe/g68 상태 "", "ask", "back", 부위 coin1..coin3. 소리 jingle, nod, bump, crush.
 * 글귀 cause_g68, 값 게이지 ask_g68_10, ask_g68_20, ask_g68_40.
 */
public final class G68 extends Gabe {

    static final double SPEED = 0.2, HOVER = 0.8;
    /** 손 내미는 거리, 기다리는 틱(박자 배율 안 받음: 대처 창), 값(찾아온 차례마다). */
    static final double ASK_RANGE = 2.0;
    static final int ASK_T = 40;
    static final int[] PRICE = {10, 20, 40};
    /** 박치기: 젖히는 틱, 피해, 밀침, 닿는 거리, 두 번 사이, 쉬는 틱. */
    static final int BACK_T = 8, BOUNCE = 8, REST = 30;
    static final double RAM_DMG = 14, RAM_PUSH = 0.8, RAM_UP = 0.3, RAM_REACH = 1.9;
    /** 물러나기: 거리, 최대 틱, 다시 오기까지 틱. */
    static final double RETREAT = 12;
    static final int RETREAT_MAX = 90, WAIT = 240;
    /** 깔아뭉개기(넷째): 젖히는 틱, 덮치는 빠르기, 피해, 묶임, 쉬는 틱. */
    static final int CRUSH_BACK = 20, CRUSH_REST = 40, CRUSH_ROOT = 20;
    static final double CRUSH_V = 0.45, CRUSH_DMG = 30;
    /** 받을 때마다 부풂(배율). */
    static final float[] SCALE = {1f, 1.25f, 1.5f, 1.7f};
    static final double REFUND = 1.5;

    enum Phase { APPROACH, ASK, BACK, RAM, REST, RETREAT, WAIT, CRUSH_BACK, CRUSH, CRUSH_REST }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, visit, nextJingle;
    private Player prey;
    private final Map<Player, Integer> paid = new LinkedHashMap<>();
    private final Vector away = new Vector(), rushDir = new Vector();
    private double tilt;

    public G68(Game game, Species sp) {
        super(game, sp);
        radius = 0.7;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        body = Body.of(this, "gabe/g68", 1.9f, 2.2f);
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    private int price() {
        return PRICE[Math.min(visit, PRICE.length - 1)];
    }

    @Override
    public void tick() {
        switch (phase) {
            case APPROACH -> approach();
            case ASK -> ask();
            case BACK -> back();
            case RAM -> ram();
            case REST -> rest();
            case RETREAT -> retreat();
            case WAIT -> waitOut();
            case CRUSH_BACK -> crushBack();
            case CRUSH -> crush();
            case CRUSH_REST -> crushRest();
        }
        // 뒤뚱: 걸을 때 좌우로 흔들리고, 맞으면 크게 기울었다 벌떡
        tilt *= 0.86;
        double mv = Math.hypot(vel.getX(), vel.getZ());
        body.roll = (float) (Math.sin(age * 0.45) * Math.min(14, mv * 90) + tilt * Math.cos(age * 0.6));
    }

    private void approach() {
        follow(speed());
        Player p = nearest(400);
        face(p != null ? chest(p) : pos.clone().add(vel));
        pitch = 0;
        if (age >= nextJingle) {
            nextJingle = age + 26 + game.random.nextInt(14);
            sound("jingle", Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 20);
        }
        if (p == null || dist(p) > ASK_RANGE + radius) return;
        prey = p;
        mark(p);
        hold();
        if (visit >= PRICE.length) {
            state("back");
            phase(Phase.CRUSH_BACK);
        } else {
            state("ask");
            phase(Phase.ASK);
        }
    }

    /** 손 내밀고 기다림. 표적에게 값 게이지. */
    private void ask() {
        if (!standing(prey)) {
            game.hud.meter(prey, null, -1, 0);
            calm();
            return;
        }
        face(chest(prey));
        hold();
        int t = inPhase();
        game.hud.meter(prey, "ask_g68_" + price(), 1.0 - (double) t / ASK_T, 4);
        if (t >= ASK_T) {
            game.hud.meter(prey, null, -1, 0);
            state("back");
            phase(Phase.BACK);
        }
    }

    /** 뒤로 크게 젖힘(박치기 예고). */
    private void back() {
        if (!standing(prey)) {
            calm();
            return;
        }
        face(chest(prey));
        hold();
        pitch = (float) (-28.0 * Math.min(1, inPhase() / (double) cd(BACK_T)));
        if (inPhase() >= cd(BACK_T)) {
            rushDir.copy(toward(prey));
            state("");
            phase(Phase.RAM);
        }
    }

    /** 박치기 두 번: 앞으로 쏠려 박고, 반동으로 젖혔다 한 번 더. */
    private void ram() {
        int t = inPhase();
        if (t == 0 || t == cd(BOUNCE)) {
            pitch = 22;
            vel.copy(rushDir).multiply(0.32);
            if (standing(prey) && touches(prey, RAM_REACH)) {
                hit(prey, RAM_DMG, "cause_g68");
                knock(prey, rushDir, RAM_PUSH, RAM_UP);
                sound("bump", Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 16);
                body.squash(1.15f, 0.85f, 1.15f, 2);
            }
        } else {
            vel.multiply(0.5);
            pitch = (float) (22 * Math.cos((t % cd(BOUNCE)) * Math.PI / cd(BOUNCE)));
        }
        if (t >= cd(BOUNCE) * 2) {
            pitch = 0;
            body.squash(1f, 1f, 1f, 3);
            phase(Phase.REST);
        }
    }

    private void rest() {
        vel.multiply(0.6);
        if (inPhase() >= cd(REST)) calm();
    }

    private void calm() {
        if (target != null) mark(null);
        prey = null;
        pitch = 0;
        state("");
        phase(Phase.APPROACH);
    }

    /** 돈을 받음: 끄덕이고 물러남. */
    private void retreat() {
        seek(pos.clone().add(away), speed() * 1.2);
        face(pos.clone().subtract(away));
        if (inPhase() >= RETREAT_MAX || wallAhead(0.6)) phase(Phase.WAIT);
    }

    private void waitOut() {
        hold();
        Player p = nearest(400);
        if (p != null) face(chest(p));
        if (inPhase() >= cd(WAIT)) phase(Phase.APPROACH);
    }

    /** 넷째: 손을 안 내밀고 뒤로 젖혔다가 온몸으로 깔아뭉갬. */
    private void crushBack() {
        if (!standing(prey)) {
            calm();
            return;
        }
        face(chest(prey));
        hold();
        pitch = (float) (-32.0 * Math.min(1, inPhase() / (double) cd(CRUSH_BACK)));
        if (inPhase() % 6 == 0) sound("jingle", Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 20);
        if (inPhase() >= cd(CRUSH_BACK)) {
            rushDir.copy(toward(prey));
            state("");
            phase(Phase.CRUSH);
        }
    }

    private void crush() {
        dash(rushDir, CRUSH_V);
        pitch = 40;
        if (standing(prey) && touches(prey, 0.5)) {
            hit(prey, CRUSH_DMG, "cause_g68");
            game.players.root(prey, CRUSH_ROOT, "g68_crush");
            sound("crush", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.6f, 24);
            body.squash(1.3f, 0.7f, 1.3f, 2);
            hold();
            phase(Phase.CRUSH_REST);
            return;
        }
        if (inPhase() >= 12) phase(Phase.CRUSH_REST);
    }

    private void crushRest() {
        vel.multiply(0.5);
        pitch = (float) (40 * Math.max(0, 1 - inPhase() / 10.0));
        if (inPhase() >= 4) body.squash(1f, 1f, 1f, 6);
        if (inPhase() >= cd(CRUSH_REST)) calm();
    }

    // ------------------------------------------------------------------ 돈

    /** 돕기 키(좌클릭)로 이 개체를 보고 값을 냄. 손을 내밀 때만, 돈이 모자라면 안 씀. */
    @Override
    public boolean helped(Player p) {
        if (phase != Phase.ASK || p == null) return false;
        int cost = price();
        if (!game.players.spend(p, cost)) return false;
        paid.merge(p, cost, Integer::sum);
        game.hud.meter(prey, null, -1, 0);
        if (prey != null && prey != p) game.hud.meter(p, null, -1, 0);
        int coins = Math.min(3, visit + 1);
        body.look.on.add("coin" + coins);
        body.scale(SCALE[Math.min(SCALE.length - 1, visit + 1)], 8);
        sound("jingle", Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 20);
        sound("nod", Sound.BLOCK_WOOL_STEP, 0.8f, 12);
        body.squash(1.05f, 0.85f, 1.05f, 3);
        visit++;
        Vector a = prey != null && standing(prey) ? toward(prey).multiply(-1) : game.mobs.flow.dir(pos).multiply(-1);
        away.copy(a.setY(0).normalize().multiply(RETREAT));
        if (target != null) mark(null);
        prey = null;
        pitch = 0;
        state("");
        phase(Phase.RETREAT);
        return true;
    }

    @Override
    public boolean damage(Damage d) {
        boolean r = super.damage(d);
        if (r && !dead) tilt = Math.min(48, tilt + 10 + d.amount());
        return r;
    }

    @Override
    public void die(Damage d) {
        if (!dead && !noReward && !paid.isEmpty()) {
            // 뜯긴 돈의 1.5배를 낸 사람마다 돌려줌(보통 보상 대신)
            reward = 0;
            for (Map.Entry<Player, Integer> e : paid.entrySet()) {
                Player p = e.getKey();
                if (p == null || !p.isOnline()) continue;
                // 뜯긴 돈은 돌려받기(돈 배율 없음), 덤(REFUND - 1)만 벌이
                game.players.addMoneyRaw(p, e.getValue() + (int) Math.round(e.getValue() * (REFUND - 1) * game.players.moneyMul));
            }
        }
        if (prey != null) game.hud.meter(prey, null, -1, 0);
        super.die(d);
    }

    @Override
    public List<double[]> spheres() {
        float s = body != null ? body.scale : 1f;
        List<double[]> out = new ArrayList<>(2);
        out.add(new double[] {pos.getX(), pos.getY() + 0.42 * s, pos.getZ(), 0.5 * s});
        out.add(new double[] {pos.getX(), pos.getY() - 0.4 * s, pos.getZ(), 0.7 * s});
        return out;
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public String toString() {
        int sum = 0;
        for (int v : paid.values()) sum += v;
        return super.toString() + " ph=" + phase + " visit=" + visit + " paid=" + sum;
    }
}
