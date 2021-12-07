package com.itmo.microservices.bombardierCore.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.bombardierCore.bombardier.external.OrderStatus
import com.itmo.microservices.bombardierCore.bombardier.external.ExternalServiceApi
import com.itmo.microservices.bombardierCore.bombardier.flow.UserManagement
import com.itmo.microservices.bombardierCore.bombardier.logging.OrderChangeItemsAfterFinalizationNotableEvents.*
import com.itmo.microservices.bombardierCore.bombardier.utils.ConditionAwaiter
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class OrderChangeItemsAfterFinalizationStage : TestStage {
    @InjectEventLogger
    private lateinit var eventLogger: EventLogger

    override suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi): TestStage.TestContinuationType {
        val shouldRunStage = Random.nextBoolean()
        if (!shouldRunStage) {
            eventLogger.info(I_STATE_SKIPPED, testCtx().orderId)
            return TestStage.TestContinuationType.CONTINUE
        }

        eventLogger.info(I_START_CHANGING_ITEMS, testCtx().orderId)

        repeat(Random.nextInt(1, 10)) {
            val itemToAdd = externalServiceApi.getAvailableItems(testCtx().userId!!).random()

            val amount = Random.nextInt(1, 13)
            externalServiceApi.putItemToOrder(testCtx().userId!!, testCtx().orderId!!, itemToAdd.id, amount)

            ConditionAwaiter.awaitAtMost(3, TimeUnit.SECONDS)
                .condition {
                    val theOrder = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                    theOrder.itemsMap.any { it.key.id == itemToAdd.id && it.value == amount }
                            && theOrder.status == OrderStatus.OrderCollecting
                }
                .onFailure {
                    eventLogger.error(E_ORDER_CHANGE_AFTER_FINALIZATION_FAILED, itemToAdd.id, amount, testCtx().orderId)
                    throw TestStage.TestStageFailedException("Exception instead of silently fail")
                }.startWaiting()
        }

        return TestStage.TestContinuationType.CONTINUE
    }
}