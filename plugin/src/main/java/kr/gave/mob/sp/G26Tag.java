package kr.gave.mob.sp;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import kr.gave.mob.Gabe;
import kr.gave.player.PState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * 먹힌 동료 이름표(G26 실뭉치, G27 겹얼굴): 몸 닻의 승객 text_display 하나, 흐린 회색 이름을 줄로 쌓는다.
 *
 * <p>엔진 Body 에 글 부품이 없어 종 파일 안에서 직접 만든다(요청: Body 에 이름표 부품). 영속 안 함, 종이 die, remove 에서 지운다.
 * '내 얼굴, 내 이름 쓰지 않기'(PState.noLikeness)인 사람 이름은 싣지 않는다. 플레이어 이름은 바닐라 글꼴로 그대로(한국어 글귀가 아님).
 */
final class G26Tag {

    static final TextColor GRAY = TextColor.color(0x8c8c8c);

    private final Gabe owner;
    private TextDisplay d;
    private String shown = "";
    private float up;

    G26Tag(Gabe owner, float up) {
        this.owner = owner;
        this.up = up;
    }

    /** 이 사람들 이름(이름 쓰기 허락한 사람만)으로 바꿈. 없으면 지움. */
    void set(List<Player> people) {
        List<String> names = new ArrayList<>();
        for (Player p : people) {
            if (p == null) continue;
            PState s = owner.game.players.get(p);
            if (s != null && s.noLikeness) continue;
            names.add(p.getName());
        }
        setNames(names);
    }

    void setNames(List<String> names) {
        String text = String.join("\n", names);
        if (text.equals(shown) && (d != null || text.isEmpty())) return;
        shown = text;
        if (text.isEmpty()) {
            remove();
            return;
        }
        if (d == null || !d.isValid()) spawn();
        if (d != null) d.text(Component.text(text).color(GRAY));
    }

    String text() {
        return shown;
    }

    private void spawn() {
        if (owner.body == null || owner.body.anchor == null || !owner.body.anchor.isValid()) return;
        var anchor = owner.body.anchor;
        float lift = (float) (owner.pos.getY() - anchor.getLocation().getY());
        float y = lift + up;
        d = anchor.getWorld().spawn(anchor.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(false);
            t.setShadowed(false);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setTextOpacity((byte) 230);
            t.setViewRange(0.6f);
            t.setBrightness(new Display.Brightness(10, 10));
            t.setTransformation(new Transformation(new Vector3f(0, y, 0), new AxisAngle4f(), new Vector3f(0.95f, 0.95f, 0.95f), new AxisAngle4f()));
        });
        anchor.addPassenger(d);
        for (Player h : owner.body.hiddenFrom) h.hideEntity(owner.game.plugin, d);
    }

    void remove() {
        if (d != null) d.remove();
        d = null;
    }
}
