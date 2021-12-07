package com.itmo.microservices.bombardierCore.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.bombardierCore.bombardier.external.ExternalServiceApi
import com.itmo.microservices.bombardierCore.bombardier.external.OrderStatus
import com.itmo.microservices.bombardierCore.bombardier.flow.UserManagement
import com.itmo.microservices.bombardierCore.bombardier.logging.OrderAbandonedNotableEvents
import com.itmo.microservices.bombardierCore.bombardier.utils.ConditionAwaiter
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class OrderAbandonedStage : TestStage {
    @InjectEventLogger
    private lateinit var eventLogger: EventLogger


    override suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi): TestStage.TestContinuationType {
        val shouldBeAbandoned = Random.nextBoolean()
        if (shouldBeAbandoned) {
            val lastBucketTimestamp = externalServiceApi.abandonedCardHistory(testCtx().orderId!!)
                .map { it.timestamp }
                .maxByOrNull { it } ?: 0
            delay(120_000) //todo shine2

            ConditionAwaiter.awaitAtMost(30, TimeUnit.SECONDS)
                .condition {
                    val bucketLogRecord = externalServiceApi.abandonedCardHistory(testCtx().orderId!!)
                    bucketLogRecord.maxByOrNull { it.timestamp }?.timestamp ?: 0 > lastBucketTimestamp
                }
                .onFailure {
                    eventLogger.error(OrderAbandonedNotableEvents.E_ORDER_ABANDONED, testCtx().orderId)
                    throw TestStage.TestStageFailedException("Exception instead of silently fail")
                }.startWaiting()

            val recentLogRecord = externalServiceApi.abandonedCardHistory(testCtx().orderId!!)
                .maxByOrNull { it.timestamp }

            if (recentLogRecord!!.userInteracted) {
                val order = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                if (order.status != OrderStatus.OrderCollecting) {
                    eventLogger.error(
                        OrderAbandonedNotableEvents.E_USER_INTERACT_ORDER, testCtx().orderId,
                        OrderStatus.OrderCollecting::class.simpleName, order.status
                    )
                    return TestStage.TestContinuationType.FAIL
                }
            } else {
                ConditionAwaiter.awaitAtMost(15, TimeUnit.SECONDS)
                    .condition {
                        val order = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                        order.status == OrderStatus.OrderDiscarded
                    }
                    .onFailure {
                        val order = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                        eventLogger.error(
                            OrderAbandonedNotableEvents.E_USER_DIDNT_INTERACT_ORDER, testCtx().orderId,
                            OrderStatus.OrderCollecting::class.simpleName, order.status
                        )
                        throw TestStage.TestStageFailedException("Exception instead of silently fail")
                    }
            }

        }
        return TestStage.TestContinuationType.CONTINUE
    }
}