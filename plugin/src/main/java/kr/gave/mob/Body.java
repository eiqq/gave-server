package kr.gave.mob;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import kr.gave.Props;
import net.kyori.adventure.key.Key;

/**
 * 게이브의 몸: 닻 item_display 하나(모델 gave:&lt;model&gt;)와 필요하면 그 승객인 부품 디스플레이({@link Part}), 떼 종이면 마리들({@link Swarm}).
 *
 * <p>아이템은 PAPER + item_model gave:&lt;model&gt; + custom_model_data(strings[0] = 상태, colors = {@link Look#colors}). 엔티티 yaw, pitch 는 늘 0,
 * 방향은 transformation 으로만 준다: T(offset + 들어올림) x Ry(-yaw) x Rx(pitch) x Rz(roll) x S(scale x stretch) x Ry(PI)
 * (아이템 디스플레이는 모델을 y 로 반 바퀴 돌려 그리므로 오른쪽에 Ry(PI), 그래서 모델 앞(-Z, north 면)이 몸 앞을 본다).
 *
 * <p>엔진이 매 틱 {@link #move()} 를 부른다: 위치는 2틱마다(0.05블록 넘게 움직였을 때, 아니면 6틱마다 남은 것) teleport_duration 2 로,
 * 방향은 15도 넘게 꺾일 때만 interpolation 3 틱으로, 아이템(상태, 겉모습)은 바뀔 때만 다시 싣는다. 부품은 닻의 승객이라 이동 패킷이
 * 하나로 묶이고, 몸이 돌면 부품 transformation 도 같이 다시 보낸다.
 *
 * <p>엔티티 위치는 몸 중심에서 height/2 아래(디스플레이 컬링 상자가 발밑에서 시작하므로), transformation 이 그만큼 다시 올린다.
 *
 * <p>죽으면 {@link Ragdoll} 이 닻과 부품 디스플레이를 그대로 넘겨받아 사체로 굴린다(그 뒤로 move() 는 아무것도 안 함). 래그돌 예산이
 * 없거나 꺼져 있으면 예전처럼 {@link #corpse(int)} 로 제자리에서 납작해진다.
 *
 * <p>보이는 거리: 새 맵은 게이브가 72~84블록 안개 너머에서 걸어 나오므로 몸 디스플레이의 view range 는 1.6(= 102블록, 클라 '엔티티 거리'
 * 100% 기준) 아래로 내려가지 않는다. 서버 추적 거리는 spigot.yml entity-tracking-range.display(128).
 */
public final class Body {

    /** 아무것도 안 그리는 모델(떼 닻, 부품만 있는 몸). */
    public static final String EMPTY = "empty";
    /** 이만큼 넘게 꺾이면 방향을 다시 보낸다(도). */
    public static final float TURN_DEG = 15f;
    /** 몸 밝기(밤과 그늘에서 시커메지지 않게, 15 로 빛나게 하지 않음). */
    public static final Display.Brightness LIGHT = new Display.Brightness(10, 10);
    /** 몸 디스플레이 view range 아래끝(x 64블록): 고리(72~84블록)에서 나오는 순간부터 보이게. */
    public static final float VIEW_MIN = 1.6f;

    public final Gabe owner;
    public String model;
    public ItemDisplay anchor;
    /** 부품 디스플레이(닻 승객). 자세와 상태는 {@link #pieces} 의 Part 로. */
    public final List<ItemDisplay> parts = new ArrayList<>();
    /** 부품(모델, 상태, 겉모습, 몸 기준 자세). parts 와 같은 차례. */
    public final List<Part> pieces = new ArrayList<>();
    /** 이 몸을 숨긴 사람(사람마다 따로 보이는 종). */
    public final List<Player> hiddenFrom = new ArrayList<>();
    public float scale = 1f;
    /** 앞축 기울기(도, + 는 몸 오른쪽이 내려감). 바꾸면 다음 move 에 보낸다. */
    public float roll;
    /** 축마다 늘이기(x 옆, y 위, z 앞). 찌그러짐은 {@link #squash}. */
    public final Vector3f stretch = new Vector3f(1, 1, 1);
    /** 몸 중심에서 모델을 옮겨 그림(블록, 세계 축). 흔들림, 끄덕임용. */
    public final Vector3f offset = new Vector3f();
    /** 방향 보간 틱(기본 3). */
    public int turnTicks = 3;
    /** 컬링 상자 폭, 높이(블록). {@link #size} 로 바꾼다. */
    public float width, height;
    /** 겉모습 상태. 고친 뒤 dirty() 를 부르거나 엔진이 매 틱 비교해 바뀌면 Look.colors 로 다시 싣는다. */
    public final LookState look = new LookState();

    float lift;
    boolean carrier;
    Swarm swarm;
    boolean corpse, removed;
    /** 소리 없는 웨이브: 엔진이 켜면 겉모습을 freeze 로 실음. */
    boolean forceFreeze;
    float viewRange;

    private String st = "";
    private String sentState;
    private LookState sentLook;
    private boolean sentFreeze, itemDirty = true;
    private double sx, sy, sz;
    private long sentAt = Long.MIN_VALUE / 2;
    float sentYaw, sentPitch;
    private float sentRoll, sentScale = 1f;
    private final Vector3f sentStretch = new Vector3f(1, 1, 1), sentOffset = new Vector3f();
    private int poseTicks = -1;

    private Body(Gabe owner, String model) {
        this.owner = owner;
        this.model = model;
        float r = (float) owner.radius;
        this.width = r * 2.4f + 0.4f;
        this.height = r * 2.4f + 0.2f;
        this.lift = height / 2;
        this.look.phase = owner.game.random.nextInt(240);
        // build() 전에(생성자 등에서) 정한 상태를 이어받음
        this.st = owner.state == null ? "" : owner.state;
    }

    // ------------------------------------------------------------------만들기

    /** 기본 몸: 모델 하나(gave:gabe/g01 처럼 이름공간 빼고). 디스플레이를 바로 만든다. 컬링 크기는 반지름에서 어림. */
    public static Body of(Gabe owner, String model) {
        Body b = new Body(owner, model);
        b.spawn();
        return b;
    }

    /** 모델 하나, 컬링 상자 폭 width, 높이 height(블록, 몸 중심 기준 위아래 반씩). */
    public static Body of(Gabe owner, String model, float width, float height) {
        Body b = new Body(owner, model);
        b.width = width;
        b.height = height;
        b.lift = height / 2;
        b.spawn();
        return b;
    }

    /** 빈 닻(아무것도 안 그림): 떼 종, 부품만으로 그리는 종. 컬링 없음. */
    public static Body carrier(Gabe owner) {
        Body b = new Body(owner, EMPTY);
        b.carrier = true;
        b.width = b.height = 0;
        b.lift = 0;
        b.spawn();
        return b;
    }

    private void spawn() {
        World w = owner.game.world;
        viewRange = owner.flying ? 2.5f : Math.max(VIEW_MIN, Math.min(2.0f, 0.9f + width * 0.35f));
        if (carrier) viewRange = 2.0f;
        sx = owner.pos.getX();
        sy = owner.pos.getY() - lift;
        sz = owner.pos.getZ();
        Location l = new Location(w, sx, sy, sz, 0, 0);
        sentYaw = owner.yaw;
        sentPitch = owner.pitch;
        sentState = st;
        sentLook = look.copy();
        sentFreeze = forceFreeze;
        itemDirty = false;
        ItemStack it = item(model, st, look);
        Matrix4f m = carrier ? new Matrix4f() : frame(sentYaw, sentPitch, Look.scale(model)).rotateY((float) Math.PI);
        anchor = w.spawn(l, ItemDisplay.class, d -> {
            setup(d, it, m, viewRange, width, height);
            d.setTeleportDuration(2);
        });
        sentAt = owner.game.now();
    }

    static void setup(ItemDisplay d, ItemStack it, Matrix4f m, float viewRange, float w, float h) {
        d.setPersistent(false);
        d.setItemStack(it);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setBillboard(Display.Billboard.FIXED);
        d.setBrightness(LIGHT);
        d.setViewRange(viewRange);
        d.setDisplayWidth(w);
        d.setDisplayHeight(h);
        d.setInterpolationDuration(3);
        d.setTransformationMatrix(m);
    }

    /** 디스플레이에 실을 아이템: PAPER + gave:model + strings[0] 상태 + colors. */
    public static ItemStack item(String model, String state, LookState ls, long worldTime) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(Props.NS, model));
        List<Color> colors;
        try {
            colors = Look.colors(model, state == null ? "" : state, ls, worldTime);
        } catch (RuntimeException e) {
            colors = List.of();
        }
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(state == null ? "" : state)
                .addColors(colors).build());
        return it;
    }

    private ItemStack item(String m, String state, LookState ls) {
        return item(m, state, ls, owner.game.worldTime());
    }

    // ------------------------------------------------------------------ 바꾸기(종이 부름)

    /** 모델 상태(strings[0]). Gabe.state 가 부른다. */
    public void state(String s) {
        st = s == null ? "" : s;
    }

    public String stateName() {
        return st;
    }

    /** 겉모습을 바꿨음(다음 move 때 다시 실음). 엔진이 매 틱 비교도 하니 부르지 않아도 된다. */
    public void dirty() {
        itemDirty = true;
    }

    /** 크기(1 = 모델 그대로). 보간 ticks. */
    public void scale(float s, int ticks) {
        scale = s;
        poseTicks = Math.max(0, ticks);
    }

    /** 찌그러짐(x 옆, y 위, z 앞 배율), ticks 보간. 되돌리려면 squash(1, 1, 1, n). */
    public void squash(float x, float y, float z, int ticks) {
        stretch.set(x, y, z);
        poseTicks = Math.max(0, ticks);
    }

    /** 다음 move 에 방향과 모양을 ticks 보간으로 바로 보냄(15도 문턱 무시). */
    public void pose(int ticks) {
        poseTicks = Math.max(0, ticks);
    }

    /** 다른 모델로 바꾸기(같은 디스플레이). 줄여 구운 배율(Look.scale)이 다르면 자세도 다시 보낸다. */
    public void model(String m) {
        if (Look.scale(m) != Look.scale(model)) poseTicks = Math.max(poseTicks, 0);
        model = m;
        itemDirty = true;
    }

    /** 컬링 상자 바꾸기(몸 중심 기준). 엔티티 높이도 따라 바뀐다. */
    public void size(float w, float h) {
        width = w;
        height = h;
        lift = carrier ? 0 : h / 2;
        if (anchor != null) {
            anchor.setDisplayWidth(w);
            anchor.setDisplayHeight(h);
        }
        sentAt = Long.MIN_VALUE / 2;
        poseTicks = 0;
    }

    /** 보이는 거리 배율(1 = 64 블록 x 클라 설정). */
    public void viewRange(float v) {
        viewRange = v;
        if (anchor != null) anchor.setViewRange(v);
        for (ItemDisplay d : parts) d.setViewRange(v);
    }

    /** 부품 디스플레이 하나 더(닻 승객). 상대 자세는 {@link #attach} 가 돌려주는 Part 로 주는 것이 좋다. */
    public ItemDisplay part(String model) {
        Part p = attach(model);
        return p == null ? null : p.d;
    }

    /** 부품 하나 더(닻 승객, 몸 기준 자세 local, 상태, 겉모습). 닻이 없으면 null. */
    public Part attach(String model) {
        if (anchor == null || removed) return null;
        Part p = new Part(model);
        Matrix4f m = p.matrix(sentYaw, sentPitch);
        ItemStack it = item(model, p.st, p.look);
        p.sentState = p.st;
        p.sentLook = p.look.copy();
        p.d = anchor.getWorld().spawn(anchor.getLocation(), ItemDisplay.class, d -> setup(d, it, m, viewRange, width, height));
        anchor.addPassenger(p.d);
        for (Player h : hiddenFrom) h.hideEntity(owner.game.plugin, p.d);
        pieces.add(p);
        parts.add(p.d);
        return p;
    }

    /** 맞은 번쩍임(셰이더가 흰 번쩍임). 엔진이 맞을 때 부른다. */
    public void flash(long untilWorldTime) {
        look.hurtUntil = untilWorldTime;
        for (Part p : pieces) p.look.hurtUntil = untilWorldTime;
        if (swarm != null) swarm.flash(untilWorldTime);
    }

    // ------------------------------------------------------------------ 엔진

    /** 몸 틀(부품이 곱해 씀): 모델 배율 없이. */
    Matrix4f frame(float yaw, float pitch) {
        return frame(yaw, pitch, 1f);
    }

    /** 몸 틀 x 모델 배율 k(줄여 구운 모델은 Look.scale(model), 보통 1). */
    Matrix4f frame(float yaw, float pitch, float k) {
        float s = scale * k;
        return new Matrix4f().translate(offset.x, offset.y + lift, offset.z).rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(roll))
                .scale(s * stretch.x, s * stretch.y, s * stretch.z);
    }

    private static float wrap(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** 엔진이 매 틱(위치, 방향, 상태 반영). */
    public void move() {
        if (removed || corpse || anchor == null) return;
        if (!anchor.isValid()) return;
        long now = owner.game.now();
        double x = owner.pos.getX(), y = owner.pos.getY() - lift, z = owner.pos.getZ();
        double d2 = (x - sx) * (x - sx) + (y - sy) * (y - sy) + (z - sz) * (z - sz);
        long since = now - sentAt;
        // RELOCATE(warp): teleport_duration 0 을 먼저 한 틱 보내고 그다음 틱에 옮긴 뒤 2 로 되돌림(같은 틱이면 클라가 옛 길이로 미끄러짐)
        boolean held = false;
        if (warpStage == 2) {
            anchor.setTeleportDuration(0);
            warpStage = 1;
            held = true;
        } else if (warpStage == 1) {
            teleport(x, y, z, now);
            warpStage = -1;
            held = true;
        } else if (warpStage == -1) {
            anchor.setTeleportDuration(2);
            warpStage = 0;
        }
        if (!held && ((since >= 2 && d2 > 0.0025) || (since >= 6 && d2 > 1e-6))) teleport(x, y, z, now);
        // 방향과 모양: 빈 닻도 돌려서(부품이 몸 방향을 따름), 닻 transformation 은 sendPose 가 빈 닻이면 건너뜀
        float dy = wrap(owner.yaw - sentYaw), dp = owner.pitch - sentPitch;
        boolean shape = roll != sentRoll || scale != sentScale || !stretch.equals(sentStretch) || !offset.equals(sentOffset);
        if (poseTicks >= 0 || shape || Math.abs(dy) > TURN_DEG || Math.abs(dp) > TURN_DEG) {
            // 큰 회전은 90도씩 끊어(쿼터니언 보간은 짧은 쪽으로 돈다)
            float yaw = Math.abs(dy) > 120f ? sentYaw + Math.signum(dy) * 90f : owner.yaw;
            sendPose(yaw, owner.pitch, poseTicks >= 0 ? poseTicks : turnTicks);
            poseTicks = -1;
        }
        // 맞은 번쩍임이 끝났으면 지우고 다시 실음(셰이더 도장이 480틱 뒤 다시 켜지지 않게). 빈 닻의 look 은 덩어리가 씀
        long wt = owner.game.worldTime();
        boolean settled = Look.settle(look, wt);
        if (!carrier && (settled || itemDirty || !st.equals(sentState) || !look.equals(sentLook) || forceFreeze != sentFreeze)) sendItem();
        for (Part p : pieces) p.sync(wt);
        if (swarm != null) swarm.sync();
    }

    private void teleport(double x, double y, double z, long now) {
        // 26.3 Paper 의 엔티티 teleport 는 늘 타고 있는 부품과 함께 옮긴다(RETAIN_PASSENGERS 는 무시되고 삭제 예정이라 뺐다)
        anchor.teleport(new Location(anchor.getWorld(), x, y, z, 0, 0));
        sx = x;
        sy = y;
        sz = z;
        sentAt = now;
    }

    void sendPose(float yaw, float pitch, int ticks) {
        sentYaw = yaw;
        sentPitch = pitch;
        sentRoll = roll;
        sentScale = scale;
        sentStretch.set(stretch);
        sentOffset.set(offset);
        if (!carrier) {
            anchor.setInterpolationDelay(0);
            anchor.setInterpolationDuration(ticks);
            anchor.setTransformationMatrix(frame(yaw, pitch, Look.scale(model)).rotateY((float) Math.PI));
        }
        for (Part p : pieces) if (p.follow) p.sendPose(ticks);
    }

    private void sendItem() {
        LookState eff = look;
        if (forceFreeze && !look.freeze) {
            eff = look.copy();
            eff.freeze = true;
        }
        anchor.setItemStack(item(model, st, eff));
        sentState = st;
        sentLook = look.copy();
        sentFreeze = forceFreeze;
        itemDirty = false;
    }

    /** 닻 엔티티 자리(마지막으로 텔레포트한 곳). 래그돌이 이 자리를 기준으로 자세를 보낸다. */
    double ex() {
        return sx;
    }

    double ey() {
        return sy;
    }

    double ez() {
        return sz;
    }

    /** 실제 살아 있는 디스플레이 수(닻 + 부품 + 마리 + 덩어리). */
    public int count() {
        if (removed) return 0;
        int n = anchor != null ? 1 : 0;
        n += pieces.size();
        for (Tag t : tags) if (t.d != null) n++;
        if (swarm != null) n += swarm.displays();
        return n;
    }

    /** 이 몸의 모든 디스플레이(닻, 부품, 마리, 덩어리). */
    public List<Entity> entities() {
        List<Entity> out = new ArrayList<>();
        if (anchor != null) out.add(anchor);
        out.addAll(parts);
        for (Tag t : tags) if (t.d != null) out.add(t.d);
        if (swarm != null) swarm.entities(out);
        return out;
    }

    /** 이 사람에게만 숨김 또는 보임. */
    public void hide(Player p, boolean hidden) {
        if (hidden) {
            if (!hiddenFrom.contains(p)) hiddenFrom.add(p);
            for (Entity e : entities()) p.hideEntity(owner.game.plugin, e);
        } else {
            hiddenFrom.remove(p);
            if (anchor != null) p.showEntity(owner.game.plugin, anchor);
            for (ItemDisplay d : parts) p.showEntity(owner.game.plugin, d);
            for (Tag t : tags) if (t.d != null) p.showEntity(owner.game.plugin, t.d);
            if (swarm != null) swarm.reshow(p);
        }
    }

    public boolean hidden(Player p) {
        return hiddenFrom.contains(p);
    }

    public void remove() {
        removed = true;
        dropTags();
        if (swarm != null) swarm.removeAll();
        for (Part p : pieces) if (p.d != null) p.d.remove();
        pieces.clear();
        parts.clear();
        if (anchor != null) anchor.remove();
    }

    /**
     * 납작 사체(래그돌 예산이 없거나 래그돌을 껐을 때 엔진이 부름): 펄럭임을 멈추고 ticks 동안 바닥으로 납작해진다. 몸은 시체 풀이 치울
     * 때까지 남는다. 빈 닻(떼)은 남은 마리를 시체로 돌리고 닻은 바로 지운다. 시체가 남았으면 true.
     */
    boolean corpse(int ticks) {
        if (removed || anchor == null || !anchor.isValid()) return false;
        if (swarm != null) swarm.dieAll();
        if (carrier && pieces.isEmpty()) {
            remove();
            return false;
        }
        long now = owner.game.now();
        teleport(owner.pos.getX(), owner.pos.getY() - lift, owner.pos.getZ(), now);
        look.freeze = true;
        look.open = false;
        if (!carrier) sendItem();
        for (Part p : pieces) {
            p.look.freeze = true;
            p.look.open = false;
            p.sendItem();
        }
        double fl = owner.floor();
        offset.set(0, (float) (fl + 0.06 - owner.pos.getY()), 0);
        stretch.set(1.25f, 0.12f, 1.25f);
        roll = 0;
        sendPose(sentYaw, 0, ticks);
        corpse = true;
        return true;
    }

    // ------------------------------------------------------------------ 새 종 갈고리(2026-11-12 sp_engine)

    /** RELOCATE: 0 없음, 2 다음 move 에 보간 길이 0, 1 그 다음 move 에 옮김, -1 그 다음 move 에 길이 2 로 되돌림. */
    private int warpStage;

    /** 다음 move 부터 두 틱에 걸쳐 보간 없이 지금 owner.pos 로 옮긴다(Mobs.relocate 가 부름, 종이 직접 불러도 됨). RELOCATE */
    public void warp() {
        warpStage = 2;
    }

    /** 몸에 붙은 글 줄(NAMETAG). */
    public final List<Tag> tags = new ArrayList<>();

    /**
     * NAMETAG: 몸에 글 한 줄(사람 이름 같은 바닐라 글자, 한국어 글귀 그림이 아님)을 붙인다. 닻의 승객 text_display 하나라 몸과 같이 움직이고 늘 사람 쪽을 본다.
     * up = 몸 중심에서 위로(블록), size = 글 배율(1 이 바닐라 이름표쯤, 큰 몸은 3~6). 몸이 죽거나 지워지면 엔진이 같이 지운다. 디스플레이 예산(count)에 든다.
     * 참가자 이름을 쓰는 종은 '내 이름 쓰지 않기'(PState.noLikeness)인 사람을 건너뛴다(G26Tag 와 같은 규칙).
     */
    public Tag tag(float up, float size) {
        Tag t = new Tag(up, size);
        tags.add(t);
        return t;
    }

    /** 글 줄을 모두 지움(엔진이 죽을 때, remove 때 부름). */
    public void dropTags() {
        for (Tag t : new ArrayList<>(tags)) t.kill();
        tags.clear();
    }

    /** 글 한 줄(NAMETAG). text 를 바꾸면 그때만 보냄, 빈 글이면 디스플레이를 치움. */
    public final class Tag {
        public TextDisplay d;
        float up, size;
        int rgb = 0xFFFFFF;
        float range = -1;
        String shown = "";

        Tag(float up, float size) {
            this.up = up;
            this.size = size;
        }

        /** 글을 바꿈(빈 글이나 null 이면 치움). */
        public Tag text(String s) {
            String t = s == null ? "" : s;
            if (t.equals(shown) && (d != null && d.isValid() || t.isEmpty())) return this;
            shown = t;
            if (t.isEmpty()) {
                kill();
                return this;
            }
            if (d == null || !d.isValid()) spawn();
            if (d != null) d.text(net.kyori.adventure.text.Component.text(t).color(net.kyori.adventure.text.format.TextColor.color(rgb)));
            return this;
        }

        public String text() {
            return shown;
        }

        /** 글자 색(RGB). */
        public Tag color(int rgb) {
            this.rgb = rgb & 0xFFFFFF;
            if (d != null && d.isValid() && !shown.isEmpty())
                d.text(net.kyori.adventure.text.Component.text(shown).color(net.kyori.adventure.text.format.TextColor.color(this.rgb)));
            return this;
        }

        /** 자리와 크기를 바꿈(ticks 보간). */
        public Tag place(float up, float size, int ticks) {
            this.up = up;
            this.size = size;
            if (d != null && d.isValid()) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(Math.max(0, ticks));
                d.setTransformation(transform());
            }
            return this;
        }

        /** 보이는 거리(view range 배율, 기본은 몸과 같음). */
        public Tag range(float v) {
            range = v;
            if (d != null && d.isValid()) d.setViewRange(v);
            return this;
        }

        public void remove() {
            kill();
            tags.remove(this);
        }

        void kill() {
            if (d != null) d.remove();
            d = null;
        }

        private org.bukkit.util.Transformation transform() {
            float lift0 = (float) (owner.pos.getY() - (anchor == null ? owner.pos.getY() : anchor.getLocation().getY()));
            return new org.bukkit.util.Transformation(new Vector3f(0, lift0 + up, 0), new org.joml.AxisAngle4f(), new Vector3f(size, size, size),
                new org.joml.AxisAngle4f());
        }

        private void spawn() {
            if (removed || anchor == null || !anchor.isValid()) return;
            float vr = range > 0 ? range : Math.max(VIEW_MIN, viewRange);
            d = anchor.getWorld().spawn(anchor.getLocation(), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setSeeThrough(false);
                t.setShadowed(false);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setTextOpacity((byte) 235);
                t.setViewRange(vr);
                t.setBrightness(LIGHT);
                t.setTransformation(transform());
            });
            anchor.addPassenger(d);
            for (Player h : hiddenFrom) h.hideEntity(owner.game.plugin, d);
        }
    }

    /** 부품 하나: 닻의 승객 디스플레이. 몸 기준 자세 local(블록, 모델 앞 = +Z 쪽 몸 앞), 상태, 겉모습. */
    public final class Part {
        public ItemDisplay d;
        public String model;
        /** 몸 기준 자세(몸 방향 회전, 크기 뒤에 곱함). 바꾸면 pose(m, ticks) 로. */
        public final Matrix4f local = new Matrix4f();
        /** false 면 몸 방향을 따르지 않음(local 이 세계 축 기준, 몸 중심에서). */
        public boolean follow = true;
        public final LookState look = new LookState();
        String st = "";
        String sentState;
        LookState sentLook;
        private boolean sentFreeze, poseDirty;
        private int ticks = 3;

        Part(String model) {
            this.model = model;
            look.phase = Body.this.look.phase;
        }

        /** 몸 기준 자세를 바꾸고 ticks 보간으로 보냄(다음 move). */
        public void pose(Matrix4f m, int ticks) {
            local.set(m);
            this.ticks = Math.max(0, ticks);
            poseDirty = true;
        }

        public void state(String s) {
            st = s == null ? "" : s;
        }

        public void model(String m) {
            if (Look.scale(m) != Look.scale(model)) poseDirty = true;
            model = m;
            sentState = null;
        }

        public void remove() {
            if (d != null) d.remove();
            parts.remove(d);
            pieces.remove(this);
        }

        /** 이 부품의 틀(닻 자리 기준, 모델을 반 바퀴 돌리기 전). 래그돌이 조각으로 넘겨받을 때도 쓴다. */
        Matrix4f rig(float yaw, float pitch) {
            Matrix4f base = follow ? frame(yaw, pitch) : new Matrix4f().translate(0, lift, 0);
            return base.mul(local).scale(Look.scale(model));
        }

        Matrix4f matrix(float yaw, float pitch) {
            return rig(yaw, pitch).rotateY((float) Math.PI);
        }

        void sendPose(int t) {
            if (d == null) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(t);
            d.setTransformationMatrix(matrix(sentYaw, sentPitch));
            poseDirty = false;
        }

        void sendItem() {
            if (d == null) return;
            LookState eff = look;
            if (forceFreeze && !look.freeze) {
                eff = look.copy();
                eff.freeze = true;
            }
            d.setItemStack(item(model, st, eff));
            sentState = st;
            sentLook = look.copy();
            sentFreeze = forceFreeze;
        }

        void sync(long worldTime) {
            if (d == null || !d.isValid()) return;
            if (poseDirty) sendPose(ticks);
            boolean settled = Look.settle(look, worldTime);
            if (settled || !st.equals(sentState) || !look.equals(sentLook) || forceFreeze != sentFreeze) sendItem();
        }
    }
}
