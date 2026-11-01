package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.RuleEntity

/**
 * 규칙 편집 화면의 입력 상태. 화면 위젯과 [RuleEntity] 사이의 변환만 담당하는 순수 코드라서 안드로이드 없이 시험할 수 있다.
 * 문자열 칸(수신처·조건·단어 바꾸기)은 화면에 보이는 줄 단위 텍스트 그대로 들고 있다.
 */
data class RuleForm(
    val name: String,
    /** 수신처(한 줄에 하나) */
    val recipients: String,
    /** 발신자(문자) 또는 알림 제목 조건 줄 */
    val senderLines: String,
    val bodyLines: String,
    val template: String,
    val ignoreCase: Boolean,
    val wildcard: Boolean,
    val matchAll: Boolean,
    val allowOtp: Boolean,
    /** 단어 바꾸기(한 줄에 `찾을말=>바꿀말`) */
    val replaceLines: String,
    val regexReplace: Boolean,
    val removeEmptyLine: Boolean,
    /** 시간대 JSON([ActiveHours]) */
    val schedule: String,
    val notifyResult: Boolean,
    val saveHistory: Boolean,
    val simIn: Int,
    val simOut: Int,
    val delayOn: Boolean,
    val delayMinutes: Long,
    /** 알림 규칙의 대상 앱 JSON */
    val apps: String,
    val useText: Boolean,
    val useSub: Boolean,
    val useBig: Boolean,
    val useMessage: Boolean,
    val skipEmpty: Boolean,
    /** level 4 알림 규칙: 보낼 내용을 자동으로 정할지, 직접 고른 목록([compParts])을 쓸지 */
    val compAuto: Boolean,
    val compParts: List<NotiPart>,
) {
    /** 저장 검증 결과. */
    sealed interface Result {
        data class Ok(val rule: RuleEntity) : Result
        /** 수신처가 비어 있음 */
        data object NoRecipient : Result
        /** 단어 바꾸기에 찾을 말 없이 바꿀 말만 있는 줄이 있음 */
        data object BadReplace : Result
    }

    /**
     * 입력값을 [initial] 위에 덮어 규칙으로 만든다. 규칙 종류에 따라 편집하지 않는 필드는 [initial] 값을 그대로 둔다.
     * @param defaultName 이름 칸이 비었을 때 쓸 이름
     */
    fun toRule(initial: RuleEntity, defaultName: String): Result {
        val dest = recipients.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (dest.isEmpty()) return Result.NoRecipient
        val pairs = replaceLines.lines().filter { it.isNotBlank() }.map { line ->
            val i = line.indexOf("=>")
            if (i < 0) line to "" else line.substring(0, i) to line.substring(i + 2)
        }
        // 새 단어가 있으면 이전 단어를 빈칸으로 둘 수 없다
        if (pairs.any { it.first.isEmpty() && it.second.isNotEmpty() }) return Result.BadReplace
        val isNoti = initial.isNotification
        val isV4 = initial.isV4
        // 직접 선택에서 모두 해제하면 구성이 비어 자동과 같아진다
        val composition = if (isNoti && isV4) (if (compAuto) "" else NotiCompose.serialize(compParts)) else initial.composition
        val legacyNotiFields = isNoti && !isV4
        return Result.Ok(
            initial.copy(
                name = name.trim().ifEmpty { defaultName },
                recipients = Conditions.encodeList(dest),
                senderCond = ConditionText.fromLines(senderLines),
                bodyCond = ConditionText.fromLines(bodyLines),
                template = template,
                replacements = ReplaceWord.toJson(pairs),
                ignoreCase = ignoreCase,
                wildcard = wildcard,
                matchAll = matchAll,
                allowOtp = allowOtp,
                regexReplace = regexReplace,
                removeEmptyLine = removeEmptyLine,
                notifyResult = notifyResult,
                saveHistory = saveHistory,
                delayEnabled = if (DelayPolicy.SHOW_DELAY_UI) delayOn && delayMinutes > 0 else initial.delayEnabled,
                useText = if (legacyNotiFields) useText else initial.useText,
                useSubText = if (legacyNotiFields) useSub else initial.useSubText,
                useBigText = if (legacyNotiFields) useBig else initial.useBigText,
                useMessageText = if (legacyNotiFields) useMessage else initial.useMessageText,
                skipEmptyNoti = if (legacyNotiFields) skipEmpty else initial.skipEmptyNoti,
                delayMinutes = if (DelayPolicy.SHOW_DELAY_UI) delayMinutes else initial.delayMinutes,
                schedule = schedule,
                simInSlot = if (isNoti) initial.simInSlot else simIn,
                simOutSlot = if (isNoti) initial.simOutSlot else simOut,
                apps = if (isNoti) apps else initial.apps,
                composition = composition,
            ),
        )
    }

    companion object {
        /** 규칙의 저장 값을 화면 입력 상태로 푼다. */
        fun of(r: RuleEntity): RuleForm = RuleForm(
            name = r.name,
            recipients = parseDestinations(r.recipients).joinToString("\n"),
            senderLines = ConditionText.toLines(r.senderCond),
            bodyLines = ConditionText.toLines(r.bodyCond),
            template = r.template,
            ignoreCase = r.ignoreCase,
            wildcard = r.wildcard,
            matchAll = r.matchAll,
            allowOtp = r.allowOtp,
            replaceLines = ReplaceWord.parsePairs(r.replacements).joinToString("\n") { "${it.first}=>${it.second}" },
            regexReplace = r.regexReplace,
            removeEmptyLine = r.removeEmptyLine,
            schedule = r.schedule,
            notifyResult = r.notifyResult,
            saveHistory = r.saveHistory,
            simIn = r.simInSlot,
            simOut = r.simOutSlot,
            delayOn = r.delayEnabled && r.delayMinutes > 0,
            delayMinutes = r.delayMinutes,
            apps = r.apps,
            useText = r.useText,
            useSub = r.useSubText,
            useBig = r.useBigText,
            useMessage = r.useMessageText,
            skipEmpty = r.skipEmptyNoti,
            compAuto = NotiCompose.parse(r.composition).isEmpty(),
            compParts = NotiCompose.parse(r.composition),
        )
    }
}
