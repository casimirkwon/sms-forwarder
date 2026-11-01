package io.github.casimirkwon.smsforwarder.sms

import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import io.github.casimirkwon.smsforwarder.core.SmsPart

/** SMS_RECEIVED 인텐트의 pdus → 조각 목록. 합치기는 SmsMerge. */
object SmsIntentParser {
    fun parts(intent: Intent): List<SmsPart> {
        val extras = intent.extras ?: return emptyList()
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val pdus = extras.get("pdus") as? Array<Any?> ?: return emptyList()
        val format = extras.getString("format", "")
        val out = ArrayList<SmsPart>(pdus.size)
        for (p in pdus) {
            val bytes = p as? ByteArray ?: continue
            val msg = (if (format.isNullOrEmpty()) SmsMessage.createFromPdu(bytes) else SmsMessage.createFromPdu(bytes, format))
                ?: continue
            try {
                out.add(SmsPart(msg.displayOriginatingAddress ?: "", msg.messageBody ?: "", msg.timestampMillis))
            } catch (e: Exception) {
                Log.w("SmsIntentParser", "pdu skipped", e)
            }
        }
        return out
    }
}
