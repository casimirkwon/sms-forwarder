package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.RuleEntity
import io.github.casimirkwon.smsforwarder.db.RuleKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 규칙·설정 내보내기/가져오기 파일(JSON) 형식. 이 앱 고유 기능이다.
 *
 * schema 2 (현재):
 * ```
 * { "format": "sms-forwarder-export", "schema": 2, "appVersion": "1.1.0", "exportedAt": 1730000000000,
 *   "rules": [ { name, kind: "sms"|"notification"|"other", enabled, level, senderCond, bodyCond, recipients, apps,
 *                matchAll, ignoreCase, wildcard, allowOtp, replacements, regexReplace, removeEmptyLine, template,
 *                schedule, simInSlot, simOutSlot, delayEnabled, delayMinutes, notifyResult, saveHistory,
 *                useText, useSubText, useBigText, useMessageText, skipEmptyNoti, composition }, … ],
 *   "settings": { "<설정 키>": 값, … },               // 선택
 *   "spamRules": [ { "type": 0|1|2, "data": "…" }, … ]  // 선택
 * }
 * ```
 * 조건·수신처·앱·단어 바꾸기·시간대·구성은 문자열이 아니라 JSON 값 그대로 담는다([Conditions]·[ReplaceWord]·[ActiveHours] 형식).
 * 규칙은 배열 순서가 곧 표시 순서다. 이력·대기열·통계·내부 상태(SMS 건수 사용량 등)는 담지 않는다. 알 수 없는 필드는 무시한다.
 *
 * 이전 형식(schema 1)은 읽기만 하며 [legacyReader] 가 등록돼 있을 때 새 형식으로 바꿔 읽는다. 내보내기는 항상 schema 2 로 쓴다.
 */
object ExportImport {
    const val FORMAT = "sms-forwarder-export"
    const val SCHEMA = 2

    /** 읽을 수 있는 파일 크기의 상한(바이트). 규칙 수백 개도 1MB 를 넘지 않는다. */
    const val MAX_BYTES = 5 * 1024 * 1024

    /** 내보내기에서 뺀 설정(내부 상태): 이번 달 SMS 건수와 마지막 초기화 달. */
    val INTERNAL_SETTINGS = setOf("monthly_used", "monthly_reset_month")

    enum class SettingType { BOOL, INT, LONG, STRING }

    /** 내보내고 가져오는 설정 키와 형식(Settings.kt 의 DataStore 키 이름과 같다). */
    val SETTING_KEYS: Map<String, SettingType> = linkedMapOf(
        "forwarding_enabled" to SettingType.BOOL,
        "foreground_mode" to SettingType.BOOL,
        "keep_send_log" to SettingType.BOOL,
        "retry_failed_sends" to SettingType.BOOL,
        "retry_limit" to SettingType.INT,
        "send_gap_min_sec" to SettingType.INT,
        "send_gap_max_sec" to SettingType.INT,
        "sim_default_in" to SettingType.INT,
        "sim_default_out" to SettingType.INT,
        "sim_label_1" to SettingType.STRING,
        "sim_label_2" to SettingType.STRING,
        "received_keep_days" to SettingType.INT,
        "result_notice" to SettingType.BOOL,
        "wake_while_sending" to SettingType.BOOL,
        "wait_for_network" to SettingType.BOOL,
        "allow_roaming" to SettingType.BOOL,
        "hours_limit_on" to SettingType.BOOL,
        "hours_start" to SettingType.INT,
        "hours_end" to SettingType.INT,
        "dedupe_on" to SettingType.BOOL,
        "dedupe_always" to SettingType.BOOL,
        "dedupe_count" to SettingType.INT,
        "burst_guard_on" to SettingType.BOOL,
        "burst_per_minute" to SettingType.INT,
        "monthly_cap_on" to SettingType.BOOL,
        "monthly_cap" to SettingType.INT,
        "noti_dedupe_on" to SettingType.BOOL,
        "noti_dedupe_strict" to SettingType.BOOL,
        "summary_text_only" to SettingType.BOOL,
        "noti_dedupe_minutes" to SettingType.LONG,
        "summary_all_apps" to SettingType.BOOL,
        "summary_apps" to SettingType.STRING,
    )

    /** 가져온 스팸 차단 규칙 한 줄(type 0 전화번호, 1 알림 제목, 2 본문). */
    data class SpamItem(val type: Int, val data: String)

    /** 파일에서 읽은 내용. [settings]·[spamRules] 가 null 이면 파일에 그 부분이 없다. */
    data class Data(
        val appVersion: String,
        val exportedAt: Long,
        val rules: List<RuleEntity>,
        val settings: Map<String, Any>?,
        val spamRules: List<SpamItem>?,
    )

    sealed class Parsed {
        data class Ok(val data: Data) : Parsed()
        /** [reason] 은 사용자에게 그대로 보여 주는 거절 이유 */
        data class Rejected(val reason: String) : Parsed()
    }

    /** 읽기 중 거절 사유. 이전 형식 변환기([LegacyReader])도 이것을 던진다. */
    class Reject(val reason: String) : Exception(reason)

    /** 이전 형식으로 내보낸 파일 하나의 schema 를 읽어 새 형식으로 바꾸는 변환기. */
    interface LegacyReader {
        val schema: Int
        /** 규칙 배열의 키 이름 */
        val rulesKey: String
        fun rule(o: JSONObject, no: Int): RuleEntity
        /** 이전 설정 키 → 새 설정 키. 버릴 키(내부 상태·모르는 키)는 null */
        fun settingKey(oldKey: String): String?
        /** 값 모양이 달라진 설정만 바꾼다 */
        fun settingValue(newKey: String, v: Any): Any
    }

    /** 앱이 시작할 때 등록한다. 없으면 이전 schema 파일은 거절한다. */
    @Volatile var legacyReader: LegacyReader? = null

    // ── 내보내기 ──

    fun build(
        rules: List<RuleEntity>,
        settings: Map<String, Any>,
        spamRules: List<SpamItem>,
        appVersion: String,
        exportedAt: Long,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("schema", SCHEMA)
        root.put("appVersion", appVersion)
        root.put("exportedAt", exportedAt)
        val ra = JSONArray()
        for (r in rules) ra.put(ruleToJson(r))
        root.put("rules", ra)
        val so = JSONObject()
        for ((k, type) in SETTING_KEYS) {
            val v = settings[k] ?: continue
            when (type) {
                SettingType.BOOL -> so.put(k, v as Boolean)
                SettingType.INT -> so.put(k, (v as Number).toInt())
                SettingType.LONG -> so.put(k, (v as Number).toLong())
                SettingType.STRING -> so.put(k, v as String)
            }
        }
        root.put("settings", so)
        val sa = JSONArray()
        for (r in spamRules) sa.put(JSONObject().put("type", r.type).put("data", r.data))
        root.put("spamRules", sa)
        return root.toString(2)
    }

    private fun ruleToJson(r: RuleEntity): JSONObject = JSONObject().apply {
        put("name", r.name)
        put("kind", r.kind.name.lowercase())
        put("enabled", r.enabled)
        put("level", r.level)
        put("senderCond", JSONArray(r.senderCond))
        put("bodyCond", JSONArray(r.bodyCond))
        put("recipients", JSONArray(r.recipients))
        put("apps", JSONArray(r.apps))
        put("matchAll", r.matchAll)
        put("ignoreCase", r.ignoreCase)
        put("wildcard", r.wildcard)
        put("allowOtp", r.allowOtp)
        put("replacements", JSONArray(r.replacements))
        put("regexReplace", r.regexReplace)
        put("removeEmptyLine", r.removeEmptyLine)
        put("template", r.template)
        put("schedule", JSONObject(r.schedule))
        put("simInSlot", r.simInSlot)
        put("simOutSlot", r.simOutSlot)
        put("delayEnabled", r.delayEnabled)
        put("delayMinutes", r.delayMinutes)
        put("notifyResult", r.notifyResult)
        put("saveHistory", r.saveHistory)
        put("useText", r.useText)
        put("useSubText", r.useSubText)
        put("useBigText", r.useBigText)
        put("useMessageText", r.useMessageText)
        put("skipEmptyNoti", r.skipEmptyNoti)
        put("composition", JSONArray(if (r.composition.isEmpty()) "[]" else r.composition))
    }

    // ── 가져오기(읽기·검증) ──

    fun parse(text: String): Parsed {
        return try {
            Parsed.Ok(parseOrThrow(text))
        } catch (e: Reject) {
            Parsed.Rejected(e.reason)
        } catch (e: JSONException) {
            Parsed.Rejected("JSON 형식이 올바르지 않습니다.")
        }
    }

    private fun parseOrThrow(text: String): Data {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith("{")) throw Reject("JSON 파일이 아닙니다.")
        val root = JSONObject(trimmed)
        val format = root.opt("format")
        if (format != FORMAT) throw Reject("이 앱의 내보내기 파일이 아닙니다(format 값이 \"$FORMAT\" 이 아님).")
        val schema = root.opt("schema")
        if (schema == null) throw Reject("schema 값이 없습니다.")
        val schemaInt = asInt(schema)
        val legacyReader = legacyReader
        if (schemaInt != SCHEMA && (legacyReader == null || schemaInt != legacyReader.schema)) throw Reject("지원하지 않는 schema 입니다(파일 ${schema}, 이 앱 $SCHEMA).")
        val legacy = if (schemaInt == SCHEMA) null else legacyReader
        val appVersion = (root.opt("appVersion") as? String) ?: ""
        val exportedAt = (root.opt("exportedAt") as? Number)?.toLong() ?: 0L

        val rulesKey = legacy?.rulesKey ?: "rules"
        val rulesRaw = root.opt(rulesKey)
        val rules = ArrayList<RuleEntity>()
        if (rulesRaw != null) {
            if (rulesRaw !is JSONArray) throw Reject("$rulesKey 가 목록이 아닙니다.")
            for (i in 0 until rulesRaw.length()) {
                val o = rulesRaw.opt(i) as? JSONObject ?: throw Reject("규칙 ${i + 1}번이 객체가 아닙니다.")
                rules.add(legacy?.rule(o, i + 1) ?: parseRule(o, i + 1))
            }
        }

        var settings: Map<String, Any>? = null
        val settingsRaw = root.opt("settings")
        if (settingsRaw != null) {
            if (settingsRaw !is JSONObject) throw Reject("settings 가 객체가 아닙니다.")
            val m = LinkedHashMap<String, Any>()
            for (rawKey in settingsRaw.keys()) {
                val key = if (legacy != null) (legacy.settingKey(rawKey) ?: continue) else rawKey
                val type = SETTING_KEYS[key] ?: continue // 알 수 없는 키·내부 상태 키는 무시
                var v: Any = settingsRaw.get(rawKey)
                if (legacy != null) v = legacy.settingValue(key, v)
                m[key] = when (type) {
                    SettingType.BOOL -> v as? Boolean
                    SettingType.INT -> asInt(v)
                    SettingType.LONG -> asLong(v)
                    SettingType.STRING -> v as? String
                } ?: throw Reject("설정 \"$rawKey\" 의 값 형식이 맞지 않습니다.")
            }
            settings = m
        }

        var spam: List<SpamItem>? = null
        val spamRaw = root.opt("spamRules")
        if (spamRaw != null) {
            if (spamRaw !is JSONArray) throw Reject("spamRules 가 목록이 아닙니다.")
            val l = ArrayList<SpamItem>()
            for (i in 0 until spamRaw.length()) {
                val o = spamRaw.opt(i) as? JSONObject ?: throw Reject("스팸 규칙 ${i + 1}번이 객체가 아닙니다.")
                val type = asInt(o.opt("type"))
                val data = o.opt("data") as? String
                if (type == null || type !in 0..2 || data == null) throw Reject("스팸 규칙 ${i + 1}번의 type(0~2)·data 가 맞지 않습니다.")
                l.add(SpamItem(type, data))
            }
            spam = l
        }

        if (rules.isEmpty() && (settings == null || settings.isEmpty()) && (spam == null || spam.isEmpty())) {
            throw Reject("가져올 규칙이나 설정이 없습니다.")
        }
        return Data(appVersion, exportedAt, rules, settings, spam)
    }

    fun asInt(v: Any?): Int? = asLong(v)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** 정수로 읽을 수 있는 숫자만 받는다(문자열 "512"·소수 512.5 는 받지 않는다). */
    fun asLong(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 9.0e15) v.toLong() else null
        else -> null
    }

    private fun parseRule(o: JSONObject, no: Int): RuleEntity {
        fun bad(name: String): Nothing = throw Reject("규칙 ${no}번의 \"$name\" 값이 없거나 형식이 맞지 않습니다.")
        fun str(name: String, required: Boolean = false): String? {
            if (!o.has(name)) { if (required) bad(name) else return null }
            return o.get(name) as? String ?: bad(name)
        }
        fun bool(name: String): Boolean? {
            if (!o.has(name)) return null
            return o.get(name) as? Boolean ?: bad(name)
        }
        fun int(name: String): Int? {
            if (!o.has(name)) return null
            return asInt(o.get(name)) ?: bad(name)
        }
        fun cond(name: String): String? {
            if (!o.has(name)) return null
            val arr = o.get(name) as? JSONArray ?: bad(name)
            for (gi in 0 until arr.length()) {
                val g = arr.opt(gi) as? JSONArray ?: bad(name)
                for (ti in 0 until g.length()) {
                    val t = g.opt(ti) as? JSONObject ?: bad(name)
                    if (t.opt("text") !is String) bad(name)
                    if (t.has("exclude") && t.opt("exclude") !is Boolean) bad(name)
                }
            }
            return Conditions.encode(Conditions.decode(arr.toString()))
        }
        fun list(name: String): String? {
            if (!o.has(name)) return null
            val arr = o.get(name) as? JSONArray ?: bad(name)
            val items = (0 until arr.length()).map { arr.opt(it) as? String ?: bad(name) }
            return Conditions.encodeList(items)
        }
        val name = str("name", true)!!
        val kind = when (str("kind", true)) {
            "sms" -> RuleKind.SMS
            "notification" -> RuleKind.NOTIFICATION
            "other" -> RuleKind.OTHER
            else -> bad("kind")
        }
        val level = int("level") ?: RuleEntity().level
        if (level < 0 || level > 99) bad("level")
        val schedule = if (o.has("schedule")) {
            val so = o.get("schedule") as? JSONObject ?: bad("schedule")
            val canonical = ActiveHours.parseOrNull(so.toString()) ?: bad("schedule")
            ActiveHours.build(canonical)
        } else ActiveHours.DEFAULT
        val replacements = if (o.has("replacements")) {
            val arr = o.get("replacements") as? JSONArray ?: bad("replacements")
            val pairs = (0 until arr.length()).map {
                val p = arr.opt(it) as? JSONObject ?: bad("replacements")
                (p.opt("find") as? String ?: bad("replacements")) to (p.opt("to") as? String ?: bad("replacements"))
            }
            ReplaceWord.toJson(pairs)
        } else "[]"
        val composition = if (o.has("composition")) {
            val arr = o.get("composition") as? JSONArray ?: bad("composition")
            val keys = (0 until arr.length()).map { arr.opt(it) as? String ?: bad("composition") }
            if (keys.isEmpty()) "" else NotiCompose.serialize(keys.mapNotNull { NotiPart.fromKey(it) })
        } else ""
        val delayMinutes = if (o.has("delayMinutes")) (asLong(o.get("delayMinutes")) ?: bad("delayMinutes")) else 0L
        val d = RuleEntity()
        return RuleEntity(
            id = 0,
            name = name,
            kind = kind,
            enabled = bool("enabled") ?: d.enabled,
            level = level,
            senderCond = cond("senderCond") ?: d.senderCond,
            bodyCond = cond("bodyCond") ?: d.bodyCond,
            recipients = list("recipients") ?: d.recipients,
            apps = list("apps") ?: d.apps,
            matchAll = bool("matchAll") ?: d.matchAll,
            ignoreCase = bool("ignoreCase") ?: d.ignoreCase,
            wildcard = bool("wildcard") ?: d.wildcard,
            allowOtp = bool("allowOtp") ?: d.allowOtp,
            replacements = replacements,
            regexReplace = bool("regexReplace") ?: d.regexReplace,
            removeEmptyLine = bool("removeEmptyLine") ?: d.removeEmptyLine,
            template = str("template") ?: d.template,
            schedule = schedule,
            simInSlot = int("simInSlot") ?: d.simInSlot,
            simOutSlot = int("simOutSlot") ?: d.simOutSlot,
            position = 0,
            delayEnabled = bool("delayEnabled") ?: d.delayEnabled,
            delayMinutes = delayMinutes,
            notifyResult = bool("notifyResult") ?: d.notifyResult,
            saveHistory = bool("saveHistory") ?: d.saveHistory,
            useText = bool("useText") ?: d.useText,
            useSubText = bool("useSubText") ?: d.useSubText,
            useBigText = bool("useBigText") ?: d.useBigText,
            useMessageText = bool("useMessageText") ?: d.useMessageText,
            skipEmptyNoti = bool("skipEmptyNoti") ?: d.skipEmptyNoti,
            composition = composition,
        )
    }

    // ── 가져오기(이름 정리) ──

    /**
     * "추가" 방식의 규칙 이름 정리: 기존 규칙이나 앞서 가져온 규칙과 이름이 같으면 뒤에 " (2)", 그것도 있으면 " (3)" … 을 붙인다.
     * 규칙 목록의 나머지 값은 건드리지 않는다.
     */
    fun uniqueNames(existing: Collection<String>, incoming: List<RuleEntity>): List<RuleEntity> {
        val used = HashSet<String>(existing)
        return incoming.map { f ->
            var name = f.name
            if (name in used) {
                var n = 2
                while ("${f.name} ($n)" in used) n++
                name = "${f.name} ($n)"
            }
            used.add(name)
            if (name == f.name) f else f.copy(name = name)
        }
    }
}
