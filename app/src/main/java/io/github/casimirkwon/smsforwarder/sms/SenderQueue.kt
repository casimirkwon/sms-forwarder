package io.github.casimirkwon.smsforwarder.sms

import io.github.casimirkwon.smsforwarder.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 전달 건을 한 줄로 세워 보낸다. 건마다
 * 설정 범위(message_delay_min~max 초, 기본 3~7) 안의 무작위 시간만큼 기다린 뒤 전송을 시작한다.
 */
class SenderQueue(
    private val settings: Settings,
    private val sender: SmsSender,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<Pair<Long, Boolean>>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)

    /** 아직 전송을 시작하지 못했거나 전송 호출이 끝나지 않은 건 수. */
    val pendingCount: Int get() = pending.get()

    init {
        scope.launch {
            for ((rowId, fresh) in channel) {
                val s = settings.snapshot()
                val minMs = s.messageDelayMin * 1000
                val maxMs = maxOf(s.messageDelayMax * 1000, minMs + 1)
                delay(Random.nextInt(minMs, maxMs).toLong())
                launch {
                    try {
                        sender.send(rowId, fresh)
                    } finally {
                        pending.decrementAndGet()
                    }
                }
            }
        }
    }

    /** [fresh] = 파이프라인에서 막 만든 건(true) / 재전송 큐에서 다시 읽은 건(false). */
    fun enqueue(rowId: Long, fresh: Boolean = true) {
        pending.incrementAndGet()
        channel.trySend(rowId to fresh)
    }
}
