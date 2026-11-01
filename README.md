# sms-forwarder (문자 전달기)

받은 문자(SMS·MMS)와 사용자가 고른 앱의 알림을 다른 전화번호로 **SMS 로** 다시 보내 주는 개인용 안드로이드 앱입니다.
서버나 계정 없이 폰 안에서만 동작하며, `INTERNET` 권한이 없어 어떤 내용도 네트워크로 나가지 않습니다.

> **English summary** — sms-forwarder is a personal Android app (minSdk 32) that forwards incoming SMS/MMS text and selected apps' notifications to other phone numbers **via SMS only**. It runs entirely on the device (no `INTERNET` permission, no server), supports per-rule conditions, word replacement, message templates, dual SIM, retries and send-safety limits, and shows delivery reports when the carrier supports them. Sideload only; it does not need to be the default SMS app. Kotlin · Jetpack Compose · Room · WorkManager. Licensed under Apache-2.0. Build with `./gradlew assembleDebug` (JDK 17+, Android SDK 36). Signed APKs are attached to GitHub releases.

- 기본 문자 앱이 아니어도 됩니다. 쓰던 문자 앱을 그대로 두고 받은 문자를 옆에서 읽어 전달합니다.
- 사이드로드 전용(Play 스토어 배포 아님). Android 12L(API 32) 이상.

## 기능

- **문자 규칙**: 발신자 번호·본문 조건(포함/제외), 모두 전달, 단어 바꾸기, 전달 형식(템플릿), 여러 수신처, 규칙별 SIM·시간대
- **알림 규칙**: 대상 앱 선택, 제목·본문 조건, 전달할 알림 필드를 자동 또는 직접 선택
- **MMS 텍스트 전달**: 본문 텍스트를 전달하고 이미지 등 첨부는 개수만 알려 줍니다.
- **보내기 안전장치**: 같은 문자 반복 차단, 수신자별 폭주 차단, 월 발송 한도, 스팸 차단 조건, 전달 시간대, 로밍 중 전달 끄기, 전송 간격(무작위)
- **신뢰성**: 전송 실패 재시도, 네트워크가 끊겼을 때 대기했다가 다시 보내기, 부팅·앱 업데이트 뒤 복구, 필요하면 상주 서비스 모드
- **기록**: 전달 이력(접수·도착 상태)과 수신 이력, 이력에서 "규칙 다시 적용"
- **내보내기/가져오기**: 규칙과 설정을 JSON 파일 하나로 저장·복원
- **듀얼 SIM**: 받은 SIM·보낼 SIM 선택과 SIM 이름 지정

## 필요 권한과 이유

| 권한 | 이유 |
| --- | --- |
| `RECEIVE_SMS` `READ_SMS` `RECEIVE_MMS` | 받은 문자·MMS 를 읽어 규칙을 적용 |
| `SEND_SMS` | 전달 문자를 보냄 |
| `READ_PHONE_STATE` `READ_PHONE_NUMBERS` | 듀얼 SIM 의 슬롯·번호 구분 |
| `READ_CONTACTS` | 보낸 사람 연락처 이름 표시(`{연락처}` 토큰) |
| 알림 접근(`BIND_NOTIFICATION_LISTENER_SERVICE`, 시스템 설정에서 허용) | 선택한 앱의 알림을 읽어 전달 |
| `POST_NOTIFICATIONS` | 결과·경고 알림 표시 |
| `FOREGROUND_SERVICE` `FOREGROUND_SERVICE_REMOTE_MESSAGING` | 상주 서비스 모드(선택) |
| `RECEIVE_BOOT_COMPLETED` | 재부팅 뒤 동작 복구 |
| `WAKE_LOCK` | 전송 중 절전 해제 |
| `ACCESS_NETWORK_STATE` | 연결이 돌아오면 대기 중인 전송 재개 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 배터리 최적화 예외 요청(백그라운드에서 끊기지 않게) |
| `QUERY_ALL_PACKAGES` | 알림 규칙의 대상 앱 목록 표시(사이드로드 전용) |

`INTERNET` 권한은 없습니다. 통신 요금은 발송하는 SMS 에만 듭니다.

## 설치

### 서명된 APK
[Releases](../../releases) 에서 APK 를 받아 폰에서 열거나 `adb install -r sms-forwarder-1.1.0.apk` 로 설치합니다. 디버그 빌드를 설치한 적이 있으면 서명이 달라 먼저 지워야 합니다.

릴리스 APK 의 서명 인증서 SHA-256 (설치 전 `apksigner verify --print-certs sms-forwarder-1.1.0.apk` 의 값과 맞는지 확인하세요):

```
0a128aa798d57d1efb374ccf71254f8dcf243d3ab025a86e3816aa3a11ddf15d
```

### 직접 빌드
JDK 17 이상(JDK 21 권장)과 Android SDK(`platforms;android-36`, build-tools)가 필요합니다.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 단위 시험
./gradlew assembleRelease      # 서명 설정이 있으면 서명된 릴리스 APK
```

릴리스 서명은 `~/.config/sms-forwarder/keystore-2026.properties` 가 있을 때만 적용됩니다(없으면 서명 없는 APK 가 만들어집니다). 다른 파일을 쓰려면 환경 변수 `SMSFWD_KEYSTORE_PROPS=<파일>` 이나 `./gradlew assembleRelease -PkeystoreProps=<파일>` 로 고릅니다. 키 파일과 비밀번호는 저장소 밖에 둡니다.

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## 처음 설정

1. 앱을 열면 배터리 최적화 예외 안내가 뜹니다. 허용해야 백그라운드에서 문자 처리가 끊기지 않습니다.
2. **상태** 탭의 "권한 요청"에서 문자·전화 상태·연락처·알림 표시를 허용하고 모두 "허용됨"인지 확인합니다.
3. **규칙** 탭 → "문자 규칙 추가": 이름, 수신처(전달받을 번호, 한 줄에 하나), 전달 조건을 정하고 저장합니다. 이후 받은 문자가 설정한 간격 뒤에 SMS 로 전달됩니다.
4. 알림 전달(선택): **상태** 탭에서 알림 접근을 허용한 뒤 "알림 규칙 추가"에서 대상 앱을 고릅니다(비우면 모든 앱).
5. 전달이 안 되면 **이력** 탭에서 수신 이력의 처리 단계와 전달 이력의 결과를 확인합니다.

기본 방식(작업 예약)에서 문자가 간헐적으로 빠질 때만 설정의 "상주 서비스 모드"를 켭니다(알림이 계속 표시됩니다).

## 전달 형식(템플릿) 토큰

전달 형식에는 `{이름}` 꼴의 자리표시자를 쓸 수 있습니다.

- 문자: `{보낸이}` 발신번호 · `{보낸이(서식)}` 서식 번호 · `{연락처}` 연락처 이름 · `{본문}` · `{규칙}` 규칙 이름 · `{SIM}` 받은 SIM · `{시각}`
- 알림: `{보낸이}` 알림 제목 · `{본문}` · `{앱}` 앱 이름 · `{서브}` 보조 내용 · `{추가}` 추가 정보 · `{규칙}` · `{시각}`
- 날짜·시각 조각: `{연}` `{월}` `{일}` `{오전오후}` `{시12}` `{시24}` `{분}` `{요일}`

토큰은 나열된 순서대로 한 번씩 바뀝니다. 본문 안에 토큰 꼴 문자열이 있으면 그 뒤 차례에 다시 바뀔 수 있습니다.

조건은 한 줄에 하나이며 `!` 로 시작하면 제외 조건입니다. 자세한 문법은 규칙 편집 화면의 도움말을 보세요.

## 규칙·설정 내보내기/가져오기

설정 탭의 "데이터·기록"에서 규칙과 설정을 JSON 파일 하나로 저장하고 다시 불러올 수 있습니다(이력·대기열은 포함되지 않습니다). 파일의 `schema` 값이 이 앱의 형식(2)과 다르면 "지원하지 않는 schema 입니다"라고 안내하고 가져오지 않습니다.

## 제한·주의

- **SMS 로만 보냅니다.** 이메일·메신저·HTTP 등으로는 보내지 않고, RCS 는 지원하지 않습니다.
- **수신 확인은 통신사 지원에 달려 있습니다.** 전달 이력의 "상대 폰에 도착"은 통신사가 도착 보고를 보내 줄 때만 표시됩니다. 보고가 없어도 "발송 접수"로 처리되며 재시도·결과 알림 판정은 도착 보고와 무관합니다. 일부 통신사·해외 번호·에뮬레이터에서는 도착 보고가 오지 않습니다.
- MMS 는 텍스트만 전달합니다. 이미지·동영상은 개수만 알려 줍니다.
- 일부 제조사(샤오미 등)는 자동 시작·백그라운드 실행을 따로 허용해야 합니다. 앱이 안내 화면을 열어 줍니다.
- 알림 접근 권한을 끄면 알림 규칙 전달만 멈추고 문자 전달은 계속됩니다. 경고 알림은 한 번만 표시됩니다.
- 이 앱은 받은 문자의 내용을 다른 번호로 보냅니다. **본인이 사용하는 번호·계정의 내용만** 전달하고, 인증번호·금융 문자 같은 민감한 내용이 새 수신처에서 보호되는지 확인하세요. 통신 요금과 문자 발송 한도는 사용자 책임입니다.
- 지연 전달 코드는 들어 있지만 기본으로 꺼져 있습니다(Android 12 부터 정확 알람 정책이 바뀌었습니다). 켜려면 세 곳을 바꿉니다.
  1. `core/DelayPolicy.kt` 의 `LEGACY_SDK_GATE = false`
  2. `AndroidManifest.xml` 에 `USE_EXACT_ALARM` 과 `SCHEDULE_EXACT_ALARM`(`android:maxSdkVersion="32"`) 권한 추가
  3. `DelayPolicy.SHOW_DELAY_UI = true`

## 구조

```
core/     순수 로직(JVM 시험 가능): 템플릿, 조건 판정, 단어 바꾸기, 알림 필드 추출, MMS 판정, 재시도·안전장치 정책, 내보내기/가져오기
db/       Room 저장소(규칙, 전달 건, 수신 이력, 대기 이벤트 등)
sms/      수신기, 전송, 전송 결과·도착 보고 처리, 상주 서비스, MMS 읽기
work/     WorkManager 워커, 모드별 분기(Dispatcher)와 공통 처리(EventProcessor)
capture/  알림 리스너
ui/       Jetpack Compose 화면(상태·규칙·설정·이력)
```

## 라이선스

[Apache License 2.0](LICENSE). 자세한 고지는 [NOTICE](NOTICE) 를 보세요.
