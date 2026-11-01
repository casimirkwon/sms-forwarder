package io.github.casimirkwon.smsforwarder.core

/** PDU 한 조각에서 꺼낸 값. */
data class SmsPart(val address: String, val body: String, val timeMillis: Long)

/** 합쳐진 수신 문자 한 통. */
data class IncomingSms(val address: String, val body: String, val timeMillis: Long)

object SmsMerge {
    /**
     * 발신주소별로 PDU 도착 순서대로 본문을 이어붙이고,
     * 시각은 그 주소의 마지막 PDU 의 시각을 쓴다. UDH 시퀀스 번호는 보지 않는다.
     * 한 인텐트에 서로 다른 발신주소가 있으면 주소별로 한 통씩 돌려준다(처음 나온 주소 순).
     */
    fun mergeParts(parts: List<SmsPart>): List<IncomingSms> {
        val merged = LinkedHashMap<String, IncomingSms>()
        for (p in parts) {
            val prev = merged[p.address]
            merged[p.address] = if (prev == null) {
                IncomingSms(p.address, p.body, p.timeMillis)
            } else {
                IncomingSms(p.address, prev.body + p.body, p.timeMillis)
            }
        }
        return merged.values.toList()
    }
}
