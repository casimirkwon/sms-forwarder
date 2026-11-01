package io.github.casimirkwon.smsforwarder.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.casimirkwon.smsforwarder.core.ActiveHours

/** 규칙이 다루는 메시지 종류. [OTHER] 는 이 앱이 전달하지 않는 종류(가져온 규칙을 보존만 하고 평가에서 건너뛴다). */
enum class RuleKind { SMS, NOTIFICATION, OTHER }

/**
 * 전달 규칙. 모든 규칙을 [position] 순으로 독립 평가한다(첫 일치에서 멈추지 않는다).
 *
 * 조건·수신처·앱·단어 바꾸기·시간대·알림 구성은 JSON 문자열로 저장한다(형식은 각각 [io.github.casimirkwon.smsforwarder.core.Conditions],
 * [io.github.casimirkwon.smsforwarder.core.ActiveHours], [io.github.casimirkwon.smsforwarder.core.ReplaceWord],
 * [io.github.casimirkwon.smsforwarder.core.NotiCompose] 참고).
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val kind: RuleKind = RuleKind.SMS,
    val enabled: Boolean = true,
    /** 규칙 방식(2·3·4). 새 문자 규칙 3, 새 알림 규칙 4. 방식마다 조건 판정·적용 순서가 조금 다르다 */
    val level: Int = 3,
    /** 발신자(문자) 또는 알림 제목 조건 */
    val senderCond: String = "[]",
    /** 본문 조건 */
    val bodyCond: String = "[]",
    /** 전달 대상(수신처) 목록. 수신처마다 전송 1건 */
    val recipients: String = "[]",
    /** 알림 규칙의 대상 앱(패키지) 목록. 비면 모든 앱 */
    val apps: String = "[]",
    val matchAll: Boolean = false,
    val ignoreCase: Boolean = false,
    val wildcard: Boolean = false,
    val allowOtp: Boolean = false,
    /** 단어 바꾸기 쌍 목록 */
    val replacements: String = "[]",
    val regexReplace: Boolean = false,
    val removeEmptyLine: Boolean = false,
    /** 전달 메시지 템플릿 */
    val template: String = "",
    val schedule: String = ActiveHours.DEFAULT,
    val simInSlot: Int = -1,
    val simOutSlot: Int = -1,
    val position: Int = 0,
    val delayEnabled: Boolean = false,
    /** 지연 전달 분. [delayEnabled] 가 켜져 있고 0 보다 클 때만 쓴다 */
    @ColumnInfo(defaultValue = "0") val delayMinutes: Long = 0,
    val notifyResult: Boolean = true,
    val saveHistory: Boolean = true,
    /** 알림 규칙(level 3 이하)이 전달 본문에 이어 붙일 알림 필드 */
    val useText: Boolean = false,
    val useSubText: Boolean = false,
    val useBigText: Boolean = false,
    val useMessageText: Boolean = false,
    /** level 3 이하: 비어 있는 알림은 보내지 않기 */
    val skipEmptyNoti: Boolean = false,
    /** level 4 알림 규칙의 "보낼 내용" 구성. 비면 자동, 아니면 고른 항목의 JSON 배열 */
    @ColumnInfo(defaultValue = "''") val composition: String = "",
) {
    val isNotification get() = kind == RuleKind.NOTIFICATION

    /** 알림 규칙 level 4(보낼 내용 구성 방식). 그 아래는 예전 방식 그대로이며 자동 승격하지 않는다 */
    val isV4 get() = kind == RuleKind.NOTIFICATION && level >= 4
}

/** 전달 건: 규칙의 수신처 하나에 보내는 문자 한 통. */
@Entity(tableName = "deliveries")
data class DeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recvTime: Long,
    val sendTime: Long = 0,
    /** 최종 전달 본문 */
    val content: String,
    /** 템플릿 적용 전 본문 */
    val rawBody: String = "",
    val sender: String,
    val recipient: String,
    val status: Int,
    val ruleName: String = "",
    /** 규칙의 "전송 결과 알림" · "전송 기록 저장" (전달 건을 만들 때의 값) */
    val notifyResult: Boolean = true,
    val saveHistory: Boolean = true,
    val simOutName: String = "",
    val simInName: String = "",
    val ruleId: Long = 0,
    val simInSlot: Int = -1,
    val simOutSlot: Int = -1,
    /** {"type":"sms","msg":"…"} */
    val detail: String = "",
    val lastRetryTime: Long = 0,
    val retryCount: Int = 0,
    val logId: Long = 0,
    /** 상대 폰 도착 보고를 모은 값(`DeliveryTally` 저장 형식). 보고가 없으면 빈 문자열 */
    @ColumnInfo(defaultValue = "''") val deliveryTally: String = "",
    /** 도착 상태가 마지막으로 바뀐(보고를 받은) 시각 */
    @ColumnInfo(defaultValue = "0") val deliveryTime: Long = 0,
)

/** 수신 기록: 받은 메시지 한 통. */
@Entity(tableName = "received")
data class ReceivedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val receiveTime: Long,
    val address: String,
    val body: String,
    val simSlot: Int = -1,
    val kind: String = "sms",
    // 아래는 "규칙 다시 적용"이 이벤트를 복원하는 데 쓰는 열이다.
    /** SMS 는 본문, 알림은 text 필드 */
    @ColumnInfo(defaultValue = "''") val content: String = "",
    @ColumnInfo(defaultValue = "''") val subText: String = "",
    @ColumnInfo(defaultValue = "''") val bigText: String = "",
    @ColumnInfo(defaultValue = "''") val messageText: String = "",
    @ColumnInfo(defaultValue = "''") val oldText: String = "",
    /** 알림 패키지(요약 알림 접두어 포함). SMS 는 빈 값 */
    @ColumnInfo(defaultValue = "''") val packageName: String = "",
    @ColumnInfo(defaultValue = "''") val extraContent: String = "",
    /** 알림 앱의 versionCode. SMS·조회 실패는 null */
    val appVersionCode: Long? = null,
)

/** 수신 기록: 메시지가 규칙별로 어떻게 처리됐는지 단계 로그. */
@Entity(tableName = "received_step")
data class ReceivedStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receivedId: Long,
    val time: Long,
    val ruleName: String,
    val code: String,
)

/** 워커에 넘길 수신 이벤트. 행 번호만 Worker 입력으로 넘긴다. */
@Entity(tableName = "pending_events")
data class PendingEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val json: String,
    val createdAt: Long,
)

/** 전역 스팸 차단 규칙. type 0 전화번호, 1 알림 제목, 2 본문. */
@Entity(tableName = "spam_rules")
data class SpamRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,
    val data: String,
)

/** 최근 60초 전송 시도 기록. 중복·반복 전송 판정에 쓴다. */
@Entity(tableName = "recent_sends")
data class RecentSend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiveTime: Long,
    val sendTime: Long,
    /** 템플릿 적용 전 본문 */
    val content: String,
    val sender: String,
    val recipient: String,
)

/** 반복 전송 차단 목록. 1시간 뒤 풀린다. */
@Entity(tableName = "repeat_blocks")
data class RepeatBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipient: String,
    val time: Long,
)

/** 중복 알림 거르기용으로 최근에 본 알림. */
@Entity(tableName = "noti_seen")
data class NotiSeenRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val text: String,
    val bigText: String,
    val messageText: String,
    val rcs: Boolean,
    val notiKey: String,
)
