package kr.gave.mob;

import java.lang.reflect.Constructor;
import java.util.EnumSet;
import java.util.Set;

import kr.gave.game.Game;

/**
 * 게이브 종(보스 포함)의 기본 값. tools/gen_species.py 가 data/bestiary.json 과 그 안의 TUNE 표로 만든다(손으로 고치지 말 것).
 * 종 행동은 kr.gave.mob.sp.<번호>(예: kr.gave.mob.sp.G01) 클래스, 생성자 (Game, Species).
 */
public enum Species {
    G01("가오리 게이브", 1, 1, Band.WAIST, Spawn.RING, 13, 1, 3, 1, Role.CHARGE),
    G02("새끼 가오리 떼", 2, 2, Band.WAIST, Spawn.RING, 5, 26, 10, 1, Role.SWARM),
    G03("막대기 곡예 게이브", 2, 2, Band.HEAD, Spawn.RING, 30, 1, 6, 1, Role.RANGED),
    G04("뭉텅이 떼 게이브", 3, 2, Band.WAIST, Spawn.RING, 8, 1, 8, 1, Role.SWARM),
    G05("혹 터지는 게이브", 3, 2, Band.WAIST, Spawn.RING, 30, 1, 6, 1, Role.CHARGE),
    G06("침 뱉는 게이브", 4, 2, Band.WAIST, Spawn.RING, 35, 1, 6, 1, Role.RANGED),
    G07("재화 흉내 게이브", 4, 1, Band.GROUND, Spawn.PLACED, 20, 1, 8, 3, Role.TRAP),
    G08("금빛 홍조 게이브", 5, 1, Band.WAIST, Spawn.RING, 45, 1, 0, 1, Role.BONUS),
    G09("멈칫 게이브", 5, 3, Band.HEAD, Spawn.RING, 50, 1, 9, 1, Role.CHARGE),
    G10("쪼개지는 게이브", 6, 2, Band.WAIST, Spawn.RING, 40, 1, 6, 1, Role.SWARM),
    G11("시체 뜯는 떼 게이브", 6, 2, Band.GROUND, Spawn.RING, 4, 26, 10, 2, Role.SWARM),
    G12("얼굴 꿰는 게이브", 7, 2, Band.WAIST, Spawn.RING, 40, 1, 6, 2, Role.DISRUPT),
    G13("바람개비 게이브", 7, 3, Band.HEAD, Spawn.RING, 60, 1, 9, 1, Role.SHIELD, Role.CHARGE),
    G14("상점 갉는 게이브", 8, 2, Band.WAIST, Spawn.RING, 40, 1, 6, 1, Role.OBJECTIVE),
    G15("박수 게이브", 8, 2, Band.HEAD, Spawn.RING, 40, 1, 6, 1, Role.SUPPORT),
    G16("내리꽂는 게이브", 9, 3, Band.SKY, Spawn.SKY, 50, 1, 9, 2, Role.CHARGE, Role.DISRUPT),
    G17("우는 아기 게이브", 9, 3, Band.GROUND, Spawn.PLACED, 70, 2, 3, 3, Role.TRAP),
    G18("겹겹 덧댄 게이브", 10, 3, Band.HEAD, Spawn.RING, 165, 1, 12, 1, Role.TANK),
    G19("펼친 얼굴 방패 게이브", 10, 3, Band.HEAD, Spawn.RING, 90, 1, 9, 1, Role.SHIELD, Role.SUPPORT),
    G20("얼굴 곡예 게이브", 11, 3, Band.HEAD, Spawn.RING, 50, 4, 9, 1, Role.RANGED, Role.DISRUPT),
    G21("노크하는 게이브", 11, 3, Band.HEAD, Spawn.PLACED, 45, 2, 9, 2, Role.TRAP),
    G22("뒷걸음 게이브", 12, 3, Band.WAIST, Spawn.RING, 55, 3, 9, 1, Role.CHARGE),
    G23("코앞 게이브", 12, 3, Band.HEAD, Spawn.RING, 50, 1, 9, 1, Role.DISRUPT),
    G24("땅밑 얼굴 게이브", 13, 3, Band.GROUND, Spawn.RING, 50, 1, 9, 2, Role.TRAP, Role.DISRUPT),
    G25("셋 목말 게이브", 13, 3, Band.HEAD, Spawn.RING, 70, 3, 9, 1, Role.CHARGE),
    G26("실뭉치 게이브", 14, 1, Band.GROUND, Spawn.DERIVED, 6, 5, 0, 2, Role.SWARM),
    G27("겹얼굴 게이브", 14, 4, Band.HEAD, Spawn.RING, 195, 1, 24, 4, Role.ELITE, Role.CHARGE),
    G28("짓누르는 게이브", 15, 3, Band.SKY, Spawn.SKY, 70, 2, 9, 2, Role.CHARGE, Role.DISRUPT),
    G29("손잡고 도는 게이브", 15, 3, Band.WAIST, Spawn.RING, 20, 9, 18, 2, Role.DISRUPT),
    G30("얼굴 다리 지네 게이브", 16, 4, Band.GROUND, Spawn.RING, 300, 3, 12, 2, Role.CHARGE, Role.TRAP),
    G31("웅덩이 게이브", 16, 3, Band.GROUND, Spawn.RING, 70, 2, 9, 2, Role.DISRUPT, Role.SWARM),
    G32("웃음 옮는 게이브", 17, 3, Band.WAIST, Spawn.RING, 50, 1, 9, 3, Role.DISRUPT),
    G33("토끼귀 게이브", 17, 3, Band.HEAD, Spawn.RING, 90, 1, 9, 3, Role.CHARGE, Role.ELITE),
    G34("딱딱 씹는 게이브", 18, 4, Band.WAIST, Spawn.RING, 195, 1, 24, 4, Role.ELITE, Role.CHARGE),
    G35("엎드린 척 게이브", 18, 4, Band.GROUND, Spawn.PLACED, 150, 2, 12, 3, Role.TRAP),
    G36("실핏줄 잇는 게이브", 19, 3, Band.HEAD, Spawn.RING, 60, 1, 9, 2, Role.SUPPORT),
    G37("보자기 게이브", 19, 3, Band.HEAD, Spawn.RING, 60, 1, 9, 2, Role.DISRUPT),
    G38("업은 게이브", 20, 3, Band.WAIST, Spawn.DERIVED, 70, 2, 9, 3, Role.ELITE, Role.DISRUPT),
    G39("사슬 늘어뜨린 게이브", 20, 4, Band.SKY, Spawn.SKY, 300, 3, 12, 2, Role.RANGED, Role.DISRUPT),
    G40("짐승 탄 게이브", 21, 4, Band.GROUND, Spawn.RING, 15, 13, 12, 4, Role.SWARM, Role.CHARGE),
    G41("가시 쏘는 게이브", 21, 4, Band.WAIST, Spawn.RING, 300, 1, 12, 2, Role.RANGED, Role.TRAP),
    G42("알주머니 게이브", 22, 4, Band.HEAD, Spawn.RING, 210, 2, 24, 4, Role.SUPPORT, Role.ELITE),
    G43("탐조등 게이브", 22, 4, Band.SKY, Spawn.PLACED, 110, 3, 12, 4, Role.RANGED, Role.DISRUPT),
    G44("꾸러미 게이브", 23, 4, Band.GROUND, Spawn.PLACED, 195, 2, 24, 3, Role.TRAP, Role.ELITE),
    G45("줄지어 나는 게이브", 23, 4, Band.SKY, Spawn.SKY, 30, 8, 12, 2, Role.SWARM, Role.RANGED),
    G46("골라 먹는 게이브", 24, 4, Band.HEAD, Spawn.RING, 210, 1, 24, 4, Role.ELITE, Role.CHARGE),
    G47("문틀에 말린 게이브", 25, 4, Band.HEAD, Spawn.PLACED, 35, 4, 12, 3, Role.DISRUPT, Role.TRAP),
    G48("열매 단 나무 게이브", 25, 4, Band.GROUND, Spawn.PLACED, 150, 4, 12, 4, Role.TRAP, Role.SUPPORT),
    G49("축복 주는 게이브", 26, 5, Band.GROUND, Spawn.PLACED, 800, 3, 15, 3, Role.DISRUPT, Role.SUPPORT),
    G50("사람 탈 게이브", 26, 5, Band.GROUND, Spawn.DERIVED, 800, 1, 15, 3, Role.TRAP, Role.DISRUPT),
    G51("보고 배우는 게이브", 27, 4, Band.HEAD, Spawn.RING, 90, 1, 12, 4, Role.SUPPORT),
    G52("총 주운 게이브", 28, 4, Band.WAIST, Spawn.DERIVED, 195, 1, 24, 3, Role.RANGED, Role.ELITE),
    G53("점으로 짠 얼굴 떼", 29, 5, Band.SKY, Spawn.SKY, 5, 26, 15, 4, Role.SWARM),
    G54("올라타는 게이브", 30, 5, Band.GROUND, Spawn.RING, 1050, 1, 15, 4, Role.SUPPORT),
    G55("가짜 종소리 게이브", 31, 5, Band.HEAD, Spawn.PLACED, 800, 1, 15, 3, Role.DISRUPT, Role.SUPPORT),
    G56("깡충 줄 게이브", 1, 1, Band.WAIST, Spawn.RING, 7, 1, 1, 1, Role.SWARM, Role.CHARGE),
    G57("종종걸음 게이브", 1, 1, Band.GROUND, Spawn.RING, 5, 1, 1, 1, Role.SWARM),
    G58("비명 횡대 게이브", 1, 1, Band.WAIST, Spawn.RING, 6, 1, 1, 1, Role.SWARM, Role.CHARGE),
    G59("물동이 게이브", 2, 2, Band.WAIST, Spawn.RING, 34, 1, 6, 1, Role.CHARGE),
    G60("튀는 공 게이브", 2, 1, Band.WAIST, Spawn.RING, 8, 1, 1, 1, Role.SWARM, Role.CHARGE),
    G61("설 짐승 게이브", 3, 3, Band.HEAD, Spawn.RING, 150, 4, 24, 1, Role.CHARGE, Role.TANK),
    G62("부채 게이브", 3, 1, Band.SKY, Spawn.SKY, 8, 1, 1, 1, Role.SWARM),
    G63("집게다리 게이브", 3, 2, Band.GROUND, Spawn.RING, 60, 1, 6, 1, Role.CHARGE, Role.TANK),
    G64("등 뒤에 앉는 게이브", 3, 2, Band.GROUND, Spawn.SKY, 5, 1, 1, 1, Role.SWARM, Role.DISRUPT),
    G65("빨랫줄 게이브", 4, 2, Band.HEAD, Spawn.RING, 70, 3, 9, 1, Role.SHIELD, Role.CHARGE),
    G66("바구니 진 덩치 게이브", 4, 3, Band.GROUND, Spawn.RING, 150, 4, 30, 1, Role.RANGED, Role.SUPPORT, Role.OBJECTIVE),
    G67("허수아비 게이브", 4, 2, Band.HEAD, Spawn.RING, 40, 1, 6, 1, Role.RANGED, Role.DISRUPT),
    G68("오뚝이 게이브", 5, 2, Band.WAIST, Spawn.RING, 110, 1, 8, 1, Role.BONUS, Role.CHARGE),
    G69("털 뭉치 게이브", 5, 1, Band.GROUND, Spawn.RING, 10, 1, 1, 1, Role.SWARM),
    G70("풍선 게이브", 5, 1, Band.SKY, Spawn.SKY, 6, 1, 1, 1, Role.SWARM),
    G71("해 가리는 날개 게이브", 6, 4, Band.SKY, Spawn.SKY, 600, 5, 40, 1, Role.DISRUPT, Role.RANGED),
    G72("불꼬리 운석 게이브", 6, 2, Band.GROUND, Spawn.SKY, 12, 1, 1, 1, Role.RANGED, Role.SWARM, Role.OBJECTIVE),
    G73("왕관 쓴 떼 게이브", 6, 2, Band.WAIST, Spawn.RING, 5, 1, 1, 1, Role.SWARM, Role.SUPPORT),
    G74("칙칙 수레 게이브", 7, 2, Band.WAIST, Spawn.RING, 14, 1, 2, 1, Role.SWARM, Role.CHARGE, Role.OBJECTIVE),
    G75("배불뚝이 게이브", 7, 3, Band.HEAD, Spawn.RING, 90, 2, 9, 1, Role.TRAP, Role.TANK),
    G76("머리 돋는 게이브", 7, 4, Band.WAIST, Spawn.RING, 230, 1, 12, 1, Role.TANK, Role.SHIELD),
    G77("하늘 발 게이브", 8, 4, Band.SKY, Spawn.SKY, 900, 4, 45, 1, Role.CHARGE),
    G78("돈 냄새 사냥개 게이브", 8, 3, Band.WAIST, Spawn.RING, 45, 6, 6, 1, Role.BONUS, Role.CHARGE),
    G79("접시 세는 게이브", 9, 3, Band.HEAD, Spawn.RING, 80, 3, 12, 1, Role.RANGED),
    G80("춤추는 굴렁쇠 게이브", 9, 3, Band.WAIST, Spawn.RING, 14, 1, 2, 1, Role.DISRUPT, Role.SWARM),
    G81("눈싸움 게이브", 9, 4, Band.HEAD, Spawn.RING, 320, 2, 24, 1, Role.DISRUPT, Role.ELITE),
    G82("발자국 게이브", 11, 2, Band.GROUND, Spawn.RING, 12, 1, 2, 2, Role.SWARM, Role.TRAP),
    G83("장승 게이브", 11, 2, Band.HEAD, Spawn.RING, 45, 1, 3, 2, Role.SWARM, Role.CHARGE),
    G84("오르골 게이브", 11, 2, Band.GROUND, Spawn.RING, 16, 1, 2, 2, Role.CHARGE, Role.SWARM),
    G85("땅 금 입 게이브", 12, 3, Band.GROUND, Spawn.RING, 70, 2, 9, 2, Role.TRAP, Role.CHARGE, Role.OBJECTIVE),
    G86("소금쟁이 게이브", 12, 2, Band.GROUND, Spawn.RING, 7, 1, 1, 2, Role.SWARM, Role.CHARGE),
    G87("세 바람 게이브", 12, 3, Band.WAIST, Spawn.RING, 22, 1, 3, 2, Role.SWARM, Role.DISRUPT),
    G88("백눈 호저 게이브", 13, 3, Band.WAIST, Spawn.RING, 150, 1, 12, 2, Role.RANGED, Role.SHIELD),
    G89("조개 게이브", 13, 3, Band.GROUND, Spawn.RING, 140, 1, 12, 2, Role.SHIELD, Role.CHARGE),
    G90("등불 나방 게이브", 13, 2, Band.HEAD, Spawn.RING, 6, 1, 1, 2, Role.SWARM, Role.OBJECTIVE),
    G91("빗줄기 커튼 게이브", 14, 4, Band.SKY, Spawn.SKY, 1100, 8, 40, 2, Role.RANGED, Role.DISRUPT),
    G92("팔 뻗는 게이브", 14, 4, Band.HEAD, Spawn.RING, 260, 3, 24, 2, Role.RANGED),
    G93("피리 부는 게이브", 14, 3, Band.HEAD, Spawn.RING, 70, 7, 12, 2, Role.DISRUPT, Role.SUPPORT),
    G94("솥 다리 게이브", 15, 3, Band.HEAD, Spawn.RING, 140, 5, 36, 2, Role.RANGED, Role.OBJECTIVE),
    G95("두 번째 그림자 게이브", 15, 3, Band.GROUND, Spawn.RING, 8, 1, 1, 2, Role.SWARM, Role.DISRUPT),
    G96("쇠 먹는 게이브", 15, 3, Band.WAIST, Spawn.RING, 140, 1, 18, 2, Role.TANK, Role.SHIELD),
    G97("올려다보면 삼키는 구름 게이브", 16, 5, Band.SKY, Spawn.SKY, 1300, 5, 45, 2, Role.DISRUPT, Role.RANGED),
    G98("물러나는 초롱 게이브", 16, 3, Band.GROUND, Spawn.PLACED, 160, 2, 12, 2, Role.BONUS, Role.TRAP),
    G99("붓 게이브", 16, 3, Band.HEAD, Spawn.SKY, 160, 2, 15, 2, Role.TRAP),
    G100("가마 게이브", 17, 4, Band.HEAD, Spawn.RING, 520, 6, 30, 2, Role.SHIELD, Role.DISRUPT),
    G101("나만 보이는 게이브", 17, 3, Band.WAIST, Spawn.RING, 30, 1, 3, 2, Role.DISRUPT, Role.CHARGE),
    G102("던져 올리는 손 게이브", 17, 4, Band.GROUND, Spawn.RING, 1000, 3, 40, 2, Role.DISRUPT, Role.CHARGE),
    G103("국자 게이브", 18, 4, Band.GROUND, Spawn.PLACED, 1000, 4, 45, 2, Role.OBJECTIVE, Role.RANGED),
    G104("딱지 게이브", 18, 2, Band.HEAD, Spawn.RING, 9, 1, 2, 2, Role.SWARM, Role.DISRUPT),
    G105("병풍 게이브", 18, 4, Band.HEAD, Spawn.RING, 420, 2, 24, 2, Role.TRAP, Role.SHIELD),
    G106("숨 쉬는 땅 게이브", 19, 4, Band.GROUND, Spawn.RING, 1400, 4, 45, 2, Role.TRAP, Role.DISRUPT),
    G107("솟구쳐 덮는 게이브", 19, 5, Band.GROUND, Spawn.RING, 1500, 6, 60, 2, Role.CHARGE, Role.ELITE),
    G108("죽은 척 게이브", 21, 2, Band.GROUND, Spawn.PLACED, 8, 1, 2, 3, Role.TRAP, Role.SWARM),
    G109("몰리면 변하는 떼 게이브", 21, 2, Band.GROUND, Spawn.RING, 4, 1, 1, 3, Role.SWARM, Role.CHARGE),
    G110("긁는 게이브", 21, 1, Band.GROUND, Spawn.RING, 8, 1, 1, 3, Role.SWARM, Role.CHARGE),
    G111("제 머리 든 게이브", 22, 4, Band.WAIST, Spawn.RING, 220, 2, 24, 3, Role.ELITE, Role.CHARGE),
    G112("허물 벗는 게이브", 22, 3, Band.HEAD, Spawn.RING, 280, 3, 36, 3, Role.CHARGE, Role.TANK),
    G113("성난 새장 게이브", 22, 4, Band.WAIST, Spawn.RING, 300, 2, 24, 3, Role.ELITE, Role.SHIELD),
    G114("개미 무덤 게이브", 23, 4, Band.GROUND, Spawn.PLACED, 500, 1, 20, 3, Role.SUPPORT, Role.SWARM, Role.OBJECTIVE),
    G115("진드기 덮인 거인 게이브", 23, 4, Band.GROUND, Spawn.RING, 600, 4, 50, 3, Role.TANK, Role.SUPPORT),
    G116("수레바퀴 게이브", 23, 3, Band.HEAD, Spawn.RING, 280, 2, 36, 3, Role.CHARGE, Role.DISRUPT, Role.OBJECTIVE),
    G117("등 맞댄 게이브", 24, 4, Band.HEAD, Spawn.RING, 300, 3, 30, 3, Role.ELITE, Role.CHARGE),
    G118("빚 놓는 게이브", 24, 3, Band.WAIST, Spawn.RING, 280, 1, 20, 3, Role.BONUS, Role.ELITE),
    G119("이름 연 게이브", 24, 4, Band.SKY, Spawn.SKY, 650, 3, 36, 3, Role.DISRUPT, Role.ELITE),
    G120("사체 버섯 게이브", 25, 3, Band.GROUND, Spawn.RING, 10, 1, 1, 3, Role.SWARM, Role.RANGED),
    G121("채 가는 둥지 게이브", 25, 4, Band.SKY, Spawn.SKY, 800, 4, 45, 3, Role.DISRUPT, Role.RANGED),
    G122("호리병 게이브", 25, 4, Band.HEAD, Spawn.RING, 260, 1, 30, 3, Role.TRAP),
    G123("벌레 구름 게이브", 26, 2, Band.SKY, Spawn.SKY, 90, 4, 12, 3, Role.SWARM),
    G124("도깨비불 게이브", 26, 2, Band.HEAD, Spawn.RING, 12, 1, 2, 3, Role.DISRUPT, Role.SWARM),
    G125("갈비 우리 거인 게이브", 26, 5, Band.HEAD, Spawn.RING, 1600, 6, 70, 3, Role.ELITE, Role.TANK),
    G126("줄 인형 부리는 게이브", 27, 5, Band.SKY, Spawn.SKY, 1600, 8, 55, 3, Role.SUPPORT, Role.DISRUPT),
    G127("머리 탑 게이브", 27, 4, Band.HEAD, Spawn.PLACED, 600, 2, 36, 3, Role.SUPPORT, Role.OBJECTIVE),
    G128("솟는 목 게이브", 27, 5, Band.HEAD, Spawn.PLACED, 1700, 6, 55, 3, Role.RANGED, Role.ELITE),
    G129("가로지르는 장대 거인 게이브", 28, 5, Band.GROUND, Spawn.RING, 1700, 6, 55, 3, Role.TANK, Role.CHARGE, Role.OBJECTIVE),
    G130("따라 하는 거인 게이브", 28, 5, Band.GROUND, Spawn.RING, 2000, 5, 60, 3, Role.DISRUPT, Role.ELITE),
    G131("묻힌 얼굴 게이브", 29, 5, Band.GROUND, Spawn.PLACED, 1500, 3, 60, 3, Role.DISRUPT),
    G132("신호탄 게이브", 31, 3, Band.WAIST, Spawn.RING, 16, 1, 2, 4, Role.SWARM, Role.SUPPORT),
    G133("물마루 게이브", 31, 3, Band.GROUND, Spawn.RING, 9, 1, 1, 4, Role.SWARM, Role.CHARGE),
    G134("북 게이브", 31, 3, Band.WAIST, Spawn.RING, 140, 1, 15, 4, Role.DISRUPT, Role.SUPPORT),
    G135("일곱 게이브", 31, 3, Band.HEAD, Spawn.RING, 30, 1, 3, 4, Role.SWARM, Role.ELITE),
    G136("총 엉키는 덩치 게이브", 31, 5, Band.GROUND, Spawn.RING, 1500, 4, 45, 4, Role.DISRUPT, Role.TANK),
    G137("하늘 원반 게이브", 31, 5, Band.SKY, Spawn.SKY, 2200, 7, 65, 4, Role.OBJECTIVE, Role.RANGED),
    G138("다친 냄새 물구나무 게이브", 32, 3, Band.GROUND, Spawn.RING, 12, 1, 2, 4, Role.SWARM, Role.CHARGE),
    G139("떠넘기는 게이브", 32, 4, Band.HEAD, Spawn.RING, 280, 1, 27, 4, Role.DISRUPT),
    G140("항아리 게이브", 32, 4, Band.HEAD, Spawn.RING, 420, 2, 45, 4, Role.SUPPORT, Role.ELITE),
    G141("밀려오는 얼굴 벽 게이브", 32, 5, Band.GROUND, Spawn.RING, 2400, 6, 65, 4, Role.SHIELD, Role.TANK),
    G142("담 치는 지네 게이브", 32, 5, Band.WAIST, Spawn.RING, 2400, 8, 80, 4, Role.SHIELD, Role.TANK),
    G143("갈아 먹는 톱니 게이브", 32, 5, Band.GROUND, Spawn.RING, 900, 3, 60, 4, Role.TANK, Role.BONUS),
    G144("소리 먹는 게이브", 33, 4, Band.HEAD, Spawn.RING, 420, 2, 30, 4, Role.RANGED, Role.DISRUPT),
    G145("못 박힌 게이브", 33, 4, Band.WAIST, Spawn.RING, 320, 2, 24, 4, Role.ELITE, Role.CHARGE),
    G146("고리 문 게이브", 33, 4, Band.GROUND, Spawn.PLACED, 420, 3, 45, 4, Role.OBJECTIVE, Role.SUPPORT),
    G147("저울 게이브", 33, 5, Band.SKY, Spawn.SKY, 2000, 6, 70, 4, Role.DISRUPT),
    G148("내려앉는 종 게이브", 33, 5, Band.SKY, Spawn.SKY, 2200, 4, 80, 4, Role.CHARGE, Role.TANK),
    G149("안 보이는 거인 게이브", 33, 5, Band.GROUND, Spawn.RING, 2000, 3, 65, 4, Role.ELITE, Role.CHARGE),
    G150("모래시계 게이브", 34, 5, Band.WAIST, Spawn.DERIVED, 1050, 2, 0, 4, Role.ELITE, Role.OBJECTIVE),
    G151("꿰뚫는 작살 게이브", 34, 4, Band.WAIST, Spawn.RING, 100, 1, 24, 4, Role.ELITE, Role.CHARGE),
    G152("하늘 시계추 게이브", 34, 5, Band.SKY, Spawn.SKY, 2200, 5, 70, 4, Role.CHARGE, Role.DISRUPT),
    G153("하늘 찢는 게이브", 34, 5, Band.SKY, Spawn.SKY, 2000, 7, 70, 4, Role.SUPPORT, Role.RANGED),
    G154("여덟 머리 게이브", 34, 5, Band.GROUND, Spawn.PLACED, 2400, 9, 90, 4, Role.ELITE, Role.TANK),
    G155("말뚝에 묶인 거인 게이브", 34, 5, Band.GROUND, Spawn.PLACED, 2400, 6, 70, 4, Role.OBJECTIVE, Role.BONUS),
    G156("줄넘기 게이브", 2, 2, Band.GROUND, Spawn.RING, 14, 1, 3, 1, Role.SWARM, Role.DISRUPT),
    G157("새끼 딸린 게이브", 3, 2, Band.GROUND, Spawn.RING, 10, 1, 1, 1, Role.SWARM, Role.BONUS),
    G158("문턱 게이브", 4, 3, Band.GROUND, Spawn.RING, 8, 1, 1, 1, Role.SWARM, Role.TRAP),
    G159("민들레 홀씨 게이브", 5, 2, Band.HEAD, Spawn.RING, 4, 1, 4, 1, Role.SWARM, Role.RANGED),
    G160("엉뚱 달리기 게이브", 7, 2, Band.GROUND, Spawn.RING, 12, 1, 1, 1, Role.SWARM, Role.DISRUPT),
    G161("순찰 병정 게이브", 9, 3, Band.GROUND, Spawn.RING, 150, 4, 30, 1, Role.RANGED, Role.TANK),
    G162("덮쳐 앉는 게이브", 11, 2, Band.GROUND, Spawn.RING, 9, 1, 1, 2, Role.SWARM, Role.CHARGE),
    G163("프로펠러 모자 게이브", 12, 2, Band.HEAD, Spawn.RING, 6, 1, 1, 2, Role.SWARM, Role.DISRUPT),
    G164("몰이 게이브", 13, 3, Band.GROUND, Spawn.RING, 8, 1, 1, 2, Role.SWARM, Role.TRAP),
    G165("무른 땅 게이브", 14, 3, Band.GROUND, Spawn.PLACED, 8, 1, 10, 2, Role.TRAP, Role.SWARM),
    G166("까치발 게이브", 15, 3, Band.WAIST, Spawn.RING, 90, 2, 15, 2, Role.SHIELD, Role.SUPPORT),
    G167("뽑기 집게 게이브", 16, 3, Band.SKY, Spawn.SKY, 420, 3, 30, 2, Role.DISRUPT, Role.SUPPORT, Role.OBJECTIVE),
    G168("꿰매는 게이브", 17, 3, Band.HEAD, Spawn.SKY, 18, 1, 2, 2, Role.SWARM, Role.DISRUPT),
    G169("물그림자 게이브", 18, 4, Band.SKY, Spawn.SKY, 700, 3, 45, 2, Role.DISRUPT, Role.ELITE),
    G170("똬리 게이브", 19, 4, Band.GROUND, Spawn.RING, 1400, 4, 60, 2, Role.TRAP, Role.DISRUPT),
    G171("빈틈 노리는 게이브", 21, 3, Band.WAIST, Spawn.RING, 16, 1, 2, 3, Role.SWARM, Role.CHARGE),
    G172("잿가루 게이브", 21, 3, Band.GROUND, Spawn.RING, 12, 1, 4, 3, Role.SWARM, Role.TANK),
    G173("외투 게이브", 22, 4, Band.WAIST, Spawn.RING, 150, 1, 18, 3, Role.TRAP, Role.CHARGE),
    G174("손자국 게이브", 22, 4, Band.GROUND, Spawn.PLACED, 10, 1, 1, 3, Role.SWARM, Role.TRAP),
    G175("태워 주는 게이브", 23, 3, Band.WAIST, Spawn.RING, 140, 2, 12, 3, Role.TRAP, Role.DISRUPT),
    G176("쟁기 게이브", 23, 3, Band.GROUND, Spawn.RING, 160, 2, 15, 3, Role.DISRUPT, Role.CHARGE),
    G177("우는 장막 게이브", 24, 4, Band.HEAD, Spawn.RING, 180, 2, 20, 3, Role.DISRUPT, Role.CHARGE),
    G178("머리 따로 게이브", 24, 4, Band.HEAD, Spawn.RING, 240, 2, 30, 3, Role.CHARGE, Role.ELITE),
    G179("다시 오는 게이브", 25, 4, Band.WAIST, Spawn.RING, 300, 2, 20, 3, Role.ELITE, Role.TANK),
    G180("송곳 게이브", 26, 4, Band.GROUND, Spawn.RING, 520, 3, 36, 3, Role.TRAP, Role.DISRUPT),
    G181("잿빛 돌 게이브", 26, 4, Band.GROUND, Spawn.PLACED, 420, 2, 30, 3, Role.SUPPORT, Role.RANGED),
    G182("요새 게이브", 27, 4, Band.GROUND, Spawn.RING, 900, 3, 40, 3, Role.SHIELD, Role.RANGED, Role.BONUS),
    G183("가짜 트럭 게이브", 28, 4, Band.GROUND, Spawn.RING, 900, 3, 40, 3, Role.OBJECTIVE, Role.TRAP),
    G184("펼친 책 게이브", 29, 5, Band.SKY, Spawn.SKY, 1600, 4, 100, 3, Role.SUPPORT, Role.DISRUPT),
    G185("말뚝 든 게이브", 31, 3, Band.WAIST, Spawn.RING, 14, 1, 1, 4, Role.SWARM, Role.CHARGE),
    G186("양탄자 게이브", 31, 3, Band.GROUND, Spawn.RING, 20, 1, 1, 4, Role.SWARM, Role.DISRUPT),
    G187("테두리 갉는 게이브", 31, 4, Band.GROUND, Spawn.PLACED, 60, 1, 2, 4, Role.SWARM, Role.OBJECTIVE),
    G188("사이렌 게이브", 31, 4, Band.HEAD, Spawn.RING, 520, 2, 45, 4, Role.SUPPORT, Role.DISRUPT),
    G189("꼭 맞는 구멍 게이브", 32, 4, Band.GROUND, Spawn.PLACED, 70, 1, 6, 4, Role.TRAP, Role.DISRUPT),
    G190("초침 게이브", 32, 4, Band.HEAD, Spawn.SKY, 180, 1, 30, 4, Role.DISRUPT, Role.ELITE),
    G191("포도송이 게이브", 32, 4, Band.WAIST, Spawn.RING, 420, 2, 45, 4, Role.TANK, Role.OBJECTIVE),
    G192("초승달 조각 게이브", 33, 3, Band.HEAD, Spawn.SKY, 6, 1, 1, 4, Role.SWARM, Role.CHARGE),
    G193("빛 쫓는 게이브", 33, 5, Band.SKY, Spawn.SKY, 1100, 3, 50, 4, Role.CHARGE, Role.OBJECTIVE),
    G194("바닥 밑 게이브", 33, 4, Band.GROUND, Spawn.PLACED, 420, 3, 45, 4, Role.CHARGE, Role.OBJECTIVE),
    G195("어둠 장막 게이브", 33, 4, Band.HEAD, Spawn.RING, 600, 3, 60, 4, Role.SHIELD, Role.SUPPORT),
    G196("하늘 아치 게이브", 34, 5, Band.SKY, Spawn.SKY, 2400, 8, 90, 4, Role.SUPPORT, Role.RANGED),
    G197("수 채우는 안개 게이브", 34, 5, Band.GROUND, Spawn.RING, 1500, 5, 90, 4, Role.ELITE, Role.DISRUPT),
    G198("나란히 걷는 거인 게이브", 34, 5, Band.HEAD, Spawn.RING, 1500, 2, 60, 4, Role.TANK, Role.SHIELD),
    G199("눈 찾는 거인 게이브", 34, 5, Band.HEAD, Spawn.RING, 3600, 5, 120, 4, Role.ELITE, Role.OBJECTIVE, Role.BONUS),
    G200("하늘 아가리 게이브", 34, 5, Band.SKY, Spawn.SKY, 4000, 6, 150, 4, Role.OBJECTIVE, Role.DISRUPT),
    G201("안아줘요 게이브", 23, 4, Band.HEAD, Spawn.RING, 260, 2, 24, 3, Role.ELITE, Role.DISRUPT),
    B1("산더미 게이브", 10, 5, Band.GROUND, Spawn.DERIVED, 18500, 6, 0, 1, Role.BOSS),
    B2("하늘 덮는 게이브", 20, 5, Band.SKY, Spawn.DERIVED, 20000, 22, 0, 2, Role.BOSS),
    B3("에워싸는 게이브", 33, 5, Band.GROUND, Spawn.DERIVED, 32000, 36, 0, 4, Role.BOSS),
    B4("지평선 게이브", 40, 5, Band.SKY, Spawn.DERIVED, 29000, 20, 0, 3, Role.BOSS),
    B5("모두의 게이브", 50, 5, Band.GROUND, Spawn.DERIVED, 56000, 30, 0, 4, Role.BOSS);

    /** 나오는 방식: RING 가장자리 고리에서 몰려옴, SKY 하늘 가장자리, PLACED 경기장 안에 미리 깔림, DERIVED 다른 일의 결과로만 생김. */
    public enum Spawn { RING, SKY, PLACED, DERIVED }

    /** 한국어 이름(HUD 글귀 키 name_g01 로도 그려짐). */
    public final String ko;
    /** 처음 나올 수 있는 웨이브. */
    public final int tier;
    /** 위협 1..5. */
    public final int threat;
    /** 높이 띠, 같은 띠 = 같은 계열. */
    public final Band band;
    public final Spawn spawn;
    /** 웨이브 1 기준 체력(감독이 웨이브 배율을 곱함). */
    public final double hp;
    /** 화면 디스플레이 점수 어림값(대기열 예산). */
    public final int cost;
    /** 잡으면 주는 원(떼는 무리 합계, 0 이면 특별 보상이나 없음). */
    public final int reward;
    /** 막(1 마른 길, 2 젖은 벌, 3 재의 땅, 4 발사장): 그 막의 라운드에 나온다(docs/모듈/원정.md 2절). */
    public final int act;
    public final Set<Role> roles;

    Species(String ko, int tier, int threat, Band band, Spawn spawn, double hp, int cost, int reward, int act, Role... roles) {
        this.ko = ko;
        this.tier = tier;
        this.threat = threat;
        this.band = band;
        this.spawn = spawn;
        this.hp = hp;
        this.cost = cost;
        this.reward = reward;
        this.act = act;
        EnumSet<Role> set = EnumSet.noneOf(Role.class);
        for (Role r : roles) set.add(r);
        this.roles = set;
    }

    public boolean is(Role r) {
        return roles.contains(r);
    }

    /** 팩, 소리, 글귀 키에 쓰는 소문자 번호(g01). */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    private Constructor<? extends Gabe> ctor;
    private boolean looked;

    /** 이 종 클래스가 있으면 true(아직 안 만든 종은 감독이 뽑지 않음). */
    public boolean implemented() {
        return ctor() != null;
    }

    @SuppressWarnings("unchecked")
    private Constructor<? extends Gabe> ctor() {
        if (!looked) {
            looked = true;
            try {
                Class<?> c = Class.forName("kr.gave.mob.sp." + name());
                ctor = (Constructor<? extends Gabe>) c.getConstructor(Game.class, Species.class);
            } catch (ReflectiveOperationException | ClassCastException e) {
                ctor = null;
            }
        }
        return ctor;
    }

    /** 새 개체(아직 세상에 안 놓음). 클래스가 없으면 null. */
    public Gabe create(Game game) {
        Constructor<? extends Gabe> c = ctor();
        if (c == null) return null;
        try {
            return c.newInstance(game, this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name() + " 생성 실패", e);
        }
    }

    public static Species of(String s) {
        try {
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            for (Species sp : values()) if (sp.ko.equals(s)) return sp;
            return null;
        }
    }
}
