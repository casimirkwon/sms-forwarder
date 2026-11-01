package io.github.casimirkwon.smsforwarder.core

/**
 * 기본 OTP 단어 차단. 본문(소문자)에 단어 중 하나가 들어 있으면 그 필터는 건너뛴다
 * (자동답장 필터이거나 필터 옵션 비트 65536 "OTP 류도 전달" 이 켜져 있으면 예외).
 * 기본 목록은 한국어 단말 기준이며 아래와 같다.
 */
object OtpBlock {
    val WORDS_KO = listOf("OTP", "Pin", "Password", "Bank", "핀", "패스워드", "암호", "은행")

    fun isBlocked(body: String, words: List<String> = WORDS_KO): Boolean {
        val lower = body.lowercase()
        return words.any { lower.contains(it.lowercase()) }
    }
}
