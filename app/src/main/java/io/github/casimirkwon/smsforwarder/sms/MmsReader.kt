package io.github.casimirkwon.smsforwarder.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.casimirkwon.smsforwarder.core.ForwardPipeline
import io.github.casimirkwon.smsforwarder.core.HandledMms
import io.github.casimirkwon.smsforwarder.core.MmsInbox
import io.github.casimirkwon.smsforwarder.core.MmsParts
import io.github.casimirkwon.smsforwarder.core.MmsRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * content://mms 에서 새로 받은 MMS 를 읽어 SMS 와 같은 경로(파이프라인)로 넘긴다.
 * 이미지 등 비텍스트 파트는 SMS 로 보낼 수 없으므로 개수만 센다(MMS 전환 전송을 쓰지 않는 기본 설정에서는 텍스트만 보낸다).
 */
class MmsReader(
    private val ctx: Context,
    private val pipeline: ForwardPipeline,
) {
    companion object {
        private const val TAG = "MmsReader"
        private const val PREFS = "mms_state"
        private const val KEY_LAST = "last_seen_date"
        private const val KEY_HANDLED = "handled"
        private val INBOX = Uri.parse("content://mms/inbox")
        private val PART = Uri.parse("content://mms/part")

        /** 처리한 MMS 기록. 처음 쓸 때 저장소에서 읽고, 전달 직전에 저장소에 남긴다(강제 종료 뒤에도 유지). */
        private var handled: HandledMms? = null
    }

    private fun handledLog(): HandledMms = synchronized(Companion) {
        handled ?: HandledMms.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HANDLED, null)).also { handled = it }
    }

    /** 처리한 MMS 기록을 바로 저장한다(commit: 곧바로 프로세스가 죽어도 남도록). */
    private fun persistHandled() {
        val text = synchronized(Companion) { handledLog().also { it.trim(System.currentTimeMillis()) }.encode() }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HANDLED, text).commit()
    }

    private class Parts(val text: String?, val imageCount: Int)

    /**
     * 읽기 시도 한 번. 수신 후 경과 시간으로 content-location 조건 사용 여부와 다음 간격을 정한다.
     * @param attempt 읽기 시도 번호(1부터)
     * @return 끝났으면 null(새 MMS 를 처리했거나 마지막 시도), 아니면 다음 시도까지 기다릴 시간(ms).
     */
    suspend fun readOnce(attempt: Int, initTimeMs: Long, url: String?): Long? = withContext(Dispatchers.IO) {
        val elapsed = MmsInbox.elapsedMs(System.currentTimeMillis(), initTimeMs)
        val next = MmsInbox.nextDelayMs(attempt, elapsed)
        val useUrl = MmsInbox.urlForElapsed(elapsed, url)
        Log.i(TAG, "readMMS attempt=$attempt, elapsed=$elapsed, url=${useUrl != null}, final=${next == null}")
        val done = try {
            readInbox(useUrl, initTimeMs, next == null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "readMMS error: ${e.javaClass.simpleName} ${e.message}")
            false
        }
        if (done) return@withContext null
        if (next == null) Log.i(TAG, "readMMS max retry")
        next
    }

    /** 수신함을 훑는다. 끝났으면(처리했고 기다리는 행이 없음) true. */
    private suspend fun readInbox(url: String?, initTimeMs: Long, isFinal: Boolean): Boolean {
        val rows = ArrayList<MmsRow>()
        val cursor = ctx.contentResolver.query(
            INBOX, arrayOf("_id", "date", "sub_id"),
            if (url != null) "ct_l=?" else null, if (url != null) arrayOf(url) else null, "date DESC",
        )
        if (cursor == null || cursor.count == 0) {
            Log.i(TAG, "There is no MMS $url, cursor=${cursor != null}")
            cursor?.close()
            if (url != null) logLatestInbox()
            return false
        }
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndex("_id"))
                val dateStr = it.getString(it.getColumnIndex("date"))
                val subId = it.getString(it.getColumnIndex("sub_id"))
                val date = dateStr?.toLongOrNull()
                if (date == null) {
                    Log.i(TAG, "readMMS invalid date - |$id|$dateStr|$subId")
                    continue
                }
                rows.add(MmsRow(id, date, subId))
            }
        }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = if (sp.contains(KEY_LAST)) sp.getLong(KEY_LAST, 0L) else null
        val pivot = MmsInbox.pivotSec(initTimeMs, saved, url != null, System.currentTimeMillis())
        Log.i(TAG, "savedLastTime=$saved, initTime=${initTimeMs / 1000}, pivotTime=$pivot, url=${url != null}, size=${rows.size}")
        // 행마다 준비 점검·본문 읽기를 하고 전달은 목록(오래된 것부터)으로 모았다가 훑기가 끝난 뒤 차례로 한다.
        val deliveries = ArrayList<suspend () -> Unit>()
        val seenSnapshot = synchronized(Companion) { handledLog().keys() }
        val scan = MmsInbox.scan(rows, pivot, seenSnapshot, url != null) { r -> checkRow(r, isFinal, deliveries) }
        for (d in deliveries) {
            try {
                d()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "readMMS checkMsg error: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        if (scan.saveTime > 0) sp.edit().putLong(KEY_LAST, scan.saveTime).apply()
        Log.i(TAG, "readMMS=${scan.done}, handled=${scan.handled}, waiting=${scan.hasWaiting}, lastTime=${scan.saveTime}")
        return scan.done
    }

    /**
     * 행 하나의 준비 점검(파트 수·보낸이). 준비 안 됐으면 false(기록하지 않고 다음 시도에 다시 읽음).
     * 준비됐으면 본문을 읽어 전달 목록 맨 앞에 넣고 true. 이미 다른 경로에서 처리한 행이면 그냥 true.
     */
    private fun checkRow(r: MmsRow, isFinal: Boolean, deliveries: MutableList<suspend () -> Unit>): Boolean {
        val sender = senderOf(r.id)
        val partCount = ctx.contentResolver.query(PART, arrayOf("_id"), "mid=?", arrayOf(r.id), null)?.use { it.count } ?: 0
        val state = MmsInbox.readiness(partCount, sender)
        Log.i(TAG, "readMMS check - ${MmsInbox.key(r)}, sender=${if (sender.isNullOrBlank()) "empty" else "ok"}, parts=$partCount, state=$state")
        if (!MmsInbox.canDeliver(state, isFinal)) {
            Log.i(TAG, "readMMS not ready - ${MmsInbox.key(r)}, state=$state, final=$isFinal")
            return false
        }
        val parts = readParts(r.id)
        val slot = r.subId?.toIntOrNull()?.let { SimUtil.slotForSubId(ctx, it) } ?: -1
        if (!synchronized(Companion) { handledLog().add(MmsInbox.key(r), System.currentTimeMillis()) }) {
            Log.i(TAG, "readMMS already processed - ${MmsInbox.key(r)}")
            return true
        }
        Log.i(TAG, "readMMS - ${MmsInbox.key(r)}, state=$state, text=${parts.text?.length} images=${parts.imageCount}")
        deliveries.add(0) {
            persistHandled()
            pipeline.onMms(parts.text, sender, r.date * 1000, parts.imageCount, slot)
        }
        return true
    }

    /** url 로 못 찾았을 때 최신 수신함 행을 로그에 남긴다(진단용). */
    private fun logLatestInbox() {
        try {
            ctx.contentResolver.query(INBOX, arrayOf("_id", "date", "ct_l"), null, null, "date DESC limit 1")?.use {
                if (it.moveToFirst()) {
                    Log.i(TAG, "latest inbox MMS - |${it.getString(0)}|${it.getString(1)}, hasUrl=${!it.getString(2).isNullOrEmpty()}")
                } else {
                    Log.i(TAG, "latest inbox MMS - none")
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "latest inbox MMS - error ${e.javaClass.simpleName}")
        }
    }

    /** 보낸이: content://mms/{id}/addr 의 type=137(from) 첫 행. */
    private fun senderOf(id: String): String? {
        val c = ctx.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address"), "msg_id = ? and type = 137", arrayOf(id), "_id asc limit 1",
        ) ?: return null
        return c.use { if (it.moveToFirst()) it.getString(it.getColumnIndex("address")) else null }
    }

    /** 파트 목록에서 text/plain 은 이어 붙이고 image/… 는 개수만 센다(seq 컬럼이 있으면 seq 순, 없으면 _id 순). */
    private fun readParts(mid: String): Parts {
        val hasSeq = try {
            ctx.contentResolver.query(PART, arrayOf("seq"), null, null, null)?.use { it.columnCount > 0 } ?: false
        } catch (_: Exception) {
            false
        }
        val projection = if (hasSeq) arrayOf("mid", "_id", "ct", "_data", "text", "seq") else arrayOf("mid", "_id", "ct", "_data", "text")
        val c = ctx.contentResolver.query(PART, projection, "mid=?", arrayOf(mid), if (hasSeq) "seq ASC" else "_id ASC")
        if (c == null || c.count == 0) {
            c?.close()
            return Parts(null, 0)
        }
        val texts = ArrayList<String?>()
        var images = 0
        c.use {
            val idCol = it.getColumnIndexOrThrow("_id")
            val ctCol = it.getColumnIndexOrThrow("ct")
            val textCol = it.getColumnIndex("text")
            val dataCol = it.getColumnIndex("_data")
            while (it.moveToNext()) {
                val partId = it.getString(idCol)
                when (MmsParts.kind(it.getString(ctCol))) {
                    MmsParts.Kind.TEXT ->
                        texts.add(if (it.getString(dataCol).isNullOrEmpty()) it.getString(textCol) else readTextFile(partId))
                    MmsParts.Kind.IMAGE -> images++
                    MmsParts.Kind.IGNORE -> {}
                }
            }
        }
        return Parts(MmsParts.joinTexts(texts), images)
    }

    /** 파일에 저장된 본문 파트: UTF-8 로 줄을 읽어 첫 빈 줄까지 이어 붙인다. 읽다 실패하면 읽은 데까지. */
    private fun readTextFile(partId: String): String? {
        val lines = ArrayList<String?>()
        try {
            ctx.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { ins ->
                val br = ins.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = br.readLine()
                    lines.add(line)
                    if (line.isNullOrEmpty()) break
                }
            } ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "text part read failed: $partId", e)
        }
        return MmsParts.joinFileLines(lines.asSequence())
    }
}
