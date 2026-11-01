package io.github.casimirkwon.smsforwarder.sms

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import io.github.casimirkwon.smsforwarder.AppSettings
import io.github.casimirkwon.smsforwarder.util.Perms

/** 듀얼 SIM 처리. */
object SimUtil {
    data class Sim(val subscriptionId: Int, val slotIndex: Int, val number: String?, val displayName: String?)

    /** 활성 구독 목록(READ_PHONE_STATE 필요). 실패하면 빈 목록. */
    fun activeSims(ctx: Context): List<Sim> {
        if (!Perms.hasPhoneState(ctx)) return emptyList()
        return try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            var list: List<SubscriptionInfo>? = null
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    list = sm.completeActiveSubscriptionInfoList
                } catch (_: SecurityException) {
                }
            }
            if (list == null) list = sm.activeSubscriptionInfoList
            (list ?: emptyList()).map {
                Sim(it.subscriptionId, it.simSlotIndex, phoneNumber(ctx, it), it.displayName?.toString())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun phoneNumber(ctx: Context, info: SubscriptionInfo): String? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(SubscriptionManager::class.java).getPhoneNumber(info.subscriptionId).ifEmpty { null }
        } else info.number?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** subscriptionId → 슬롯 인덱스(0/1). 못 찾으면 -1. */
    fun slotForSubId(ctx: Context, subId: Int): Int =
        activeSims(ctx).firstOrNull { it.subscriptionId == subId }?.slotIndex ?: -1

    /**
     * 수신 인텐트 extras 에서 SIM 슬롯을 얻는다. READ_PHONE_STATE 가 있을 때만,
     * SUBSCRIPTION_INDEX → subscription_id → subscription 순으로 subId 를 읽는다. 없으면 -1.
     */
    fun slotFromExtras(ctx: Context, extras: Bundle?): Int {
        if (extras == null || !Perms.hasPhoneState(ctx)) return -1
        val keys = arrayOf("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription_id", "subscription")
        var subId: Int? = null
        for (k in keys) {
            val v = extras.get(k)
            if (v is Int) {
                subId = v
                break
            }
        }
        return if (subId != null) slotForSubId(ctx, subId) else -1
    }

    fun defaultSmsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()

    /** 슬롯 → 발신용 SmsManager. slot == -1 이면 기본, 슬롯에 해당하는 구독이 없으면 null. */
    fun smsManagerForSlot(ctx: Context, slot: Int): SmsManager? {
        if (slot == -1) return defaultSmsManager(ctx)
        val sim = activeSims(ctx).firstOrNull { it.slotIndex == slot } ?: return null
        return if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(sim.subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(sim.subscriptionId)
        }
    }

    /** 슬롯 → SIM 이름(사용자 지정명 → 전화번호 → 표시 이름). 슬롯을 모르면 빈 문자열. */
    fun simName(ctx: Context, s: AppSettings, slot: Int): String {
        if (slot < 0) return ""
        val custom = when (slot) {
            0 -> s.simName1
            1 -> s.simName2
            else -> ""
        }
        if (custom.isNotEmpty()) return custom
        val sim = activeSims(ctx).firstOrNull { it.slotIndex == slot }
        return sim?.number ?: sim?.displayName ?: ""
    }
}
