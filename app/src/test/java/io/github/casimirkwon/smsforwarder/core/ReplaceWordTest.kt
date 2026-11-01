package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplaceWordTest {
    private fun plain(text: String, vararg p: Pair<String, String>, removeLine: Boolean = false) =
        ReplaceWord.apply(text, p.toList(), regex = false, removeEmptyLine = removeLine)

    @Test fun 순서대로_차례로_적용된다() {
        assertEquals("c", plain("a", "a" to "b", "b" to "c"))
    }

    @Test fun 특수문자는_이스케이프되어_글자_그대로_바뀐다() {
        assertEquals("가격 X", plain("가격 \$100", "\$100" to "X"))
        assertEquals("X Y Z", plain("(a) [b] {c}", "(a)" to "X", "[b]" to "Y", "{c}" to "Z"))
        assertEquals("1 2", plain("1+1 a|b", "1+1" to "1", "a|b" to "2"))
        assertEquals("ok", plain("a?b^c", "a?b^c" to "ok"))
    }

    @Test fun 점은_이스케이프되지_않아_임의_한글자와_맞는다() {
        // '.' 와 '\' 는 이스케이프 목록에 없다
        assertEquals("X X X", plain("a.b axb a-b", "a.b" to "X"))
    }

    @Test fun 별표는_임의_문자열이고_줄바꿈도_포함한다() {
        assertEquals("[광고] 본문", plain("(광고) 이것저것\n본문", "(광고)*본문" to "[광고] 본문"))
        assertEquals("앞-뒤", plain("앞\n\n뒤", "앞*뒤" to "앞-뒤"))
    }

    @Test fun 바꾸기_문자열은_정규식_치환문자열로_해석된다() {
        // replaceAll 의 치환 문자열을 그대로 넘긴다 → $1 이 그룹 참조
        assertEquals("<12>", ReplaceWord.apply("12", listOf("(\\d+)" to "<\$1>"), regex = true, removeEmptyLine = false))
        // 비정규식 모드는 바꿀 말을 글자 그대로 쓴다 → $1 도 그대로 들어간다
        assertEquals("\$1", plain("(1)", "(1)" to "\$1"))
    }

    @Test fun 정규식_모드는_찾기를_그대로_정규식으로_쓴다() {
        assertEquals("전화 X X", ReplaceWord.apply("전화 010-1234-5678 02-123-4567", listOf("\\d{2,3}-\\d{3,4}-\\d{4}" to "X"), regex = true, removeEmptyLine = false))
    }

    @Test fun 정규식_문법_오류면_그때까지_바뀐_문자열을_돌려준다() {
        var err = 0
        val out = ReplaceWord.apply("abc", listOf("a" to "x", "(" to "y", "c" to "z"), regex = true, removeEmptyLine = false) { err++ }
        assertEquals("xbc", out)
        assertEquals(1, err)
    }

    @Test fun 규칙이_없으면_그대로() {
        assertEquals("abc", ReplaceWord.apply("abc", emptyList(), regex = false, removeEmptyLine = false))
        assertEquals("abc", ReplaceWord.forRule(io.github.casimirkwon.smsforwarder.db.RuleEntity(replacements = "[]"), "abc"))
        assertEquals("abc", ReplaceWord.forRule(io.github.casimirkwon.smsforwarder.db.RuleEntity(replacements = ""), "abc"))
        val r = io.github.casimirkwon.smsforwarder.db.RuleEntity(replacements = ReplaceWord.toJson(listOf("a" to "x", "x" to "y")))
        assertEquals("yb", ReplaceWord.forRule(r, "ab"))
        assertEquals(listOf("a" to "x", "x" to "y"), ReplaceWord.parsePairs(r.replacements))
    }

    @Test fun 개행_제거_옵션은_바꿀_말이_빈_문자열일_때만_맞은_자리_뒤_개행_하나를_지운다() {
        assertEquals("본문\n둘째", plain("(광고)\n본문\n둘째", "(광고)" to "", removeLine = true))
        // 맞은 자리 바로 뒤가 개행이 아니면 그대로
        assertEquals("뒤\n", plain("앞뒤\n", "앞" to "", removeLine = true))
        // 여러 곳
        assertEquals("ab", plain("A\naA\nb", "A" to "", removeLine = true))
    }

    @Test fun 개행_제거_옵션이_켜져_있어도_바꿀_말이_비어_있지_않으면_개행을_지우지_않는다() {
        // 비어 있지 않으면 일반 replaceAll
        assertEquals("[광고]\n본문\n둘째", plain("(광고)\n본문\n둘째", "(광고)" to "[광고]", removeLine = true))
        assertEquals("X뒤\n", plain("앞뒤\n", "앞" to "X", removeLine = true))
    }

    @Test fun 개행_제거_경로는_DOTALL_로_치환한다() {
        // 빈 바꿀 말 + 개행 제거 옵션이면 정규식 점이 줄바꿈과도 맞는다(Pattern.DOTALL)
        assertEquals("", ReplaceWord.apply("a\nb", listOf("a.b" to ""), regex = true, removeEmptyLine = true))
        // 바꿀 말이 있으면 DOTALL 이 아니다
        assertEquals("a\nb", ReplaceWord.apply("a\nb", listOf("a.b" to "X"), regex = true, removeEmptyLine = true))
    }

    // --- 바꿀 말 해석 ---
    @Test fun 바꿀_말의_역슬래시_n_t_는_줄바꿈_탭이_된다() {
        assertEquals("가\n나", plain("가,나", "," to "\\n"))
        assertEquals("가\t나", plain("가,나", "," to "\\t"))
        assertEquals("a\nb\nc", ReplaceWord.apply("a,b,c", listOf("," to "\\n"), regex = true, removeEmptyLine = false))
    }

    @Test fun 바꿀_말의_이중_역슬래시는_역슬래시_한_글자다() {
        assertEquals("a\\b", plain("a,b", "," to "\\\\"))
        assertEquals("a\\b", ReplaceWord.apply("a,b", listOf("," to "\\\\"), regex = true, removeEmptyLine = false))
        // \n 은 역슬래시 + n (줄바꿈 아님)
        assertEquals("a\\nb", plain("a,b", "," to "\\\\n"))
    }

    @Test fun 알_수_없는_역슬래시_조합은_역슬래시를_그대로_두고_다음_글자를_정상_처리한다() {
        // \n \t \\ 만 두 글자를 한 번에 해석하고, 그 밖의 \x 는 역슬래시를 그대로 둔다
        assertEquals("\\q\n", ReplaceWord.parseReplacement("\\q\\n", true))
        assertEquals("q", ReplaceWord.apply("a", listOf("a" to "\\q"), regex = true, removeEmptyLine = false))   // 정규식 치환에서 \q = q
        assertEquals("\\q", plain("a", "a" to "\\q"))
        assertEquals("끝\\", plain("a", "a" to "끝\\"))     // 끝의 홑 역슬래시
        assertEquals("", ReplaceWord.parseReplacement(null, true))
    }

    @Test fun 비정규식_모드는_바꿀_말의_달러와_역슬래시를_글자_그대로_쓴다() {
        assertEquals("가격 \$5", plain("가격 X", "X" to "\$5"))
        assertEquals("a\\b", plain("a/b", "/" to "\\\\"))
        assertEquals("a\n\$1", plain("a,b", ",b" to "\\n\$1"))
    }

    @Test fun 정규식_모드는_달러_그룹_참조를_유지한다() {
        assertEquals("[12]", ReplaceWord.apply("12", listOf("(\\d+)" to "[\$1]"), regex = true, removeEmptyLine = false))
        assertEquals("<1>\n2", ReplaceWord.apply("1,2", listOf("(\\d)," to "<\$1>\\n"), regex = true, removeEmptyLine = false))
    }

    @Test fun parseReplacement_결과_표() {
        assertEquals("a\nb", ReplaceWord.parseReplacement("a\\nb", true))
        assertEquals("\\\\", ReplaceWord.parseReplacement("\\\\", true))   // 정규식: 치환 문자열에서 \\ = 역슬래시 한 글자
        assertEquals("\\\\", ReplaceWord.parseReplacement("\\\\", false))   // 비정규식: 역슬래시 한 글자를 quoteReplacement 한 결과
        assertEquals("\\\$", ReplaceWord.parseReplacement("\$", false))
        assertEquals("\\\$", ReplaceWord.parseReplacement("\\\$", true))      // 정규식: \$ 그대로(= 글자 \$)
    }

    @Test fun 정규식_모드에서_점은_줄바꿈과_맞지_않는다() {
        assertEquals("a\nb", ReplaceWord.apply("a\nb", listOf("a.b" to "X"), regex = true, removeEmptyLine = false))
    }

    @Test fun 비정규식_별표_패턴은_문자열_앞에_s_플래그가_붙는다() {
        assertEquals("(?s)a.*b", ReplaceWord.wildcardPattern("a*b"))
        assertEquals("a\\(b\\)", ReplaceWord.wildcardPattern("a(b)"))
        assertEquals("a[+]b[|]c", ReplaceWord.wildcardPattern("a+b|c"))
        assertEquals("a.b", ReplaceWord.wildcardPattern("a.b"))
    }
}
