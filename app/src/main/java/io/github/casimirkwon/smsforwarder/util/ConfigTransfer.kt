package io.github.casimirkwon.smsforwarder.util

import android.content.Context
import androidx.room.withTransaction
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.core.ExportImport
import io.github.casimirkwon.smsforwarder.db.SpamRuleEntity

/** 규칙·설정 내보내기/가져오기를 DB·설정 저장소에 적용한다(형식·검증은 [ExportImport]). */
class ConfigTransfer(private val ctx: Context, private val graph: Graph) {
    enum class Mode { ADD, REPLACE }

    suspend fun exportJson(now: Long = System.currentTimeMillis()): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        val rules = graph.db.rules().all()
        val spam = graph.db.spam().all().map { ExportImport.SpamItem(it.type, it.data) }
        return ExportImport.build(rules, graph.settings.exportValues(), spam, version, now)
    }

    /**
     * 가져온 내용을 적용한다. ADD: 기존 규칙은 두고 뒤에 추가(같은 이름이면 " (2)"), REPLACE: 기존 규칙을 모두 지우고 파일의 규칙으로 교체.
     * 규칙의 level·켜짐 여부는 파일 값 그대로 저장한다. [applySettings] 가 true 이고 파일에 설정이 있으면 설정도 덮어쓴다.
     * 반환: 저장한 규칙 수.
     */
    suspend fun apply(data: ExportImport.Data, mode: Mode, applySettings: Boolean): Int {
        val db = graph.db
        var saved = 0
        db.withTransaction {
            val dao = db.rules()
            val incoming = if (mode == Mode.REPLACE) {
                dao.deleteAll()
                data.rules
            } else {
                ExportImport.uniqueNames(dao.all().map { it.name }, data.rules)
            }
            var order = dao.nextOrder()
            for (f in incoming) dao.insert(f.copy(id = 0, position = order++))
            saved = incoming.size
            val spam = data.spamRules
            if (spam != null) {
                val sdao = db.spam()
                if (mode == Mode.REPLACE) sdao.deleteAll()
                val have = sdao.all().map { it.type to it.data }.toHashSet()
                for (r in spam) if (have.add(r.type to r.data)) sdao.insert(SpamRuleEntity(type = r.type, data = r.data))
            }
        }
        val s = data.settings
        if (applySettings && s != null) graph.settings.importValues(s)
        return saved
    }
}
