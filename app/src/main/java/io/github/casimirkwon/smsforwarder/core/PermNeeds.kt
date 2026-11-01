package io.github.casimirkwon.smsforwarder.core

/**
 * 이벤트 처리 전 권한 점검(서비스 onStartCommand 의 호출 형태 = 첫 부족 권한 코드 반환).
 * 코드는 권한 비트: 1=전화 상태, 2=문자 수신·읽기, 4=문자 발송, 16=연락처, 32=알림 접근. 0 이면 문제 없음.
 * 규칙마다(꺼진 규칙도 훑는다): 알림 규칙이 아니면 수신 권한(2) → 알림 규칙이면 알림 접근(32) →
 * 수신처가 있으면 발송(4)·전화 상태(1) → 템플릿에 {연락처} 가 있으면 연락처(16, 마지막에 돌려줌).
 */
object PermNeeds {
    const val PHONE_STATE = 1
    const val SMS_RECEIVE = 2
    const val SMS_SEND = 4
    const val CONTACTS = 16
    const val NOTIFICATION_ACCESS = 32

    data class Have(
        val phoneState: Boolean,
        val smsReceive: Boolean,
        val smsSend: Boolean,
        val contacts: Boolean,
        val notificationAccess: Boolean = true,
    )

    data class FilterNeed(val hasDestination: Boolean, val usesContactName: Boolean, val isNotification: Boolean = false)

    /**
     * 첫 부족 권한 코드. [checkNotificationAccess] 가 false 면 알림 접근은 있는 것으로 본다 — 문자(SMS·MMS)·지연 전달
     * 경로는 알림 접근이 없어도 계속 진행해야 하기 때문이다(알림 규칙 전달만 [NotiAccessWarn] 으로 따로 다룬다).
     */
    fun firstMissing(have: Have, filters: List<FilterNeed>, checkNotificationAccess: Boolean = true): Int {
        var contactsMissing = 0
        val notiAccess = have.notificationAccess || !checkNotificationAccess
        for (f in filters) {
            if (!have.smsReceive && !f.isNotification) return SMS_RECEIVE
            if (!notiAccess && f.isNotification) return NOTIFICATION_ACCESS
            if (f.hasDestination) {
                if (!have.smsSend) return SMS_SEND
                if (!have.phoneState) return PHONE_STATE
            }
            if (f.usesContactName && !have.contacts) contactsMissing = CONTACTS
        }
        return contactsMissing
    }
}

/**
 * 알림 접근이 빠졌을 때의 경고 알림 판정. 경고는 한 번만 띄우고, 권한이 돌아올 때까지 다시 띄우지 않으며,
 * 돌아오면 상태를 초기화해 다음에 또 빠지면 한 번 더 띄운다.
 */
object NotiAccessWarn {
    /** [show] 지금 경고를 띄울지, [warned] 판정 뒤 저장할 "이미 경고함" 상태 */
    data class Decision(val show: Boolean, val warned: Boolean)

    /**
     * @param hasAccess 알림 접근이 허용돼 있는가
     * @param needsAccess 켜진 알림 규칙이 있어 알림 접근이 필요한가
     * @param warned 이미 경고를 띄웠는가(저장된 상태)
     */
    fun decide(hasAccess: Boolean, needsAccess: Boolean, warned: Boolean): Decision = when {
        hasAccess -> Decision(show = false, warned = false)
        !needsAccess -> Decision(show = false, warned = warned)
        else -> Decision(show = !warned, warned = true)
    }
}
