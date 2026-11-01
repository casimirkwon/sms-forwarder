package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.ReceivedEntity

/**
 * 앱 이력 ↔ 수신 이벤트 변환과 "필터 다시 적용" 판단.
 *
 * 수신한 메시지를 처리할 때 이력 행에 알림 필드(content·subText·bigText·messageText·oldText·
 * packageName·extraContent·appVersionCode)까지 남기고, 이력 항목의 메뉴 "필터 다시 적용"이 그 행으로 이벤트를 복원해
 * 수신 때와 같은 판정 함수(checkMsg)를 다시 부른다. 새 이력 행은 만들지 않고 같은 행에 단계 로그를 덧붙인다.
 */
object Reapply {
    const val KIND_SMS = "sms"
    const val KIND_NOTI = "noti"

    /** 알림 이력 항목의 목록 표시용 본문: `[패키지] 첫 비어 있지 않은 알림 문구` */
    fun notiBody(n: IncomingNotification): String {
        val shown = listOf(n.text, n.bigText, n.messageText, n.oldText).firstOrNull { !NotiText.isBlank(it) } ?: ""
        return "[${n.packageName.removePrefix(NotiText.SUMMARY_PREFIX)}] $shown"
    }

    fun fromSms(sms: IncomingSms, now: Long, simSlot: Int): ReceivedEntity = ReceivedEntity(
        time = now, receiveTime = sms.timeMillis, address = sms.address, body = sms.body, simSlot = simSlot,
        kind = KIND_SMS, content = sms.body,
    )

    fun fromNotification(n: IncomingNotification, now: Long, appVersionCode: Long?): ReceivedEntity = ReceivedEntity(
        time = now, receiveTime = n.timeMillis, address = n.title, body = notiBody(n), simSlot = -1, kind = KIND_NOTI,
        content = n.text, subText = n.subText, bigText = n.bigText, messageText = n.messageText, oldText = n.oldText,
        packageName = n.packageName, extraContent = n.extraContent, appVersionCode = appVersionCode,
    )

    /** 이 이력 항목에 "필터 다시 적용"을 보여 줄지. 알림 필드를 보관하기 전(스키마 5 이하)에 쌓인 알림 행은 복원할 수 없어 뺀다. */
    fun canReapply(h: ReceivedEntity): Boolean = if (h.kind == KIND_NOTI) h.packageName.isNotEmpty() else true

    /** 이력 행에서 SMS 로 복원하는 이벤트(content, inNumber, 수신 시각). 스키마 5 이하 행은 content 가 비어 body 로 대신한다. */
    fun toSms(h: ReceivedEntity): IncomingSms = IncomingSms(h.address, h.content.ifEmpty { h.body }, h.receiveTime)

    /** 이력 행에서 알림으로 복원하는 이벤트. 이미지는 보관하지 않으므로 넘기지 않는다. */
    fun toNotification(h: ReceivedEntity): IncomingNotification = IncomingNotification(
        title = h.address, text = h.content, subText = h.subText, bigText = h.bigText, messageText = h.messageText,
        oldText = h.oldText, timeMillis = h.receiveTime, packageName = h.packageName, imageCount = 0, extraContent = h.extraContent,
    )

    /**
     * 처리 결과: 수신처로 보낼 전달 건을 하나라도 곧바로 대기열에 넣었는지(이력 단계 "19_<채널>" 이 남은 경우).
     * 지연 전달로 예약만 한 규칙은 세지 않는다.
     */
    fun anyQueued(outcomes: Iterable<Boolean>): Boolean = outcomes.any { it }
}
