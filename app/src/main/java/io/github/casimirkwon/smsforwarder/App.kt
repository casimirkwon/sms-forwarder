package io.github.casimirkwon.smsforwarder

import android.app.Application
import android.content.Context
import io.github.casimirkwon.smsforwarder.core.ExportImport
import io.github.casimirkwon.smsforwarder.core.ForwardPipeline
import io.github.casimirkwon.smsforwarder.db.AppDb
import io.github.casimirkwon.smsforwarder.sms.MmsReader
import io.github.casimirkwon.smsforwarder.sms.ResultHandler
import io.github.casimirkwon.smsforwarder.sms.SendDefense
import io.github.casimirkwon.smsforwarder.sms.SenderQueue
import io.github.casimirkwon.smsforwarder.sms.SmsSender
import io.github.casimirkwon.smsforwarder.util.Notifs

/** 앱 전역에서 쓰는 객체 묶음. */
class Graph(ctx: Context) {
    val db = AppDb.get(ctx)
    val settings = Settings(ctx)
    val results = ResultHandler(ctx, db, settings)
    val defense = SendDefense(db, settings)
    val sender = SmsSender(ctx, db, settings, results, defense)
    val queue = SenderQueue(settings, sender)
    val pipeline = ForwardPipeline(ctx, db, settings, queue)
    val mms = MmsReader(ctx, pipeline)
}

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifs.createChannels(this)
    }
}
