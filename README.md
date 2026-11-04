# 드라이브 돌림판 (Drive Spin)

남은 주행거리로 갔다 올 수 있는 오늘의 축제만 돌림판에 올려 고르고, 그날 남긴 한마디를 몇 년 뒤 같은 길에서 차가 다시 들려주는 Pleos Connect 앱입니다.

## 영상
-  — Pleos Connect 에뮬레이터에서 녹화한 실제 앱 화면으로 기능 14가지(차 음성은 녹음이 막혀 같은 문장을 재현)
-  — 쓰는 장면을 이야기로(인물·풍경은 AI 제작, 차 화면은 실제 앱)
-  — 숏폼(세로 52초)

## APK
- `apk/DriveSpin_드라이브돌림판_release.apk` (release 서명)

## 실행 방법
1. Pleos Connect 에뮬레이터(Android 14 차량 이미지)를 켭니다.
2. 주행 중 사용 앱으로 인식되도록 앱마켓 설치처럼 설치합니다.
   ```
   adb install -r -g -i ai.umos.applibrary "apk/DriveSpin_드라이브돌림판_release.apk"
   adb shell am start -n app.drivespin/.MainActivity
   ```
3. 가운데 '돌려'를 누르면 남은 주행거리로 왕복 가능한 오늘 축제 중 하나를 골라 음성으로 알려 줍니다. '여기로 안내'를 누르면 차량 내비로 넘어갑니다.
4. 발자취(다녀온 곳 기록)는 기본 꺼짐입니다. 화면 아래 '발자취 남기기'를 켜면 도착 기록·'한마디 남기기'·다시 지날 때 읽어 주기가 동작합니다. 처음 켤 때 '기록 열쇠'(20자리)가 한 번 뜹니다.
5. 오늘 발자취가 생기면 '폰 사진 넣기'가 보입니다. 누르면 QR이 뜨고, 폰 카메라로 찍어 열린 웹 화면에서 사진을 고르면 2초 안에 차로 들어와 그 발자취에 붙습니다. 다음에 그 자리에 차를 세우면 사진이 화면 가운데로 떠오릅니다(여러 장이면 차례로 넘김, 디버그 빌드는 아래 demoNear 신호로 시험).
6. 차 바꾸기 시험: 앱을 지웠다 다시 설치하고 '예전 차 기록 가져오기'에 기록 열쇠를 넣으면 발자취·한마디·사진이 돌아옵니다(자판이 한글이면 왼쪽 아래 지구본 단추로 영문 전환).

## 테스트 환경과 필요한 설정
- Pleos Connect 에뮬레이터, Android 14(API 34) 차량 이미지에서 확인했습니다.
- 인터넷 연결이 필요합니다(축제 정보: 한국관광공사 TourAPI를 모아 둔 서버 / 폰 사진 우편함·기록 금고: 같은 서버, Cloudflare Workers).
- 서버에 맡기는 기록은 차에서 기록 열쇠로 잠근 뒤(AES-GCM) 보내 서버는 내용을 열 수 없습니다. 폰 사진 우편함은 차가 받는 즉시 지워지고, 안 받아도 1시간 뒤 지워집니다.
- 에뮬레이터 가상 콘솔에서 남은 주행거리(Remaining Range)를 넣어 두면 그 값으로 왕복 가능한 축제를 고릅니다. 값이 없으면 예시값(서울시청 기준 120km)으로 동작하고 화면에 '(예시)'가 붙습니다.
- 가족 모드: 화면 아래 '가족 등록'에 아이 이름을 넣어 두면, 뒷좌석 탑승이 감지될 때 "오늘 ○○이랑 가요?"라고 묻고, 고르면 편도 40km 안쪽 축제를 먼저 올립니다. (에뮬레이터 좌석 값은 늘 '탑승'이라 아래 시험 신호로 탑승·내림을 흉내 낼 수 있습니다)
- 발자취 지도: 다녀온 곳이 생기면 '발자취 지도'에서 해마다 다녀온 곳을 지도로 봅니다.

## 에뮬레이터에서 주행·도착 시험 (디버그 빌드 전용)
에뮬레이터에는 모의 주행이 없고 개발 중인 앱은 주행 중 화면이 가려져, 디버그 빌드에만 시험 신호를 넣어 두었습니다(`./gradlew assembleDebug`).
```
adb shell am start -n app.drivespin/.MainActivity --ez demoDrive true             # 주행 중 화면
adb shell am start -f 0x20000000 -n app.drivespin/.MainActivity --ez demoArrive true # 목적지 도착
adb shell am start -f 0x20000000 -n app.drivespin/.MainActivity --ez demoNear true   # 마지막 발자취 근처로 온 척
adb shell am start -f 0x20000000 -n app.drivespin/.MainActivity --ez demoRear true    # 뒷좌석에 사람이 탄 척(false면 내린 척)
adb shell am start -f 0x20000000 -n app.drivespin/.MainActivity --es demoPick 양재천  # 돌림판이 그 이름이 든 칸에 멈춤
```

## 빌드
```
./gradlew testDebugUnitTest assembleRelease
```
Pleos SDK는 공개 저장소(nexus-playground.pleos.ai)에서 받습니다. 사용 SDK: Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1 + 안드로이드 차량 표준(CarPropertyManager, CarUxRestrictionsManager). QR 그리기: ZXing core 3.5.3.

## 파일
- `Festivals.kt` — 축제 받기·거르기(오늘 진행 중, 왕복 가능), 발자취 근처 찾기, 폰 사진 우편함
- `Vault.kt` — 기록 금고(차에서 잠가 맡기기·새 차에서 되찾기)
- `CarLink.kt` — 차 연결(주행가능거리·주행 규제·좌석·위치·길안내·음성)
- `MainActivity.kt` — 돌림판 화면, 주행 중 화면, 발자취·한마디, 폰 사진 넣기(QR)·기록 열쇠 화면
