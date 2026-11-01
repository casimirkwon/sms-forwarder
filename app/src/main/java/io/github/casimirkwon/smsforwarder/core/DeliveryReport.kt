package io.github.casimirkwon.smsforwarder.core

/**
 * 수신 확인(도착 보고) 판정. 통신사가 지원할 때만 상대 폰에 도착했다는 보고가 오며, 이 결과는 이력 화면에 표시만 한다 —
 * 발송 접수 판정([SentResult])·재시도·결과 알림에는 영향을 주지 않는다.
 */
object DeliveryReport {
    /** 도착 보고 인텐트 액션(보내는 쪽 PendingIntent 에 붙인다) */
    const val ACTION = "io.github.casimirkwon.smsforwarder.action.DELIVERED"

    /** 도착 보고 인텐트의 결과 코드가 정상일 때의 값(Activity.RESULT_OK) */
    const val RESULT_OK = -1

    /** 도착 보고 PDU 의 상태 값 구간: 0~31 도착, 32~63 아직 시도 중(최종 아님), 64 이상 실패 */
    const val STATUS_PENDING = 32
    const val STATUS_FAILED = 64

    /** 보고 한 건의 해석 */
    enum class Part { DELIVERED, FAILED, PENDING, IGNORED }

    /**
     * 보고 한 건 해석. 결과 코드가 정상이 아니면 실패, 정상이면 [status](보고 PDU 에서 읽은 상태 값)로 가른다.
     * 상태를 읽을 수 없으면 도착으로 단정하지 않고 무시한다.
     */
    fun classify(resultCode: Int, status: Int?): Part = when {
        resultCode != RESULT_OK -> Part.FAILED
        status == null || status < 0 -> Part.IGNORED
        status >= STATUS_FAILED -> Part.FAILED
        status >= STATUS_PENDING -> Part.PENDING
        else -> Part.DELIVERED
    }

    /** 보고 인텐트의 data 값: `전달건/부분번호/전체부분수` */
    data class Key(val rowId: Long, val part: Int, val total: Int)

    fun key(rowId: Long, part: Int, total: Int) = "$rowId/$part/$total"

    fun parseKey(text: String?): Key? {
        val p = text?.split("/") ?: return null
        if (p.size != 3) return null
        val rowId = p[0].toLongOrNull() ?: return null
        val part = p[1].toIntOrNull() ?: return null
        val total = p[2].toIntOrNull() ?: return null
        if (total < 1 || part !in 0 until total) return null
        return Key(rowId, part, total)
    }
}

/** 전달 건의 도착 상태 */
enum class DeliveryState { NONE, PARTIAL, DELIVERED, FAILED }

/**
 * 부분 문자별 도착 보고를 모은 값. 여러 부분 문자는 모든 부분이 도착 보고를 받았을 때 [DeliveryState.DELIVERED],
 * 하나라도 실패 보고면 [DeliveryState.FAILED], 그 사이는 [DeliveryState.PARTIAL] 이다.
 * 같은 부분의 보고가 겹쳐 와도 한 번으로 세고, 나중 보고가 앞선 보고를 대신한다.
 */
data class DeliveryTally(val total: Int = 0, val ok: Set<Int> = emptySet(), val fail: Set<Int> = emptySet()) {
    val state: DeliveryState
        get() = when {
            fail.isNotEmpty() -> DeliveryState.FAILED
            total > 0 && ok.size >= total -> DeliveryState.DELIVERED
            ok.isNotEmpty() -> DeliveryState.PARTIAL
            else -> DeliveryState.NONE
        }

    /**
     * 보고 한 건을 더한다. 시도 중([DeliveryReport.Part.PENDING])·무시는 그대로 돌려준다.
     * 전체 부분 수가 앞서 기록한 것과 다르면(다시 보낸 건의 옛 보고) 새 값으로 처음부터 센다.
     */
    fun with(report: DeliveryReport.Part, index: Int, partTotal: Int): DeliveryTally {
        if (report != DeliveryReport.Part.DELIVERED && report != DeliveryReport.Part.FAILED) return this
        val base = if (total != 0 && total != partTotal) DeliveryTally() else this
        return when (report) {
            DeliveryReport.Part.DELIVERED -> base.copy(total = partTotal, ok = base.ok + index, fail = base.fail - index)
            else -> base.copy(total = partTotal, ok = base.ok - index, fail = base.fail + index)
        }
    }

    /** 저장 형식: `전체;도착한 부분들;실패한 부분들` (예: `3;0,2;1`). 비어 있으면 빈 문자열. */
    fun encode(): String =
        if (total == 0) "" else "$total;${ok.sorted().joinToString(",")};${fail.sorted().joinToString(",")}"

    companion object {
        fun decode(text: String?): DeliveryTally {
            val p = text?.split(";") ?: return DeliveryTally()
            if (p.size != 3) return DeliveryTally()
            val total = p[0].toIntOrNull() ?: return DeliveryTally()
            if (total < 1) return DeliveryTally()
            fun set(s: String) = s.split(",").mapNotNull { it.toIntOrNull() }.filter { it in 0 until total }.toSet()
            return DeliveryTally(total, set(p[1]), set(p[2]))
        }
    }
}
