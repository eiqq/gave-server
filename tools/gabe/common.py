"""모든 종이 같이 쓰는 엔진 자산(Mobs, Ragdoll 이 씀, 종 번호가 없는 이름). make_gabe 가 조각 build/gabe/sp/common/ 으로 굽는다.

gave:gabe.bite  물어뜯기(서로 다른 종 머리가 닿음, 반경 20)
gave:gabe.split 얼굴이 갈라져 둘(오래 살면 변함 3단계, 반경 20)
gave:gabe/common_rod  래그돌 사체의 흐물 막대기 마디 하나(엔진 kr.gave.mob.Ragdoll.ROD_MODEL). 한 변 1 블록 정육면체이고 길이 쪽이 +Y:
                디스플레이가 (굵기, 마디 길이, 굵기)로 늘여 그린다. 상태 = 텍스처("" 엔드 막대기, flesh, wetskin, bone: kit.RAG_PARTS)
"""
from gabe import audio, kit


def build(k: kit.Kit):
    m = k.model("common_rod", poses=1, period=20, mut2=False, open=False, torn=False, rag=False)
    for part in kit.RAG_PARTS:
        s = m.state("" if part == "rod" else part)
        if part == "rod":
            # 종 모델의 막대기 마디와 같은 그림 자리(옆 [0, 0, 2, 15], 마구리 [2, 0, 4, 2])
            uv = {f: [0, 0, 2, 15] for f in ("north", "south", "east", "west")}
            uv.update(up=[2, 0, 4, 2], down=[2, 0, 4, 2])
        else:
            uv = [0, 0, 16, 16]
        s.box((-0.5, -0.5, -0.5), (0.5, 0.5, 0.5), part, uv=uv)


def sounds(k: kit.Kit):
    k.sound("bite", "게이브끼리 물어뜯음", vanilla=[("mob/fox/bite1", 0.75), ("mob/fox/bite2", 0.7), ("mob/fox/bite3", 0.8)],
            attenuation=20, event="gabe.bite")
    k.sound("split", "얼굴이 갈라짐", synth=lambda i: audio.squish(seed=60 + i, dur=0.4), vanilla=[("mob/slime/big1", 0.6)],
            attenuation=20, event="gabe.split")
