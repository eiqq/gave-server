package kr.gave.mob.sp;

import org.bukkit.util.Vector;

import kr.gave.mob.Gabe;
import kr.gave.mob.Species;

/**
 * 묶음 4(숨고 속이는 떼) 공용 도우미: 몸 하나짜리 떼 종(G82 G83 G84 G86 G95 G108 G109)은 감독이 한 개체를 단위 4(Director.unit 떼 역할)로 세어
 * 내보내므로, 감독이 낸 개체가 첫 틱에 곁에 동족을 더 놓아 무리(도감의 수)를 채운다. 더 놓은 개체도 마리당 보상과 같은 체력을 갖는다
 * (감독 몫을 나눈 것이지 소환이 아님). 예산(Mobs.BUDGET)이 차면 그만큼 덜 놓는다. 시험 소환(/gave 소환)한 개체는 채우지 않는다.
 */
final class G82Pack {

    private G82Pack() {
    }

    /**
     * lead 가 감독이 낸 개체면 곁에 동족 extra 마리를 더 놓는다(수평 spread 블록 안 고리, 미리 깔리는 종은 감독의 다른 깔 자리). 놓은 수.
     */
    static int fill(Gabe lead, int extra, double spread) {
        if (extra <= 0 || lead.noReward || !lead.game.director.fromDirector(lead)) return 0;
        int made = 0;
        for (int i = 0; i < extra; i++) {
            double a = lead.game.random.nextDouble() * Math.PI * 2, r = spread * (0.45 + 0.55 * lead.game.random.nextDouble());
            Vector at = lead.pos.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
            if (lead.sp.spawn == Species.Spawn.PLACED) {
                Vector pl = lead.game.director.placeFor(lead.sp);
                if (pl != null) at = pl;
            }
            Gabe c = lead.child(lead.sp, at);
            if (c == null) break;
            c.noReward = false;
            c.reward = lead.reward;
            c.maxHp = lead.maxHp;
            c.hp = lead.maxHp;
            made++;
        }
        return made;
    }
}
