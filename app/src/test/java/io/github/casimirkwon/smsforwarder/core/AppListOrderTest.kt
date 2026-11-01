package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** 대상 앱 목록 정렬·검색. */
class AppListOrderTest {
    private val ko = Locale.KOREAN

    @Test fun 한국어_단말에서는_한글_라벨이_먼저_그다음_그_밖의_문자_마지막이_라틴() {
        assertEquals(0, AppListOrder.scriptRank("카카오톡", ko))
        assertEquals(2, AppListOrder.scriptRank("Chrome", ko))
        assertEquals(1, AppListOrder.scriptRank("日本語", ko))
        assertEquals(1, AppListOrder.scriptRank("123", ko))
        assertEquals(0, AppListOrder.scriptRank("Chrome", Locale.ENGLISH))
    }

    @Test fun 정렬은_문자체계_순위_라벨_패키지_순() {
        val apps = listOf(
            AppItem("Chrome", "com.chrome"), AppItem("카카오톡", "com.kakao"), AppItem("가계부", "com.account"),
            AppItem("Same", "com.b"), AppItem("Same", "com.a"),
        )
        val sorted = apps.sortedWith(AppListOrder.comparator(ko)).map { it.packageName }
        // 한글(가계부 < 카카오톡) → 라틴(Chrome < Same, 라벨이 같으면 패키지 이름 순)
        assertEquals(listOf("com.account", "com.kakao", "com.chrome", "com.a", "com.b"), sorted)
    }

    @Test fun 선택된_앱은_맨_위로_설치안된_선택은_더_위로() {
        val apps = listOf(AppItem("가", "p.a"), AppItem("나", "p.b"), AppItem("다", "p.c"))
        val r = AppListOrder.arrange(apps, setOf("p.c", "p.gone"), ko)
        assertEquals(listOf("p.gone", "p.c", "p.a", "p.b"), r.map { it.packageName })
        assertEquals("p.gone(미설치)", r[0].label)
    }

    @Test fun 검색은_라벨_또는_패키지에_들어_있으면_남긴다() {
        val apps = listOf(AppItem("카카오톡", "com.kakao.talk"), AppItem("Chrome", "com.android.chrome"))
        assertEquals(2, AppListOrder.filter(apps, "", ko).size)
        assertEquals(listOf("com.kakao.talk"), AppListOrder.filter(apps, "카카오", ko).map { it.packageName })
        assertEquals(listOf("com.android.chrome"), AppListOrder.filter(apps, " CHROME ", ko).map { it.packageName })
        assertEquals(listOf("com.kakao.talk"), AppListOrder.filter(apps, "kakao", ko).map { it.packageName })
    }
}
