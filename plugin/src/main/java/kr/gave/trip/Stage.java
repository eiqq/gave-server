package kr.gave.trip;

import java.util.Collections;
import java.util.List;

/**
 * 스테이지 하나(갈림길의 갈래이자, 고른 뒤에는 지금 있는 곳). 한 스테이지 = 2라운드(스테이지 n 은 라운드 2n - 1, 2n), 16 은 발사장.
 *
 * <p>type 은 표지판에 보이는 종류(미지면 "unknown"), kind() 는 실제로 걸리는 종류(미지는 도착해서 드러난 것, 보스 길목은 "gate").
 * 보스 길목(스테이지 5, 10, 15)의 갈래는 종류가 아니라 준비 셋(gate: gate_rest, gate_arm, gate_run)이다.
 */
public final class Stage {

    public static final String FIGHT = "fight", UNKNOWN = "unknown", GATE = "gate", EVENT = "event", RUSH = "rush", TRAP = "trap", REST = "rest",
        LAUNCH = "launch";

    /** 스테이지 번호(1..16), 막(1..4). */
    public final int index, act;
    /** 표지판에 보이는 종류 키. */
    public String type;
    /** 미지가 드러난 종류(도착 전에는 가려 둠, 미지가 아니면 null). */
    public String hidden;
    /** 드러났나(미지만 뜻이 있음). */
    public boolean revealed;
    /** 카드(이름과 소품). 보스 길목과 발사장은 null. */
    public StageData.Card card;
    /** 보스 길목 준비(아니면 null). */
    public StageData.Gate gate;
    /** 사건 키(종류가 event 일 때), 첫 라운드를 바꾸는 특수 라운드 키(험로 카드의 것, 보장으로 낀 것. 없으면 null). */
    public String event, special;
    /** 갈림길을 열 때 고쳐 넣은 갈래인가(정비 보장, 세 번 이어짐 막기). */
    public boolean patched;
    /** 특수 라운드 보장으로 첫 라운드가 바뀐 스테이지인가. */
    public boolean forced;

    private final StageData data;

    Stage(StageData data, int index, String type) {
        this.data = data;
        this.index = index;
        this.act = actOf(index);
        this.type = type;
    }

    /** 스테이지의 막: 1~5 는 1막, 6~10 은 2막, 11~15 는 3막, 16 은 4막. */
    public static int actOf(int stage) {
        return stage <= 5 ? 1 : stage <= 10 ? 2 : stage <= 15 ? 3 : 4;
    }

    /** 보스 길목 스테이지인가(5, 10, 15). */
    public static boolean gateStage(int stage) {
        return stage == 5 || stage == 10 || stage == 15;
    }

    /** 스테이지의 첫 라운드와 둘째 라운드. */
    public int firstRound() {
        return index * 2 - 1;
    }

    public int secondRound() {
        return index * 2;
    }

    /** 실제로 걸리는 종류(미지는 드러난 것. 드러나기 전에는 "unknown"). */
    public String kind() {
        if (UNKNOWN.equals(type)) return revealed && hidden != null ? hidden : UNKNOWN;
        return type;
    }

    /** 표지판 모델 상태(종류 키. 보스 길목은 준비의 아이콘 종류). */
    public String sign() {
        if (gate != null) return gate.icon;
        return data.type(type).sign;
    }

    /** 위험(해골 수 0..3. 미지는 0 = 가림). */
    public int risk() {
        if (gate != null) return gate.risk;
        if (LAUNCH.equals(type)) return 3;
        return data.type(type).risk;
    }

    /** 보상 키 목록(표지판 아이콘. 미지는 빈 목록 = 가림). */
    public List<String> rewards() {
        if (gate != null) return List.of("boss");
        if (UNKNOWN.equals(type) || LAUNCH.equals(type)) return Collections.emptyList();
        return data.type(type).reward;
    }

    /** 이름 글귀 키(카드가 있으면 카드, 보스 길목은 준비 이름, 없으면 종류 이름). */
    public String phrase() {
        if (gate != null) return "rt_" + gate.key;
        if (LAUNCH.equals(type)) return "rt_launch";
        if (card != null) return "rt_card_" + card.key;
        return "rt_type_" + type;
    }

    /** 사람이 읽는 이름(기록용). */
    public String label() {
        if (gate != null) return gate.name;
        if (LAUNCH.equals(type)) return "발사장";
        String t = data.type(kind()).name;
        return card != null ? card.name + "(" + t + ")" : t;
    }

    /** 규칙과 보상 표의 키(보스 길목은 준비 키, 그 밖은 실제 종류). */
    public String effectKey() {
        return gate != null ? gate.key : kind();
    }

    /**
     * 그 라운드의 라운드 풀 글(Rounds 가 읽음): "pool:plain" 같은 풀, "special"(그 막 특수에서 고름), "special:chase"(정한 특수),
     * "special:act2"(보장으로 낀 그 막 특수), "event", "reveal", "boss", "launch". 험로와 보장은 첫 라운드, 함정 카드의 특수는 둘째 라운드를 바꾼다.
     */
    public String roundSpec(int round) {
        boolean firstRound = round == firstRound();
        if (LAUNCH.equals(type)) return "launch";
        if (gate != null) return firstRound ? (special != null ? "special:" + special : gate.first) : "boss";
        String k = kind();
        if (special != null) {
            if (firstRound && (forced || RUSH.equals(k))) return "special:" + special;
            if (!firstRound && TRAP.equals(k)) return "special:" + special;
        }
        StageData.Type t = data.type(k);
        return firstRound ? t.first : t.second;
    }

    /** 소품 묶음 키(없으면 "none"). */
    public String props() {
        return card != null ? card.props : "none";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(index).append(':').append(type);
        if (hidden != null) sb.append('>').append(hidden);
        if (gate != null) sb.append('/').append(gate.key);
        if (card != null) sb.append('/').append(card.key);
        if (event != null) sb.append('/').append(event);
        if (special != null) sb.append("/!").append(special);
        if (patched) sb.append('*');
        return sb.toString();
    }
}
