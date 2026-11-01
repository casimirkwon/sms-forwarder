package io.github.casimirkwon.smsforwarder.core

import java.text.Collator
import java.util.Locale

/** 대상 앱 목록 한 줄(라벨/패키지). 설치되지 않은 앱은 [installed] = false 로 라벨 뒤에 "(설치안됨)" 이 붙는다. */
data class AppItem(val label: String, val packageName: String, val installed: Boolean = true)

/**
 * 대상 앱 목록 정렬·검색.
 * - 라벨의 첫 글자(문자)가 속한 문자 체계로 순위를 매긴다: 단말 언어의 문자 체계 0, 라틴 2, 그 밖 1(글자가 없으면 1).
 * - 같은 순위끼리는 Collator 비교, 그래도 같으면 패키지 이름 비교.
 * - 이미 선택된 앱은 같은 정렬 순서 그대로 맨 위로 올리고, 선택됐지만 설치되지 않은 패키지는 그보다 더 위에 "(설치안됨)"으로 둔다.
 */
object AppListOrder {
    fun scriptRank(label: String, locale: Locale): Int {
        var first: Char? = null
        for (c in label) {
            if (Character.isLetter(c)) {
                first = c
                break
            }
        }
        if (first == null) return 1
        val script = Character.UnicodeScript.of(first.code)
        val lang = locale.language
        val native: Set<Character.UnicodeScript> = when (lang) {
            Locale.KOREAN.language -> setOf(Character.UnicodeScript.HANGUL)
            Locale.JAPANESE.language -> setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN)
            Locale.CHINESE.language -> setOf(Character.UnicodeScript.HAN)
            "ru" -> setOf(Character.UnicodeScript.CYRILLIC)
            else -> setOf(Character.UnicodeScript.LATIN)
        }
        if (native.contains(script)) return 0
        return if (script == Character.UnicodeScript.LATIN) 2 else 1
    }

    fun comparator(locale: Locale): Comparator<AppItem> {
        val collator = Collator.getInstance(locale)
        return Comparator { a, b ->
            val ra = scriptRank(a.label, locale)
            val rb = scriptRank(b.label, locale)
            if (ra != rb) ra - rb
            else {
                val c = collator.compare(a.label, b.label)
                if (c != 0) c else a.packageName.compareTo(b.packageName)
            }
        }
    }

    /** [installed] 는 실행 가능한 설치 앱(패키지 중복 제거됨), [selected] 는 선택된 패키지. */
    fun arrange(installed: List<AppItem>, selected: Set<String>, locale: Locale, notInstalledLabel: String = "미설치"): List<AppItem> {
        val sorted = installed.sortedWith(comparator(locale))
        val result = ArrayList<AppItem>()
        var top = 0
        val remaining = HashSet(selected)
        for (app in sorted) {
            if (selected.contains(app.packageName)) {
                result.add(top++, app)
                remaining.remove(app.packageName)
            } else {
                result.add(app)
            }
        }
        for (pkg in remaining) result.add(0, AppItem("$pkg($notInstalledLabel)", pkg, installed = false))
        return result
    }

    /** 검색: 라벨 또는 패키지 이름에 검색어가 들어 있으면(소문자 비교) 남긴다. 빈 검색어는 전체. */
    fun filter(list: List<AppItem>, query: String, locale: Locale): List<AppItem> {
        val q = query.trim().lowercase(locale)
        if (q.isEmpty()) return list
        return list.filter { it.label.lowercase(locale).contains(q) || it.packageName.lowercase(locale).contains(q) }
    }
}
