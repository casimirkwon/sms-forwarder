package io.github.casimirkwon.smsforwarder.core

import android.content.Context
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.util.Log
import io.github.casimirkwon.smsforwarder.Settings
import io.github.casimirkwon.smsforwarder.db.AppDb
import io.github.casimirkwon.smsforwarder.db.ReceivedStepEntity
import io.github.casimirkwon.smsforwarder.db.RuleEntity
import io.github.casimirkwon.smsforwarder.db.RuleKind
import io.github.casimirkwon.smsforwarder.db.DeliveryEntity
import io.github.casimirkwon.smsforwarder.sms.DelayAlarm
import io.github.casimirkwon.smsforwarder.sms.SenderQueue
import io.github.casimirkwon.smsforwarder.sms.SimUtil
import io.github.casimirkwon.smsforwarder.util.Contacts
import java.util.Locale

/**
 * 수신 문자 → 규칙 평가 → 전달 건 생성. SMS 수신 경로.
 * 모든 규칙을 order 순으로 독립 평가한다(첫 일치에서 멈추지 않음).
 */
class ForwardPipeline(
    private val ctx: Context,
    private val db: AppDb,
    private val settings: Settings,
    private val queue: SenderQueue,
) {
    private val tag = "Pipeline"

    /** 반환값: 전달 건을 하나라도 대기열에 넣었으면 true([Reapply.anyQueued]). */
    suspend fun onSms(sms: IncomingSms, simSlot: Int): Boolean = process(sms, simSlot, imageCount = 0)

    /**
     * MMS 의 텍스트 파트를 SMS 와 같은 경로로 처리한다. 본문·보낸이가 없으면 빈 문자열.
     * 이미지는 SMS 로 보낼 수 없어 개수만 받는다 — 본문이 비어 있어도 이미지가 있으면 "본문 비어 있음"으로 건너뛰지 않는다.
     */
    suspend fun onMms(body: String?, address: String?, timeMillis: Long, imageCount: Int, simSlot: Int): Boolean =
        process(IncomingSms(address ?: "", body ?: "", timeMillis), simSlot, imageCount)

    /**
     * [existingHid] 가 있으면 "필터 다시 적용"이다: 새 이력 행을 만들지 않고 그 행에 단계 로그를 덧붙인다.
     * 그 밖의 판정·전달 경로는 수신 때와 같다.
     */
    private suspend fun process(sms: IncomingSms, simSlot: Int, imageCount: Int, existingHid: Long? = null): Boolean {
        val s = settings.snapshot()
        if (!s.appEnable) {
            Log.i(tag, "app disabled, drop")
            return false
        }
        val now = System.currentTimeMillis()
        val hid = existingHid ?: run {
            purgeOldHistory(s.historyRetentionDays, now)
            db.received().insert(Reapply.fromSms(sms, now, simSlot))
        }
        val queued = mutableListOf<Boolean>()
        suspend fun step(f: io.github.casimirkwon.smsforwarder.db.RuleEntity, code: String) {
            db.received().insertStep(ReceivedStepEntity(receivedId = hid, time = System.currentTimeMillis(), ruleName = f.name, code = code))
        }

        val simInName = SimUtil.simName(ctx, s, simSlot)
        val spamRules = db.spam().all().map { SpamRule(it.id, it.type, it.data) }
        for (f in db.rules().all()) {
            if (!f.enabled) continue
            // 수신 SMS 에는 문자 규칙만 쓴다(알림 규칙과 그 밖의 종류는 건너뛴다).
            if (f.kind != RuleKind.SMS) continue

            val inSlot = if (f.simInSlot != -1) f.simInSlot else s.defaultInSlot
            if (inSlot != -1 && (simSlot == -1 || simSlot != inSlot)) {
                step(f, StepCode.SIM_IN_MISMATCH)
                continue
            }
            if (!ActiveHours.isActive(f.schedule)) {
                step(f, StepCode.SCHEDULE_SKIP)
                continue
            }
            if (OtpBlock.isBlocked(sms.body) && !f.allowOtp) {
                step(f, StepCode.OTP_BLOCKED)
                continue
            }

            // 전역 스팸 차단: OTP 차단 다음, 조건 판정 앞. SMS 는 번호·본문 규칙을 본다(phoneOrRcs = true).
            val spam = SpamBlock.match(spamRules, sms.address, sms.body, phoneOrRcs = true) { a, b -> PhoneNumberUtils.compare(a, b) }
            if (spam != null) {
                Log.i(tag, "Filter(${f.name}): block spam")
                step(f, StepCode.SPAM_BLOCKED_PREFIX + spam)
                continue
            }

            if (!f.matchAll) {
                val ignoreCase = f.ignoreCase
                val wildcard = f.wildcard
                val senderOk = ConditionMatch.matchSender(
                    f.senderCond, sms.address, ignoreCase, wildcard, f.level,
                ) { a, b -> PhoneNumberUtils.compare(a, b) }
                val textOk = ConditionMatch.matchText(f.bodyCond, sms.body, ignoreCase, wildcard)
                if (!senderOk || !textOk) {
                    step(f, if (!senderOk && !textOk) StepCode.NOT_MATCH_BOTH else if (!senderOk) StepCode.NOT_MATCH_SENDER else StepCode.NOT_MATCH_TEXT)
                    continue
                }
            }

            // 단어 바꾸기는 본문에만 적용하고, 그 결과를 템플릿에 넣는다(SMS 필터: 바꾸기 → 템플릿).
            val body = ReplaceWord.forRule(f, sms.body)
            val text = Template.apply(
                f.template,
                TemplateInput(
                    body = body,
                    address = sms.address,
                    simName = simInName,
                    timeMillis = sms.timeMillis,
                    filterName = f.name,
                    contactName = { Contacts.lookup(ctx, sms.address) },
                    formattedAddress = { PhoneNumberUtils.formatNumber(sms.address, Locale.getDefault().country) },
                ),
            )?.replace("\r\n", "\n") ?: ""
            if (text.replace("\n", "").isEmpty() && imageCount == 0) {
                step(f, StepCode.EMPTY_BODY)
                continue
            }

            queued += queueRows(f, s, text, origContent = sms.body, inNumber = sms.address, recvTime = sms.timeMillis, simSlot = simSlot, simInName = simInName, hid = hid) { code -> step(f, code) }
        }
        return Reapply.anyQueued(queued)
    }

    /**
     * 이력 항목의 "필터 다시 적용": 이력 행의 필드로 수신 이벤트를 복원해 같은 판정·전달 경로를 다시 돈다.
     * 앱 사용 꺼짐·행 없음·복원 불가면 false. 전역 근무시간·로밍·권한 게이트는 거치지 않는다(호출하는 쪽이 게이트 앞에서 처리한다).
     */
    suspend fun reapply(historyId: Long): Boolean {
        val h = db.received().get(historyId)
        if (h == null) {
            Log.w(tag, "Resend AppHistory rowId=$historyId: not found")
            return false
        }
        Log.i(tag, "Resend AppHistory rowId=$historyId kind=${h.kind}")
        if (!Reapply.canReapply(h)) return false
        return if (h.kind == Reapply.KIND_NOTI) {
            onNotification(Reapply.toNotification(h), existingHid = h.id)
        } else {
            process(Reapply.toSms(h), h.simSlot, imageCount = 0, existingHid = h.id)
        }
    }

    /**
     * 알림 한 건을 알림 규칙으로 평가해 전달 건을 만든다. 알림 경로(SIM 슬롯 -1).
     * 규칙 순서·게이트: 켜짐 → 알림 규칙 → 대상 앱(코드 8) → 시간대(6) → 전달 내용 구성 → OTP(1) → 빈 알림(2) →
     * 스팸(10_) → 제목·본문 조건(11/5/3, v4 는 본문 조건만) → 템플릿·단어 바꾸기(version 2·3 은 템플릿 → 바꾸기, v4 는 바꾸기 → 템플릿) →
     * 빈 본문(12) → 수신처별 전달 건.
     */
    suspend fun onNotification(n: IncomingNotification, existingHid: Long? = null): Boolean {
        val s = settings.snapshot()
        if (!s.appEnable) {
            Log.i(tag, "checkMsg: App Disabled")
            return false
        }
        val now = System.currentTimeMillis()
        val hid = existingHid ?: run {
            purgeOldHistory(s.historyRetentionDays, now)
            db.received().insert(Reapply.fromNotification(n, now, appVersionCode(n.packageName)))
        }
        val queued = mutableListOf<Boolean>()
        suspend fun step(f: RuleEntity, code: String) {
            db.received().insertStep(ReceivedStepEntity(receivedId = hid, time = System.currentTimeMillis(), ruleName = f.name, code = code))
        }

        val pkg = NotiRule.strippedPackage(n.packageName)
        val spamRules = db.spam().all().map { SpamRule(it.id, it.type, it.data) }
        // 알림 앱 이름({앱}): 설치된 앱의 라벨, 못 찾으면 빈 문자열
        val appLabel = try {
            ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString()
        } catch (e: Exception) {
            ""
        }
        for (f in db.rules().all()) {
            if (!f.enabled) continue
            if (!f.isNotification) continue
            if (!NotiRule.packageMatches(f.apps, pkg)) {
                Log.i(tag, "Filter(${f.name}): Notification package does not match=$pkg")
                step(f, StepCode.PACKAGE_MISMATCH)
                continue
            }
            if (!ActiveHours.isActive(f.schedule)) {
                step(f, StepCode.SCHEDULE_SKIP)
                continue
            }

            // 전달 내용 구성·OTP(1)·빈 알림(2)·스팸(10_)·제목/본문 조건(11/5/3)은 NotiRule.evaluate 가 판정한다.
            // v3 이하 규칙은 고른 비트 필드를 이어 붙이고(없으면 oldText) 제목 조건도 본다. v4 규칙은 보낼 내용 구성(notiComposition,
            // 비면 자동)을 쓰고 제목 조건은 보지 않으며 빈 본문이면 항상 2 로 건너뛴다.
            val gate = NotiRule.evaluate(f, n) { title, body ->
                // 알림은 제목 규칙·본문 규칙만 본다(phoneOrRcs = false).
                SpamBlock.match(spamRules, title, body, phoneOrRcs = false) { a, b -> PhoneNumberUtils.compare(a, b) }
            }
            val composed = gate.body
            if (gate.skip != null) {
                Log.i(tag, "Filter(${f.name}): skip code=${gate.skip}")
                step(f, gate.skip)
                continue
            }

            fun template(body: String): String = Template.apply(
                f.template,
                TemplateInput(
                    body = body,
                    address = n.title,
                    simName = "",
                    timeMillis = n.timeMillis,
                    appLabel = appLabel,
                    subText = n.subText,
                    extraContent = n.extraContent,
                    filterName = f.name,
                    contactName = { Contacts.lookup(ctx, n.title) },
                    formattedAddress = { PhoneNumberUtils.formatNumber(n.title, Locale.getDefault().country) },
                ),
            ) ?: ""
            // 순서: 알림 규칙 version 2·3 은 템플릿 → 단어 바꾸기, 그 밖(구형·v4)은 바꾸기 → 템플릿. 마지막에 \r\n → \n.
            val text = NotiRule.render(f, composed, replace = { ReplaceWord.forRule(f, it) }, template = ::template)
            if (NotiText.isBlank(text) && n.imageCount == 0) {
                step(f, StepCode.EMPTY_BODY)
                continue
            }

            queued += queueRows(f, s, text, origContent = composed, inNumber = n.title, recvTime = n.timeMillis, simSlot = -1, simInName = "", hid = hid) { code -> step(f, code) }
        }
        return Reapply.anyQueued(queued)
    }

    /**
     * 처리 경로로 보내지 않고 끝낸 알림을 앱 이력에만 남긴다: 요약 알림을 전달하지 않도록
     * 설정됐거나(코드 23) 켜진 알림 규칙이 없을 때.
     */
    suspend fun recordSkippedNotification(n: IncomingNotification, code: String?) {
        val s = settings.snapshot()
        val now = System.currentTimeMillis()
        purgeOldHistory(s.historyRetentionDays, now)
        val hid = db.received().insert(Reapply.fromNotification(n, now, appVersionCode(n.packageName)))
        if (code != null) db.received().insertStep(ReceivedStepEntity(receivedId = hid, time = now, ruleName = "", code = code))
    }

    /** 규칙의 수신처마다 전달 건을 만들어 큐에 넣거나(지연 전달이면 예약) 한다. SMS·알림 경로가 같이 쓴다. */
    private suspend fun queueRows(
        f: RuleEntity,
        s: io.github.casimirkwon.smsforwarder.AppSettings,
        text: String,
        origContent: String,
        inNumber: String,
        recvTime: Long,
        simSlot: Int,
        simInName: String,
        hid: Long,
        step: suspend (String) -> Unit,
    ): Boolean {
        var queuedNow = false
        val outSlot = if (f.simOutSlot != -1) f.simOutSlot else s.defaultOutSlot
        val simOutName = SimUtil.simName(ctx, s, outSlot)
        for (dest in parseDestinations(f.recipients)) {
            // 지연 전달(규칙 옵션 128 + 지연 분): 곧바로 보내지 않고 예약 행(10000003)만 만들고 알람을 건다.
            // 이 경우 대기열·이력 단계("19_…")를 거치지 않는다.
            val delay = DelayPolicy.plan(f.delayEnabled, f.delayMinutes, System.currentTimeMillis(), Build.VERSION.SDK_INT)
            val row = DeliveryEntity(
                recvTime = recvTime,
                sendTime = delay?.sendTime ?: System.currentTimeMillis(),
                content = text,
                rawBody = origContent,
                sender = inNumber,
                recipient = dest,
                status = if (delay != null) SendResult.SCHEDULED else SendResult.SENDING,
                ruleName = f.name,
                notifyResult = f.notifyResult,
                saveHistory = f.saveHistory,
                simOutName = simOutName,
                simInName = simInName,
                ruleId = f.id,
                simInSlot = simSlot,
                simOutSlot = outSlot,
                logId = hid,
            )
            val rowId = db.deliveries().insert(row)
            if (delay != null) {
                Log.i(tag, "scheduled: filter=${f.name} send=$rowId out=$dest at=${delay.sendTime} (+${f.delayMinutes}분)")
                DelayAlarm.register(ctx, delay.sendTime)
                continue
            }
            Log.i(tag, "queued: filter=${f.name} send=$rowId out=$dest in=$inNumber")
            step(StepCode.QUEUED_PREFIX + "SMS")
            queue.enqueue(rowId)
            queuedNow = true
        }
        return queuedNow
    }

    /** 알림 앱의 versionCode. 요약 알림 접두어는 떼고 조회하며 실패하면 null. */
    private fun appVersionCode(packageName: String): Long? = try {
        ctx.packageManager.getPackageInfo(NotiRule.strippedPackage(packageName), 0).longVersionCode
    } catch (e: Exception) {
        null
    }

    private suspend fun purgeOldHistory(days: Int, now: Long) {
        val before = now - days.coerceAtLeast(1) * 24L * 3600_000L
        db.received().deleteOlderThan(before)
        db.received().deleteStepsOlderThan(before)
    }
}
