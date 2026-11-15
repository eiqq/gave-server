# 게이브 API 모음(종 작성자용, 2026-11-11 통합 기준)

종 하나를 만들 때 부르는 것을 한곳에 모았다. 지금 코드(`plugin/src/main/java/kr/gave/**`, `tools/gabe/**`)를 `javap -public` 과 소스로 대조해 적었다. 더 자세한 뜻은 각 클래스 javadoc, 팩 쪽 키트는 `docs/모듈/종작성법.md`. 본보기 종은 G01(디스플레이 하나짜리 돌격)과 G02(떼)이고 맨 끝 13절에 두 종을 이 API 로 풀어 두었다.

규칙: 공개 이름은 계약이다. 쓰기만 하고 이름, 뜻을 바꾸지 않는다. 남의 파일에 필요한 것은 작업 보고의 '요청'으로.

## 0. 한 종 = 파일 둘, 이름으로만 이어짐

| 이음 | Java(`kr.gave.mob.sp.Gxx`) | 팩 스크립트(`tools/gabe/sp/gxx.py`) |
|---|---|---|
| 모델 | `Body.of(this, "gabe/gxx")`, 부품 `body.attach("gabe/gxx_arm")` | `k.model()`(= `gabe/gxx`), `k.model("gxx_arm")` |
| 상태 | `state("charge")`, 떼 마리 `m.state("a")` | `m.state("charge")` |
| 부위 | `body.look.on.add("cut_l")` | `with s.when("on:cut_l"):` |
| 소리 | `sound("flap", Sound.ENTITY_PHANTOM_FLAP, 20)` → 사건 `gave:gabe.gxx.flap` | `k.sound("flap", "퍼덕", ..., attenuation=20)` |
| 원인 글귀 | `hit(p, 3, "cause_gxx")` | `def phrases(): return {"cause_gxx": "..."}` |
| 덮개 | `game.players.cover(p, "gxx_cloth", 틱)` | `k.cover("gxx_cloth", img)` |
| 아이콘 | (HUD 가 종 키로 찾음) | `k.icon(...)` 또는 저절로 |

이름은 소문자, 숫자, `_` 만. 기본 상태는 `""`(떼처럼 `""` 를 안 만들면 `m.default`). 가운뎃점(U+00B7) 금지.

## 1. 순서(명령은 모두 `gave/` 에서)

```
python -X utf8 tools/make_gabe.py --only gxx --preview          # 굽기, build/gabe/preview/gxx.png 를 Read 로 본다
python -X utf8 tools/make_hud.py && python -X utf8 tools/compile_wip.py --name hud --promote   # 글귀, 아이콘, 덮개를 바꿨을 때만
python -X utf8 tools/compile_wip.py --name gxx                  # Java 는 build/wip/gxx/plugin/src/main/java/kr/gave/mob/sp/Gxx.java 에 먼저
python -X utf8 tools/ingame.py --name gxx --scenario tools/scenarios/<대본>.json > build/gxx/ingame.out 2>&1   # 백그라운드로, 파이프 금지
python -X utf8 tools/compile_wip.py --name gxx --promote        # 통과한 뒤에만 진짜 자리로
```

- 종 클래스가 생기면 `Species.Gxx.implemented()` 가 참이 되고 감독이 그 종을 묶음에 넣기 시작한다(공용 등록 파일 없음, 리플렉션).
- 체력, 디스플레이 점수, 보상, 높이 띠, 나오는 방식은 `tools/gen_species.py` 의 TUNE 표가 정한다(`Species` 는 생성 파일, 손으로 고치지 않음).

## 2. Gabe(종 클래스의 바탕, `kr.gave.mob.Gabe`)

```java
public final class Gxx extends Gabe {
    public Gxx(Game game, Species sp) { super(game, sp); radius = 0.42; hover = 1.0; baseSpeed = 0.15; }
    @Override protected void build() { body = Body.of(this, "gabe/gxx", 폭, 높이); }
    @Override protected void spawned() { ... }   // 놓인 직후 한 번
    @Override public void tick() { ... }         // vel, yaw/pitch, 상태만 정한다
}
```

### 2.1 생성자에서 정하는 필드
| 필드 | 기본 | 뜻 |
|---|---|---|
| `radius` | 0.45 | 맞는 구 반지름(탄 판정 +0.25), 밀어내기, 물어뜯기 |
| `hover` | `sp.band.hover`(GROUND 0.1, WAIST 0.8, HEAD 2.0, SKY 10) | 땅 몹의 바닥에서 몸 중심 높이. 뛰려면 이 값을 바꾼다 |
| `baseSpeed` | 0.18 | 블록/틱. 실제 빠르기는 `speed()` |
| `flying` | SKY 띠면 true | 벽, 바닥 무시, vel 을 그대로 더함(바닥 0.3 위로만 막힘) |
| `ghost` | false | 벽 무시(땅밑, 웅덩이) |
| `noPush` | false | 다른 몹, 사람 몸과 겹쳐도 됨 |
| `noMutate`, `noRules`, `noBite` | 떼 역할, PLACED 종은 noMutate | 판 규칙 빼기(변함 / 모두 / 물어뜯기만) |
| `leaveCorpse` | true | false 면 죽을 때 몸을 바로 지움(사체 없음: 터지는 종, 쪼개지는 종) |
| `ragdoll` | true | 사체가 래그돌(맞은 쪽으로 튕겨 구르고 막대기가 늘어짐, 5.1절). false 면 예전처럼 제자리에서 납작해짐 |
| `ragKick` | 1.0 | 래그돌 충격 배율(무거운 종은 0.5 처럼, 0 이면 어떤 무기에도 제자리에서 쓰러짐) |
| `ragRods` | -1 | 흐물거리는 막대기 수 상한(-1 = 모델이 가진 만큼, 0 = 막대기가 몸에 굳은 채 통째로 구름) |
| `disguised`, `untargetable` | false | 흉내 중(겨눈 사격에만 정체), 지금 안 맞음(탄이 지나감) |
| `reward`, `noReward` | `sp.reward`, false | 보상 원. 감독의 웨이브 끝 정리는 noReward 로 죽인다 |

생성자에서 `state("x")` 를 불러도 된다(Body 가 이어받음). `hp`, `maxHp` 는 감독이 놓은 뒤 `hpMul(sp)` 를 곱해 다시 넣는다(아래 9절).

### 2.2 덮어쓰는 곳
| 메서드 | 기본 | 언제 |
|---|---|---|
| `build()` | `Body.of(this, "gabe/"+sp.key())` | 몸 만들기. 던지면 엔진이 몸을 지우고 spawn 이 null |
| `spawned()` | 없음 | 놓인 직후 한 번(대기실 우리 개체일 때 규칙은 9.4) |
| `tick()` | 추상 | 매 틱. 물어뜯기(`biting()`), 끌림(`lured()`), 몹정지 중에는 안 불림 |
| `damage(Damage d)` | hp 깎고 `game.mobs.onHurt(this, d)`, 0 이하면 `die(d)` | 막거나 부위 판정. 실제 깎은 양이 다르면 `game.mobs.onHurt(this, d, effective)` |
| `die(Damage d)` | `dead = true` 뒤 `game.mobs.onDeath` | 덮어쓰면 `super.die(d)` |
| `spheres()` | `pos` 하나 | 여러 구(세계 좌표 `{x, y, z, r}` 목록) |
| `head()` | `pos` | 물어뜯기가 보는 머리 자리 |
| `helped(Player p)` | false | 사람이 이 개체를 보고 좌클릭(달래기, 떼기). 썼으면 true |
| `reveal()` | `disguised = false` | 조명탄, 정화가 정체를 드러냄 |
| `lure(Vector at, int ticks)` | 엔진이 그쪽으로 끌고 감 | 딸랑이 |
| `listenSound()` | null | 웅크려 듣기: 사건 이름(`gave:...` 또는 `minecraft:...`) |
| `bitten(Gabe other)` | 없음 | 물어뜯기 시작 반응 |

종 코드(tick, damage, spheres, head, bitten, build, spawned, reveal, lure, die, helped, listenSound)가 던지면 엔진은 **그 개체만 치우고** 붙잡힌 사람, 갇힌 사람을 풀어 준 뒤 판을 계속한다(기록은 '종:곳'마다 한 번). 다른 모듈은 `game.mobs.helped(g, p)`, `game.mobs.listenSound(g)` 로 부른다.

### 2.3 도우미(부르기만)
- 움직임: `follow(speed)`(흐름장, 하늘 종은 직선 + 높이 유지), `seek(to, speed)`, `hold()`, `dash(dir, speed)`, `circle(center, r, speed, ±1)`, `wallAhead(dist)`, `floor()`.
- 방향: `face(at)`, `turnTo(at, maxDeg)`(다 돌았으면 true). `yaw` 0 = +Z, 90 = -X(마크 약속), pitch + 아래.
- 표적: `nearest(r)`, `acquire(r)`(블록에 안 가린 가장 가까운 서 있는 사람), `mark(p | null)`(실눈 뜸 + `target`), `standing(p)`, `sees(p)`, `dist(p)`, `toward(p)`(수평 단위), `chest(p)`(발 + 0.9), `touches(p, reach)`.
- 공격: `hit(p, 기본피해, "cause_gxx")`(웨이브 배율 `dmgMul`, 방어는 Players 가 곱함), `knock(p, dir, 세기, 위)`(여럿이 밀어도 수평 1.5배까지), `grab(p, clicks)`(boolean).
- 소리: `sound(name, 대체음, range)`, `sound(name, 대체음, 대체높이, range)` → `gave:gabe.<key>.<name>`. 팩에 없으면 바닐라 대체음. 소리 없는 웨이브(`director.silent()`)엔 안 남.
- 그 밖: `speed()`(baseSpeed x 감독 speedMul x `buff()` x 판 규칙), `speedBuff`(지원 종이 매 틱 걸면 다음 틱까지), `state(s)`, `inState()`, `mut()`(0..3), `gaze()`(돌아보기 대상), `hpFrac()`, `every(n)`(개체마다 어긋난 n 틱), `now()`, `flash()`, `loc()`, `child(sp, at)`(보상 0, 예산 차면 null), `kin(r)`(같은 띠 다른 게이브).

### 2.4 판 규칙(엔진이 모든 종에 건다)
- 오래 살면 변함: age 400 → `look.mut = 1`(입 찢어짐), 800 → 2(막대기 둘 더, 빠르기 x1.25), 1200 → 갈라짐(체력 절반, noReward 조각 하나, 예산 안에서만). 시간은 `director.mutTimeMul()` 배.
- 물어뜯기: 서로 다른 종의 `head()` 가 닿으면 둘 다 20틱 멈추고 체력 5%씩(같은 쌍 60틱 재사용). 공용 소리 `gave:gabe.bite`.
- 돌아보기: 서 있는 사람 중 체력 25% 아래가 생기면 모두 40틱 그 사람을 봄(같은 사람 200틱 재사용, 떼 마리도 저절로).
- 예산: `budgetUsed()` = 실제 디스플레이 수(닻 + 부품 + 마리 + 덩어리), 몹 200(`BUDGET`), 종 child 는 이 안, 감독 아닌 소환은 `HARD_CAP` 600.

## 3. 몸과 겉모습(`Body`, `Body.Part`, `LookState`, `Look`)

- `Body.of(owner, model)`, `Body.of(owner, model, 폭, 높이)`(컬링 상자, 몸 중심 기준), `Body.carrier(owner)`(빈 닻: 떼 종, 부품만으로 그리는 종).
- 몸 필드: `look`(LookState), `roll`(도), `stretch`, `offset`(Vector3f, 세계 축 블록), `scale`, `turnTicks`(3), `width`, `height`.
- 몸 메서드: `squash(x, y, z, 틱)`, `scale(s, 틱)`, `pose(틱)`(15도 문턱 무시하고 다음 move 에 바로), `model(m)`, `size(w, h)`, `viewRange(v)`, `attach(model)` → Part, `hide(p, true)`(사람마다 숨김), `hidden(p)`, `count()`, `entities()`, `dirty()`(선택: 엔진이 매 틱 비교).
- Part: `local`(Matrix4f, 몸 기준, 모델 앞 = 몸 앞 +Z), `follow`(false 면 세계 축), `look`, `pose(Matrix4f, 틱)`, `state(s)`, `model(m)`, `remove()`. 엔진이 Ry(PI) 와 모델 배율 `Look.scale(model)` 을 붙인다.
- `Body.item(model, state, look, worldTime)`: 디스플레이에 실을 아이템(PAPER + `gave:<model>` + strings[0] + colors). 2026-11-10 부터 상태를 아는 `Look.colors(model, state, look, wt)` 를 쓴다(상태마다 주기가 달라도 맞음).

| `body.look`(LookState) | 화면 |
|---|---|
| `open = true` | 실눈 뜸(`mark` 가 켬) |
| `mut` | 엔진이 올림(1 입 찢어짐, 2 막대기 둘 더) |
| `on.add("부위")` | `on:부위` 요소 보임, `!on:부위` 숨김 |
| `palette` | `Look.PAL_WHITE_EYES 1`, `PAL_PALE 2`, `PAL_DEEP_BLUSH 4`, `PAL_BLANK 8`, `PAL_GOLD 16`(얼굴 텍스처만) |
| `freeze`, `rate`, `phase` | 펄럭임 멈춤, 빠르기 배율, 개체 위상 |
| `hurtUntil` | 흰 번쩍임(엔진 `flash`, 끝나면 엔진이 `Look.settle` 로 지우고 다시 실음) |
| `pose` | 멈췄을 때(freeze) 보이는 자세 번호. 엔진이 래그돌 사체의 누운 자세를 고를 때만 쓴다(종은 건드리지 않음) |

고치면 엔진이 매 틱 비교해 바뀔 때만 다시 싣는다. 소리 없는 웨이브엔 엔진이 freeze 사본으로 싣는다.

## 4. 떼(`Swarm`, 본보기 G02)

- 만들기: `build()` 에서 `swarm = game.mobs.swarm(this, "gabe/gxx", n);` 뒤 마리마다 `m.home.copy(자리)`, `m.state("a")`, 마지막에 `swarm.formation(yaw, 0)`. 엔진이 닻(빈 몸)과 마리, 덩어리를 만든다.
- 필드: `members`, `lod`, `lodState`("lod"), `lodDist`(15), `memberScale`, `lodScale`, `memberRadius`(0.18), `heading`, `turnTicks`.
- 메서드: `add(home, state)`, `formation(yaw, 틱)`, `heading(yaw)`(15도 넘게 꺾으면 모든 얼굴이 같은 틱에 휙, true), `flinch()`(무리 움찔), `alive()`, `nearest(at | null)`, `within(at, r)`, `anyWithin(at, r)`, `centroid()`, `spheres()`, `kill(m)`(마리를 시체로), `farFor(p)`, `static rotate(v, yaw)`, `displays()`.
- Member: `index`, `home`, `yaw/pitch/roll`, `scale`, `hp`, `dead`, `look`, `off()`, `pos()`, `moveTo(off, 틱)`, `face(y, p, r, 틱)`, `lookAt(at, 틱)`, `state(s)`.
- 무리 체력 약속: 감독이 넣은 `maxHp` 를 첫 틱에 '마리 하나 체력'으로 읽고, 그 뒤 `maxHp`, `hp` 는 무리 합계(G02.init). 마지막 마리가 죽을 때 종이 `die(d)`.
- 폭발, 불: `d.at()` 은 엔진 `center` 가 준 점(같은 틱 inRadius 에 걸렸으면 폭발점에 가장 가까운 산 마리, 아니면 산 마리 가운데).

## 5. 엔진(`game.mobs` = `Mobs`, `game.mobs.flow` = `Flow`)

- 놓기: `spawn(sp, Vector, child)`(child 면 BUDGET, 아니면 HARD_CAP 안에서. 미구현, 예외면 null), `place(g, at)`(build, spawned 실패면 null).
- 조회: `all()`, `alive()`, `count(sp)`, `near(at, r, 조건)`, `nearest(at, r, 조건)`, `byId(id)`, `standing()`(이번 틱 서 있는 사람 캐시), `nearestPlayer(at, r)`, `acquire(g, r)`, `clearLine(a, b)`, `static chest(p)`, `static lagTicks(p)`.
- 예산: `budgetUsed()`, `budgetFor(int)`, `budgetFor(Species)`, `costOf(Species)`(놓아 본 종은 실제 센 수, 처음이면 `sp.cost`).
- 상호작용: `looked(p, reach)`(사람이 보는 개체), `helped(g, p)`, `listenSound(g)`, `reveal(at, r)`, `lure(g, at, 틱)`, `lure(at, r, 틱)`, `corpses()`(Corpse: `sp`, `pos`, `at`, `gone()`, `left(now)`), `eat(corpse)`. 사체는 래그돌이라 구르는 동안 `pos` 가 따라 바뀐다(같은 Vector 를 고쳐 씀, 5.1절).
- 맞힘(HitTargets, 총과 수류탄이 씀): `cast`, `damage`, `inRadius(c, r)`, `center(o)`. 폭발 피해는 **inRadius 를 먼저 부르고 center** 차례를 지킨다.
- 피해 배율 훅(2026-11-11): `scale(key, Mobs.DamageScale)`, `unscale(key)`. `DamageScale.scale(Gabe 맞은것, Damage)` 가 돌려준 배율을 게이브가 받는 **모든** 피해(총 직격, 산탄, 저격, 수류탄, 폭발탄, 화염탄, 화염병, 연쇄)에 곱한다(건 차례대로, 0 이하는 0). 종류는 `d.type()`. 판이 바뀌어도 남으니 건 쪽이 판을 살펴 1 을 돌려주거나 뗀다. 본보기 G51Lesson(배운 종류 x0.7, 집계도 이 훅에서).
- 쏠 수 있는 것(게이브 아님): `Mobs.Shootable`(`rayHit(o, dir, max)`, `shot(Damage)`, `center()`, `gone()`), `addShootable(s)`, `removeShootable(s)`. 총 광선이 게이브보다 먼저 닿으면 그것이 맞는다(`cast` 가 `Hit(s, ..)`, `damage` 가 `s.shot`). 수류탄 반경(`inRadius`)에는 안 든다. 진짜 보급 상자(10절)가 쓴다.
- 기록: `contributions(g)`, `killedSpecies()`, `gazeTarget(g)`, `ruleSpeed(g)`, 카운터 `bites, gazes, mut1, mut2, splits`.
- 죽음(onDeath): 보상을 지갑(`kr.gave.player.Wallet.kill`)에 넘김: 팀 몫 70% 는 판에 든 모두가 똑같이, 30% 는 때린 만큼, 둘 이상이 함께 잡으면 때린 사람들에게 +10%, 1초에 한 번 정산(소수 올림). 사람이 준 피해도 처치한 사람도 없으면 `Damage.how` 가 사람 편 장치(`aug_turret`, `aug_spikes`, `trip_spikes`: Mobs 의 공격력 배율 장치 목록)일 때만 전부 팀 몫이고 그 밖(자폭, 깔림, 불길, 게이브끼리)은 0. 처치한 사람 kills++, `Hud.hit(p, true)`, 판마다 종별 첫 처치 `Hud.firstKilled`. 그 뒤 붙잡힘 풀고 `Players.freeFrom(g)` 를 조건 없이, 죽음 소리(`gabe.gxx.die`), 사체(래그돌, 5.1절).
- Flow: `dir(at)`, `dist(x, z)`, `target(x, z)`, `floor(x, z)`, `walkable(x, z)`, `inGrid(x, z)`, `invalidate()`, `rescan()`(맵이 바뀌면). 상수 `STEP 0.6`(오르는 턱), `PERIOD 10`, `NEAR_BLOCKS 3`(마지막 3블록은 사람에게 직선).

### 5.1 사체(래그돌, `game.mobs.ragdoll` = `Ragdoll`, 명세 `docs/모듈/래그돌.md`)

죽으면 엔진이 몸 디스플레이를 그대로 넘겨받아 사체로 굴린다. 종 코드는 할 일이 없다(`die(d)` 에 죽인 `Damage` 만 넘기면 됨).
- 세 단계(엔진이 고른다): **물리 단계**(새 죽음. 매 틱 강체 + 흐물 막대기 사슬, 동시에 10구), **값싼 단계**(물리 단계에 자리가 없는 죽음과 새 죽음에 자리를 내준 구. 죽는 순간 끝까지 굴려 본 길의 키프레임만 보간으로 보냄: 디스플레이 하나, 사슬 없이 누운 막대기 자세), **납작**(값싼 단계도 가득할 때만 예전처럼 제자리에서 납작해지며 2초). 떼를 쓸어 담아도 거의 모든 죽음이 날아가 눕는다.
- 몸은 강체 상자: 맞은 쪽으로 튕겨 나가 구르고 떨어져 넓은 면으로 눕는다. 부품(`body.attach`)이 있는 몸은 부품마다 강체 조각으로 흩어지고, 떼 마리(`swarm.kill(m)`)는 마리 하나가 작은 강체 하나(사슬 없음).
- 사체는 쌓인다: 굳은 사체 위에 다음 사체가 올라앉아 더미가 되고, 아래 사체가 꺼지거나 먹히면 위의 것이 내려앉는다. 사람, 살아 있는 게이브, 탄과는 부딪히지 않는다(보이기만: 종 코드는 사체를 피해 다닐 필요가 없고 더미 속을 그냥 지나간다. 더미 속에서 죽은 개체는 구르며 더미 위나 옆으로 밀려 나온다).
- 얼굴이 보이게 굳는다: 엎어져 멈추면 아래 모서리를 축으로 굴러 넘어가 뒤로 눕는다(얼굴이 하늘). 얼굴 방향은 팩 키트가 `s.head()` 에서 저절로 굽는다(다르면 팩 스크립트의 `s.front`, 종작성법.md 5.1절).
- 펄럭이던 엔드 막대기는 공용 마디 모델(`gave:gabe/common_rod`) 디스플레이 사슬로 흐물거리며 끌려다니다 바닥에 눕는다(물리 단계). 어느 막대기가 흐물거리는지는 팩 키트가 모델에서 저절로 고른다(11절, `s.rod(rag=)`).
- 충격은 죽인 피해의 종류와 방향: BULLET 작게(뒤로 0.25~0.45블록/틱 + 위로 조금), PELLET 크게, SNIPER 세게 멀리, EXPLOSION 폭발점에서 바깥 위로 크게, FIRE, BITE, OTHER 는 제자리에서 쓰러짐. 맞은 점(`d.at()`)이 가운데에서 벗어난 만큼 회전이 붙고, 죽기 직전에 움직이던 속도(돌진)를 이어받는다. 큰 몸은 덜 날아간다.
- 멈추면 굳는다: 막대기 디스플레이를 치우고 몸 디스플레이 하나(사체 상태 `<상태>.c` 의 누운 막대기 자세)로 합쳐 12초 남았다가 땅으로 꺼진다(떼 마리는 4초). 웃는 얼굴은 그대로, 실눈은 감긴다.
- 예산(config.yml `ragdoll.*`, 몹 예산 200 과 따로): 물리 단계의 구 10(`active`, 떼 마리는 안 셈)과 디스플레이 50(`activeDisplays`), 값싼 단계에서 날아가는 조각 150(`cheap`), 굳은 디스플레이 120(`settled`). 물리 단계에 자리가 없으면 12틱 넘게 난 가장 오래된 구를 값싼 단계로 내리고(바로 굳히지 않는다: 마저 날아가 눕는다), 굳은 것이 넘치면 가장 오래된 것부터 꺼뜨린다. 물리 단계의 자세는 2틱마다, 값싼 단계는 키프레임(평균 5번)만 보낸다.
- 시체 API 는 그대로: `corpses()` 에 래그돌 사체도 오른다(한 구 = Corpse 하나, 떼 마리도 하나씩). `pos` 는 바닥 자리이고 구르는 동안 따라 바뀐다(값싼 단계의 사체는 처음부터 굳을 자리). `eat(c)` 는 그 구를 통째로(막대기까지) 치우고, 그 위에 얹혀 있던 사체는 내려앉는다.
- 종이 고르는 값(생성자에서, 또는 죽기 전에): `leaveCorpse = false`(사체 없음), `ragdoll = false`(예전 납작 사체), `ragKick`(충격 배율), `ragRods`(흐물 막대기 수 상한, 0 이면 얼어붙은 몸 통째로). 사체를 직접 처리하는 종(터지는 종, 쪼개지는 종)은 지금처럼 `die()` 에서 `leaveCorpse = false` 로 두거나 몸을 먼저 바꾼 뒤 `super.die(d)`.
- 사체 상태가 없는 모델(새 키트로 굽지 않은 조각)은 얼어붙은 몸이 통째로 구른다. 서버가 팩을 읽을 때 '래그돌 정보 없는 모델 N개' 를 기록에 남긴다.
- 읽기: `ragdoll.displays()`, `activeDisplays()`, `cheapMoving()`, `settledDisplays()`, `status()`, `audit()`. `game.mobs.hitNow()`(지금 처리 중인 피해, `damage()` 안에서만), `blastAt()`(이 틱의 폭발 자리).

## 6. 값 꼴(`Damage`, `DamageType`, `Species`)

- `Damage(amount, type, by, at, dir, how, aimTicks)` 레코드. `Damage.of(amount, type)`, `times(k)`, `aimed()`(겨눈 틱 10 이상). `at` 은 핑 되돌림을 푼 지금 좌표. `how`: 총 키, `explosive`, `chain`, `incendiary`, `molotov`, `grenade`.
- `DamageType`: BULLET, PELLET, SNIPER, EXPLOSION, FIRE, BITE, OTHER.
- `Species`: `ko`, `tier`, `threat`, `band`(GROUND, WAIST, HEAD, SKY), `spawn`(RING, SKY, PLACED, DERIVED), `hp`, `cost`, `reward`, `roles`, `is(Role)`, `key()`(g01), `implemented()`, `create(game)`, `static of("G01" | 한국어 이름)`.
- `Role`: CHARGE, SWARM, RANGED, TRAP, DISRUPT, SHIELD, TANK, SUPPORT, ELITE, BONUS, OBJECTIVE.

## 7. 사람(`game.players` = `Players`, `PState`, `Life`)

- 상태 `Life`: LOBBY, ALIVE(`PState.standing()`), DOWNED, DEAD, TRAPPED, WAITING. 사람에게 거는 것(hurt, slow, root, grab)은 ALIVE 에만, latent 는 ALIVE 와 DOWNED, cover 는 LOBBY 만 빼고.
- 찾기: `get(Player)`(만들어서라도), `get(UUID)`(null 가능), `all()`, `standing()`, `inPlay()`, `withLife(l)`, `downed()`, `grabbed()`, `trappedIn(g)`, `shopSpot()`.
- 피해: `hurt(p, 기본피해, src, causeKey)`(종은 `Gabe.hit` 로), `heal(p, n)`. 0 이하면 `down(p, src, causeKey)` 와 `Hud.cause`.
- 상태 이상(계열 `group` 마다): `slow(p, pct, ticks, group)`(pct 가 1 보다 크면 퍼센트, 합 50% 까지), `root(p, ticks, group)`(못 걷고 못 쏨), `immune(p, group)`(끝나면 30틱 면역), `clearSlows(p)`. 다른 모듈은 걷기 속도를 직접 쓰지 말고 `PState.moveMul` 만.
- 붙잡기: `grab(p, by, clicks)` → boolean(이미 붙잡힘, 면역, 비옷이면 false). 성공 직후 `get(p).grabPin = false`(걷게 둠), `grabDetach = false`(동료가 못 뗌). `release(p)`, `grabbedBy(p)`, `carry(p, 좌석자리)`(매 틱 불러도 됨, 사람 발 = 좌석 - 0.6). 잡은 게이브가 죽거나 사라지면 저절로 풀림.
- 덮기, 머리: `cover(p, key, ticks)` → boolean(한 번에 하나, 비옷), `uncover(p)`, `PState.coverDetach`. `attachHead(p, item)`, `attachHead(p, item, by)`(남들에게만 머리 칸 모델, by 가 죽으면 저절로 뗌, null 이면 뗌).
- 잠복: `latent(p, kind, ticks, onPop[, onCure])` → boolean(한 사람 하나, 판 전체 `latentCap()` = config `latentCap` 4), `cure(p)`, `purify(p)`, `latentCount()`. 동료가 등 뒤에서 돕기 3초로 짜냄.
- 비옷: `PState.raincoat` 이 grab, latent, cover 를 한 번 막고 하나 줄어듦. `coat(p, s)`.
- 쓰러짐, 죽음: `down`, `bleedLeft(p)`, `bleedFrac(p)`, `reviveHere(p, helper)`(체력 30%), `reviveAtShop(p, penalty)`(재화 20% 잃음). 출혈 240틱 + `bleedBonus` 뒤 DEAD 가 되고 결과를 굴린다(G38 40, G52 30, G50 30, 묶음과 구현과 웨이브 단계를 거친 것만). 만든 개체가 `DeathBorn` 이면 `bornFrom(p, s, at)`.
- 갇힘: `trap(p, eater)`(ALIVE, DOWNED 만), `freeFrom(g)`(엔진이 모든 죽음에 부름: 갇힌 사람 손실 없이 부활, 그 사람의 G38, G52 deathMob 을 사람이 잡았으면 바로 손실 없이), `transferTrapped(from, to)`.
  - 웨이브 끝 정리(`noReward` 죽음 또는 쉬는 시간 첫 틱의 죽음)는 '살아남음'. 그래서 **G38, G52, G50 종은 noReward 를 켜지 말 것.**
- 떼기 알림: 사람에게 붙는 종은 `Detachable.detached(victim, helper, how)` 를 구현(how = `escape`, `detach`, `release`. 나감, 붙인 개체 죽음도 release).
- 돕기 차례(좌클릭, `help(p)`): 탈출 연타 → 일으키기(2.5블록, 3초 / reviveMul) → 떼기 `detach(helper, victim)` → 짜내기 → `game.mobs.helped(looked, p)` → 진짜 보급 상자 열기 `game.crates.tryOpen(p)` → `game.shop.helpOpen(p)`.
- 아군 사격: `friendlyHit(shooter, victim)`(서 있으면 6틱 못 쏨, 쓰러졌으면 출혈 60틱 줄어듦). 재화: `addMoney(p, n)`(벌이, HUD +n 까지, 빚이면 절반), `addBonus(p, n)`(증강이 주는 돈: 빚에 안 걸림), `addMoneyRaw(p, n)`(옮기기), `teamMoney(n)`, `spend(p, cost)`. 처치 돈 나누기 `players.wallet`: `kill(기여, 보상)`, `helped(도운 사람, 대상)`(일으키기, 짜내기, 떼기 수당 0.25U: 같은 대상은 한 웨이브에 한 번, 도운 사람마다 여섯 번), `owe(낸 사람, 원)`(꺼내기에 낸 돈, 단골 셈에서 뺌), `repairFill(낸 사람, 찬 비율)`(Truck.repairPress: 수리는 `PState.repairPaid` 가운데 찬 몫만 갚음, 정산마다 팀 몫의 절반까지 먼저 갚음), `rate(사람, 처치 돈 배율, 팁 몫, 물주 몫, 빚의 벌이 배율)`(AugRules 가 1초에 한 번), `earnMul(사람)`, `joinGrant(PState)`. 활동(팀 몫을 받음) = 최근 60초 안에 피해 1 이상(`PState.dealt`)이나 도움, 판 시작과 합류 뒤 첫 60초. 시험 훅 `지갑 <이름|x>`, `지갑처치 <이름> <보상> [내 몫] [같이 때린 이름]`, `지갑값 x <TEAM|JOINT> <값>`, `지갑검사 x`.
- 엎드린 척 종(G35)용 공개 부품: `Players.downedTag(name, sec, reviving)`, `TAG_UP 1.25`, `TAG_SCALE 0.8`, `RING_KIND`(= `ShotFx.DOWN_RING` 14, 2026-11-11), `RING_SCALE 2.4`, `RING_EVERY 6`. 바닥 고리는 늘 보이는 빨간 고리이고 남은 출혈만큼 호가 줄어든다(일으키는 중이면 초록): 아이템 `game.shotFx.downRing(남은몫 0..1, 일으키는중)` 을 6틱마다 다시 찍는다. `game.shotFx.fx("fx_flat", RING_KIND, 0)` 로 찍으면 가득 찬 빨간 고리(시간으로 지워지지 않음).
- 다른 모듈은 `playerListName` 과 주 점수판 팀을 쓰지 않는다(Players 의 탭 색, 팀 `gave_downed`).
- PState 써도 되는 칸: `attackMul`(사람별 공격력 배율, 총 직격에 곱함, 기본 1, 건 쪽이 되돌림: G49 축복용, 2026-11-11), `armor`, `moveMul`, `maxHp`, `bleedBonus`, `reviveMul`, `raincoat`, `grabPin`, `grabDetach`, `coverDetach`, `noLikeness`(참가자 이름, 스킨을 쓰는 종은 이 사람을 건너뜀). 나머지는 읽기만.

## 8. HUD(`game.hud` = `Hud`)

다른 코드는 보스바, 액션바, 타이틀을 쓰지 않는다. 모든 글은 글귀 키(미리 그린 글리프)로 부른다. 없는 키는 그리지 않고 서버 기록에 '[허드] 없는 글귀' 한 번.

| 부름 | 화면 |
|---|---|
| `notice(key, ticks)`, `notice(p, key, ticks)` | 맨 위 가운데 알림 띠(한 번에 하나, 줄 4) |
| `notice(key, Species icon, ticks)`, `notice(p, key, icon, ticks)`, `notice(key, "그림|종키", ticks)` | [아이콘] 글귀 |
| `alert(key, level 0..5, ticks)`, `alert(p, ...)` | 재난문자 꼴(붉은 머리 !, 짙기 칸, 5 는 빨강) |
| `waveFrom(key, ticks, yaw...)` | 월드 방향 주황 화살촉(마크 yaw: 0 남, 90 서, 180 북, 270 동) + 알림 |
| `point(p | null, Vector at, ticks)` | 그 자리 쪽 금색 화살촉(한 사람 4개까지) |
| `mark(p, key, ticks)` | 조준점 오른쪽 위 작은 표시(종 키면 그 종 아이콘) |
| `meter(p, key, frac 0..1, ticks)` | 조준점 아래 게이지(같은 키는 값만 바뀜, frac < 0 지움) |
| `hint(p, key, ticks)` → boolean | 한 줄 도움말(판당 그 키 한 번, 죽은 이유 카드가 걷힌 뒤부터) |
| `cover(p, key, ticks)`, `uncover(p)` | 그리기만. 종은 `game.players.cover/uncover`(한 번에 하나, 비옷) |
| `blinkRoster(p, ticks)` | 동료 목록 깜빡임(G49) |
| `fakeRemaining(ticks)` | 남은 수를 0 으로 흉내(G55, 웨이브 끝에 풀림). G55 는 `notice("wave_clear")` 를 부르지 말 것 |

- 엔진, 사람 모듈이 이미 부르는 것: `hit(p, kill)`, `money(p, n)`(직접 부르지 말 것), `cause(p, sp, causeKey)`, `firstSeen(sp)`, `firstKilled(sp)`(나오면 저절로, disguised/untargetable 은 드러날 때).
- 웨이브 시작, 끝, 판 끝 알림은 HUD 가 Game 상태를 읽어 저절로. 감독이 `beginWave` 안에서 넣는 사건 알림은 웨이브 알림 뒤에 남는다.
- 이름표 지우기 변이(`director.hideNames()`) 중에는 종 이름을 그리지 않는다.
- 정적: `Hud.phrase(key)`, `hasPhrase`, `phraseWidth`, `hasIcon(sp)`, `hasCover(key)`.
- 공통 글귀(`data/phrases/common.json`, 다시 적지 말 것): 판 흐름 `wave_start, wave_clear, shop_open, break, revive, downed, game_over, cause_none, dead_wait, trapped`, 도움말 `hint_escape, hint_revive, hint_shop`, 사건 `ev_dir`(몰려온다), `ev_fog`(안개 짙기), `ev_flood`, `ev_silent`, `ev_night`, `ev_forehead`, `ev_eye`, `ev_dirty`, `ev_breather`, `ev_mutation`. 감독 글귀 `data/phrases/director.json`: `notice_mut_<변이>`, `murmur_1..6`, `cause_mut_burst`.

## 9. 감독(`game.director` = `Director`)

### 9.1 종이 읽는 것
- 배율: `hpMul()`, `hpMul(sp)`(이마 글자 체력 단계 포함), `speedMul()`, `dmgMul()`(Players.hurt 가 곱함: `DMG_BASE + DMG_PER_WAVE x (웨이브 - 1)`, 상한 `DMG_MAX`, **혼자면 x `DMG_SOLO` 0.8**), `mutTimeMul()`, `crowdMul()`(무리 체력 배율 = 접속자^HP_POW x 동시 수 상한 넘침 몫, 감독이 hpMul 과 보상에 곱함), `rewardMul()`(감독이 낸 개체의 보상 배율 = `REWARD_MUL x (1 + REWARD_PER_WAVE x (웨이브 - 1)) x crowdMul() x plan.rewardBonus`: **무리 배율 포함**, 한 사람 벌이가 인원과 무관하게).
- 판 끝(전멸): `gameOver()`(Game 이 부름: 남은 게이브가 END_SPEED 배로 중앙 원에 모여 메움), `endTicks()`(그 연출 틱 = `END_TICKS` 200, Game 은 이 뒤에 기록, HUD 는 이 뒤에 '모두 쓰러졌다'), `ending()`(연출 중인가).
- 사건: `fog()`(0 없음, 1 맑음, 2..5 짙음), `silent()`, `night()`, `flood()`, `puddleEye()`, `breather()`, `strength()`(-2..3), `mutations()`, `mutation(key)`, `hideNames()`, `forehead(sp)`, `foreheadSpecies()`, `lettered(g)`(이마 글자 개체), `eaten()`, `downsThisWave()`.
- 판: `inBundle(sp)`, `isBanned(sp)`, `static bannedTogether(a, b)`, `phase()`, `static phaseOf(w)`(1 배우기 .. 6 끝없는 증폭), `plan()`, `nextPlan()`, `waveSpecies()`, `dirs()`(황야에서는 늘 네 방향), `fromDir(d)`(늘 참), `lean()`(쏠림 쪽 0..3, 없으면 -1), `aliveGoal()`, `aliveNow()`, `rewardMul()`, `total()`, `spawned()`, `remaining()`, `status()`.
- 자리: `placeFor(sp)`(PLACED 기본 자리, 모두 황야 위: G21 중앙 원 테두리 R 10~14 허공 바닥 + 3.5, G47 R 16~40, 그 밖 R 16~44), `ringPoint(sp)`(몰려오는 자리: 고리 R 72~84 위, 후보 각도 셋 가운데 사람들이 가장 안 보는 쪽, 안개에 가려진 띠), `static placedCap(sp, 접속자)`.
- Plan(읽기만): `wave, counts, lead, base, spike, fresh, dirs, lean, leanExtra, leanCounts, fog, breather, silent, night, flood, puddleEye, searchlights, lightDir, dotDir, bonus, units, hpBonus, rewardBonus(넘침 보정만큼 보상도 곱함), elites, total(), describe()`.
- 인원 비례(2026-11-11): `Tune.UNITS_POW` 0.4, `HP_POW` 0.45(옛 0.45, 0.58), 혼자일 때 동시 수 넘침 한계 `OVERFILL_SOLO` 1.25(여럿은 `OVERFILL` 1.5). 모의 표는 `docs/모듈/떼와절망.md`.

### 9.2 놓는 방식
- 감독은 `Mobs.spawn(sp, at, false)` 뒤 `maxHp *= hpMul(sp); hp = maxHp`(child 에는 안 곱함). DERIVED 종(G26, G38, G50, G52)은 감독이 내지 않는다.
- 웨이브 시작 20틱(쏠림 웨이브 70틱) 뒤: 미리 깔리는 종(예산 안, `PLACED_WAIT` 600틱까지 기다림) → 새 종 혼자(죽거나 200틱까지 대기열 멈춤, 실패하면 20틱 뒤 건너뜀) → 대기열(사방 고리: 첫 무리는 360도에 고르게 한꺼번에, 그 뒤 무리마다 같은 반지름 띠, 절반은 등 뒤 편향, 몰아치기와 잦아듦. 숫자는 `Director.Tune`, 설명은 `docs/모듈/감독맵.md`).
- 웨이브 끝: 대기열이 비고 살아 있는 것 중 'PLACED 이면서 target == null' 이 아닌 것이 0. **사람을 잡거나 쫓는 PLACED 종은 `mark(p)` 로 target 을 세운다.** PLACED 만 남으면 20초 뒤 끝, 아무도 안 죽고 120초면 정리(noReward 로 die, 죽음 효과 없음).
- 보상 종 G08 은 웨이브마다 1.5% 로 하나만, 탐조등 G43 은 밤 다음 맑은 웨이브(22~60)에 2~3(자리는 감독이 줌, spawned 에서 옮기지 말 것).
- 판 규칙 표: `GAZE`(시선 규칙 종, 한 웨이브 하나), `LATENT`(둘까지), `EATERS`, `MIMIC`, `MUTATIONS`(burst, riders, split, mutfast, elite, swift, thick, horde).

### 9.3 Game 이 부르는 것(종은 안 부름)
`reset()`, `beginWave(n)`, `beginBreak(n)`, `tick()`(WAVE), `sideTick()`(모든 상태), `lobbyTick()`(LOBBY), `waveDone()`, `breakTicks()`, `addMutation(key)`.

### 9.4 대기실 우리 개체(설정 `cage`, 기본 꺼짐: 대기실은 중앙 원이고 우리 개체는 생기지 않는다)
감독은 생성자 다음, `spawned()` 앞에 `untargetable, noRules, noMutate, noBite, noReward, reward = 0` 을 걸고 `cageSpawning()` 을 true 로 둔 채 놓는다. 그때 `spawned()` 는 자리를 옮기거나 사람 쪽 일(소리, 붙잡기, 알림)을 하지 않는다. `inCage(g)` 로 물을 수 있고, 우리 개체의 `tick()` 은 불리지 않는다(Mobs.lure 로만 움직임). `/gave 상태` 의 `mobs=` 는 우리 개체를 빼고 `cage=` 로 따로 센다.

### 9.5 맵(`kr.gave.world.Plaza`, 구조물 없는 황야)
- 끝없이 평평한 황야, 바닥 윗면 `FLOOR_Y` 64 완전 평지. 가운데 `CX, CZ` = 0, 0 칸에 보급 상자(상점 카운터, 그 칸만 막힘)와 등불. 거리는 `radius(x, z)`(가운데 칸 가운데에서).
- 반지름: `R_SHOP` 5, `R_SAFE` 12(중앙 원), `R_ROAM` 48(사람의 끝, `Roam` 이 되밂), `R_SPAWN_IN` 72, `R_SPAWN_OUT` 84, `R_EDGE` 96(몹의 끝). 격자 `MIN..MAX` = -100..100.
- 목록: `grates()`(땅 갈라진 틈 12, R 20~60), `puddles()`(물웅덩이 7, R 25~45, `puddleRadius(i)`), `lamps()`(등불 하나), `gates(d)`(그 방향 고리 위 점들), `gate(d)`. 빈 목록: `eaves()`, `doorFrames()`, `doorDecor()`, `cauldrons()`, `roofs()`. `nearGate` 늘 false, `roofed` 늘 false(지붕 없음).
- 자리: `center(w)`, `lobby(w)`(중앙 원 안, 보급 상자 남쪽 6블록), `lobbySpot(w)`(원 안 흩음), `shopFront(w)`(보급 상자 남쪽 4블록, 부활 자리), `zone(x, z)`(보급상자, 상점둘레, 중앙원, 황야, 바깥, 고리북.., 가장자리, 밖).
- 호환용(뜻 없음): `PAV`, `PAV_ROOF`, `PAV_DOOR` 0, `EAVE_Y` 바닥 + 3(허공에 매달리는 높이로만), `R_PLAZA` 36, `R_OUTER` 62, `R_WORLD` = R_EDGE, `LOBBY_*`, `inLobby`, `inCage`, `cagePoint`(cage 를 켰을 때만 뜻이 있음). 벽 뒤, 지붕 위, 기둥, 그늘 같은 가정은 하지 않는다. `clearLine`, `sees` 는 그대로 써도 된다(가리는 것이 거의 없을 뿐).
- 안개 `kr.gave.world.Fog`: 단계 0 기본 흙먼지(70블록에서 안 보임), 1 맑음, 2..5 짙음(`Fog.END`). 감독 `fog()` 의 뜻은 그대로.

### 9.6 배경 음악(`game.music` = `kr.gave.game.Music`, 명세 `docs/모듈/브금.md`)
- Game 이 매 틱 `tick()`(hud 뒤), 시험 훅 차례 맨 끝 `test(p, a)`(행동 `음악`).
- 읽기: `tension()`(판 긴장도 0..1, 8초 평균: 살아 있는 몰려오는 수(감독 동시 수 목표 `aliveGoal()` 이면 가득, 2026-11-12) 0.35 + 중앙 원 테두리 16블록 안 수 0.35 + 쓰러지거나 죽은 사람 비율 0.3), `tier()`(곡이 끝날 때 쓸 단계 0 낮음, 1 중간, 2 높음), `current(p)`(그 사람이 듣는 곡 키). 필드 `enabled`, `volume`, `category`, `log`.
- 곡 길이와 단계 목록은 내보내는 팩 바이트(`game.plugin.pack.bytes()`, PackServer)의 `assets/gave/music.json` 에서 읽는다(/gave 팩 뒤 다시).
- 종과 다른 모듈은 음악을 직접 틀지 않는다. 소리 없는 웨이브(`director.silent()`)이면 음악도 저절로 끊긴다.
- 보스전: `boss(true|false)`(보스 감독이 등장과 쓰러짐에 부름), `hush(true|false)`(웨이브 중 조용히: 보스가 쓰러진 뒤 무너짐 물결과 엔딩 동안. 웨이브를 벗어나면 저절로 풀림), `hushed()`.

### 9.7 보스 바탕(`game.director.bosses` = `kr.gave.mob.boss.BossDirector`, 명세와 API 전부는 `docs/모듈/보스.md` 11.11~11.13절)
- 보스 웨이브: 10 B1, 20 B2, 30 B4, 35 B5(그 판에서 처음 잡으면 엔딩, 60초 쉬고 36웨이브로), 45 B3, 그 뒤 55, 65 ... 에 같은 차례로 회차 +1. `Director.Tune.BOSS_WAVES`, `BOSS_EVERY`, `Director.bossIndex(n)`(몇 번째 보스 웨이브, 아니면 -1), `nextBossWave(n)`, `BossDirector.ORDER`, `bossFor(n)`, `cycleOf(n)`, `waveOf(종, 회차)`. 클래스가 없는 보스의 웨이브는 보통 웨이브다(`Plan.boss` 가 false 로 내려감). 숨 고르기는 15, 25, 40, 50, 60 ...
- 감독이 다른 모듈에 주는 것: `bossWave()`(이번 웨이브가 보스 웨이브이고 보스가 나옴), `bossActive()`(보스 싸움 중: 등장부터 무너짐 물결 끝까지), `bossBlocking()`, `bosses.boss()`(지금 보스, 없으면 null), `bosses.frame()`(HUD 틀), `bosses.endingSeen()`, `bossFog(단계)`, `delayAnnounce(틱)`, `addQueue(종, 수)`, `bossLean(각, 수)`, `dropQueue()`.
- 보스 클래스: `kr.gave.mob.sp.B1 .. B5 extends kr.gave.mob.boss.Boss`(생성자 `(Game, Species)`). 종 코드가 다른 게이브를 고르거나 건드릴 때는 `sp.is(Role.BOSS)` 를 뺀다. 보스 사체는 `Mobs.corpses()` 에 없고 `bossCorpses()` 에 있다.
- 맵 연출 `bosses.terrain`(`kr.gave.mob.boss.Terrain`: 구덩이, 둔덕, 기둥, 벽, 금, 그을음을 잠깐 만들고 쉬는 시간, 새 웨이브, 판 바뀜에 모두 되돌림. 기록 파일 `plugins/Gave/boss_terrain.journal`). 보스가 아닌 모듈은 쓰지 않는다(되돌리는 때가 보스 웨이브에 맞춰져 있음).
- 지킬 것(트럭 같은 사람 아닌 표적): `kr.gave.mob.boss.Ward` 를 이어받아 `bosses.guard(ward)`, 없어질 때 `unguard(ward)`. 보스가 표적으로 삼고(`Boss.Pick.WARD`, `hitWard`) 맵 연출이 그 둘레를 건드리지 않는다.
- HUD: `hud.bossTitle(제목 키, 부제 키, 틱)`, `hud.endingCard(제목 키, 부제 키, 웨이브, 초, 사람, 틱)`, `hud.bossHit(p, 약점인가)`, `hud.mark(p, "b1", 틱)`. 큰 글귀는 키가 `t_` 로 시작하는 글귀(2배). 공용 보스 글귀 `data/phrases/boss.json`.
- 기록: `records.noteBoss(보스, 웨이브, 회차, 초, 사람, 쓰러짐)`, `noteEnding(웨이브, 초, 사람, 쓰러짐)`, `endingBest(구간)`.
- 설정 `plugins/Gave/config.yml`: `bosses`(기본 true), `bossHp`(체력 배율, 기본 1.0). `/gave 상태` 끝에 `boss=B1:0.73:p2`(등장 중 `:intro`, 죽음 연출 `:down`, 없으면 `boss=-`).

## 10. 상점, 소모품, 증강, 총 갈고리

- `game.shop`(Shop): 상점 자리 `static counterCell()`(가운데 칸 x, z), `static counterCenter()`(칸 가운데, y = FLOOR_Y), `static counterCells()`(카운터 칸들, 가운데 둘레 `COUNTER_R`), `static isCounter(block)`. 상점 자리를 찾는 종(G14)은 Plaza.CX/CZ 대신 이것을. `inShop(p)`, `canStay(p)`, `canOpen(p)`, `lookingAtCounter(p)`, `tryOpen(p)`(우클릭, 카운터 = 한가운데 보급 상자와 그 위 등불, 웨이브 중엔 보급 상자 둘레 R_SHOP 에서 카운터 블록에 시선이 닿고 사이에 게이브가 없어야, 쉬는 시간에는 중앙 원 안 어디서나), `helpOpen(p)`(좌클릭 돕기용), `open/close/refresh(p)`, `price(p, Ware)`, `buy(p, Ware)`, `offerGun(p, Gun)`, `pendingGun(p)`, `queuedGuns(p)`, `take/keep(p)`. 종용: `contaminate(level)`, `contamination()`, `gnawStart()`, `gnawEnd(key, done)`, `gnawing()`(상점 갉는 종), `inFlare(at, extra)`, `nearFlare(at, r)`, `inFire(at)`, `raincoat(p)`, `tearRaincoat(p)`.
- 강화, 스탯 상한(2026-11-11): `Gun.MAX_LEVEL` 20(옛 10, 값은 단계마다 x1.25 로 계속 오름), `Augments.Stat.cap`(체력 20, 방어 14, 걷기 10, 재장전 14, 값은 단계마다 x1.3), `Stat.MAX` 20(가장 큰 상한). 새로 뽑은 총(상점 뽑기, `offerGun` 으로 온 총)은 뽑을 때와 바꿀 때 지금 총 강화 단계를 그대로(`Gun.INHERIT` 1.0, 2026-11-14 전에는 절반) 이어받는다(`Gun.inherit(lv)`).
- 진짜 보급 상자(`game.crates` = `kr.gave.game.Crates`, 2026-11-11): 웨이브 중 드물게 떨어지는 팀 보급 상자. 꾸러미 게이브(G44)가 이것을 흉내 낸다.
  - 놓는 규칙: 2웨이브부터, 지난 상자 뒤 2웨이브째에 반반, 3웨이브째에 꼭(두세 웨이브에 한 번). 웨이브 시작 400~1000틱 뒤, 중앙(Plaza.CX+0.5, CZ+0.5)에서 R `R_MIN` 14 ~ `R_MAX` 40 무작위 각도. `DROP_H` 24블록 위에서 `FALL` 40틱 동안 내려오고(가속), 내려앉을 때 나무 먼지와 소리. 알림 `notice_crate` + 금색 화살표 100틱. 한 번에 `MAX_LIVE` 2개, `KEEP_WAVES` 2웨이브 지나면 조용히 사라짐, 판 끝에 치움.
  - 모습(G44 위장과 같게): 아이템 디스플레이 하나, 아이템 PAPER + `item_model` `gave:ammo_box`(소품 층 모드 탄약통, 없으면 바닐라 상자), 변환 rotY(-yaw) x scale(`SCALE` 1.3) x translate(0, `LIFT` 0.17, 0) x rotY(180), 디스플레이 자리 = 바닥(FLOOR_Y) + `BOX_H` 0.62 + `BOB` 0.06 x sin(틱 x 0.12), 한 틱에 `SPIN` 6도(2틱마다 12도, 보간 2), 밝기 10, view range 1.6. 맞는 구 반지름 `HIT_R` 0.62(가운데 = 자리 + 0.22).
  - 열기: 돕기(좌클릭)로 3블록 안에서 바라보면(1.6블록 안이면 대충 봐도) 열림: 판에 든 서 있는 사람 모두 탄약 가득, 연 사람은 소모품 하나(빈 자리가 있는 것 무작위), 없으면 30 + 6 x 웨이브 원. 알림 `notice_crate_open`.
  - 부서짐: 총 직격(`Damage.by` 가 있고 how 가 chain, explosive, incendiary 가 아닌 것)에 맞으면 아무것도 안 주고 부서짐(`notice_crate_broken`). 수류탄, 불은 안 건드림.
  - 읽기: `game.crates.live()`(Crate: `pos`(바닥 자리), `center()`, `gone()`), `drop(at)`(at null 이면 규칙대로 무작위), `tryOpen(p)`, 수 `dropped, opened, broken`. G44 는 이 상수(자리 띠, 높이, 둥실거림, 회전, 모델, 낙하)를 그대로 써서 진짜와 같게 떨어진다(2026-11-11 final: 감독이 낸 G44 는 숨어 기다리다 웨이브 시작 AT_MIN..AT_MAX 틱 뒤 낙하, 알림과 화살표도 같음. 다른 모습은 경첩 얼굴과 막대기 둘). 감독이 낸 개체인지는 `game.director.fromDirector(g)`.
- `game.shop.supplies`(Supplies): `KINDS`(grenade, flare, molotov, rattle, cleanse, bandage), `count(p, kind)`, `give(p, kind, n)`, `wouldDrop(p, kind)`, `pocket(p, n)`(주머니 수류탄), `use(p)`, `flares()`, `fires()`, `inFlare`, `nearFlare`, `inFire`. 상수 `FLARE_R`, `FIRE_R`, `RATTLE_R`, `FIRE_DPS`, `MAX_TOSSES 12`.
- `game.augments`(Augments, GunHooks 구현): `offer(p, n)`(증강 뽑기 n 장, 금빛 G08, 이마 글자 처치 보상), `goldReward()`, `give(p, Aug|key)`, `count(p, aug)`, `owned(p)`, `mods(p)`, `bleedBonus(p)`, `reviveMul(p)`, `pending(p)`.
- `game.guns`(Guns): `arms(p)`(Arms: `grenades`, `item`, `itemCount`, `kills`, `hits` ...), `held(p)`, `gun(p)`, `ammo(p)`, `addAllyShot(Guns.AllyShot f)`(종이 한 번 등록, 아군 몸에 닿은 탄을 가로채려면 true: 등 막대기 종), `removeAllyShot(f)`, `static kick(p, yaw, pitch)`(화면 흔들림), 칸 `SLOT_PRIMARY 0, SLOT_PISTOL 1, SLOT_ITEM 2`. 사람에게 붙은 게이브(`PState.grabbedBy`, `headBy`)는 동료 몸이 탄을 막지 않는다.
- 연출: `game.shotFx`(ShotFx) `fx(model, kind, variant)`(셰이더 연출 아이템), `downRing(몫, 일으키는중)`(쓰러짐 고리, 종류 `DOWN_RING` 14), `spawn(at, model, kind, variant, matrix, billboard, glow, life[, prio])`(효과 예산 50, 차면 null, `prio` KEY|NORMAL|EXTRA), `grow`, `drift`, `explosion(at)`, `pop(at)`, `streak(from, to)`, `flame(at, size, life, prio)`, `bloodHit(at, dir, full[, BLOOD_PINK])`. `glow` 는 밝기 15(스스로 빛남)일 뿐 발광 테두리가 아니다(`setGlowing` 은 어디서도 쓰지 않음). 탄, 빛줄기, 줄은 셰이더 모양 아이템 모델로(발광 테두리, 블록 디스플레이 상자 하나 금지).
- 소리 일반: `kr.gave.Props.soundAround(loc, "사건", 대체Sound, 볼륨, 흔들림, 대체높이, 반경, -1)`(팩 `sounds.json` 에 있으면 모드 소리, 없으면 바닐라). 종 소리는 `Gabe.sound` 로.

## 11. 팩 쪽 약속(소리, 글귀, 덮개, 아이콘)

- 키트 전체는 `docs/모듈/종작성법.md` 4~5절(Kit, Model, State, Wave, faces, audio). 핵심 한도: 모델 하나 틴트 칸 80 이하, 상태 모델 하나 면 400(`k.model(budget=)`), 요소 상자 -16..32 px, 큰 종은 `shrink=0.5`(엔진이 `Look.scale` 을 곱함).
- 소리: `k.sound(name, 한국어 자막, vanilla=[...], synth=..., attenuation=반경)` → `gave:gabe.gxx.name`, 자막 키 `subtitles.gave.gabe.gxx.name`. `attenuation` = Java `sound(..., range)` 반경. `hurt`(16), `die`(24)는 안 만들면 기본값. 공용 `gave:gabe.bite`, `gave:gabe.split`. 권리 깨끗한 바닐라 피치 바꿈이나 numpy 합성만. 웃음, 킥킥은 떼 종과 극히 일부만.
- 글귀: `phrases()` → `data/phrases/Gxx.json`(make_gabe 가 씀, 손으로 고치지 않음). 키에 종 번호(`cause_gxx`, `notice_gxx_x`). 종 이름 `name_gxx` 는 bestiary 에서 저절로. 겹치면 make_hud 가 멈춤. 바꾸면 make_hud + `compile_wip --name hud --promote`.
- 덮개: `k.cover(key, img)`, `k.cover_face(key, face, size=(480, 270))`, 키는 종 번호로 시작, 16:9 권장. 기본 덮개 `dark` 는 누구나.
- 아이콘: `k.icon(img=None, model=, state=, view=)` → `build/gabe/icons/gxx.png`(없으면 첫 모델 정면 그림이 저절로, 그것도 없으면 기본 얼굴).
- 실제 사람: 게이브 얼굴은 `_Gave` 스킨을 본뜬 팩 텍스처로만(프로필 조회 금지). 팩은 공개 배포 금지(운영.md '배포할 때').
- 다른 아이템 틴트에 `0xE00000..0xEFFFFF` 색을 쓰지 않는다(게이브 셰이더 신호, R 위 4비트 14).
- 래그돌 사체(5.1절)는 키트가 저절로 굽는다: 상태마다 몸 상자, 펄럭이는 막대기(`s.rod` 에 `wave` 를 준 것, 길이 0.3 이상, 긴 것부터 4개)가 있으면 사체 상태 `<상태>.c`(막대기 뺀 몸 + 누운 막대기 자세). 종 스크립트는 고칠 것이 없고, 바꾸려면 `s.rod(..., rag=False)`(그 막대기는 몸에 굳은 채), `rag=True`(가만히 있는 막대기도 흐물거림), `rag=2`(흐물 마디 수), `k.model(rag=False)`(모델 통째로 끔), `s.front = (x, y, z)`(얼굴이 보는 방향: 기본은 가장 큰 `s.head()` 의 앞면, 사체가 그 방향을 바닥에 깔고 굳지 않는다). 요약 줄 끝 `사체 [상태 n, 누운 자세 6, 흐물 막대기 n]`, 미리보기 맨 아래 줄에서 누운 모습을 본다(종작성법 5.1절).

## 12. 시험

### 12.1 명령
`/gave 시작 | 중지 | 상태 | 팩 | 자동 on|off | 웨이브 <n> | 소환 <종> [수] [거리] [이름] | 시험 <행동> <이름> [수...]`. `/gave 상태` 는 `state= wave= mobs= cage= alive= inplay= left= tick=` 과 감독 상태(`total= spawned= queue= fog= dirs= [lean=] str= out=살아있는수/목표(+|-)`).

### 12.2 시험 훅(`/gave 시험 <행동> <이름|x> ...`)
`Game.test` 는 mobs → players → guns → director → shop → augments → hud → music → crates 차례로 묻고 처음 답한 모듈이 가져간다(같은 이름이면 앞 모듈).

| 모듈 | 행동 |
|---|---|
| 엔진 | `몹목록 [종]`, `몹정지 on|off`, `몹죽이기 [종]`(보상 없음, noReward 켬: G38, G52 에 쓰면 '살아남음'), `흐름 x z`, `몹보기`(hurt, stamp), `몹하나 번호|종`(pose, m0, lod, part0Yaw), `보기 [종] [틱] [옆] [위]`(가장 가까운 게이브를 조준, 틱 동안 따라감. 옆(오른쪽 +), 위 블록 오프셋으로 부위 겨눔), `예산`, `몹시간`(틱 평균과 최대, 가장 느린 틱의 단계별 시간 worst=flow ai move body corpse rag, 래그돌 시작 시간 ragStart=평균/최대, 값싼 길 계산 ragPlan=평균/최대, 15ms 넘게 걸린 틱 slow=[@JVM 켜진 뒤 ms:걸린 ms(단계별)]: GC, 세이프포인트 기록과 맞춰 볼 때), `몹놓기 종 수 x z [흩뿌림]`, `몹고리 종 수 반지름`, `몹나이 틱 [종]`, `흐름벽 x1 z1 x2 z2 [높이] | 지움`, `몹폭발 [반경] [피해]`, `몹치기 <BULLET|PELLET|SNIPER|EXPLOSION|FIRE|BITE|OTHER> [수] [종] [방향 yaw]`(가까운 개체부터 그 피해로 수 번 쳐서 죽임. 방향을 주면 탄이 그쪽으로 난 것으로: 래그돌 충격 보기), `래그돌 [on|off|다시]`(사체 수와 예산: 물리 단계 active, 값싼 단계 cheap, 굳음 settled, 밀려 내려감 evicted, 납작 flat, 내려앉음 dropped), `래그돌값 <키> <값>`(active, activeDisplays, cheap, settled, settleSeconds, minAir, maxAir, pile 0|1, faceUp 0|1 ...), `래그돌검사`(굳은 사체의 면별 수 faces, 얼굴이 깔린 수 hidden, 얹힌 수 stacked, 더미 높이 top, 허공에 뜬 수 floating), `몹빠름 <종류> <종> <빠르기> [수] [틱]`(그 종이 한 틱에 그 블록 넘게 움직이면 죽임: 돌진 중 죽음), `패킷 [on|off|다시]`(그 사람에게 나가는 패킷 수와 바이트를 틱마다 셈: 틱당 평균, 가장 많은 틱과 1초, 종류별 수), `떼모음 번호|종`, `몹고장 tick|spheres|head|bitten|build|spawned|damage`, `몹틀`, `소리 [near on|off | far on|off|<크기> | 울음|이동|살덩이|사체 | 부름|대답|울림|숨|거인 | 다시]`(보탠 크리처 소리 `Ears`, 먼 저음 `Horizon.farTick`: 보낸 수, 갈래 이름이면 그 사람에게 가장 가까운 개체 소리나 먼 저음을 예산 없이 한 번. 이름 자리가 사람이어야 가까운 소리를 보냄) |
| 사람 | `판끝막기 x on|off`(가짜 사람 Hicks: 혼자 쓰러져도 판이 안 끝남), `쉬기 x [정리]`, `더미죽이기 x`, `체력`, `쓰러뜨리기`, `일으키기`, `죽이기`, `가두기`, `풀기`, `느리게`, `묶기`, `붙잡기`, `놓기`, `끌기`, `덮기`, `머리 [off|더미]`, `업힘 [G38|G52]`, `잠복`, `정화`, `아군`, `돕기`, `돈`, `비옷`, `시선 yaw pitch`, `옆으로`, `보기사람`, `자리`, `사람상태`, `닮음끔 [on|off]`(PState.noLikeness), `결과 <G38|G52|G50|없음|자동>`(다음 죽음 결과 한 번, 묶음과 단계 무시) |
| 총, 소모품 | `쏘기 <이름> [틱]`, `재장전`, `총 KIND`, `총굴리기 KIND 등급 [특성...]`, `총정보`, `강화`, `불붙음`, `장전박자`, `맡기기`, `수류탄`, `수류탄주기 n`, `총상태`, `배율`, `탄창`, `예비`, `바꾸기 0|1|2`, `반동보기`, `웅크리기`, `탭`, `멈춤`, `쏜틱`, `총색`, `누름`, `화력표 [종류]`(종류마다 한 대상 지속 화력 일반/전설과 초당 발 수, 종류를 주면 그 모델마다), `총줄`(든 총의 상점 스탯 줄), `부품소리`(든 총의 재장전 박자와 부품 소리 사건, 팩에 없는 것은 *), `소리시각`(마지막 총소리 간격 ms), `틱재우기 [ms 62] [틱 200]`(서버 틱마다 재워 TPS 를 떨굼), `도장보정 [on 또는 off]`(셰이더 도장의 클라 시계 어림 켬, 끔, 지금 앞섬 틱), `소모품 <이름> <종류> <수>`, `쓰기 <이름> 들고`, `소모품상태`, `비옷찢기`, `던진것끄기`, `폭발`, `터짐` |
| 보스(감독이 넘김) | `보스 <이름|x> <상태 | 빈보스 on|off | 켬 | 끔 | 배율 값 | 사람수 n|자동 | 소환 B1..B5 [회차] [사람수] | 체력 0..1 | 단계 n | 공격 이름 [대상] | 멈춤 on|off | 부위 이름 0..1 | 죽이기 | 화남 | 엔딩 [또] | 물결 [빠르기] | 예고 원|띠|그림자|안전|파도 [반지름] | 쓸기 [반지름] [틱] | 땅 구덩이|둔덕|기둥|벽|금|도랑|그을음|가림|고침|되돌림|상태 [반지름] [깊이나 높이] [틱] | 균형 [이름 [값]] | 예고값 [LIFT|BOX] [값]>`(보스.md 18절) |
| 감독 | `감독`, `묶음 [다시]`, `웨이브내용 n`, `사건 x <안개 n | 범람 [n] | 밤 [방향|끝] | 조용 [끝] | 오염 n | 웅얼 [n] | 구경 [n] | 이마 [종] | 눈 | 눈뜸 | 탐조등 [n] | 금빛 | 끝>`, `변이 x <키|목록>`, `방향 x <북|동|남|서> | 모두 | 끝`(쏠림 쪽 고정), `균형 x [이름 [값]]`(Director.Tune), `안개틱 x <틱>`, `새종 x <종>`, `안개계획 x <웨이브> <0..5>`, `깔린`, `막힘정리`, `감독빨리 x [배]`, `카메라`, `카메라끝`, `우리`, `기록`, `맵검사`, `맵점`, `맵목록 x <틈|웅덩이|등불|북|동|남|서>`(처마, 문틀, 장식, 가마솥, 지붕은 빈 목록) |
| 상점, 증강 | `상점열기`, `상점보기`(실제 시선으로 tryOpen), `상점닫기`, `상점상태`, `상점돈 <이름> <수>`, `사기 <이름> <ware|칸>`, `값표`, `상점칸`, `오염`, `갉기`, `총선물 <이름> KIND [등급]`, `바꾸기총`, `지키기총`, `카운터짓기 x [없앰|되돌림]`, `나갔다옴 <이름>`, `증강주기 <이름> <키>`, `증강뽑기`, `증강고르기`, `증강목록`, `아군기록`, `증강표` |
| 보급 상자 | `보급 <이름|x> [떨굼 [거리] | 열기 | 상태 | 치움]`(떨굼 거리를 주면 그 사람 앞 그 거리에, 없으면 규칙대로 무작위) |
| HUD | `허드 <이름>`(보낸 수와 칸), `알림 <이름|all> <키> [틱] [짙기]`, `카드 <이름> <종|-> [원인]`, `허드덮기 <이름> <키|off> [틱]`, `표시`, `게이지`, `적중`, `처음 x <종>`, `처음잡음 x <종>`, `가짜남은 x [틱]`, `허드방향 x <틱> <yaw...>`, `가리키기`, `깜빡`, `허드동료 x <수>`, `도움말`, `알림줄 <이름>`, `웨이브알림 x <웨이브> <키[:짙기]...>`, `이름숨김 x on|off` |

### 12.3 대본 버릇
- 맨 앞에 `["cmd", "gamemode adventure {USER}"]`(크리에이티브면 판에 못 들고 HUD 도 숨음). 혼자 쓰러짐을 보려면 `판끝막기 x on`.
- 26.3 시험 클라는 창 포커스가 없어 키가 안 먹는다. 시선과 사격은 `보기`, `쏘기`, `시선` 훅으로. `보기` 는 벽을 무시하고 겨누므로 기둥 뒤 게이브를 쏘면 기둥에 박힌다.
- 대본은 3분 안. 사진은 본 뒤 지우고 증거 3장 이하(`build/evidence/<이름>/`). 시험 클라는 가끔 팩 직후 `spirv-cross.dll` 에서 0xC0000005 로 꺼진다(ingame.py 가 3번까지 다시 띄움).
- RCON 출력이 길면 `GV_RCON_MAX=600` 을 주고 ingame.py 를 돌린다.

## 13. 본보기 둘

### 13.1 G01 가오리 게이브(디스플레이 하나, 돌격)
- Java `plugin/src/main/java/kr/gave/mob/sp/G01.java`
  - 생성자: `radius = FACE_R(0.42)`, `hover = 1.0`, `baseSpeed = 0.15`. 수치는 맨 위 `static final` 로 모아 도감과 맞춘다.
  - `build()`: `Body.of(this, "gabe/g01", 3.2f, 1.4f)`(얼굴 0.8 + 양옆 막대기 1.2 씩의 컬링 상자).
  - `tick()` 은 단계(enum Phase: APPROACH, WINDUP, CHARGE, STICK, SLIDE, TURN, SPIN)마다 짧은 메서드. 다가오기 `follow(speed())` + 옆 흔들림, 8블록 안 보이면 `mark(p)` 하고 `hold()`, 준비 중 `body.look.rate` 를 올려 펄럭임을 빠르게, 돌진 `state("charge")` + `dash(dir, v)`, 닿으면 `hit(p, 3, "cause_g01")`, `knock`, `sound("bump", ...)`, `body.squash(1.35f, 0.7f, 0.75f, 2)`.
  - 부위 판정: `spheres()` 가 얼굴 구 하나와 막대기 구 둘을 내고, `damage(d)` 에서 `d.at()` 이 막대기 구에 더 가까우면 `body.look.on.add("cut_l")` 후 `super.damage(d)`.
  - 몸 왼쪽: `left() = (cos yaw, 0, sin yaw)`(yaw 0 으로 +Z 를 볼 때 +X).
- 팩 `tools/gabe/sp/g01.py`
  - `k.model(poses=8, period=20)`, 상태 `""` 와 `"charge"`(같은 20틱 주기에 `Wave(cycles=2)` 로 두 배 빠르게).
  - `s.head(size=0.8, depth=0.55, face="smile")`, 양옆 `s.rod(h.point(±0.92, -0.5, -0.55), ...)` 를 `with s.when("!on:cut_l")` 로 감싸고 끊긴 쪽은 `on:cut_l` 그루터기.
  - 젖은 천 가장자리 `s.band(..., tex="wetskin")`.
  - 소리 flap(20), bump(16), cut(16), 글귀 `cause_g01`.
  - 결과: 틴트 칸 40, 면 385/385.

### 13.2 G02 새끼 가오리 떼(떼, LOD)
- Java `plugin/src/main/java/kr/gave/mob/sp/G02.java`
  - 생성자: `radius = 1.0`(무리 몸통), `noPush = true`, `baseSpeed = 0.14`.
  - `build()`: `swarm = game.mobs.swarm(this, "gabe/g02", 16..24)`, 마리마다 `m.home` 을 납작한 타원체 안 서로 0.42 떨어진 점으로, `m.state("a"|"b"|"c")`, `swarm.formation(heading, 0)`.
  - 첫 틱 `init()`: 감독이 넣은 `maxHp` 를 마리 체력으로 나눠 갖고 무리 합계로 바꿈, 13웨이브부터는 강화판.
  - 단계 CRUISE(흐름장 쪽으로 한 틱 7도씩 휨, `swarm.heading`), HALT(8블록 앞 일제히 `m.lookAt`, `m.face(..., tilt)`), POUNCE(마리마다 `moveTo` 사람 몸통 둘레, 닿은 마리 `hit(p, 1, "cause_g02")`), REGROUP(`swarm.formation(heading, 8)`).
  - `spheres()` = `swarm.spheres()`. `damage(d)`: 폭발, 불은 `swarm.within(d.at(), 3)` 모두, 그 밖은 `swarm.nearest(d.at())` 한 마리. 마리 `m.look.hurtUntil = wt + 3`, 죽으면 `swarm.kill(m)`. 그 뒤 `game.mobs.onHurt(this, d, 깎인 양)`, `swarm.flinch()`, 다 죽으면 `die(d)`.
- 팩 `tools/gabe/sp/g02.py`
  - `k.model(poses=6, period=12, mut2=False, torn=False)`: 떼는 변함 규칙에서 빠지니 자동 부위를 끔.
  - 상태 a, b, c(실눈 각도 다른 얼굴 0.35 + 꼬리 막대기 하나, 좌우로 흔듦 `toward=(1, 0, 0)`), `m.default = "a"`.
  - 덩어리 `lod` 는 `s.lump(count=16, ...)`(면 384).
  - 소리 giggle(24, 합성), flutter(16), 글귀 `cause_g02`.

## 14. 점검표(종 담당)
- [ ] Java 상태, 부위, 소리, 글귀 이름이 팩 스크립트와 글자까지 같다(`build/gabe/pack_layer/assets/gave/gabe_looks.json`, `sounds.json`)
- [ ] `tick()` 은 vel, yaw, 상태만. 엔티티를 직접 만들지 않는다(부품은 `body.attach`)
- [ ] 사람 피해는 `hit(p, 기본값, "cause_gxx")`, 원인 글귀가 있다
- [ ] PLACED 종이 사람을 잡으면 `mark(p)`. G38, G52, G50 은 noReward 를 켜지 않음
- [ ] 우리 개체(`game.director.cageSpawning()`)일 때 `spawned()` 가 사람 쪽 일을 안 함
- [ ] 틴트 칸 80, 면 400 이하, 효과는 셰이더 모양(발광 상자 금지), 글 벽 없음
- [ ] 사체: 터지거나 쪼개지는 종은 `leaveCorpse = false`, 그 밖은 그대로 두면 래그돌(미리보기 맨 아래 줄의 누운 모습이 어색하면 `s.rod(rag=)` 로 조정)
- [ ] 컴파일은 `build/wip/<이름>/` 에서, 통과한 뒤 promote. 게임 사진은 직접 보고 지움

## 15. 원정(트럭, 죽음과 꺼내기: `game.trip` = `kr.gave.trip.Trip`, 2026-11-12)

자세한 것은 `docs/모듈/원정.md` 13.5절. 원정이 꺼져 있으면(설정 `trip.enabled`, 운영자 명령 `/gave 원정 on|off` 로 바꿈: `Trip.want`) `game.trip.truck()`, `revive()` 는 null 이고 예전 판 그대로다.

- 상점 자리: 트럭이 서 있으면(`kr.gave.trip.Truck.live()`) 카운터는 트럭 발자국(x -1..1, z -3..2 배리어 두 층)이다. 종은 지금처럼 `Shop.counterCells()`, `counterCenter()`, `isCounter(block)` 을 쓰면 되고, 트럭에 붙는 자리는 `Truck.slots()`(발자국에 맞닿은 18칸과 트럭 쪽 방향), 거리는 `Truck.dist(x, z)`.
- 트럭을 깎는 종: `Truck t = game.trip.truck(); if (t != null) t.hit(양, this, "cause_gxx")`(트럭 체력 단위, 최대 1000) 또는 `t.hitFrac(비율, this, 원인)`. 한 번 무는 값의 기준은 `t.biteDamage(sp)`. 터지는 종은 폭발이 발자국 3블록 안이면 사람 피해의 세 배를 `hit` 으로(`Trip.Tune.TRUCK_BLAST`). 엔진이 하는 것: 발자국 곁에 선 트럭 노림 개체와 사람을 2초 동안 못 문 개체는 저절로 문다(종 코드가 할 일 없음). 트럭 노림으로 찍으려면 `t.mark(g)`.
- 보스는 지킬 것으로 겨눈다(`Boss.Pick.WARD`, `hitWard`): 트럭이 `game.director.bosses.guard` 에 올라가 있다.
- 죽음: 원정에서는 죽은 사람이 저절로 돌아오지 않는다(`Players.reviveAtShop` 이 죽은 사람을 건너뜀, 꺼내기는 `Players.reviveAtTruck`). 죽으면 늘 죽음 결과 종 하나가 서고(G38, G52, G50), 그 개체를 사람이 잡으면 그 사람의 꺼내는 값이 싸진다(부활하지 않음: `PState.faceKilled`). 죽은 사람의 얼굴과 이름을 쓰는 종은 `game.trip.revive().dead()`(죽은 채인 사람)와 `PState.deaths`(이 판에서 죽은 횟수)를 읽는다.
- HUD 값: 부품이 `TripPart.hud(p, out)` 로 내놓은 값을 Hud 가 그린다(키 목록은 원정.md 13.5절 HUD). 검은 화면에 글 한 줄씩은 `game.hud.story(키 목록, 줄 틱, 머무는 틱)`.
- 발사장(31~35, `kr.gave.trip.Launch`, 원정.md 6.5절): `Launch l = game.trip.part(Launch.class)`(없으면 null). `l.built()`, `l.pad()`(발사대 가운데), `l.point(i)`(건조 지점 0..2), `l.build()`(건조 0..1), `l.damageBuild(몫, 원인)`(그 라운드 몫 안에서 깎음), `l.phase()`(엔딩 차례), `l.boarded(p)`, `l.lights()`(= PadLights 한 벌). 4막 특수가 건조를 깎을 때는 사건 `game.trip.fire(Launch.EV_DRAIN, 몫)`, 빠르기는 `mul("build")` 로 답함.

## 16. 새 종 갈고리(2026-11-12, sp_engine)

새 종 묶음 8~14(`build/species_critic/batches.md`), 17~20(`build/species_critic2/batches.md`)이 쓸 공용 갈고리. 모두 덧붙인 것이라 쓰지 않으면 예전과 같다. 종 코드는 자기 파일에서 부르기만 한다. 키를 받는 갈고리는 같은 키로 다시 걸면 바꾸고, 판이 바뀌면(Game.reset) 엔진이 모두 뗀다: 종은 놓일 때(spawned) 자기 종 키(`"G139"` 처럼)로 다시 건다. 시험 훅은 맨 끝.

### 16.1 몸, 맞음(`Gabe`, `Mobs`, `Body`)
| 기능 | 부름 | 뜻 |
|---|---|---|
| BIG | `big = true`(생성자) + `spheres()`(틱마다 한 번 계산), `game.mobs.aimPoint(o, 눈, 단위 방향, 길이)` | 폭발, 불 반경(`inRadius`)은 구 표면으로 재고 같은 틱 `center` 는 그 폭발점에서 가장 가까운 구 표면 점(개체마다 제 폭발점을 기억해 폭발 도중 다른 물음이 끼어도 맞음). 수류탄, 화염병 불, 폭발탄, 연쇄, 전기총 번짐, 유탄, 로켓, 증강 폭발과 불벽이 모두 이 길. 화염방사기, 냉동총(Arsenal.stream)은 큰 몸이면 `aimPoint`(줄기에 가장 가까운 점)로 원뿔 각을 잰다 |
| UNTARGET | `g.cloak(틱)`, `g.cloaked()`, `g.unhittable()` | 남이 가림: 그동안 총 광선(cast), 폭발 반경(inRadius), 모든 피해(Mobs.damage: 포탑, 증강까지)가 지나간다. 종 자신의 `untargetable` 과 따로 세니 서로 덮어쓰지 않는다. 계속 가리려면 매 틱 다시 |
| MOBDMG | `g.dmgMul`(오래, 건 쪽이 되돌림), `g.dmgBuff`(이 틱만, speedBuff 꼴), `g.hurtMul()` | 이 개체가 사람에게 주는 피해 배율. `Players.hurt` 가 감독 dmgMul, armor 와 함께 곱한다 |
| HPEXP | `g.hpPow = Director.Tune.HP_POW_BIG`(생성자) | 감독이 놓을 때 체력의 사람 수 몫을 접속자^hpPow 로(기본 NaN = HP_POW). HP_POW_BIG 은 지금 HP_POW 와 같은 0.75(값은 파워커브 담당) |
| RELOCATE | `game.mobs.relocate(g, at)` → boolean, `body.warp()` | 보간 없이 한 번에 옮김(닻이 미끄러지지 않음: 보간 길이 0 을 한 틱 먼저 보냄). 위치 기록(핑 되돌림), 속도, 끌림, 표적 나누기를 지움. 땅 몹 높이는 다음 틱에 엔진이. 보스는 false |
| NAMETAG | `Body.Tag t = body.tag(위, 크기)`, `t.text(글)`, `t.color(rgb)`, `t.place(위, 크기, 틱)`, `t.range(v)`, `t.remove()`, `body.dropTags()` | 몸에 바닐라 글 한 줄(사람 이름: 한국어 글귀 그림이 아님). 닻의 승객 text_display 라 몸을 따라가고 늘 사람 쪽을 본다. 크기 1 = 이름표쯤, 큰 몸은 3~6. 죽거나 지워지면 엔진이 치움, 디스플레이 예산에 듦. `PState.noLikeness` 사람 이름은 종이 건너뜀(G26Tag 와 같은 규칙, G26Tag 는 그대로) |
| VOLUME | `g.soundFor(p, 이름, 대체음, 볼륨)` | 한 사람에게만 이 몸 자리에서 그 볼륨으로(사람마다 다르게: 멀수록 크게 같은 셈은 종이). 바탕은 이미 있던 `Props.soundTo`. 모드 소리는 클라가 팩 attenuation 으로 또 줄이니 팩 쪽 반경을 넉넉히 |

### 16.2 표적, 판(`Mobs`, `Director`)
| 기능 | 부름 | 뜻 |
|---|---|---|
| FORCE | `game.mobs.force(키, p, 반경, 틱)`, `unforce(키)`, `forcedFor(g)` | 그 사람 둘레 반경(수평, 0 이하 = 판 전체) 안 게이브가 그 사람만 노린다: 표적 나누기(prey, 그래서 follow 가 거리와 상관없이 곧장), `nearest(r)`, `acquire(r)`(반경 안이면). 제 표적을 따로 고르는 종은 `forcedFor` 를 읽는다. 보스, 흉내 중, noRules 는 빠짐. 여럿이면 가장 가까운 사람 |
| FIRELOG | `game.mobs.shotsNear(자리, r, 틱)`, `shotsBy(p, 틱)`, `blastsNear(자리, r, 틱)`, `lastBlastNear(자리, r, 틱)` | 최근 200틱까지. 사격 = 서 있는 사람의 모든 무기 한 발(엔진이 틱마다 Arms.shots 늘어남을 눈 자리로 적음, G16, G33 이 종 안에서 하던 셈). 폭발 = ShotFx 폭발 연출(explosion, pop, blast)을 쓴 모든 것. 자리 null 이면 어디서나 |
| FIELDTICK | `game.mobs.field(키, new Mobs.Field() {..})`, `unfield(키)`, `field(키)` | 종 개체가 없어도 도는 판 단위 갈고리: `tick(now)`(false 면 뗌), `waveStart(n)`, `waveEnd(n)`(쉬는 시간 첫 엔진 틱, 감독 정리 뒤: '웨이브 끝 통지'), `reset()`(판 바뀜 뒤 뗌). 준비, 웨이브, 쉬는 시간, 판 끝 연출 동안 돈다(대기실은 안 돎). 던지면 떼고 한 번 기록 |
| LIGHTS | `game.mobs.addLight(Mobs.Light)`, `removeLight`, `lights()`, `lit(자리)`, `dark(자리)` | 빛 = pos, radius, lit, `off(누가, 끔)`, gone. 투광등을 세우는 쪽이 올린다(**4막 발사장 투광등은 아직 코드에 없음**: 그 담당이 올려야 '끄기'가 된다). lit = 켜진 빛 반경, 조명탄, 불, 트럭 등불(단계 몫 x LAMP_R). dark = 빛 밖이고 빛이 하나라도 올라와 있으면 어둠, 없으면 밤 사건(director.night()) 동안만 |
| DMGMUL | `game.director.dmgBoost(키, 배율)`, `dmgBoost()` | 판 전체 게이브 피해 배율 칸(키마다, 곱, 1 이면 뗌). `dmgMul()` 에 곱해져 사람이 받는 피해에 든다(트럭 dmgWave 에는 안 듦). 판이 바뀌면 지움, 웨이브마다 되돌리기는 건 쪽이(Field.waveStart) |
| DIRECTOR | `game.director.waveTicks()`(웨이브 아니면 -1), `spawnLike(종, 자리)`, `g.noHold`, `g.persist` | spawnLike = 감독이 낸 것처럼 놓기(체력 hpMul + hpPow, 보상 배율, fromDirector, 처음 보는 종 알림. 웨이브 총량 셈에는 안 듦): 줄로 서는 종이 나머지를 놓을 때. noHold = 살아 있어도 웨이브가 끝남(남은 수에서 빠지고 끝 정리에선 치워짐). persist = 끝 정리, 막힘 정리에서 빠져 쉬는 시간과 다음 웨이브까지 산다(웨이브도 안 붙듦, 쉬는 시간에도 tick 되니 `game.state` 를 볼 것, 판이 바뀌면 치워짐). 웨이브 끝 통지는 `Mobs.Field.waveEnd` |
| ARENA | `kr.gave.world.Roam.limit(키, 반지름)`, `Roam.radius()`, `Roam.clearLimits()` | 사람의 끝(되밀기, 흙먼지)을 줄임(가장 작은 것, 0 이하나 R_ROAM 48 이상이면 뗌, R_SAFE + 2 아래로는 안 줆). 판이 바뀌면 지움. 몰려오는 고리 자리는 그대로(갉힌 땅 너머에서 걸어 들어옴) |

### 16.3 사람(`game.players` = `Players`, `PState`)
| 기능 | 부름 | 뜻 |
|---|---|---|
| LAUNCH | `launch(p, 높이, 옆 벡터, 착지 피해, 원인 키)`, `launch(p, 높이, 옆, 피해, src, 원인)`, `launched(p)` | 높이 블록까지 오르는 위 속도(바닐라 중력으로 셈) + 옆 수평 속도. 실제로 0.6블록 넘게 오른 뒤(20틱 안에 못 오르면 피해 없이 지움) 꼭대기에서 내려와 다시 땅에 닿는 첫 틱에 `hurt`(src 가 있으면 감독, 개체 배율, 죽은 이유의 종). 서 있는 사람만(쓰러짐, 붙잡힘, 좌석은 false), 공중에서 쓰러지거나 갇히면 착지 피해 없음, 쉬는 시간과 판 바뀜에 지움. 바닐라 낙하 피해는 판이 막는다 |
| SEAT | `ride(p, 좌석 자리, 틱)`, `unride(p)`, `riding(p)` | 붙잡힘이 아닌 좌석(carry 와 같은 좌석, 사람 발 = 자리 - 0.6): 탄 채 쏘고 재장전한다. 걷기와 웅크려 내리기는 막힘. 매 틱 불러 옮기면서 늘림, 끝나면 그 자리 바닥에 선다. 탄 사람 웅크림은 `p.getCurrentInput().isSneak()`. 공중이라 총 퍼짐은 뛰는 중과 같다 |
| TAKEN | `taken(p, 키, 배율, 틱)`, `takenMul(p)` | 받는 피해 배율(키마다, 곱, 끝나면 저절로). 증강, 상점의 `armor` 는 안 건드림 |
| STICK | `stick(p, 종류, 틱, onPop, onCure)` | 잠복 칸을 쓰는 '붙음'(정화, 등 뒤 짜내기 3초로 풀림, 한 사람 하나)이지만 판 전체 상한 latentCap 에 안 든다. `PState.latentFree` |
| CURE | `addCureHook(키, (p, how) -> ..)`, `removeCureHook(키)`, `cured(p, how)` | 낫게 함 통지: how = `cleanse`(정화 소모품), `bandage`(붕대가 다 감김), `squeeze`(동료 짜내기). 종의 지연 피해 같은 것을 지울 때 |
| NOMISS | `noMiss(p, 틱)`, `noMiss(p)` | '빗나감 판정을 건너뜀' 깃발. 뛰기, 웅크리기로 빗나가는 종은 빗나감 전에 `noMiss(p)` 를 본다(지금 있는 종은 아직 안 읽음) |
| HELPHOOK, HELPOBJ | `addHelpHook(키, (helper, mate) -> boolean)`, `removeHelpHook(키)` | 돕기(좌클릭) 차례: 탈출 연타 → 일으키기 → 떼기 → 짜내기 → **갈고리(건 차례)** → 게이브 helped → 진짜 보급 상자 → 상점. mate = 2.5블록 안에서 바라본 서 있는 동료(없으면 null). 게이브 아닌 대상(땅의 눈 알)은 종이 helper 시선과 거리를 잰다. 썼으면 true |
| HURTHOOK | `addHurtHook(키, (p, 깎인 양, src, 원인) -> ..)`, `removeHurtHook(키)` | 체력이 깎인 직후(배율 뒤 양), 쓰러짐 판정 전. 갈고리 안에서 부른 `hurt`(피해 나누기)는 다시 통지하지 않는다 |
| PUSH | `push(p, 수평 벡터)`, `Players.PUSH_MAX` 0.8 | 넘어뜨리지 않고 민다: 이번 틱 속도에 수평만 더하고 위 속도는 그대로(knock 은 튀어 오름). 같은 틱 여럿은 그 틱 처음 속도에 합을 한 번(합 0.8 까지). 서 있고 좌석에 안 탄 사람만. 매 틱 조금씩 끄는 데 |
| LASTKILLER | `PState.lastDownBy`(종), `lastDownAt`(판 틱) | `down` 이 적는 마지막으로 쓰러뜨린 종(원인 없는 쓰러짐은 null). 판에 들 때 지움 |
| FREE | `free(p)`, `holdTrapped(p, on)`, `retrap(p, g)`, `trapEnd(p)` | free = 갇힌 그 사람만 손실 없이 상점 앞(한 개체가 여러 자리에 가둘 때). holdTrapped = 쉬는 시간 첫 틱의 갇힘 끝 규칙, 먹은 개체가 사라짐(remove), 웨이브 끝 정리로 치워짐에도 갇힌 채(좌석은 그 자리). retrap = 다시 나온 개체로 옮김(이어 받는 개체 없이 Players.TRAP_HOLD_MAX 2400틱 지나면 저절로 trapEnd). trapEnd = 갇힘 끝 규칙을 지금(원정: 죽음, 꺼내는 값 절반). 먹은 개체가 사람에게 잡히면 엔진 freeFrom 이 여느 때처럼 풂 |
| VEIL | `veil(p, 덮개 키, 틱)`, `unveil(p)`, `PState.veil`, `veilUntil` | 덮개(cover)와 따로 하나: 덮개가 있어도 걸리고 비옷, 동료 떼기를 안 탐. **그리기는 HUD 요청(지금은 상태만 있고 화면엔 안 나옴)** |

### 16.4 총(`game.guns` = `Guns`)
| 기능 | 부름 | 뜻 |
|---|---|---|
| RELOAD | `reloading(p)`, `reloadProgress(p)`(0..1, 아니면 -1), `cancelReload(p)` | 재장전 중 읽기와 끊기(넣던 탄은 버림, 다시 Q) |
| AMMO | `takeAmmo(p, 몫, 탄창도)` → 뺀 발 수 | 주무기와 권총마다 그 총 예비탄 최대(Spec.reserve)의 몫(한 발 이상)을 예비탄에서, 탄창도면 모자란 만큼 탄창에서. 무한 예비탄은 그대로 |
| JAM | `jam(p, 틱, 재장전 배, 헛방 확률, 퍼짐 배)`, `jammed(p)` | 그동안 시작한 재장전이 느려지고(박자 소리도 같이), 한 발마다 헛방(철컥, 탄 안 씀, 그 박자는 날아감), 퍼짐 배. 겹치면 늦은 끝, 큰 값. 틱 0 이하면 풂 |
| WEAPONLOCK | `lockPrimary(p, 틱)`, `primaryLocked(p)` | 주무기를 들고 있으면 권총으로 바꾸고(꺼내기 시간) 숫자키 1 을 막음. 0 이하면 풂 |
| 덤 | `Guns.onHeld` 는 `@EventHandler(ignoreCancelled = true)` | 다른 갈고리가 막은 칸 바꿈(원정 갈림길 숫자키 투표, LOWEST)에 재장전 끊기, 꺼내기 시간을 걸지 않는다 |

### 16.5 트럭 등불(`kr.gave.trip.Truck`)
| 기능 | 부름 | 뜻 |
|---|---|---|
| LAMP | `Truck t = Truck.get()`(원정이 꺼져 있으면 null), `t.lampCover(키, 앉은 수)`, `t.lampOff(키, 끔)`, `lampCovered()`, `lampDown()`, `lampLevel()`(0..14), `lampLocked()`. 상수 `LAMP_COVER_FULL` 8, `LAMP_R` 10, `LAMP_OFF_SHARE` 2, `LAMP_OFF_BITE` 1.5 | 가린 수 합이 늘수록 지붕 빛 블록 단계가 14 에서 0 으로, 8 이상이면 트럭 상점이 잠긴다(Shop.canStay). 끄면(lampOff) 빛 0, 감독이 낸 개체의 트럭 노림 몫 x2, 무는 피해 x1.5. 키마다(0, false 로 뗌), 판 시작과 reset 에 지움. 종이 죽을 때 자기 키를 떼야 한다 |

### 16.6 이미 있는 것(새로 안 만듦)
- PHRASE: 종 알림 글귀는 팩 스크립트 `phrases()` → `data/phrases/Gxxx.json`(make_gabe 가 씀)을 make_hud 가 굽고, Java 는 `game.hud.notice(p|모두, 키, 틱)`, `notice(키, 종 아이콘, 틱)`, `alert(키, 짙기 0..5, 틱)`(재난문자 꼴, 짙기 칸 = 사이렌 단계 같은 1~5), `meter(p, 키, 몫, 틱)`(게이지: 남은 반지름, 채울 수, 남은 틈), `mark`, `point` 로 부른다. HUD 는 글귀 안에 숫자를 끼우지 못하니 수를 보여 줄 때는 몫(meter)이나 수마다 키(`notice_g155_left_3` .. `_0`)로.
- VOLUME 바탕 `Props.soundTo(p, 자리, 사건, 대체음, 볼륨, 흔들림, 대체높이, 변형)`. NAMETAG 의 옛 길 `G26Tag`(같은 패키지, 작은 몸용)도 그대로 쓸 수 있다.
- SEAT 확인: 옛 G45 매달림은 붙잡힘(grab + carry)이라 그동안 못 쏜다. 쏘게 하려면 위 `ride`.
- TAKEN 을 armor 로 대신하는 길은 증강과 상점이 armor 를 다시 쓰므로 쓰지 않는다. CURE 를 latent onCure 로 대신하는 길은 붕대에 안 불리고 상한을 먹어 위 CureHook 으로.

### 16.7 그리기 높이 약속(DRAWORDER, 연출 쪽과 맞춤: 코드 바뀜 없음)
바닥에 깔리는 것은 바닥 윗면(FLOOR_Y) 위 높이로 차례를 정한다. 아래 것이 위 것에 가린다.
| 높이(블록) | 것 |
|---|---|
| 0.02 | 바닥 예고(보스 Tele 원, 띠, 그을음, 종의 빨간 예고 판) |
| 0.04 | 쓰러짐 고리(Players 의 바닥 원) |
| 0.06 ~ 0.08 | 바닥에 깔리는 종(양탄자 G186, 얼룩, 그림자 몸): 예고를 가리려는 종은 이 높이로 |
| 0.1 이상 | 서 있는 몸, 사체 |
예고를 가리지 말아야 하는 종은 0.02 아래로 내려가지 않는다(예고와 같은 높이면 깜빡임).

### 16.8 시험 훅(`/gave 시험 <행동> <이름|x> ...`)
- 엔진: `강제표적 <이름> [반경] [틱] | 끝`, `몹옮기기 <이름|x> <종|아무> <x> <z>`, `몹가림 x [종] [틱]`, `사격기록 <이름> [반경] [틱]`(near, by, blasts, last, dark, lit), `몹이름표 <이름> [글|끔] [위] [크기]`, `판갈고리 x [켬|끔]`(틱, 웨이브 시작과 끝 수).
- 사람: `띄우기 <이름> [높이] [착지 피해] [옆]`, `태우기 <이름> [틱] [위]`, `받는배율 <이름> [배] [틱]`, `붙음 <이름> [틱]`, `빗나감끔 <이름> [틱]`, `밀기 <이름> <x> <z> [틱]`, `갇힘잇기 <이름> [off]`, `한사람풀기 <이름>`, `옅은테 <이름> [키] [틱]`, `새갈고리 <이름>`(사람 칸 보기).
- 총: `탄약빼기 <이름> [몫] [탄창도 0|1]`, `엉킴 <이름> [틱] [재장전 배] [헛방] [퍼짐 배]`, `주무기잠금 <이름> [틱]`, `재장전끊기 <이름>`.
- 대본 `tools/scenarios/sp_engine_hooks.json`(띄우기, 강제 표적, 좌석에서 쏘기, 탄약 빼기, 엉킴, 큰 몸 폭발과 불길, 가림, 이름표, 옮기기, 판 갈고리).
