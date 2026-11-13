package kr.gave.hud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD 칸 한 줄. 조각(글자, 그림, 셰이더 모양, 펜 이동)을 왼쪽에서 오른쪽으로 쌓으며 펜 자리(GUI 정수)를 센다.
 * 칸 기준점이 펜 0, 글자 기준선이 0 이다(tools/make_hud.py SLOTS). 줄 끝의 펜은 {@link HudScreen} 이 0 으로 되돌린다.
 * 글리프 세로 자리는 글리프마다 정해져 있다(make_hud.py 의 높이, ascent).
 */
public final class HudLine {
    /** 글꼴: 바닐라 글자 1배, 2배, HUD 그림과 모양(펜 이동 포함) */
    public static final int S = 0, M = 1, HUD = 2;

    /** 조각 하나. b 는 표시 색 B(0 이면 펜 이동, 색 없음), g 는 팔레트 또는 채움, 시간 */
    record Piece(int font, String text, int g, int b, boolean shadow) {
    }

    final List<Piece> pieces = new ArrayList<>(6);
    int pen;

    // ------------------------------------------------------------------ 표

    private static final Map<String, Integer> IMG = new HashMap<>(), SHAPE = new HashMap<>(), SLOT = new HashMap<>(), PAL = new HashMap<>();
    private static final int[][] ADV = new int[2][0x80];

    static {
        for (int i = 0; i < HudSpec.IMAGES.length; i++) IMG.put(HudSpec.IMAGES[i], i);
        for (int i = 0; i < HudSpec.SHAPES.length; i++) SHAPE.put(HudSpec.SHAPES[i], i);
        for (int i = 0; i < HudSpec.SLOTS.length; i++) SLOT.put(HudSpec.SLOTS[i], i);
        for (int i = 0; i < HudSpec.PALETTE.length; i++) PAL.put(HudSpec.PALETTE[i], i);
        String[] adv = {HudSpec.ADV_S, HudSpec.ADV_M};
        for (int f = 0; f < 2; f++)
            for (int c = 0x20; c < 0x7F; c++) ADV[f][c] = adv[f].charAt(c - 0x20) - 0x1000;
    }

    /** 칸 번호(없으면 -1) */
    public static int slot(String name) {
        return SLOT.getOrDefault(name, -1);
    }

    /** 팔레트 번호(없으면 0 = 흰색) */
    public static int pal(String name) {
        return PAL.getOrDefault(name, 0);
    }

    /** 그 이름의 그림 글리프가 팩에 있는지(소품이 없으면 생성기가 안 만듦) */
    public static boolean has(String image) {
        return IMG.containsKey(image);
    }

    /** 그림 잉크 폭(GUI). 없으면 0 */
    public static int ink(String image) {
        Integer i = IMG.get(image);
        return i == null ? 0 : HudSpec.IMAGE_INK[i];
    }

    /** 글자열 폭(마지막 글자 뒤 1 칸 포함, 바닐라와 같음). 없는 글자는 '?' */
    public static int width(int font, String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += ADV[font][safe(s.charAt(i))];
        return w;
    }

    private static char safe(char c) {
        return c >= 0x20 && c < 0x7F ? c : '?';
    }

    // ------------------------------------------------------------------ 쌓기

    /** 펜을 칸 안 자리 x 로 */
    public HudLine at(int x) {
        return move(x - pen);
    }

    public HudLine move(int n) {
        if (n != 0) {
            pieces.add(new Piece(HUD, moves(n), 0, 0, false));
            pen += n;
        }
        return this;
    }

    public int pen() {
        return pen;
    }

    /** 글자(그림자 있음). pal = 팔레트 번호 */
    public HudLine text(int font, String s, int pal) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) b.append(safe(s.charAt(i)));
        pieces.add(new Piece(font, b.toString(), pal, HudSpec.B_IMG, true));
        pen += width(font, s);
        return this;
    }

    /** 오른쪽 잉크 끝을 x 에 맞춘 글자 */
    public HudLine textRight(int x, int font, String s, int pal) {
        return at(x - width(font, s) + 1).text(font, s, pal);
    }

    /** 그림 글리프(그림자 없음). 없는 그림이면 아무것도 안 함 */
    public HudLine img(String name, int pal) {
        Integer i = IMG.get(name);
        if (i == null) return this;
        pieces.add(new Piece(HUD, String.valueOf(HudSpec.IMAGE_CHARS.charAt(i)), pal, HudSpec.B_IMG, false));
        pen += HudSpec.IMAGE_ADV[i];
        return this;
    }

    /**
     * 폭 w 의 단색 네모(kind = strike, panel, band, uline, plate: tools/make_hud.py RECTS)를 2^k 조각으로 이어 pal 색으로.
     * 조각은 셰이더 단색 모양(G = 팔레트, 가장 가까운 텍셀)이라 이음매에 틈이 없다. 모양이 없으면 같은 이름 그림 글리프로
     */
    public HudLine rect(String kind, int w, int pal) {
        int x = pen;
        for (int k = 7; k >= 0 && w > 0; k--) {
            int part = 1 << k;
            while (w >= part) {
                at(x);
                solid(kind + "_" + part, pal);
                x += part;
                w -= part;
            }
        }
        return at(x);
    }

    /** 단색 모양 글리프 하나(G = 팔레트 번호). 모양이 없으면 그림 글리프로 */
    private HudLine solid(String name, int pal) {
        Integer i = SHAPE.get(name);
        if (i == null) return img(name, pal);
        pieces.add(new Piece(HUD, String.valueOf(HudSpec.SHAPE_CHARS.charAt(i)), pal, HudSpec.B_SHAPE, false));
        pen += HudSpec.SHAPE_ADV[i];
        return this;
    }

    /** 셰이더 모양(채움 0..1, 칸 색) */
    public HudLine shape(String name, double fill) {
        Integer i = SHAPE.get(name);
        if (i == null) return this;
        int g = (int) Math.round(Math.max(0, Math.min(1, fill)) * 255);
        pieces.add(new Piece(HUD, String.valueOf(HudSpec.SHAPE_CHARS.charAt(i)), g, HudSpec.B_SHAPE, false));
        pen += HudSpec.SHAPE_ADV[i];
        return this;
    }

    /** 칸 단위 채움: 폭 w 텍셀 막대의 k 칸을 정확히 채우는 값(make_hud.py bar_cell 의 열 가운데 자리와 맞춤) */
    public static double cells(int k, int w) {
        return Math.max(0, Math.min(w, k)) / (double) w;
    }

    /**
     * 시간 모양: 셰이더가 GameTime 으로 남은 틱을 계산해 매 프레임 채운다. endWorldTick = 끝나는 월드 게임 틱(World#getGameTime),
     * ticks = 전체 길이(가까운 길이 칸으로). 적중 표시(hit)는 길이가 정해져 있다.
     */
    public HudLine timed(String name, long endWorldTick, int ticks) {
        Integer i = SHAPE.get(name);
        if (i == null) return this;
        int bin = name.equals("hit") ? 0 : bin(ticks);
        int v = (int) Math.floorMod(endWorldTick, (long) HudSpec.PERIOD);
        pieces.add(new Piece(HUD, String.valueOf((char) (HudSpec.SHAPE_CHARS.charAt(i) + bin)), v & 0xFF, HudSpec.B_TIMED + (v >> 8), false));
        pen += HudSpec.SHAPE_ADV[i];
        return this;
    }

    static int bin(int ticks) {
        int best = 0;
        for (int k = 1; k < HudSpec.DUR.length; k++)
            if (Math.abs(HudSpec.DUR[k] - ticks) < Math.abs(HudSpec.DUR[best] - ticks)) best = k;
        return best;
    }

    boolean empty() {
        for (Piece p : pieces) if (p.b() != 0) return false;
        return true;
    }

    /** 펜 이동 글리프(+2^i = U+E000+i, -2^i = U+E010+i) */
    static String moves(int n) {
        StringBuilder b = new StringBuilder();
        int base = n > 0 ? 0xE000 : 0xE010;
        int m = Math.abs(n);
        int top = 1 << (HudSpec.MOVE_BITS - 1);
        while (m >= top * 2) {
            b.append((char) (base + HudSpec.MOVE_BITS - 1));
            m -= top;
        }
        for (int i = 0; i < HudSpec.MOVE_BITS; i++) if ((m & (1 << i)) != 0) b.append((char) (base + i));
        return b.toString();
    }

    /** 시험 출력용 짧은 설명 */
    public String describe() {
        StringBuilder b = new StringBuilder();
        for (Piece p : pieces) {
            if (p.b() == 0) continue;
            if (b.length() > 0) b.append(' ');
            if (p.font() != HUD) b.append('"').append(p.text()).append('"');
            else {
                char c = p.text().charAt(0);
                int ii = HudSpec.IMAGE_CHARS.indexOf(c);
                if (ii >= 0) b.append(HudSpec.IMAGES[ii]);
                else {
                    int best = -1;
                    for (int k = 0; k < HudSpec.SHAPE_CHARS.length(); k++)
                        if (HudSpec.SHAPE_CHARS.charAt(k) <= c && (best < 0 || HudSpec.SHAPE_CHARS.charAt(k) > HudSpec.SHAPE_CHARS.charAt(best))) best = k;
                    b.append(best >= 0 ? HudSpec.SHAPES[best] + "#" + (c - HudSpec.SHAPE_CHARS.charAt(best)) : "?");
                }
            }
            b.append(p.b() == HudSpec.B_IMG ? "/" + HudSpec.PALETTE[Math.min(p.g(), HudSpec.PALETTE.length - 1)]
                : p.b() == HudSpec.B_SHAPE ? "=" + p.g() : "@" + ((p.b() - HudSpec.B_TIMED) * 256 + p.g()));
        }
        return b.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HudLine l && l.pen == pen && l.pieces.equals(pieces);
    }

    @Override
    public int hashCode() {
        return pieces.hashCode() * 31 + pen;
    }
}
