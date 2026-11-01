package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ConditionTextTest {
    @Test fun trailingAndRepeatedOrLinesAreHandledLikeBefore() {
        // 끝의 "또는" 은 버리고, 가운데의 연속 "또는" 은 빈 묶음(= 항상 통과)을 만든다
        assertEquals(listOf(listOf(Term("a"))), Conditions.decode(ConditionText.fromLines("a\n또는")))
        assertEquals(listOf(listOf(Term("a")), emptyList(), listOf(Term("b"))), Conditions.decode(ConditionText.fromLines("a\n또는\n또는\nb")))
        assertEquals(listOf(emptyList(), listOf(Term("a"))), Conditions.decode(ConditionText.fromLines("또는\na")))
    }

    @Test fun linesToTokensAndBack() {
        val text = "카드\n!광고\n또는\n입금"
        val rule = ConditionText.fromLines(text)
        assertEquals(
            listOf(listOf(Term("카드"), Term("광고", exclude = true)), listOf(Term("입금"))),
            Conditions.decode(rule),
        )
        assertEquals(text, ConditionText.toLines(rule))
    }

    @Test fun blankIsEmptyRule() {
        assertEquals(Conditions.EMPTY, ConditionText.fromLines("  \n "))
        assertEquals("", ConditionText.toLines(""))
        assertEquals("", ConditionText.toLines(Conditions.EMPTY))
    }
}
