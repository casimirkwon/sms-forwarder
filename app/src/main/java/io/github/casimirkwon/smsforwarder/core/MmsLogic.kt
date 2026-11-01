package io.github.casimirkwon.smsforwarder.core

/**
 * MMS 수신 처리의 순수 로직: WAP push PDU 해석, 받은편지함 행 훑기·재시도 간격, 처리한 MMS 기록, 파트 읽기 규칙.
 */

/** WAP_PUSH_RECEIVED 의 data(MMS m-notification-ind PDU)에서 content-location 을 꺼낸다(필요한 헤더만 해석). */
object WapPushParser {
    private const val HDR_MESSAGE_TYPE = 0x8C // 140
    private const val HDR_CONTENT_LOCATION = 0x83 // 131
    private const val MSG_TYPE_NOTIFICATION_IND = 130

    /**
     * 메시지 종류(0x8C)가 130(m-notification-ind)이고 Content-Location(0x83)이 있으면 그 문자열. 아니면 null.
     * 헤더는 `필드 id(≥0x80) + 값` 의 나열이다. 값은 WSP 규칙: 0~30 = 값 길이, 31 = uintvar 길이, 32~127 = 널 종료
     * 문자열, 128~255 = 짧은 정수. 모르는 헤더는 이 규칙으로 건너뛴다. 본문(헤더 id 가 아닌 바이트)을 만나면 멈춘다.
     */
    fun contentLocation(data: ByteArray?): String? {
        if (data == null) return null
        var i = 0
        var type = -1
        var location: String? = null
        while (i < data.size) {
            val id = data[i].toInt() and 0xFF
            if (id < 0x80) break
            i++
            when (id) {
                HDR_MESSAGE_TYPE -> {
                    if (i >= data.size) return null
                    type = data[i].toInt() and 0xFF // 값 바이트를 그대로 쓴다(0x82 = 130)
                    i++
                }
                HDR_CONTENT_LOCATION -> {
                    val end = textEnd(data, i) ?: return null
                    var start = i
                    if (start < end && (data[start].toInt() and 0xFF) == 0x7F) start++ // 따옴표 문자
                    location = String(data, start, end - start, Charsets.ISO_8859_1)
                    i = end + 1
                }
                else -> i = skipValue(data, i) ?: return null
            }
        }
        return if (type == MSG_TYPE_NOTIFICATION_IND) location else null
    }

    /** i 위치부터 널 종료 문자열의 널 위치. 없으면 null. */
    private fun textEnd(d: ByteArray, from: Int): Int? {
        var j = from
        while (j < d.size) {
            if (d[j].toInt() == 0) return j
            j++
        }
        return null
    }

    private fun skipValue(d: ByteArray, at: Int): Int? {
        if (at >= d.size) return null
        val b = d[at].toInt() and 0xFF
        return when {
            b < 31 -> if (at + 1 + b <= d.size) at + 1 + b else null
            b == 31 -> {
                var j = at + 1
                var len = 0
                while (j < d.size) {
                    val c = d[j].toInt() and 0xFF
                    len = (len shl 7) or (c and 0x7F)
                    j++
                    if (c and 0x80 == 0) break
                }
                if (j + len <= d.size) j + len else null
            }
            b < 128 -> textEnd(d, at)?.plus(1)
            else -> at + 1
        }
    }
}

/** content://mms/inbox 한 행(_id, date(초), sub_id). */
data class MmsRow(val id: String, val date: Long, val subId: String?)

/** MMS 행의 준비 상태: 수신함 행이 생겼어도 파트·보낸이가 저장 전일 수 있다. */
enum class MmsReadiness { READY, NO_SENDER, NO_PARTS }

/**
 * MMS 읽기 재시도·준비 점검·기준 시각·중복 판정.
 * 시도 횟수 상한 대신 수신 후 경과 시간으로 간격·종료를 정한다.
 */
object MmsInbox {
    /** WAP push 수신 뒤 첫 시도까지 기다리는 시간(SmsReceiver 3초 → 서비스 핸들러 3초) */
    const val FIRST_DELAY_MS = 3_000L

    /** 수신 후 이 시간 안(<30초)이면 짧은 간격, 이후는 긴 간격. content-location 조건도 이 안에서만 쓴다. */
    const val FAST_WINDOW_MS = 30_000L
    const val FAST_DELAY_MS = 6_000L
    const val SLOW_DELAY_MS = 15_000L

    /** 시도가 이 값 이상이고 (경과+간격)이 [GIVE_UP_MS] 를 넘으면 마지막 시도로 끝낸다. */
    const val FINAL_MIN_ATTEMPT = 3
    const val GIVE_UP_MS = 120_000L

    /** FeatureFlags.MMS_READY_CHECK && DUPLICATED_MMS (둘 다 기본 true) */
    const val READY_CHECK = true

    /** 보낸이 자리표시값(저장 전 기본 주소) */
    private const val SENDER_TOKEN = "insert-address-token"

    /** 수신 후 경과 시간(음수는 0) */
    fun elapsedMs(nowMs: Long, initTimeMs: Long) = maxOf(0L, nowMs - initTimeMs)

    /** 이번 시도 뒤 다음 시도까지 기다릴 시간. null 이면 이번이 마지막 시도다. */
    fun nextDelayMs(attempt: Int, elapsedMs: Long): Long? {
        val interval = if (elapsedMs < FAST_WINDOW_MS) FAST_DELAY_MS else SLOW_DELAY_MS
        return if (attempt >= FINAL_MIN_ATTEMPT && elapsedMs + interval > GIVE_UP_MS) null else interval
    }

    /** content-location 조건은 수신 후 30초 안에서만 쓴다. */
    fun urlForElapsed(elapsedMs: Long, url: String?): String? = if (url == null || elapsedMs >= FAST_WINDOW_MS) null else url

    /** 파트 수·보낸이로 준비 상태 판정. 점검이 꺼져 있으면 항상 READY. */
    fun readiness(partCount: Int, sender: String?, readyCheck: Boolean = READY_CHECK): MmsReadiness = when {
        !readyCheck -> MmsReadiness.READY
        partCount <= 0 -> MmsReadiness.NO_PARTS
        sender == null || sender.isBlank() || sender == SENDER_TOKEN -> MmsReadiness.NO_SENDER
        else -> MmsReadiness.READY
    }

    /** 전달해도 되는 상태인가: READY, 또는 마지막 시도에서의 NO_SENDER(보낸이 없이라도 전달). */
    fun canDeliver(state: MmsReadiness, isFinal: Boolean) =
        state == MmsReadiness.READY || (state == MmsReadiness.NO_SENDER && isFinal)

    /**
     * 처리 기준 시각(초). `max(저장한 마지막 시각, 수신 시각-60초)`; url 로 찾을 때는 0, 아니면 준비 점검이 켜져 있을 때
     * `min(기준, 수신 시각-5초)`(Adjust3); 마지막으로 `max(기준, 지금-900초)`(ADJUST_MMS_PIVOT_TIME 기본 true).
     */
    fun pivotSec(initTimeMs: Long, savedLast: Long?, hasUrl: Boolean, nowMs: Long, readyCheck: Boolean = READY_CHECK): Long {
        val initSec = initTimeMs / 1000
        val floor = initSec - 60
        var pivot = maxOf(savedLast ?: floor, floor)
        if (hasUrl) pivot = 0
        else if (readyCheck) pivot = minOf(pivot, initSec - 5)
        pivot = maxOf(pivot, nowMs / 1000 - 900)
        return pivot
    }

    fun key(row: MmsRow) = "|${row.id}|${row.date}|${row.subId}"

    /**
     * 훑기 결과. [handled] 처리한 행이 있음, [maxProcessed] 처리·이미 처리·기준 이하 행의 최대 date(-1 = 없음),
     * [waitingMin] 아직 준비 안 된 행의 최소 date(-1 = 없음).
     */
    data class Scan(val handled: Boolean, val maxProcessed: Long, val waitingMin: Long) {
        val hasWaiting get() = waitingMin > 0

        /** 저장할 "마지막 시각" = min(처리한 최대 date, 기다리는 최소 date - 1). 0 이하면 저장하지 않는다. */
        val saveTime: Long get() = if (waitingMin > 0) minOf(maxProcessed, waitingMin - 1) else maxProcessed

        /** 끝났나: 처리한 행이 있고 기다리는 행이 없을 때만. */
        val done get() = handled && waitingMin < 0
    }

    /**
     * 받은편지함 행(date 내림차순)을 훑는다. 이미 처리한 키는 건너뛰되 최대 date 에는 반영하고
     * (url 로 찾는 중이면 처리한 것으로 본다), 새 키인데 date 가 기준 이하이면 거기서 멈춘다.
     * 나머지는 [handle] 에 넘기고, 준비돼 처리했으면 true / 아직이면 false. [handle] 예외는 false 로 취급한다.
     */
    fun scan(
        rows: List<MmsRow>,
        pivotSec: Long,
        seen: Set<String>,
        hasUrl: Boolean,
        handle: (MmsRow) -> Boolean,
    ): Scan {
        var handled = false
        var maxProcessed = -1L
        var waitingMin = -1L
        for (r in rows) {
            if (seen.contains(key(r))) {
                maxProcessed = maxOf(maxProcessed, r.date)
                if (hasUrl) handled = true
                continue
            }
            if (r.date <= pivotSec) {
                maxProcessed = maxOf(maxProcessed, r.date)
                break
            }
            val ok = try {
                handle(r)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                maxProcessed = maxOf(maxProcessed, r.date)
                handled = true
            } else {
                waitingMin = if (waitingMin >= 0) minOf(waitingMin, r.date) else r.date
            }
        }
        return Scan(handled, maxProcessed, waitingMin)
    }
}

/**
 * 이미 처리한 MMS 의 기록(키 → 처리 시각 ms). 앱이 강제 종료되거나 다시 시작돼도 같은 MMS 를 두 번 전달하지 않도록
 * 저장소에 문자열([encode])로 남겨 두고 시작할 때 [decode] 로 읽는다. 오래된 것([TTL_MS] 초과)은 버리고,
 * 개수가 [MAX] 를 넘으면 가장 오래 전에 넣은 것부터 버려서 무한히 커지지 않는다.
 */
class HandledMms private constructor(private val entries: LinkedHashMap<String, Long>) {
    companion object {
        /** 보관 개수 상한 */
        const val MAX = 200

        /** 보관 기간(처리 후 7일). 읽기 기준 시각이 지금-15분보다 늘 뒤라서 이보다 오래된 기록은 쓸 일이 없다. */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        fun empty() = HandledMms(LinkedHashMap())

        /** [encode] 로 만든 문자열을 되살린다. 모양이 틀린 줄은 건너뛴다. */
        fun decode(text: String?): HandledMms {
            val map = LinkedHashMap<String, Long>()
            if (!text.isNullOrEmpty()) {
                for (line in text.split("\n")) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0 || tab == line.length - 1) continue
                    val at = line.substring(0, tab).toLongOrNull() ?: continue
                    map[line.substring(tab + 1)] = at
                }
            }
            return HandledMms(map)
        }
    }

    val size get() = entries.size

    fun contains(key: String) = entries.containsKey(key)

    /** 지금 기록에 있는 키(오래된 것부터). 훑기에 넘길 사본이다. */
    fun keys(): Set<String> = LinkedHashSet(entries.keys)

    /** 키를 기록한다. 이미 있으면 false. 기간·개수 상한을 넘은 것은 버린다. */
    fun add(key: String, nowMs: Long): Boolean {
        if (entries.containsKey(key)) return false
        entries[key] = nowMs
        trim(nowMs)
        return true
    }

    /** 기간이 지난 기록을 버리고, 남은 것이 [MAX] 를 넘으면 가장 오래된 것부터 버린다. */
    fun trim(nowMs: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) if (nowMs - it.next().value > TTL_MS) it.remove()
        while (entries.size > MAX) {
            val first = entries.entries.iterator()
            first.next()
            first.remove()
        }
    }

    /** 한 줄에 `처리시각<탭>키` */
    fun encode(): String = entries.entries.joinToString("\n") { "${it.value}\t${it.key}" }
}

/** MMS 파트에서 본문·첨부 수를 얻는 규칙. */
object MmsParts {
    /** 한 파트의 종류: text/plain(본문), 그 밖의 text/…·smil(무시), image/…(첨부 이미지), 나머지(무시) */
    enum class Kind { TEXT, IMAGE, IGNORE }

    fun kind(contentType: String?): Kind = when {
        contentType == "text/plain" -> Kind.TEXT
        contentType == null || contentType.startsWith("text/") || contentType == "application/smil" -> Kind.IGNORE
        contentType.startsWith("image/") -> Kind.IMAGE
        else -> Kind.IGNORE
    }

    /**
     * 파일에 저장된 본문 파트 읽기: 줄을 읽어 이어 붙이되(구분 문자 없음) **첫 빈 줄(또는 끝)에서 멈춘다**.
     */
    fun joinFileLines(lines: Sequence<String?>): String {
        val sb = StringBuilder()
        for (line in lines) {
            if (line.isNullOrEmpty()) break
            sb.append(line)
        }
        return sb.toString()
    }

    /** 본문 파트들(null 은 건너뜀)을 구분 없이 이어 붙인다. 본문 파트가 하나도 없으면 null. */
    fun joinTexts(texts: List<String?>): String? {
        var sb: StringBuilder? = null
        for (t in texts) {
            if (t == null) continue
            if (sb == null) sb = StringBuilder()
            sb.append(t)
        }
        return sb?.toString()
    }
}
