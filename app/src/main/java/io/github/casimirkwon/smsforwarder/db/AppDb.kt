package io.github.casimirkwon.smsforwarder.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 앱 데이터베이스. 1.1.0 에서 새 이름·새 구조로 다시 시작했다(앱 아이디가 바뀌어 이전 데이터는 이어받지 않는다). */
@Database(
    entities = [
        RuleEntity::class,
        DeliveryEntity::class,
        ReceivedEntity::class,
        ReceivedStepEntity::class,
        PendingEvent::class,
        SpamRuleEntity::class,
        RecentSend::class,
        RepeatBlock::class,
        NotiSeenRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun deliveries(): DeliveryDao
    abstract fun received(): ReceivedDao
    abstract fun pendingEvents(): PendingEventDao
    abstract fun spam(): SpamDao
    abstract fun recentSends(): RecentSendDao
    abstract fun repeatBlocks(): RepeatBlockDao
    abstract fun notiSeen(): NotiSeenDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "forwarder.db")
                .build().also { instance = it }
        }
    }
}
