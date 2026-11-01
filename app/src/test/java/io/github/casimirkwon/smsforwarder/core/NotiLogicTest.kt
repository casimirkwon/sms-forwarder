package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.github.casimirkwon.smsforwarder.db.RuleEntity
import io.github.casimirkwon.smsforwarder.db.RuleKind
import org.junit.Test

/** 알림 필드 추출·전달 내용 구성·대상 앱 판정. */
class NotiLogicTest {
    private fun noti(
        title: String = "제목", text: String = "본문", sub: String = "서브", big: String = "긴 본문", msg: String = "메시지", old: String = "옛 본문",
    ) = IncomingNotification(title, text, sub, big, msg, old, 1000L, "com.x")

    // --- 필드 추출

    @Test fun 제목의_null_문자열은_빈_문자열이고_격리_문자는_지운다() {
        assertEquals("", NotiText.cleanTitle("null"))
        assertEquals("홍길동", NotiText.cleanTitle("⁨홍길동⁩"))
        assertEquals("a null", NotiText.cleanTitle("a null"))
    }

    @Test fun 무시할_알림은_자기_앱_systemui_진행중_포그라운드서비스() {
        assertTrue(NotiText.shouldIgnore("me", "me", false, 0))
        assertTrue(NotiText.shouldIgnore("com.android.systemui", "me", false, 0))
        assertTrue(NotiText.shouldIgnore("com.x", "me", true, 0))
        assertTrue(NotiText.shouldIgnore("com.x", "me", false, 64))
        // FLAG_LOCAL_ONLY(256)나 그룹 요약(512)은 그 자체로는 무시하지 않는다
        assertFalse(NotiText.shouldIgnore("com.x", "me", false, 256))
        assertFalse(NotiText.shouldIgnore("com.x", "me", false, 512))
    }

    @Test fun 비어있음은_개행만_있어도_비어있고_공백은_비어있지_않다() {
        assertTrue(NotiText.isBlank(null))
        assertTrue(NotiText.isBlank(""))
        assertTrue(NotiText.isBlank("\n\n"))
        assertFalse(NotiText.isBlank(" "))
    }

    @Test fun 다섯_필드가_모두_비어야_버린다_subText는_보지_않는다() {
        assertTrue(NotiText.allBlank("", "", "", "", ""))
        assertFalse(NotiText.allBlank("t", "", "", "", ""))
        assertFalse(NotiText.allBlank("", "", "", "", "old"))
    }

    @Test fun oldText_스타일_없으면_android_text() {
        assertEquals("hello", NotiText.oldText(null, "hello", "", null))
        assertEquals("hello", NotiText.oldText("android.app.Notification\$MediaStyle", "hello", "", null))
        assertEquals("", NotiText.oldText(null, "", "", null))
    }

    @Test fun oldText_InboxStyle_은_빈_문자열() {
        assertEquals("", NotiText.oldText("android.app.Notification\$InboxStyle", "a", "b", null))
    }

    @Test fun oldText_MessagingStyle_은_마지막_메시지_text_extra가_없으면_빈_문자열() {
        val t = "android.app.Notification\$MessagingStyle"
        assertEquals("마지막", NotiText.oldText(t, "요약", "", "마지막"))
        assertEquals("", NotiText.oldText(t, "요약", "", null))
        // 메시지에 text 가 없으면 String.valueOf 결과 "null" 이 그대로 나온다
        assertEquals("null", NotiText.oldText(t, "요약", "", "null"))
    }

    @Test fun oldText_BigTextStyle_긴쪽을_쓰고_포함하지_않으면_줄바꿈으로_잇는다() {
        val t = "android.app.Notification\$BigTextStyle"
        // text 가 더 길고 bigText 를 포함: text 만
        assertEquals("abcdef", NotiText.oldText(t, "abcdef", "cde", null))
        // text 가 더 길지만 bigText 를 포함하지 않음: text + \n + bigText
        assertEquals("abcdef\nxy", NotiText.oldText(t, "abcdef", "xy", null))
        // bigText 가 더 길고 text 를 포함: bigText 만
        assertEquals("hello world", NotiText.oldText(t, "world", "hello world", null))
        // bigText 가 더 길지만 text 를 포함하지 않음: text + \n + bigText
        assertEquals("zz\nhello world", NotiText.oldText(t, "zz", "hello world", null))
        // 길이가 같으면 bigText 쪽 가지: 서로 포함 안 하면 text\nbigText, 같으면 bigText 만
        assertEquals("ab\ncd", NotiText.oldText(t, "ab", "cd", null))
        assertEquals("ab", NotiText.oldText(t, "ab", "ab", null))
        assertEquals("only big", NotiText.oldText(t, "", "only big", null))
    }

    // --- 전달 내용 구성

    @Test fun 고른_필드가_없으면_oldText() {
        assertEquals("옛 본문", NotiRule.compose(RuleEntity(), noti()))
        assertEquals("옛 본문", NotiRule.compose(RuleEntity(kind = RuleKind.NOTIFICATION), noti()))
    }

    @Test fun 고른_필드를_text_sub_big_message_순으로_줄바꿈으로_잇는다() {
        val all = RuleEntity(useText = true, useSubText = true, useBigText = true, useMessageText = true)
        assertEquals("본문\n서브\n긴 본문\n메시지", NotiRule.compose(all, noti()))
        assertEquals("긴 본문\n메시지", NotiRule.compose(RuleEntity(useBigText = true, useMessageText = true), noti()))
        assertEquals("서브", NotiRule.compose(RuleEntity(useSubText = true), noti()))
    }

    @Test fun 앞쪽_필드가_비어_있으면_줄바꿈을_넣지_않는다() {
        val o = RuleEntity(useText = true, useBigText = true)
        assertEquals("긴 본문", NotiRule.compose(o, noti(text = "")))
        // 앞에 내용이 있으면 뒤 필드가 비어도 줄바꿈은 붙는다
        assertEquals("본문\n", NotiRule.compose(o, noti(big = "")))
    }

    // --- 대상 앱

    @Test fun 대상_앱이_비어_있으면_모든_앱() {
        assertTrue(NotiRule.packageMatches("", "com.any"))
        assertTrue(NotiRule.packageMatches("[]", "com.any"))
    }

    @Test fun 대상_앱은_목록에_있는_패키지만() {
        val p = NotiRule.serializePackages(listOf("com.a", "com.b"))
        assertEquals("[\"com.a\",\"com.b\"]", p)
        assertTrue(NotiRule.packageMatches(p, "com.b"))
        assertFalse(NotiRule.packageMatches(p, "com.c"))
        assertFalse(NotiRule.packageMatches(p, "com.a\",\"com.b"))
    }

    @Test fun 요약_알림_접두어는_떼어서_규칙에_견준다() {
        assertEquals("com.a", NotiRule.strippedPackage(NotiText.SUMMARY_PREFIX + "com.a"))
        assertEquals("com.a", NotiRule.strippedPackage("com.a"))
    }

    @Test fun 요약_알림은_모든_앱_허용이_켜져_있거나_허용_목록에_있는_앱만_전달된다() {
        assertTrue(NotiRule.summaryAllowed(true, "", "com.a"))
        assertTrue(NotiRule.summaryAllowed(true, NotiRule.serializePackages(listOf("com.b")), "com.a"))
        assertFalse(NotiRule.summaryAllowed(false, "", "com.a"))
        val list = NotiRule.serializePackages(listOf("com.a", "com.c"))
        // 목록에 있는 앱은 전달, 없는 앱은 건너뜀(이름이 접두어로만 겹치는 앱도 건너뜀)
        assertTrue(NotiRule.summaryAllowed(false, list, "com.a"))
        assertTrue(NotiRule.summaryAllowed(false, list, "com.c"))
        assertFalse(NotiRule.summaryAllowed(false, list, "com.b"))
        assertFalse(NotiRule.summaryAllowed(false, list, "com"))
        assertFalse(NotiRule.summaryAllowed(false, NotiRule.serializePackages(listOf("com.ab")), "com.a"))
        // 요약 표식이 붙은 이름이 들어와도 같은 앱으로 본다
        assertTrue(NotiRule.summaryAllowed(false, list, NotiText.SUMMARY_PREFIX + "com.a"))
    }

    // --- 제목·본문 조건

    @Test fun 알림_제목_조건은_포함과_제외_그룹_판정() {
        val rule = ConditionText.fromLines("카카오\n!광고")
        assertTrue(ConditionMatch.matchText(rule, "카카오톡 새 메시지", ignoreCase = false, wildcard = false))
        assertFalse(ConditionMatch.matchText(rule, "카카오 광고", ignoreCase = false, wildcard = false))
        assertFalse(ConditionMatch.matchText(rule, "다른 앱", ignoreCase = false, wildcard = false))
        // OR 그룹
        val or = ConditionText.fromLines("a\n또는\nb")
        assertTrue(ConditionMatch.matchText(or, "xxb", false, false))
        assertFalse(ConditionMatch.matchText(or, "xxc", false, false))
        // 대소문자 무시 + 와일드카드(전체 일치)
        assertTrue(ConditionMatch.matchText(ConditionText.fromLines("ABC*"), "abcdef", ignoreCase = true, wildcard = true))
        assertFalse(ConditionMatch.matchText(ConditionText.fromLines("ABC*"), "xabcdef", ignoreCase = true, wildcard = true))
    }
}
