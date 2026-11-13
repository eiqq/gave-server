package kr.gave.hud;

import java.util.Arrays;

import org.bukkit.entity.Player;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * 한 사람의 화면 HUD: 흰 보스바 하나(막대 그림은 팩에서 투명)의 제목 한 줄에 모든 칸의 글리프를 싣는다.
 * 리소스팩 core/text 셰이더(gave:hud.glsl)가 표시 색 R(칸)을 읽어 화면 칸으로 옮긴다. 셰이더는 첫 보스바 제목 자리를 기준으로
 * 삼으므로 이 보스바는 다른 보스바보다 먼저 보여야 한다(판의 단계 막대도 이 줄에 들어 있어 다른 보스바는 없다).
 * 칸 내용은 매 틱 다시 짜도 되고, 바뀐 것이 있을 때만 제목을 보낸다.
 */
public final class HudScreen {
    public static final int SLOTS = HudSpec.SLOTS.length;
    private static final Key[] FONTS = {Key.key(HudSpec.FONT_S), Key.key(HudSpec.FONT_M), Key.key(HudSpec.FONT_HUD)};

    private final BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    private final HudLine[] lines = new HudLine[SLOTS];
    private HudLine[] sent = new HudLine[SLOTS];
    private boolean shown;
    private int sends;

    public void show(Player p) {
        if (!shown) {
            p.showBossBar(bar);
            shown = true;
        }
    }

    public void hide(Player p) {
        if (shown) {
            p.hideBossBar(bar);
            shown = false;
        }
    }

    public boolean shown() {
        return shown;
    }

    /** 이번 틱 칸 내용을 모두 비운다(다시 짜기 전에) */
    public void reset() {
        Arrays.fill(lines, null);
    }

    /** 칸 하나(null 이나 빈 줄이면 비움) */
    public void set(int slot, HudLine line) {
        if (slot < 0 || slot >= SLOTS) return;
        lines[slot] = line == null || line.empty() ? null : line;
    }

    public HudLine get(int slot) {
        return slot < 0 || slot >= SLOTS ? null : lines[slot];
    }

    /** 바뀐 칸이 있으면 제목을 보낸다. 보냈으면 참 */
    public boolean flush() {
        if (Arrays.equals(lines, sent)) return false;
        sent = lines.clone();
        bar.name(render(sent));
        sends++;
        return true;
    }

    /** 지금까지 보낸 횟수(시험용) */
    public int sends() {
        return sends;
    }

    /** 시험 출력: 칸마다 짧은 설명 */
    public String describe() {
        StringBuilder b = new StringBuilder();
        for (int s = 0; s < SLOTS; s++) {
            if (sent[s] == null) continue;
            b.append(HudSpec.SLOTS[s]).append('[').append(sent[s].describe()).append("] ");
        }
        return b.toString().trim();
    }

    /**
     * 칸마다: 조각들, 펜을 0 으로 되돌림. 줄 전체 순 폭이 0 이라 제목은 늘 화면 가운데에서 시작한다.
     * 글자 그림자는 글자 팔레트와 같은 깜빡임 효과의 그림자 팔레트(HudSpec.PAL_SHADOW)로: 숨은 글자의 그림자가 비치지 않게
     */
    static Component render(HudLine[] lines) {
        TextComponent.Builder root = Component.text();
        for (int slot = 0; slot < lines.length; slot++) {
            HudLine line = lines[slot];
            if (line == null) continue;
            for (HudLine.Piece p : line.pieces) {
                Component c = Component.text(p.text()).font(FONTS[p.font()]);
                if (p.b() != 0) c = c.color(TextColor.color(slot, p.g(), p.b())).shadowColor(p.shadow() ? shadow(slot, p.g()) : ShadowColor.none());
                root.append(c);
            }
            if (line.pen != 0) root.append(Component.text(HudLine.moves(-line.pen)).font(FONTS[HudLine.HUD]));
        }
        return root.build();
    }

    private static ShadowColor shadow(int slot, int pal) {
        int sp = HudSpec.PAL_SHADOW[Math.max(0, Math.min(pal, HudSpec.PAL_SHADOW.length - 1))];
        return ShadowColor.shadowColor(0xFF000000 | slot << 16 | sp << 8 | HudSpec.B_IMG);
    }
}
