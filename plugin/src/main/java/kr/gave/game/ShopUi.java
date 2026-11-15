package kr.gave.game;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import kr.gave.Props;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 상점 화면 꾸미기(상자 창을 어두운 판과 칸 아이템으로). 자산은 tools/make_shop_ui.py 가 만들고(창 바탕, 줄 이름과 숫자 글꼴 gave:shop,
 * 칸 아이템 gave:shop/cell, 돈 표시 gave:shop/wallet, 풍선 도움말 틀 gave:shop), 그 생성기가 팩에 같이 넣는 표 assets/gave/shop_ui.json
 * (제목 글, 아는 그림 키)을 여기서 읽는다. 표가 없는 팩(옛 팩)이면 {@link #on()} 이 false 이고 그림 칸은 받은 아이템 그대로, 값 칸과 돈 표시는
 * null 을 돌려준다: 부르는 쪽은 옛 그림 아이템을 만들어 넘기면 된다(값은 풍선 도움말에 있다).
 * <p>
 * 칸 아이템 계약(custom_model_data, 생성기 머리 주석과 같게): strings[0] 그림 키, strings[1] 판, flags[0] 가림막, floats[0..4] 큰 글자 줄
 * (자리마다 글리프 번호 + 1), floats[5..8] 작은 글자 네 자리, floats[9] 오른쪽 위 표시 번호 + 1, floats[10..11] 오른쪽 아래 구석 두 글자,
 * colors[0] 글자 색, colors[1] 표시 색.
 * 색(가독성이 먼저: 밝은 글자만 쓰고 뜻이 있을 때만 색을 바꾼다): 금색 = 살 수 있는 값과 돈, 빨강 = 돈이 모자람, 초록 = 다 삼(확인 표시),
 * 흰색 = 값이 아닌 숫자(강화 단계, 수량), 밝은 회색 = 잠김(자물쇠). 글자 색에 어두운 남색 계열(R &lt; 84 이면서 B 96..109)은 쓰지 않는다
 * (HUD 의 core/text 고리가 화면 위쪽의 그런 글자를 HUD 글리프로 잡는다, build/audit/팩셰이더.md 3.6).
 * 틴트 색(글자, 표시, 띠)의 R 은 0xE0..0xEF 를 피한다(core/item 고리가 그 틴트를 게이브 면 신호로 읽어 면을 접거나 숨긴다, gave:gabe.glsl).
 * 성능: 칸 아이템은 화면을 그릴 때만 만든다. 위 54칸은 바닐라가 바뀐 칸만 보내고, 아래 세 줄은 Shop.flush 가 바뀐 칸만 쓴다(제목은 열 때 한 번).
 */
public final class ShopUi {

    /** 글자 색: 흰색, 금색(살 수 있는 값, 돈), 빨강(돈이 모자람), 초록(다 삼), 밝은 회색(잠김, 덜 중요한 설명) */
    public static final int WHITE = 0xF0EEE8, GOLD = 0xFFC53D, RED = 0xFF5A4D, GREEN = 0x6EE06E, SOFT = 0xCFCBC2;
    /** 오른쪽 위 표시: 자동, 새것. 없으면 -1 */
    public static final int BADGE_AUTO = 0, BADGE_NEW = 1;
    /** 제목 종류(Shop.View 의 종류와 같은 글) */
    public static final String SHOP = "shop", CARDS = "cards", PARTS = "parts";
    /** 값 칸 글자: 확인 표시(다 삼), 자물쇠(잠김) */
    public static final String CHECK = "v", LOCK = "l";

    private static final Key CELL = Key.key(Props.NS, "shop/cell"), WALLET = Key.key(Props.NS, "shop/wallet"), STYLE = Key.key(Props.NS, "shop"),
        FONT = Key.key(Props.NS, "shop");
    private static final String GLYPHS = "0123456789k+vl-x";
    private static final int[] BADGE_COLOR = {WHITE, GOLD};

    private static byte[] seen;
    private static boolean read;
    private static Map<String, String> titles = Map.of();
    private static Set<String> icons = Set.of();

    private ShopUi() {
    }

    /** 내보내는 팩(PackServer.bytes())이 바뀌었으면 표를 다시 읽는다(같은 배열이면 아무 일 없음: 틱마다 불러도 됨) */
    public static void sync(Game game) {
        byte[] b = game.plugin.pack == null ? null : game.plugin.pack.bytes();
        if (read && b == seen) return;
        read = true;
        seen = b;
        titles = Map.of();
        icons = Set.of();
        if (b == null) return;
        long t0 = System.nanoTime();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (!e.getName().equals("assets/gave/shop_ui.json")) continue;
                JsonObject o = new Gson().fromJson(new String(z.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                // 칸 아이템 계약이 바뀌면 판 번호가 오른다: 모르는 판이면 옛 모습으로
                if (!o.has("v") || o.get("v").getAsInt() != 2) break;
                Map<String, String> t = new HashMap<>();
                for (Map.Entry<String, JsonElement> x : o.getAsJsonObject("titles").entrySet()) t.put(x.getKey(), x.getValue().getAsString());
                Set<String> ic = new HashSet<>();
                for (JsonElement x : o.getAsJsonArray("icons")) ic.add(x.getAsString());
                titles = t;
                icons = ic;
                break;
            }
        } catch (IOException | RuntimeException e) {
            game.plugin.getLogger().warning("[상점] 팩의 shop_ui.json 을 못 읽음(옛 모습으로 그림): " + e);
        }
        game.plugin.getLogger().info("[상점] 화면 꾸미기 " + (on() ? "켬(그림 키 " + icons.size() + ")" : "끔(팩에 shop_ui.json 없음)") + " "
            + (System.nanoTime() - t0) / 1_000_000 + "ms");
    }

    /** 팩에 상점 화면 자산이 있나 */
    public static boolean on() {
        return !titles.isEmpty();
    }

    /** 창 제목(글리프: 바탕과 줄 이름, 고르기 창 이름). 꺼져 있으면 빈 제목 */
    public static Component title(String kind) {
        String s = titles.get(kind);
        // 제목은 어두운 회색으로 그려지므로 흰색을 줘야 그림 색이 그대로 나온다(흰색은 HUD 고리에도 안 걸린다)
        return s == null ? Component.empty() : Component.text(s, TextColor.color(0xFFFFFF)).font(FONT);
    }

    /** 풍선 도움말의 숫자 글(굵은 숫자 글꼴: 값, 지금 → 다음). 꺼져 있으면 보통 글꼴 */
    public static Component num(String s, int rgb) {
        Component c = Component.text(s, TextColor.color(rgb)).decoration(TextDecoration.ITALIC, false);
        return on() ? c.font(FONT) : c;
    }

    /** 값 칸 글자: 999 까지와 네 자리는 그대로(네 자리는 작은 글자로 그려짐), 만 원부터는 천 단위(12k) */
    public static String price(int v) {
        return v < 10000 ? String.valueOf(Math.max(0, v)) : Math.min(99, v / 1000) + "k";
    }

    /** 풍선 도움말 틀만 입힘(카드처럼 그림은 그대로 둘 아이템) */
    public static ItemStack style(ItemStack it) {
        if (on() && it != null) it.setData(DataComponentTypes.TOOLTIP_STYLE, STYLE);
        return it;
    }

    /**
     * 증강 카드 아이템(Augments.card)을 칸 아이템으로: 총 그림 카드(gave:weapon_icon, strings = 등급, 총 키)는 등급 색 판의 그림 칸으로(옛 뒤판은
     * 어두운 판 위에서 총이 안 보임), 카드 그림(gave:shop/aug/&lt;키&gt;)은 그대로, 상점 그림을 빌린 카드는 기본 판 위에. 가진 수가 둘 이상이면
     * 쌓임 수 대신 오른쪽 아래에 굵은 글자로 x2. 팩이 모르는 그림이면 틀만 입히고 그대로 돌려준다
     */
    public static ItemStack card(ItemStack it) {
        if (!on() || it == null) return it;
        Key model = it.getData(DataComponentTypes.ITEM_MODEL);
        if (model == null) return style(it);
        String path = model.value(), icon = path, plate = "tile";
        CustomModelData cmd = it.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (path.equals("weapon_icon") && cmd != null && cmd.strings().size() >= 2) {
            icon = cmd.strings().get(1);
            if (!cmd.strings().get(0).equals("none")) plate = cmd.strings().get(0);
        } else if (path.startsWith("shop/aug/")) {
            icon = "aug_" + path.substring(9);
            plate = "";
        } else if (path.startsWith("shop/")) icon = path.substring(5);
        if (!icons.contains(icon)) return style(it);
        int n = it.getAmount();
        return cell(it, icon, plate, false, null, WHITE, -1, n < 2 ? "" : n < 10 ? "x" + n : String.valueOf(Math.min(99, n)));
    }

    private static ItemStack cell(ItemStack it, String icon, String plate, boolean veil, float[] text, int color, int badge) {
        return cell(it, icon, plate, veil, text, color, badge, "");
    }

    /** corner = 오른쪽 아래 구석 글자(두 글자까지: 가진 수) */
    private static ItemStack cell(ItemStack it, String icon, String plate, boolean veil, float[] text, int color, int badge, String corner) {
        CustomModelData.Builder b = CustomModelData.customModelData().addString(icon).addString(plate).addFlag(veil);
        for (int i = 0; i < 9; i++) b.addFloat(text == null ? 0 : text[i]);
        b.addFloat(badge + 1);
        // 구석 두 자리(오른쪽 맞춤)
        for (int i = 0; i < 2; i++) {
            int at = corner.length() - 2 + i;
            b.addFloat(at < 0 ? 0 : GLYPHS.indexOf(corner.charAt(at)) + 1);
        }
        b.addColor(Color.fromRGB(color)).addColor(Color.fromRGB(badge >= 0 && badge < BADGE_COLOR.length ? BADGE_COLOR[badge] : WHITE));
        it.setData(DataComponentTypes.TOOLTIP_STYLE, STYLE);
        it.setData(DataComponentTypes.ITEM_MODEL, CELL);
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
        it.unsetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        it.setAmount(1);
        return it;
    }

    /**
     * 그림 칸: it 의 이름과 설명 줄은 그대로 두고 그림만 칸 아이템으로 바꾼다. 팩이 icon 을 모르면 틀만 입히고 그대로 돌려준다.
     *
     * @param plate 판: tile(기본), gold(강조), none, 등급 키(common..), 등급 키_hi(새로 뽑은 총)
     * @param veil 어둡게 가림(못 삼, 잠김)
     * @param badge 오른쪽 위 표시(BADGE_*, 없으면 -1)
     */
    public static ItemStack tile(ItemStack it, String icon, String plate, boolean veil, int badge) {
        if (!on() || it == null) return it;
        if (!icons.contains(icon)) return style(it);
        return cell(it, icon, plate, veil, null, WHITE, badge);
    }

    /**
     * 값 칸(그림 칸 바로 아래): 굵은 글자만 그리는 빈 아이템(이름, 설명 줄 없음: 패킷을 작게). 풍선 도움말은 없다(화면을 열면 커서가 한가운데 값
     * 칸에 놓이는데 그때 풍선 도움말이 화면을 가리지 않게. 설명은 그 위 그림 칸에 있다). 꺼져 있거나 글이 비면 null.
     *
     * @param text 숫자, k, +, x, - 또는 {@link #CHECK}, {@link #LOCK}. 세 글자까지 큰 글자, 네 글자는 작은 글자
     */
    public static ItemStack tag(String text, int color) {
        if (!on() || text.isEmpty()) return null;
        float[] f = new float[9];
        int n = Math.min(4, text.length());
        // 큰 글자 자리 i 는 글자 반 칸씩: 세 글자 0, 2, 4, 두 글자 1, 3, 한 글자 2(가운데 맞춤). 네 글자는 작은 글자 자리 5..8
        for (int i = 0; i < n; i++) f[n == 4 ? 5 + i : 3 - n + 2 * i] = GLYPHS.indexOf(text.charAt(i)) + 1;
        ItemStack it = cell(ItemStack.of(Material.FLINT), "", "", false, f, color, -1);
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return it;
    }

    /** 돈 표시(머리띠 오른쪽에 동전 + 숫자를 그리는 아이템, 걸린 칸은 비어 보임, 풍선 도움말 없음). 꺼져 있으면 null */
    public static ItemStack wallet(int money) {
        if (!on()) return null;
        String s = String.valueOf(Math.max(0, Math.min(99999, money)));
        CustomModelData.Builder b = CustomModelData.customModelData();
        // 오른쪽부터 숫자(글리프 번호 + 1), 그 왼쪽에 동전(11)
        for (int i = 0; i <= s.length(); i++) b.addFloat(i < s.length() ? s.charAt(s.length() - 1 - i) - '0' + 1 : 11);
        ItemStack it = ItemStack.of(Material.FLINT);
        it.setData(DataComponentTypes.ITEM_MODEL, WALLET);
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return it;
    }

    /**
     * 카드 위아래 갈래 색 띠(색유리판 대신). above = 카드 위 칸(띠가 칸 아래쪽에 붙음). 꺼져 있으면 그 유리판 그대로.
     * 색은 유리판 염료 색(검정은 어두운 판에서 안 보여 회색으로)
     */
    public static ItemStack bar(Material pane, boolean above) {
        ItemStack it = ItemStack.of(on() ? Material.FLINT : pane);
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        if (!on()) return it;
        int rgb = 0x8A857C;
        try {
            DyeColor dye = DyeColor.valueOf(pane.name().replace("_STAINED_GLASS_PANE", "").toUpperCase(Locale.ROOT));
            if (dye != DyeColor.BLACK) rgb = dye.getColor().asRGB();
        } catch (IllegalArgumentException e) {
            // 색유리판이 아니면 회색
        }
        return cell(it, above ? "bar_down" : "bar_up", "", false, null, rgb, -1);
    }
}
