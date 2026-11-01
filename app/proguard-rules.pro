# R8 keep 규칙. Room(RoomDatabase 하위 클래스·@Entity/@Dao)·WorkManager(ListenableWorker 생성자)·Compose 는
# 각 라이브러리의 consumer 규칙이 처리하고, 매니페스트에 선언된 액티비티·서비스·수신기는 AGP 가 자동으로 유지한다.

# WorkManager 는 워커 클래스 이름을 DB 에 저장해 두고 나중에(앱 업데이트·재부팅 뒤) 이름으로 되살린다.
# 난독화로 이름이 바뀌면 대기 중이던 재시도·네트워크 대기 작업이 되살려지지 않으므로 이름을 유지한다.
-keepnames class * extends androidx.work.ListenableWorker

# 알림 리스너: 시스템이 이름으로 바인딩한다(매니페스트 선언으로도 유지되지만 이름 의존이라 명시).
-keep class io.github.casimirkwon.smsforwarder.capture.NotificationCapture { *; }

# 스택 트레이스 가독성
-keepattributes SourceFile,LineNumberTable
