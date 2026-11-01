package io.github.casimirkwon.smsforwarder.core

/**
 * 조건([Conditions] 형식)과 화면 입력 줄 사이의 변환. 화면에서는
 * 한 줄 = 조건 하나(같은 묶음 안에서는 AND), "또는" 만 있는 줄 = OR 묶음 구분, 줄 앞의 "!" = 제외 조건.
 */
object ConditionText {
    private fun isOrLine(line: String) = line.trim().let { it == "또는" || it.equals("or", ignoreCase = true) }

    fun fromLines(text: String): String {
        val groups = ArrayList<List<Term>>()
        var cur = ArrayList<Term>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (isOrLine(line)) {
                groups.add(cur)
                cur = ArrayList()
            } else if (line.startsWith("!")) {
                cur.add(Term(line.substring(1).trim(), exclude = true))
            } else {
                cur.add(Term(line))
            }
        }
        if (cur.isNotEmpty()) groups.add(cur)
        while (groups.isNotEmpty() && groups.last().isEmpty()) groups.removeAt(groups.size - 1)
        return Conditions.encode(groups)
    }

    fun toLines(rule: String?): String {
        val lines = ArrayList<String>()
        var pendingOr = false
        for (group in Conditions.decode(rule)) {
            for (term in group) {
                if (!term.exclude && term.text.isEmpty()) continue
                if (pendingOr && lines.isNotEmpty()) lines.add("또는")
                pendingOr = false
                lines.add(if (term.exclude) "!" + term.text else term.text)
            }
            pendingOr = true
        }
        return lines.joinToString("\n")
    }
}
