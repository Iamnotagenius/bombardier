package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.flow.*
import com.itmo.microservices.demo.bombardier.logging.OrderCommonNotableEvents
import com.itmo.microservices.demo.bombardier.logging.OrderDeliveryNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class OrderDeliveryStage(
    private val serviceApi: ServiceApi
) : TestStage {
    @InjectEventLogger
    private lateinit var eventLogger: EventLogger

    override suspend fun run(): TestStage.TestContinuationType {
        val orderBeforeDelivery = serviceApi.getOrder(testCtx().orderId!!)

        if (orderBeforeDelivery.status !is OrderStatus.OrderPayed) {
            eventLogger.error(E_INCORRECT_ORDER_STATUS, orderBeforeDelivery.id)
            return TestStage.TestContinuationType.FAIL
        }

        if (orderBeforeDelivery.deliveryDuration == null) {
            eventLogger.error(E_NULL_DELIVERY_TIME, orderBeforeDelivery.id)
            return TestStage.TestContinuationType.FAIL
        }

        serviceApi.simulateDelivery(testCtx().orderId!!)

        ConditionAwaiter.awaitAtMost(orderBeforeDelivery.deliveryDuration.toSeconds() + 5, TimeUnit.SECONDS)
            .condition {
                val updatedOrder = serviceApi.getOrder(testCtx().orderId!!)
                updatedOrder.status is OrderStatus.OrderDelivered ||
                        updatedOrder.status is OrderStatus.OrderRefund &&
                        serviceApi.userFinancialHistory(
                            testCtx().userId!!,
                            testCtx().orderId!!
                        ).last().type == FinancialOperationType.REFUND
            }
            .onFailure {
                eventLogger.error(E_ORDER_STATUS_NOT_CHANGED_AND_NO_REFUND, orderBeforeDelivery.id)
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }
            .startWaiting()
        val orderAfterDelivery = serviceApi.getOrder(testCtx().orderId!!)
        when (orderAfterDelivery.status) {
            is OrderStatus.OrderDelivered -> {
                val deliveryLog = serviceApi.deliveryLog(testCtx().orderId!!)
                if (deliveryLog.outcome != DeliverySubmissionOutcome.SUCCESS) {
                    eventLogger.error(E_DELIVERY_OUTCOME_FAIL, orderAfterDelivery.id)
                    return TestStage.TestContinuationType.FAIL
                }
                val expectedDeliveryTime = Duration.ofMillis(orderBeforeDelivery.paymentHistory.last().timestamp)
                    .plus(Duration.ofSeconds(orderBeforeDelivery.deliveryDuration.toSeconds()))
                if (orderAfterDelivery.status.deliveryFinishTime > expectedDeliveryTime.toMillis()) {
                    eventLogger.error(
                        E_DELIVERY_LATE,
                        orderAfterDelivery.id,
                        orderAfterDelivery.status.deliveryFinishTime,
                        expectedDeliveryTime.toMillis()
                    )
                    return TestStage.TestContinuationType.FAIL
                }
                eventLogger.info(I_DELIVERY_SUCCESS, orderAfterDelivery.id)
            }
            is OrderStatus.OrderRefund -> {
                val userFinancialHistory = serviceApi.userFinancialHistory(testCtx().userId!!, testCtx().orderId!!)
                if (userFinancialHistory.filter { it.type == FinancialOperationType.WITHDRAW }.sumOf { it.amount } !=
                    userFinancialHistory.filter { it.type == FinancialOperationType.REFUND }.sumOf { it.amount }) {
                    eventLogger.error(E_WITHDRAW_AND_REFUND_DIFFERENT, orderAfterDelivery.id,
                        userFinancialHistory.filter { it.type == FinancialOperationType.WITHDRAW }
                            .sumOf { it.amount },
                        userFinancialHistory.filter { it.type == FinancialOperationType.REFUND }
                            .sumOf { it.amount }
                    )
                }
                eventLogger.info(I_REFUND_CORRECT, orderAfterDelivery.id)
            }
            else -> {
                eventLogger.error(
                    OrderCommonNotableEvents.E_ILLEGAL_ORDER_TRANSITION,
                    orderBeforeDelivery.id,
                    orderBeforeDelivery.status,
                    orderAfterDelivery.status
                )
                return TestStage.TestContinuationType.FAIL
            }
        }
        return TestStage.TestContinuationType.CONTINUE
    }
}