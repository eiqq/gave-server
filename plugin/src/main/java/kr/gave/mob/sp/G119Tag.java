package kr.gave.mob.sp;

import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

import kr.gave.mob.Gabe;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * G119 이름 연의 이마 이름표: 닻의 승객 text_display 하나에 표적 사람 이름을 크게(검붉게) 적고, 연 판과 같은 방향(yaw, pitch)으로 기울인다.
 * 이름 숨김 변이(director.hideNames())나 '내 얼굴, 내 이름 쓰지 않기'(PState.noLikeness)면 이름 대신 붉은 점 하나.
 *
 * <p>글 디스플레이는 앞이 -Z 쪽이라(게임 안에서 이름이 거울로 보였음) 반 바퀴 돌려 연 얼굴과 같은 쪽을 보게 한다.
 *
 * <p>엔진 Body 에 글 부품이 없어(NAMETAG 요청) 종 파일 안에서 직접 만든다. 영속 안 함, 종이 die, remove 에서 지운다. 플레이어 이름은 바닐라 글꼴 그대로.
 */
final class G119Tag {

    static final TextColor INK = TextColor.color(0x6e0f12);
    static final TextColor DOT = TextColor.color(0xc0161c);
    /** 이마 높이(판 가운데 기준 블록), 판 앞으로 띄움, 이름 폭 상한(블록). */
    static final float UP = 1.72f, OUT = 0.1f, MAX_W = 4.3f, MAX_S = 3.6f;

    private final Gabe owner;
    private TextDisplay d;
    private String shown = null;
    private float sentYaw = Float.NaN, sentPitch = Float.NaN;
    private float scale = 1f;

    G119Tag(Gabe owner) {
        this.owner = owner;
    }

    /** 이 사람 이름으로(null 이면 지움). */
    void set(Player p) {
        String text;
        if (p == null) text = "";
        else {
            PState s = owner.game.players.get(p);
            boolean hide = owner.game.director.hideNames() || s != null && s.noLikeness;
            text = hide ? "●" : p.getName();
        }
        if (text.equals(shown)) return;
        shown = text;
        if (text.isEmpty()) {
            remove();
            return;
        }
        if (d == null || !d.isValid()) spawn();
        if (d == null) return;
        boolean dot = "●".equals(text);
        d.text(Component.text(text).color(dot ? DOT : INK));
        scale = dot ? 3.2f : Math.min(MAX_S, MAX_W / Math.max(1f, text.length() * 0.15f));
        sentYaw = Float.NaN;
        pose(0);
    }

    private void spawn() {
        if (owner.body == null || owner.body.anchor == null || !owner.body.anchor.isValid()) return;
        var anchor = owner.body.anchor;
        d = anchor.getWorld().spawn(anchor.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.FIXED);
            t.setSeeThrough(false);
            t.setShadowed(false);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setViewRange(3f);
            t.setBrightness(new Display.Brightness(12, 12));
            t.setDisplayWidth(6f);
            t.setDisplayHeight(owner.body.height);
        });
        anchor.addPassenger(d);
    }

    /** 몸 방향을 따라(ticks 보간, 조금이라도 바뀌었을 때만). */
    void follow(int ticks) {
        if (d == null || !d.isValid()) return;
        if (Math.abs(owner.yaw - sentYaw) < 0.2f && Math.abs(owner.pitch - sentPitch) < 0.2f) return;
        pose(ticks);
    }

    private void pose(int ticks) {
        if (d == null || !d.isValid() || owner.body == null || owner.body.anchor == null) return;
        sentYaw = owner.yaw;
        sentPitch = owner.pitch;
        float lift = owner.body.height / 2f;
        var o = owner.body.offset;
        Matrix4f m = new Matrix4f().translate(o.x, o.y + lift, o.z).rotateY((float) Math.toRadians(-owner.yaw))
                .rotateX((float) Math.toRadians(owner.pitch)).translate(0, UP, OUT).rotateY((float) Math.PI).scale(scale);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformationMatrix(m);
    }

    void remove() {
        if (d != null) d.remove();
        d = null;
        shown = null;
    }
}
