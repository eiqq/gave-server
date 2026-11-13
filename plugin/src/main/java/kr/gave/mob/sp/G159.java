package kr.gave.mob.sp;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import kr.gave.game.Game;
import kr.gave.gun.Guns;
import kr.gave.mob.Damage;
import kr.gave.mob.DamageType;
import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * G159 민들레 홀씨 게이브(떼, 원거리). 손바닥만 한 얼굴 홀씨가 바람 타듯 둥실둥실 사람 쪽으로 흘러온다(멀면 FAR_SPEED, 가까우면 SPEED, 위아래로
 * 일렁임). 사람에게 닿으면 터져 POP 피해와 재채기(조준 흔들림), 사체 없음.
 *
 * <p>내려앉음: 사람 NEAR 블록 안에 든 뒤 개체마다 정한 틱(DRIFT_MIN..DRIFT_MAX)이 지나면 천천히 내려앉는다. 땅에 닿았을 때 EMPTY_R 안에 서 있는
 * 사람이 있으면 시들고(사라짐), 빈 땅이면 같은 개체가 꽃 모델(gabe/g159_flower)로 바뀌어 싹(sprout) → SPROUT_T 틱 뒤 봉오리(bud) → BLOOM_AT 틱에
 * 핀다(꽃 체력 BLOOM_HP, 싹과 봉오리 BUD_HP, 감독 체력 배율). 싹과 봉오리는 사람이 그 위를 걸어 지나가기만 해도 뭉개진다. 핀 꽃은 걷지 않고
 * 2블록 안 사람을 꽃잎 얼굴로 물고(BITE), PUFF_EVERY 틱마다 PUFF_WARN 틱 오므라든 뒤(puff) 홀씨 PUFF_N 개를 불어 날린다(child, 보상 0).
 * 꽃은 불에 FIRE_MUL 배로 탄다. 판에 꽃은 FLOWER_CAP 까지, 꽃이 불어 내는 홀씨는 판에 떠 있는 홀씨가 SPORE_CAP 아래일 때만.
 *
 * <p>보상: 홀씨 0, 감독이 낸 홀씨에서 핀 꽃만 감독 보상(공통 규칙: 꽃이 불어 낸 홀씨와 그 꽃은 보상 0).
 *
 * <p>팩 계약(tools/gabe/sp/g159.py): 홀씨 gabe/g159 상태 "", 꽃 gabe/g159_flower 상태 sprout, bud, "", puff. 소리 pop(16), puff(24), bite(12),
 * squash(12). 글귀 cause_g159, cause_g159_bite.
 */
public final class G159 extends Gabe {

    // ------------------------------------------------------------------ 수치(도감)

    /** 홀씨: 뜨는 높이(바닥 위), 빠르기, 일렁임, 터짐 피해. */
    static final double FLOAT_H = 1.6, SPEED = 0.1, FAR_SPEED = 0.22, FAR = 24, BOB = 0.3, POP = 14, SPORE_R = 0.3;
    /** 내려앉음: 사람 이 안에 든 뒤, 틱 범위, 내려오는 빠르기, 빈 땅(이 안에 사람이 없음). */
    static final double NEAR = 26, FALL_V = 0.045, EMPTY_R = 4;
    static final int DRIFT_MIN = 140, DRIFT_MAX = 320;
    /** 꽃: 몸 가운데 높이(팩 바닥 -0.8), 체력, 싹, 핌, 불기 간격과 예고, 홀씨 수, 물기, 상한, 밟힘, 불 배율. */
    static final double FLOWER_HOVER = 0.8, BUD_HP = 8, BLOOM_HP = 30, BITE = 8, BITE_R = 2, TRAMPLE = 0.55, FIRE_MUL = 3;
    static final int SPROUT_T = 60, BLOOM_AT = 160, PUFF_EVERY = 200, PUFF_WARN = 20, PUFF_N = 6, BITE_CD = 10, FLOWER_CAP = 16;
    /** 꽃이 불어 내는 홀씨는 판에 떠 있는 홀씨가 이만큼 아래일 때만(꽃밭이 몹 예산을 다 먹지 않게). */
    static final int SPORE_CAP = 24;
    /** 꽃 맞는 구: 얼굴(몸 가운데 위 0.32), 싹은 땅(몸 가운데 아래 0.62). */
    static final double FACE_UP = 0.32, BLOOM_R = 0.55, SPROUT_DOWN = 0.62;

    enum Form { SPORE, FALL, SPROUT, BUD, BLOOM }

    private Form form = Form.SPORE;
    private int formAt, driftLimit, nearTicks = 0, biteReady, puffAt;
    private boolean init, puffing;
    private int flowerReward;
    private double hpScale = 1;

    public G159(Game game, Species sp) {
        super(game, sp);
        radius = SPORE_R;
        flying = true;
        baseSpeed = SPEED;
        leaveCorpse = false;
        noBite = true;
    }

    @Override
    protected void spawned() {
        driftLimit = DRIFT_MIN + game.random.nextInt(DRIFT_MAX - DRIFT_MIN + 1);
        if (!game.director.cageSpawning()) {
            double fl = floor();
            if (pos.getY() < fl + 0.8) pos.setY(fl + FLOAT_H);
        }
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        if (!init) {
            // 감독의 체력, 보상 배율은 spawned 뒤에 곱해지므로 첫 틱에 읽음
            init = true;
            hpScale = sp.hp > 0 ? maxHp / sp.hp : 1;
            flowerReward = noReward ? 0 : reward;
            reward = 0;
        }
        switch (form) {
            case SPORE, FALL -> sporeTick();
            case SPROUT, BUD -> budTick();
            case BLOOM -> bloomTick();
        }
    }

    private void form(Form f) {
        form = f;
        formAt = age;
    }

    private int inForm() {
        return age - formAt;
    }

    private void sporeTick() {
        Player p = nearest(400);
        double fl = floor();
        if (p == null) {
            vel.setX(0).setZ(0).setY(form == Form.FALL ? -FALL_V : 0);
            if (form == Form.FALL && pos.getY() <= fl + 0.35) land();
            return;
        }
        double d = dist(p);
        if (form == Form.SPORE && d <= NEAR && ++nearTicks >= driftLimit) form(Form.FALL);
        Vector to = chest(p).subtract(pos);
        Vector h = to.clone().setY(0);
        double hl = h.length();
        double spd = (d > FAR ? FAR_SPEED : SPEED) * speed() / baseSpeed * (form == Form.FALL ? 0.5 : 1);
        if (hl > 1e-6) h.multiply(spd / hl);
        // 옆으로 바람에 흔들림(개체마다 어긋나게)
        double sway = Math.sin((age + id * 7) * 0.09) * spd * 0.6;
        if (hl > 1e-6) h.add(new Vector(-to.getZ() / hl * sway, 0, to.getX() / hl * sway));
        double vy;
        if (form == Form.FALL) vy = -FALL_V;
        else {
            double want = (d < 3 ? chest(p).getY() + 0.4 : fl + FLOAT_H) + Math.sin((age + id * 13) * 0.15) * BOB;
            vy = Math.max(-0.06, Math.min(0.06, (want - pos.getY()) * 0.12));
        }
        vel.copy(h).setY(vy);
        face(chest(p));
        if (touches(p, 0.05)) {
            pop(p);
            return;
        }
        if (form == Form.FALL && pos.getY() <= fl + 0.35) land();
    }

    /** 사람에게 닿아 터짐. */
    private void pop(Player p) {
        hit(p, POP, "cause_g159");
        Guns.kick(p, (game.random.nextFloat() - 0.5f) * 8f, -2f - game.random.nextFloat() * 2f);
        sound("pop", Sound.ENTITY_CHICKEN_EGG, 1.4f, 16);
        quietDie();
    }

    /** 땅에 닿음: 곁에 사람이 있으면 시들고, 빈 땅이면 싹. */
    private void land() {
        boolean empty = true;
        for (Player o : game.mobs.standing()) {
            Location l = o.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= EMPTY_R * EMPTY_R) {
                empty = false;
                break;
            }
        }
        if (!empty || flowers() >= FLOWER_CAP) {
            quietDie();
            return;
        }
        flying = false;
        hover = FLOWER_HOVER;
        hold();
        pos.setY(floor() + FLOWER_HOVER);
        body.model("gabe/g159_flower");
        body.size(1.5f, 1.9f);
        state("sprout");
        maxHp = hp = BUD_HP * hpScale;
        radius = 0.3;
        leaveCorpse = true;
        form(Form.SPROUT);
        if (target != null) mark(null);
    }

    private void budTick() {
        hold();
        // 사람이 위를 걸어 지나가면 뭉개짐
        for (Player o : game.mobs.standing()) {
            Location l = o.getLocation();
            double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= TRAMPLE * TRAMPLE && l.getY() - floor() < 0.6) {
                sound("squash", Sound.ENTITY_SLIME_SQUISH_SMALL, 1.4f, 12);
                quietDie();
                return;
            }
        }
        if (form == Form.SPROUT && inForm() >= SPROUT_T) {
            state("bud");
            form(Form.BUD);
        } else if (form == Form.BUD && age >= formAt + BLOOM_AT - SPROUT_T) bloom();
    }

    private void bloom() {
        state("");
        form(Form.BLOOM);
        maxHp = hp = BLOOM_HP * hpScale;
        radius = BLOOM_R;
        reward = flowerReward;
        noReward = flowerReward <= 0;
        puffAt = age + cd(PUFF_EVERY) / 2;
        Player p = nearest(40);
        if (p != null) mark(p);
    }

    private void bloomTick() {
        hold();
        Player p = nearest(400);
        if (p != null) {
            face(chest(p));
            pitch = 0;
            if (dist(p) <= 30 && target != p) mark(p);
        }
        if (age >= biteReady) {
            boolean bit = false;
            for (Player o : game.mobs.standing()) {
                Location l = o.getLocation();
                double dx = l.getX() - pos.getX(), dz = l.getZ() - pos.getZ();
                if (dx * dx + dz * dz > BITE_R * BITE_R) continue;
                hit(o, BITE, "cause_g159_bite");
                bit = true;
            }
            if (bit) {
                biteReady = age + cd(BITE_CD);
                sound("bite", Sound.ENTITY_FOX_BITE, 1.5f, 12);
            }
        }
        if (!puffing && age >= puffAt - PUFF_WARN && p != null && dist(p) <= 48) {
            puffing = true;
            state("puff");
        }
        if (puffing && age >= puffAt) {
            puffing = false;
            state("");
            puffAt = age + cd(PUFF_EVERY);
            if (p != null) blow(p);
        }
        if (!puffing && age >= puffAt && p == null) puffAt = age + 40;
    }

    /** 꽃이 홀씨를 불어 날림(child, 보상 0). */
    private void blow(Player p) {
        int room = Math.min(PUFF_N, SPORE_CAP - spores());
        if (room <= 0) return;
        sound("puff", Sound.ENTITY_BREEZE_SHOOT, 1.4f, 24);
        Vector headAt = pos.clone().add(new Vector(0, FACE_UP, 0));
        for (int i = 0; i < room; i++) {
            double a = Math.PI * 2 * i / PUFF_N;
            Vector at = headAt.clone().add(new Vector(Math.cos(a) * 0.5, 0.3 + game.random.nextDouble() * 0.4, Math.sin(a) * 0.5));
            Gabe c = child(sp, at);
            if (c instanceof G159 s) {
                s.init = true;
                s.hpScale = hpScale;
                s.maxHp = s.hp = sp.hp * hpScale;
                s.flowerReward = 0;
            }
        }
    }

    private void quietDie() {
        leaveCorpse = false;
        noReward = true;
        reward = 0;
        die(Damage.of(0, DamageType.OTHER));
    }

    /** 판에 떠 있는 홀씨(내려앉는 중 포함) 수. */
    private int spores() {
        int n = 0;
        for (Gabe g : game.mobs.all()) if (!g.dead && g instanceof G159 f && f.form.ordinal() < Form.SPROUT.ordinal()) n++;
        return n;
    }

    /** 판에 있는 꽃(싹, 봉오리 포함) 수. */
    private int flowers() {
        int n = 0;
        for (Gabe g : game.mobs.all()) if (!g.dead && g instanceof G159 f && f.form.ordinal() >= Form.SPROUT.ordinal()) n++;
        return n;
    }

    // ------------------------------------------------------------------ 맞음

    @Override
    public boolean damage(Damage d) {
        if (form.ordinal() >= Form.SPROUT.ordinal() && d.type() == DamageType.FIRE) d = d.times(FIRE_MUL);
        return super.damage(d);
    }

    @Override
    public List<double[]> spheres() {
        if (form == Form.BLOOM) return List.of(new double[] {pos.getX(), pos.getY() + FACE_UP, pos.getZ(), BLOOM_R});
        if (form == Form.SPROUT) return List.of(new double[] {pos.getX(), pos.getY() - SPROUT_DOWN, pos.getZ(), 0.32});
        if (form == Form.BUD) return List.of(new double[] {pos.getX(), pos.getY() + FACE_UP - 0.15, pos.getZ(), 0.4});
        return super.spheres();
    }

    @Override
    public Vector head() {
        return form == Form.BLOOM ? pos.clone().add(new Vector(0, FACE_UP, 0)) : pos;
    }

    @Override
    public String toString() {
        return super.toString() + " form=" + form + (form == Form.SPORE ? " near=" + nearTicks + "/" + driftLimit : "");
    }
}
