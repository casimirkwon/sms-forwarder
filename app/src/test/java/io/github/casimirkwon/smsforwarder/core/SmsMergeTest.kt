package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsMergeTest {
    @Test fun singlePartIsKept() {
        val r = SmsMerge.mergeParts(listOf(SmsPart("010-1111-2222", "안녕", 1000)))
        assertEquals(listOf(IncomingSms("010-1111-2222", "안녕", 1000)), r)
    }

    @Test fun partsOfSameAddressAreConcatenatedInArrivalOrder() {
        val r = SmsMerge.mergeParts(
            listOf(SmsPart("A", "첫번째 ", 1), SmsPart("A", "두번째 ", 2), SmsPart("A", "세번째", 3)),
        )
        assertEquals(1, r.size)
        assertEquals("첫번째 두번째 세번째", r[0].body)
        // 시각은 마지막 PDU 의 시각
        assertEquals(3L, r[0].timeMillis)
    }

    @Test fun differentAddressesStaySeparateInFirstSeenOrder() {
        val r = SmsMerge.mergeParts(
            listOf(SmsPart("A", "a1", 1), SmsPart("B", "b1", 2), SmsPart("A", "a2", 3), SmsPart("B", "b2", 4)),
        )
        assertEquals(listOf(IncomingSms("A", "a1a2", 3), IncomingSms("B", "b1b2", 4)), r)
    }

    @Test fun emptyInputGivesEmptyList() {
        assertEquals(emptyList<IncomingSms>(), SmsMerge.mergeParts(emptyList()))
    }
}
