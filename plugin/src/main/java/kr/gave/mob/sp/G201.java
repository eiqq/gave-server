package kr.gave.mob.sp;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import kr.gave.game.Game;
import kr.gave.mob.Body;
import kr.gave.mob.Damage;
import kr.gave.mob.Gabe;
import kr.gave.mob.Look;
import kr.gave.mob.Species;
import kr.gave.player.Detachable;
import kr.gave.player.Life;
import kr.gave.player.PState;
import kr.gave.world.Plaza;
import kr.gave.world.Roam;

/**
 * G201 안아줘요 게이브(3막, 정예, 방해). 작은 머리가 주렁주렁 달린 키 2.9 블록의 머리채 기둥이 떼와 함께 걸어와 6.5 블록 앞에 서고, 8 블록 안의
 * 한 사람을 찍어 안아 달라고 조르듯 두 팔을 활짝 벌린다(예고 0.9초, 손마디 꺾는 소리). 그 팔을 한 번에 뻗어(9 블록까지) 사람을 움켜쥐고 품으로 끌어당긴 뒤, 등의 다리 넷으로 이불처럼 감싸고
 * 등을 토닥이며 2초 동안 뽀뽀를 퍼붓는다(네 번 7 피해). 다 안은 사람은 5.5 블록 앞에 내던지고 손을 흔들어 배웅한다.
 *
 * <p>규칙
 * <ul>
 * <li>예고: 찍힌 사람은 조준점 곁에 이 종 표시가 뜬다. 예고 끝 0.4초는 겨눈 자리가 굳어 그 뒤에 비키면 빗나간다(팔을 거두는 0.7초 무방비).
 * 예고와 뻗는 동안 최대 체력의 12% 를 맞으면 그만둔다.</li>
 * <li>품은 동안: 본인은 좌클릭 연타 10번(서 있는 사람이 둘 이하면 6번), 동료는 안긴 사람이나 이 게이브를 보고 좌클릭(떼기) 또는 쏴서 최대 체력의
 * 18% 를 깎으면 놓친다. 그러면 내던지지 못하고 멈칫한다.</li>
 * <li>경련: 끝까지 안긴 사람은 체력이 남아 있어도 떨어진 자리에서 쓰러져 5초 동안 버둥댄다({@link G201Fits}). 그동안은 동료가 1초만 일으켜도 깨어나고
 * 남아 있던 체력을 그대로 돌려받는다. 5초가 지나면 보통 쓰러짐(3초, 체력 30%).</li>
 * <li>배부름: 내던진 뒤 3초는 볼이 붉고(짙은 홍조) 아무도 노리지 않는다. 그 뒤 다시 핏기가 빠지고, 버려 둔 사람이 쓰러져 있는 동안(길어야 8초) 그 곁을
 * 지키며 팔이 닿는 사람을 노린다(일으키러 온 사람).</li>
 * <li>동시에 사람을 노리거나 안고 있을 수 있는 수: 1 + 서 있는 사람 / 10.</li>
 * <li>딸랑이 같은 것에 끌리면 하던 일을 그만둔다(품은 사람을 놓음).</li>
 * </ul>
 *
 * <p>사람의 시점은 돌리거나 흔들지 않는다(좌석으로 자리만 옮김). 품은 사람이 쓰러지거나 이 개체가 죽으면 붙잡힘은 Players 가 푼다.
 *
 * <p>팩 계약(tools/gabe/sp/g201.py): 몸 gave:gabe/g201(상태 "", "aim", "hug"), 팔 부품 gave:gabe/g201_arm(상태 "", "aim", "hug", "throw", "bye",
 * "r3", "r6", "r9", 부위 grip). 소리 crack(32), lunge(24), clutch(16), kiss(16), pat(12), purr(16), fling(24), fit(12). 글귀 cause_g201, hint_g201.
 */
public final class G201 extends Gabe implements Detachable {

    static final double SPEED = 0.15, HOVER = 1.5;
    /** 팔 뿌리(몸 틀: 위, 앞)와 뻗은 팔 상태 길이. 팩 g201.py 의 SH, REACH 와 같게. */
    static final double SH_Y = 0.42, SH_Z = 0.0;
    static final int[] LENGTHS = {3, 6, 9};
    /** 팔이 닿는 길이, 멈춰 서는 거리, 뻗는 빠르기(블록/틱), 본 자리보다 더 뻗는 길이, 움켜쥐는 굵기, 접은 팔 길이. */
    static final double REACH = 9, STAND = 6.5, EXTEND_V = 3.0, OVERSHOOT = 1.0, GRAB_R = 0.9, FOLD_LEN = 0.9;
    /** 품는 자리(몸 앞), 좌석 높이(바닥 위, 사람 발은 0.6 아래), 내던지는 거리와 높이. */
    static final double HOLD_Z = 1.0, SEAT_UP = 0.85, THROW_DIST = 5.5, THROW_UP = 1.4;
    /** 겨눈 자리를 굳힐 때 그 사람이 걷던 쪽으로 미리 겨누는 거리 상한(블록). */
    static final double LEAD_MAX = 2.5;
    /** 예고(끝 LOCK 틱은 겨눈 자리 굳힘), 끌어당김, 품음, 뽀뽀 간격, 내던짐, 배웅, 배부름, 지킴, 멈칫, 다음 낚아채기까지. */
    static final int AIM = 18, LOCK = 8, PULL = 4, HUG = 40, PECK = 5, THROW = 8, BYE = 24, SATED = 60, GUARD = 160, FLINCH = 30, COOLDOWN = 100;
    /** 빗나간 팔: 뻗은 채 멈추는 틱, 거두는 틱. */
    static final int MISS_HOLD = 4, RETRACT = 10;
    /** 뽀뽀 피해(품는 동안 네 번). */
    static final double KISS = 7;
    /** 예고와 뻗는 동안 이만큼(최대 체력 몫) 맞으면 그만둠, 품은 동안 이만큼 맞으면 놓침. */
    static final double STAGGER = 0.12, DROP = 0.18;
    /** 탈출 연타 수(서 있는 사람이 둘 이하면 FEW). */
    static final int ESCAPE = 10, ESCAPE_FEW = 6;
    /** 내려다볼 때 허리를 숙이는 각(도). */
    static final float LEAN = 16f;

    enum Phase { APPROACH, AIM, LUNGE, PULL, HUG, THROW, WATCH, RECOVER }

    private Phase phase = Phase.APPROACH;
    private int phaseAt, readyAt, satedUntil, guardUntil, recoverHold, recoverLen, reachL, tickedAt;
    /** 찍은 사람, 품은 사람, 방금 품을 떠난 사람(다음 틱에 처리), 이번 뻗기에서 못 잡은 사람(비옷), 내던져 둔 사람. */
    private Player prey, held, freed, denied;
    private UUID fed;
    private boolean locked, reaching, waving;
    private double len, reachLen, hpMark;
    private final Vector aim = new Vector(), dir = new Vector(0, 0, 1), shoulder = new Vector(), from = new Vector(), land = new Vector(),
        headAt = new Vector(), drift = new Vector();
    private Body.Part arms;
    private final List<double[]> sph = List.of(new double[4], new double[4], new double[4], new double[4]);

    public G201(Game game, Species sp) {
        super(game, sp);
        radius = 0.55;
        hover = HOVER;
        baseSpeed = SPEED;
    }

    @Override
    protected void build() {
        // 팔이 9 블록까지 뻗으므로 컬링 상자를 넓게
        body = Body.of(this, "gabe/g201", 20f, 6f);
        arms = body.attach("gabe/g201_arm");
    }

    @Override
    protected void spawned() {
        body.look.palette |= Look.PAL_PALE;   // 배고픈 동안은 핏기 없는 얼굴
        readyAt = age + 40;
    }

    private void phase(Phase p) {
        phase = p;
        phaseAt = age;
    }

    private int inPhase() {
        return age - phaseAt;
    }

    /** 사람을 노리거나 안고 있는 중인가(동족끼리 동시 수를 셈). */
    boolean busy() {
        return phase != Phase.APPROACH && phase != Phase.WATCH && phase != Phase.RECOVER;
    }

    // ------------------------------------------------------------------ 매 틱

    @Override
    public void tick() {
        // 예고부터 내던질 때까지는 판 규칙(돌아보기, 물어뜯기, 변함)에서 빠진다: 겨눈 쪽과 품은 사람에게서 몸이 돌아가지 않게
        // 틱이 건너뛰였다(엔진이 끌고 감: 딸랑이 같은 것): 하던 일을 그만둔다
        if (age - tickedAt > 1) abort();
        tickedAt = age;
        noRules = busy();
        // 품은 동안은 밀려나지 않는다(엔진이 품은 사람 몸에서 이 개체를 밀어내 둘이 같이 뒤로 미끄러지지 않게)
        noPush = held != null;
        if (held != null && (!held.isOnline() || game.players.grabbedBy(held) != this)) {
            freed = held;
            held = null;
        }
        if (freed != null) {
            Player p = freed;
            freed = null;
            lost(p);
        }
        switch (phase) {
            case APPROACH -> approach();
            case AIM -> aim();
            case LUNGE -> lunge();
            case PULL -> pull();
            case HUG -> hug();
            case THROW -> fling();
            case WATCH -> watch();
            case RECOVER -> recover();
        }
    }

    /** 떼와 함께 걸어와 STAND 블록 앞에 서서 팔이 닿는 사람을 기다린다. */
    private void approach() {
        Player p = nearest(400);
        if (p == null) {
            follow(speed());
            return;
        }
        Vector c = chest(p);
        faceYaw(c);
        if (flat(c) > STAND) follow(speed());
        else hold();
        tryAim();
    }

    /** 팔이 닿는 곳에 붙잡히지 않은 사람이 보이고, 사람을 노리거나 안고 있는 동족이 상한 아래면 예고를 시작한다. */
    private boolean tryAim() {
        if (age < readyAt || !every(2)) return false;
        Player p = nearest(REACH - 1);
        if (p == null || !free(p) || !sees(p)) return false;
        int cap = 1 + game.mobs.standing().size() / 10;
        if (game.mobs.near(pos, 400, g -> g != this && g instanceof G201 o && o.busy()).size() >= cap) return false;
        prey = p;
        mark(p);
        // 종 키 대신 HUD 그림 이름으로: Hud.iconImage 는 세 글자 종 키(g01..g99)만 알아본다
        game.hud.mark(p, "ic_s_" + sp.key(), cd(AIM) + 10);
        hold();
        aim.copy(chest(p));
        drift.zero();
        faceYaw(aim);
        state("aim");
        armState("aim");
        sound("crack", Sound.BLOCK_BONE_BLOCK_BREAK, 0.6f, 32);
        hpMark = hp;
        locked = false;
        phase(Phase.AIM);
        return true;
    }

    /** 예고: 눈을 뜨고 두 팔을 활짝 벌린 채 다리 넷을 치켜들고 떤다. 끝 LOCK 틱은 겨눈 자리를 굳힌다(그 뒤에 비키면 빗나감). 많이 맞으면 그만둔다. */
    private void aim() {
        hold();
        if (hpMark - hp >= STAGGER * maxHp) {
            flinch(cd(COOLDOWN), 0, 0);
            return;
        }
        int t = inPhase(), n = cd(AIM);
        if (!locked) {
            if (!standing(prey)) {
                flinch(20, 0, 0);
                return;
            }
            // 그 사람이 틱마다 움직인 만큼을 고르게 적어 둔다(굳힐 때 걷던 쪽으로 미리 겨눔: 그 뒤에 방향을 바꾸거나 멈춰야 빗나감)
            Vector c = chest(prey);
            drift.multiply(0.6).add(c.clone().subtract(aim).setY(0).multiply(0.4));
            aim.copy(c);
            int lock = Math.min(LOCK, n / 2);
            if (t >= n - lock) {
                locked = true;
                double v = drift.length(), lead = v * (lock + flat(c) / EXTEND_V);
                if (v > 0.02) aim.add(drift.clone().multiply(Math.min(lead, LEAD_MAX) / v));
                body.pose(2);
                sound("crack", Sound.BLOCK_BONE_BLOCK_BREAK, 0.6f, 32);
            }
            faceYaw(aim);
        }
        if (t < n) return;
        double r = Math.toRadians(yaw);
        shoulder.setX(pos.getX() - Math.sin(r) * SH_Z).setY(pos.getY() + SH_Y).setZ(pos.getZ() + Math.cos(r) * SH_Z);
        Vector to = aim.clone().subtract(shoulder);
        double l = to.length();
        if (l < 0.5) {
            flinch(20, 0, 0);
            return;
        }
        dir.copy(to.multiply(1 / l));
        reachLen = Math.min(REACH, l + OVERSHOOT);
        len = FOLD_LEN;
        reaching = true;
        reachL = 0;
        denied = null;
        sound("lunge", Sound.ENTITY_FROG_TONGUE, 0.55f, 24);
        poseReach(0);
        phase(Phase.LUNGE);
    }

    /** 두 팔이 겨눈 자리로 곧게 뻗는다. 이번 틱에 손이 지나간 길에 가슴이 닿은 사람 가운데 가장 가까운 사람을 움켜쥔다. */
    private void lunge() {
        hold();
        if (hpMark - hp >= STAGGER * maxHp) {
            flinch(cd(COOLDOWN), 0, 4);
            return;
        }
        double prev = len;
        len = Math.min(reachLen, len + EXTEND_V);
        Player got = null;
        double at = Double.MAX_VALUE;
        for (Player p : game.mobs.standing()) {
            if (p == denied || !free(p)) continue;
            Location l = p.getLocation();
            double dx = l.getX() - shoulder.getX(), dy = l.getY() + 0.9 - shoulder.getY(), dz = l.getZ() - shoulder.getZ();
            double along = dx * dir.getX() + dy * dir.getY() + dz * dir.getZ();
            if (along < prev - GRAB_R || along > len + GRAB_R || along >= at) continue;
            double ox = dx - dir.getX() * along, oy = dy - dir.getY() * along, oz = dz - dir.getZ() * along;
            if (ox * ox + oy * oy + oz * oz > GRAB_R * GRAB_R) continue;
            got = p;
            at = along;
        }
        if (got != null) {
            if (grab(got, game.mobs.standing().size() <= 2 ? ESCAPE_FEW : ESCAPE)) {
                caught(got, at);
                return;
            }
            denied = got;
        }
        poseReach(1);
        // 빗나감: 뻗은 채 잠깐 멈췄다가 천천히 거둔다(그동안 무방비)
        if (len >= reachLen - 1e-6) flinch(cd(COOLDOWN), MISS_HOLD, RETRACT);
    }

    private void caught(Player p, double along) {
        held = p;
        prey = p;
        game.players.get(p).grabPin = false;   // 좌석이 데려간다
        Location l = p.getLocation();
        from.setX(l.getX()).setY(l.getY() + 0.6).setZ(l.getZ());
        game.players.carry(p, from);
        len = Math.max(FOLD_LEN, Math.min(reachLen, along));
        if (arms != null) arms.look.on.add("grip");
        poseReach(1);
        sound("clutch", Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.55f, 16);
        hpMark = hp;
        phase(Phase.PULL);
    }

    /** 팔을 한 번에 거두며 움켜쥔 사람을 품으로 데려온다. */
    private void pull() {
        hold();
        Player p = held;
        if (p == null) return;
        int t = inPhase();
        if (t == 1) {
            len = FOLD_LEN;
            poseReach(PULL - 1);
        }
        double k = Math.min(1.0, t / (double) PULL);
        Vector seat = holdSeat();
        game.players.carry(p, seat.subtract(from).multiply(k).add(from));
        if (t < PULL) return;
        // 품음: 다리 넷이 앞으로 오므라들어 감싸고, 두 손은 등을 번갈아 토닥인다. 눈을 지그시 감고 볼이 달아오른다
        state("hug");
        if (arms != null) arms.look.on.remove("grip");
        gesture("hug", 10, 2);
        reaching = false;
        body.look.open = false;
        blush(true);
        phase(Phase.HUG);
    }

    /** 품은 채 뽀뽀를 퍼붓는다(PECK 틱마다 몸을 내밀며 눌렸다 돌아옴, 두 번에 한 번 피해). 많이 맞으면 놓친다. */
    private void hug() {
        hold();
        Player p = held;
        if (p == null) return;
        if (hpMark - hp >= DROP * maxHp) {
            held = null;
            game.players.release(p);
            flinch(cd(COOLDOWN), 0, 0);
            return;
        }
        int t = inPhase(), c = t % PECK;
        game.players.carry(p, holdSeat());
        if (c == 0 && t < HUG) {
            double r = Math.toRadians(yaw);
            body.offset.set((float) (-Math.sin(r) * 0.12), 0f, (float) (Math.cos(r) * 0.12));
            body.squash(1.05f, 0.95f, 1.05f, 2);
            sound("kiss", Sound.ENTITY_ITEM_PICKUP, 1.3f, 16);
            if (t / PECK % 2 == 1) {
                hit(p, KISS, "cause_g201");
                if (held == null) return;   // 품에서 쓰러짐(다음 틱에 lost)
            }
        } else if (c == 2) {
            body.offset.set(0f, 0f, 0f);
            body.squash(1f, 1f, 1f, 3);
        }
        if (t % 10 == 3) sound("pat", Sound.BLOCK_WOOL_HIT, 0.8f, 12);
        if (t < HUG) return;
        // 다 안았다: 감쌌던 다리 넷을 활짝 펴고 내던진다. 떨어질 자리는 보는 쪽 앞(사람의 끝 밖이나 막힌 칸이면 짧게)
        calm();
        state("aim");
        gesture("throw", 8, 4);
        sound("fling", Sound.ENTITY_IRON_GOLEM_ATTACK, 0.85f, 24);
        from.copy(holdSeat());
        land.copy(from);
        double r = Math.toRadians(yaw), fx = -Math.sin(r), fz = Math.cos(r);
        for (double d = THROW_DIST; d > 0.5; d -= 1.25) {
            double x = from.getX() + fx * d, z = from.getZ() + fz * d;
            if (Plaza.radius(x, z) > Roam.radius() - 1.5 || !game.mobs.flow.walkable(x, z)) continue;
            land.setX(x).setZ(z);
            break;
        }
        land.setY(game.mobs.flow.floor(land.getX(), land.getZ()) + 0.6);
        phase(Phase.THROW);
    }

    /** 내던짐: 품은 사람을 포물선으로 떨어질 자리까지 옮기고, 닿으면 쓰러뜨려 경련시킨다(남은 체력은 깨어날 때 돌려받음). */
    private void fling() {
        hold();
        Player p = held;
        if (p == null) return;
        int t = inPhase();
        double k = Math.min(1.0, t / (double) THROW);
        Vector at = land.clone().subtract(from).multiply(k).add(from);
        at.setY(at.getY() + THROW_UP * Math.sin(Math.PI * k));
        game.players.carry(p, at);
        if (t < THROW) return;
        held = null;
        double left = game.players.get(p).hp;
        game.players.down(p, this, "cause_g201");
        G201Fits.of(game).add(p, left);
        sate(p);
    }

    /** 다 안았다: 손을 흔들어 배웅하고 SATED 틱 동안 아무도 노리지 않는다(볼이 붉은 동안). 그 뒤 버려 둔 사람 곁을 지킨다. */
    private void sate(Player p) {
        calm();
        fed = p.getUniqueId();
        Location l = p.getLocation();
        land.setX(l.getX()).setY(l.getY()).setZ(l.getZ());
        mark(null);
        prey = null;
        blush(true);
        state("");
        if (arms != null) arms.look.on.remove("grip");
        reaching = false;
        gesture("bye", 6, 2);
        waving = true;
        sound("purr", Sound.ENTITY_CAT_PURR, 0.6f, 16);
        satedUntil = age + SATED;
        guardUntil = satedUntil + GUARD;
        readyAt = Math.max(readyAt, satedUntil);
        phase(Phase.WATCH);
    }

    /** 버려 둔 사람을 내려다본다. 배부름이 끝나면 다시 핏기가 빠지고, 그 사람이 쓰러져 있는 동안 곁에서 팔이 닿는 사람을 노린다. */
    private void watch() {
        hold();
        faceYaw(land);
        if (waving && inPhase() >= BYE) {
            // 손을 내리고 허리를 숙여 들여다본다
            waving = false;
            armState("");
            pitch = LEAN;
            body.pose(8);
        }
        if (age < satedUntil) return;
        blush(false);
        if (pitch != 0) {
            pitch = 0;
            body.pose(8);
        }
        if (tryAim()) return;
        PState s = game.players.get(fed);
        if (s == null || s.life != Life.DOWNED || age >= guardUntil) phase(Phase.APPROACH);
    }

    /** 품던 사람이 손을 떠났다: 품에서 쓰러졌으면 경련과 배웅, 빠져나갔으면(연타, 동료가 뗌) 멈칫. */
    private void lost(Player p) {
        if (p.isOnline() && game.players.get(p).life == Life.DOWNED) {
            G201Fits.of(game).add(p, 0);
            sate(p);
        } else flinch(cd(COOLDOWN), 0, 4);
    }

    /**
     * 그만둠(맞아서, 놓쳐서, 빗나가서): 팔을 거두고 FLINCH 틱 멈칫한 뒤 다시 다가간다. holdOut 뻗은 팔을 그대로 두는 틱, retract 거두는 틱(뻗은 팔일 때만).
     */
    private void flinch(int cooldown, int holdOut, int retract) {
        calm();
        blush(false);
        mark(null);
        prey = null;
        state("");
        if (arms != null) arms.look.on.remove("grip");
        if (!reaching) {
            armState("");
            holdOut = retract = 0;
        }
        recoverHold = holdOut;
        recoverLen = retract;
        readyAt = age + cooldown;
        phase(Phase.RECOVER);
    }

    /** 하던 낚아채기를 그만둠(끌려감): 품은 사람을 놓고 팔을 거둔 채 멈칫으로. 하던 일이 없으면 아무것도 안 함. */
    private void abort() {
        if (!busy() && phase != Phase.WATCH) return;
        Player p = held;
        held = null;
        if (p != null) game.players.release(p);
        reaching = false;
        flinch(cd(COOLDOWN), 0, 0);
    }

    private void recover() {
        hold();
        int t = inPhase();
        if (reaching) {
            if (t == recoverHold + 1 && recoverLen > 0) {
                len = FOLD_LEN;
                poseReach(recoverLen);
            }
            if (t > recoverHold + recoverLen) {
                reaching = false;
                armState("");
            }
        }
        if (t > recoverHold + recoverLen + FLINCH) phase(Phase.APPROACH);
    }

    // ------------------------------------------------------------------ 몸짓

    /** 뽀뽀로 내민 몸과 숙인 허리를 되돌린다. */
    private void calm() {
        body.offset.set(0f, 0f, 0f);
        body.squash(1f, 1f, 1f, 3);
        pitch = 0;
    }

    /** 볼이 달아오름(짙은 홍조, 핏기가 돎) 또는 다시 핏기 없는 얼굴. */
    private void blush(boolean on) {
        int p = body.look.palette & ~(Look.PAL_PALE | Look.PAL_DEEP_BLUSH);
        body.look.palette = p | (on ? Look.PAL_DEEP_BLUSH : Look.PAL_PALE);
    }

    /** 안아 줄 수 있는 사람인가: 남에게 붙잡히지 않았고, 떠 있지 않고, 막 꺼내져 무적인 동안이 아님. */
    private boolean free(Player p) {
        return game.players.grabbedBy(p) == null && !game.players.launched(p) && game.players.get(p).safeUntil <= game.now();
    }

    /** 수평으로만 그쪽을 본다(허리 숙임 pitch 는 그대로). */
    private void faceYaw(Vector at) {
        double dx = at.getX() - pos.getX(), dz = at.getZ() - pos.getZ();
        if (dx * dx + dz * dz > 1e-8) yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private double flat(Vector at) {
        return Math.hypot(at.getX() - pos.getX(), at.getZ() - pos.getZ());
    }

    /** 품는 자리의 좌석(몸 앞 HOLD_Z, 바닥 위 SEAT_UP). */
    private Vector holdSeat() {
        double r = Math.toRadians(yaw);
        return new Vector(pos.getX() - Math.sin(r) * HOLD_Z, floor() + SEAT_UP, pos.getZ() + Math.cos(r) * HOLD_Z);
    }

    /** 팔 부품 상태를 바꾸고 몸 기준 제자리로(늘어뜨림, 벌림, 끌어안음 같은 구운 자세). */
    private void armState(String s) {
        waving = false;
        if (arms == null) return;
        arms.state(s);
        arms.pose(new Matrix4f(), 0);
    }

    /** 팔 부품의 몸짓 상태 st(주기 period 틱, 자세 n)를 켜고 지금 틱이 자세 0 의 첫머리가 되게 위상을 맞춘다(셰이더 위상은 주기의 16분의 1 단위). */
    private void gesture(String st, int period, int n) {
        armState(st);
        if (arms == null) return;
        long t = Math.floorMod(game.worldTime(), 24000L);
        int best = 0;
        double bestRes = Double.MAX_VALUE;
        for (int ph = 0; ph < period; ph++) {
            double m = (t + (ph * 16 / period) * period / 16.0) % period;
            double res = m < period / (double) n ? m : 1e6 + m;
            if (res < bestRes) {
                bestRes = res;
                best = ph;
            }
        }
        arms.look.phase = best;
    }

    /**
     * 뻗은 팔: 지금 길이보다 길거나 같은 가장 가까운 상태를 골라 길이 쪽(+Z)으로 줄이고, 팔 뿌리에서 겨눈 높이로 숙인다(몸이 겨눈 쪽을 봄).
     * 더 긴 상태로 바뀌는 틱은 보간 없이 바로(모델이 바뀌는 순간 옛 배율로 그려지지 않게), 거둘 때는 상태를 그대로 둔 채 줄이기만 한다.
     */
    private void poseReach(int ticks) {
        if (arms == null) return;
        int L = LENGTHS[LENGTHS.length - 1];
        for (int v : LENGTHS) {
            if (v >= len - 1e-6) {
                L = v;
                break;
            }
        }
        if (L > reachL) {
            reachL = L;
            ticks = 0;
            arms.state("r" + L);
        }
        float down = (float) Math.atan2(-dir.getY(), Math.hypot(dir.getX(), dir.getZ()));
        arms.pose(new Matrix4f().translate(0f, (float) SH_Y, (float) SH_Z).rotateX(down).scale(1f, 1f, (float) (len / reachL)), ticks);
    }

    // ------------------------------------------------------------------ 맞음, 돕기, 죽음

    /** 올림머리, 본 얼굴, 치마 두 단(아래 둘은 몸통에 달린 머리들이 삐져나온 데까지). */
    @Override
    public List<double[]> spheres() {
        sphere(0, 1.15, 0.42);
        sphere(1, 0.42, 0.55);
        sphere(2, -0.4, 0.66);
        sphere(3, -1.05, 0.8);
        return sph;
    }

    private void sphere(int i, double up, double r) {
        double[] a = sph.get(i);
        a[0] = pos.getX();
        a[1] = pos.getY() + up;
        a[2] = pos.getZ();
        a[3] = r;
    }

    @Override
    public Vector head() {
        return headAt.copy(pos).setY(pos.getY() + 0.42);
    }

    /** 이 게이브를 보고 돕기(좌클릭): 품에 안긴 사람을 떼어 낸다(안긴 사람을 보고 누르는 것과 같음). */
    @Override
    public boolean helped(Player p) {
        return held != null && p != held && game.players.detach(p, held);
    }

    /** 딸랑이 같은 것에 끌림: 하던 일을 그만두고(품은 사람은 놓고) 그쪽으로 간다. */
    @Override
    public void lure(Vector at, int ticks) {
        abort();
        super.lure(at, ticks);
    }

    /** 품던 사람이 풀려남(연타 탈출, 동료 떼기, 쓰러짐): 다음 틱에 {@link #lost}. 내가 놓거나 내던진 것은 held 를 먼저 비워 여기로 안 온다. */
    @Override
    public void detached(Player victim, Player helper, String how) {
        if (victim != held) return;
        held = null;
        freed = victim;
    }

    @Override
    public void die(Damage d) {
        if (dead) return;
        // 사체는 서 있던 꼴로(다리 넷이 흐물거림), 팔은 늘어뜨린 채
        held = null;
        calm();
        state("");
        if (arms != null) arms.look.on.remove("grip");
        armState("");
        super.die(d);
    }

    @Override
    public String toString() {
        return super.toString() + " ph=" + phase + (held != null ? " held=" + held.getName() : "") + (reaching ? String.format(java.util.Locale.ROOT, " len=%.1f", len) : "");
    }
}
