package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import kr.gave.mob.Body;
import kr.gave.mob.Look;
import kr.gave.player.Life;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * B5 몸통에 박힌 죽은 사람들의 머리와 이름(이야기: 죽으면 의식이 있는 채로 게이브 몸에 갇힌다). 그 판에서 죽은 채 남은 사람(Life.DEAD, TRAPPED)만 쓴다:
 * 보스가 일어설 때 죽어 있던 사람(많이 쓰러진 차례)과 싸우는 동안 죽은 사람이 여덟까지 하나씩 박힌다. 서 있는 사람, 쓰러졌다 일어난 사람은 안 쓰고,
 * 박힌 뒤 꺼내지거나 부활한 사람은 몸에서 빠진다.
 *
 * <p>'내 얼굴, 내 이름 쓰지 않기'(PState.noLikeness)인 사람은 머리 대신 바랜 작은 게이브 얼굴(gabe/b5_drop)이고 이름표가 없다. 머리는 몸 부품
 * (Body.attach)의 아이템을 사람 머리(PLAYER_HEAD, 접속해 있는 그 사람의 프로필: 조회 없음)로 바꾼 것이라 엔진이 아이템을 다시 실으면 되돌린다
 * (G38 방식). 이름표는 닻의 승객 text_display(G26Tag 방식)이고 몸이 돌 때 B5 가 {@link #place} 로 옮긴다.
 */
final class B5Souls {

    static final int MAX = 8;
    static final TextColor NAME = TextColor.color(0xd8d0d0);
    /** 몸통 앞 자리(몸 틀, 몸 배율 1 단위): 위 무더기부터 아래로. */
    static final float[][] TORSO = {{0.27f, -0.74f, 0.58f}, {-0.27f, -0.74f, 0.58f}, {0f, -1.02f, 0.64f}, {0.33f, -1.3f, 0.6f}, {-0.33f, -1.3f, 0.6f},
        {0f, -1.6f, 0.65f}, {0.3f, -1.9f, 0.6f}, {-0.3f, -1.9f, 0.6f}};
    /** 4단계(몸통이 무너진 뒤): 머리 꼭대기 둘레. */
    static final float[][] CROWN = {{0.2f, 0.88f, 0.3f}, {-0.2f, 0.88f, 0.3f}, {0.55f, 0.86f, 0.1f}, {-0.55f, 0.86f, 0.1f}, {0.36f, 0.9f, -0.25f},
        {-0.36f, 0.9f, -0.25f}, {0f, 0.92f, -0.05f}, {0f, 0.88f, -0.45f}};
    /** 머리 배율(몸 배율 1 단위: 세계 크기 = x 6.5 x 0.5 블록), 이름표 글자 배율(세계). */
    static final float HEAD_K = 0.66f, TAG_K = 4.2f;

    final class Soul {
        UUID id;
        String name;
        boolean likeness;
        ItemStack skull;
        Body.Part head;
        TextDisplay tag;
        int slot;
    }

    final B5 b;
    final List<Soul> souls = new ArrayList<>(MAX);
    boolean crown;
    private float tagYaw = Float.NaN;

    B5Souls(B5 b) {
        this.b = b;
    }

    boolean has(UUID id) {
        for (Soul s : souls) if (s.id.equals(id)) return true;
        return false;
    }

    /** 지금 죽어 있는 사람(판에 든 사람 가운데 DEAD, TRAPPED) 가운데 아직 안 박힌 사람들: 많이 쓰러진 차례. */
    List<PState> waiting() {
        List<PState> out = new ArrayList<>();
        for (PState s : b.game.players.all()) {
            if (s.offline || s.life != Life.DEAD && s.life != Life.TRAPPED) continue;
            if (has(s.id)) continue;
            out.add(s);
        }
        out.sort((x, y) -> Integer.compare(y.downs, x.downs));
        return out;
    }

    /** 한 사람을 박는다(자리가 남았으면). 박았으면 true. */
    boolean add(PState s) {
        if (souls.size() >= MAX || b.body == null || b.body.anchor == null || has(s.id)) return false;
        Soul o = new Soul();
        o.id = s.id;
        o.name = s.name;
        o.likeness = !s.noLikeness;
        o.slot = freeSlot();
        Player p = Bukkit.getPlayer(s.id);
        if (o.likeness && p != null) {
            try {
                ItemStack it = ItemStack.of(Material.PLAYER_HEAD);
                it.editMeta(SkullMeta.class, m -> m.setPlayerProfile(p.getPlayerProfile()));
                o.skull = it;
            } catch (RuntimeException e) {
                o.skull = null;
            }
        }
        o.head = b.body.attach("gabe/b5_drop");
        if (o.head != null) {
            o.head.look.palette = Look.PAL_PALE;
            o.head.look.freeze = true;
            // 처음엔 살 속에 묻혀 있다가 밖으로 밀려 나온다
            float[] at = spot(o.slot);
            o.head.pose(new Matrix4f().translate(at[0], at[1], at[2] - 0.25f).scale(0.02f), 0);
            Soul ref = o;
            b.game.plugin.getServer().getScheduler().runTaskLater(b.game.plugin, () -> {
                if (ref.head != null && ref.head.d != null && ref.head.d.isValid()) ref.head.pose(matrix(ref), 16);
            }, 2);
            keep(o);
        }
        if (o.likeness && o.name != null && !o.name.isEmpty()) tag(o);
        souls.add(o);
        return true;
    }

    /** 비어 있는 가장 앞 자리(빠져나간 사람의 자리를 다시 씀). */
    private int freeSlot() {
        for (int i = 0; i < MAX; i++) {
            boolean used = false;
            for (Soul o : souls) used |= o.slot == i;
            if (!used) return i;
        }
        return souls.size();
    }

    /** 다시 살아난 사람(꺼냄, 부활, 나감)의 머리와 이름은 몸에서 빠진다: 몸에는 죽은 채인 사람만 남는다. */
    private void release() {
        for (java.util.Iterator<Soul> it = souls.iterator(); it.hasNext();) {
            Soul o = it.next();
            PState s = b.game.players.get(o.id);
            if (s != null && !s.offline && (s.life == Life.DEAD || s.life == Life.TRAPPED)) continue;
            if (o.head != null) o.head.remove();
            if (o.tag != null) o.tag.remove();
            o.head = null;
            o.tag = null;
            it.remove();
        }
    }

    private float[] spot(int slot) {
        float[][] t = crown ? CROWN : TORSO;
        return t[Math.min(slot, t.length - 1)];
    }

    private Matrix4f matrix(Soul o) {
        float[] at = spot(o.slot);
        Matrix4f m = new Matrix4f().translate(at[0], at[1], at[2]);
        if (crown) m.rotateX((float) Math.toRadians(-35));
        m.scale(HEAD_K);
        // 사람 머리 아이템은 아래로 반 칸 치우쳐 그려진다(G38 SKULL_UP)
        if (o.skull != null) m.translate(0, 0.25f, 0);
        return m;
    }

    /** 엔진이 부품 아이템을 다시 실으면(맞은 번쩍임 등) 사람 머리로 되돌림. 맞은 번쩍임은 미리 지워 다시 싣지 않게 한다. */
    private void keep(Soul o) {
        if (o.head == null || o.head.d == null || !o.head.d.isValid()) return;
        o.head.look.hurtUntil = 0;
        if (o.skull == null) return;
        if (o.head.d.getItemStack().getType() != Material.PLAYER_HEAD) o.head.d.setItemStack(o.skull);
    }

    private void tag(Soul o) {
        var anchor = b.body.anchor;
        if (anchor == null || !anchor.isValid()) return;
        try {
            o.tag = anchor.getWorld().spawn(anchor.getLocation(), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setSeeThrough(false);
                t.setShadowed(true);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setTextOpacity((byte) 235);
                t.setViewRange(1.6f);
                t.setBrightness(new Display.Brightness(12, 12));
                t.setDisplayWidth(B5.BOX_W);
                t.setDisplayHeight(B5.BOX_H);
                t.text(Component.text(o.name).color(NAME));
                t.setInterpolationDuration(5);
                t.setTransformation(where(o));
            });
            anchor.addPassenger(o.tag);
        } catch (RuntimeException e) {
            o.tag = null;
        }
    }

    /** 이름표 자리(닻 엔티티 기준, 세계 축): 머리 바로 아래. */
    private Transformation where(Soul o) {
        float[] at = spot(o.slot);
        double yr = Math.toRadians(-b.yaw), c = Math.cos(yr), s = Math.sin(yr);
        double x = at[0] * B5.S, y = at[1] * B5.S, z = (at[2] + 0.12) * B5.S;
        float wx = (float) (x * c + z * s), wz = (float) (-x * s + z * c);
        return new Transformation(new Vector3f(wx, (float) (y + B5.BOX_H / 2 - 2.6), wz), new AxisAngle4f(), new Vector3f(TAG_K, TAG_K, TAG_K), new AxisAngle4f());
    }

    /** 몸이 돌았거나 자리가 바뀐 뒤 이름표를 옮긴다(ticks 보간). */
    void place(int ticks) {
        tagYaw = b.yaw;
        for (Soul o : souls) {
            if (o.tag == null || !o.tag.isValid()) continue;
            o.tag.setInterpolationDelay(0);
            o.tag.setInterpolationDuration(ticks);
            o.tag.setTransformation(where(o));
        }
    }

    /** 매 틱: 머리 아이템 지키기, 몸이 2도 넘게 돌았으면 이름표 옮기기(5틱마다), 살아난 사람 빼기(20틱마다). */
    void tick() {
        if (b.age % 20 == 3) release();
        for (Soul o : souls) keep(o);
        if (souls.isEmpty()) return;
        if (Float.isNaN(tagYaw) || Math.abs(b.yaw - tagYaw) > 2f && b.age % 5 == 0) place(5);
    }

    /** 4단계: 몸통이 무너져 머리들이 큰 머리 꼭대기로 옮겨 붙는다. */
    void toCrown() {
        crown = true;
        for (Soul o : souls) if (o.head != null && o.head.d != null && o.head.d.isValid()) o.head.pose(matrix(o), 24);
        place(24);
    }

    /** 이름표를 지운다(사체가 되기 직전, 사라질 때). 머리 부품은 몸과 함께 간다. */
    void clearTags() {
        for (Soul o : souls) {
            if (o.tag != null) o.tag.remove();
            o.tag = null;
        }
    }

    int size() {
        return souls.size();
    }

    String names() {
        StringBuilder sb = new StringBuilder();
        for (Soul o : souls) sb.append(sb.length() == 0 ? "" : ",").append(o.name).append(o.skull != null ? "" : o.likeness ? "(얼굴없음)" : "(닮음끔)");
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
