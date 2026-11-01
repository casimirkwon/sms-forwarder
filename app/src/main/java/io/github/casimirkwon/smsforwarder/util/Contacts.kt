package io.github.casimirkwon.smsforwarder.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** 번호 → 주소록 이름(PhoneLookup, 결과 캐시). 권한이 없거나 이름이 없으면 null. */
object Contacts {
    private val cache = HashMap<String, String>()

    fun lookup(ctx: Context, number: String?): String? {
        if (number.isNullOrEmpty() || !Perms.hasContacts(ctx)) return null
        synchronized(cache) {
            cache[number]?.let { return it.ifEmpty { null } }
        }
        var name: String? = null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (_: Exception) {
        }
        synchronized(cache) { cache[number] = name ?: "" }
        return name
    }
}
