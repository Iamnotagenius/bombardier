package com.itmo.microservices.demo.bombardier.flow

import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.exception.BadRequestException
import com.itmo.microservices.demo.bombardier.external.knownServices.KnownServices
import com.itmo.microservices.demo.bombardier.external.knownServices.ServiceWithApiAndAdditional
import com.itmo.microservices.demo.bombardier.stages.*
import com.itmo.microservices.demo.bombardier.stages.TestStage.TestContinuationType.CONTINUE
import com.itmo.microservices.demo.common.RateLimiter
import com.itmo.microservices.demo.common.SlowStartRateLimiter
import com.itmo.microservices.demo.common.logging.LoggerWrapper
import com.itmo.microservices.demo.common.metrics.Metrics
import com.itmo.microservices.demo.common.metrics.PromMetrics
import io.micrometer.core.instrument.util.NamedThreadFactory
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@Service
class TestController(
    private val knownServices: KnownServices,
    val choosingUserAccountStage: ChoosingUserAccountStage,
    val orderCreationStage: OrderCreationStage,
    val orderPaymentStage: OrderPaymentStage,
) {
    companion object {
        val log = LoggerFactory.getLogger(TestController::class.java)
    }

    val runningTests = ConcurrentHashMap<String, TestingFlow>()

    private val executor: ExecutorService = Executors.newFixedThreadPool(16, NamedThreadFactory("test-controller-executor")).also {
        Metrics.executorServiceMonitoring(it, "test-controller-executor")
    }

//    private val testInvokationScope = CoroutineScope(executor.asCoroutineDispatcher())
    private val testLaunchScope = CoroutineScope(executor.asCoroutineDispatcher())

//    private val testStages = listOf<TestStage>(
//        choosingUserAccountStage.asErrorFree().asMetricRecordable(),
//        orderCreationStage.asErrorFree().asMetricRecordable(),
//        orderCollectingStage.asErrorFree().asMetricRecordable(),
//        OrderAbandonedStage(serviceApi).asErrorFree(),
//        orderFinalizingStage.asErrorFree().asMetricRecordable(),
//        orderSettingDeliverySlotsStage.asErrorFree().asMetricRecordable(),
//        orderChangeItemsAfterFinalizationStage.asErrorFree(),
//        orderFinalizingStage.asErrorFree(),
//        orderSettingDeliverySlotsStage.asErrorFree(),
//        orderPaymentStage.asErrorFree().asMetricRecordable(),
//        orderDeliveryStage.asErrorFree()
//    )

//    private val testStage = mutableListOf<TestStage>().let {
//        it.add(choosingUserAccountStage.asErrorFree().asMetricRecordable())
//        it.add(orderCreationStage.asErrorFree().asMetricRecordable())
//        orderPaymentStage.asErrorFree().asMetricRecordable()
//    }


    fun startTestingForService(params: TestParameters) {
        val logger = LoggerWrapper(log, params.serviceName)

        val testingFlowCoroutine = SupervisorJob()

        val v = runningTests.putIfAbsent(params.serviceName, TestingFlow(params, testingFlowCoroutine))
        if (v != null) {
            throw BadRequestException("There is no such feature launch several flows for the service in parallel :(")
        }

        val descriptor = knownServices.descriptorFromName(params.serviceName)
        val stuff = knownServices.getStuff(params.serviceName)

        runBlocking {
            stuff.userManagement.createUsersPool(params.numberOfUsers)
        }

        logger.info("Launch coroutine for $descriptor")
        launchTestCycle(descriptor, stuff)
    }

    fun getTestingFlowForService(serviceName: String): TestingFlow {
        return runningTests[serviceName] ?: throw IllegalArgumentException("There is no running test for $serviceName")
    }

    suspend fun stopTestByServiceName(serviceName: String) {
        runningTests[serviceName]?.testFlowCoroutine?.cancelAndJoin()
            ?: throw BadRequestException("There is no running tests with serviceName = $serviceName")
        runningTests.remove(serviceName)
    }

    suspend fun stopAllTests() {
        runningTests.values.forEach {
            it.testFlowCoroutine.cancelAndJoin()
        }
        runningTests.clear()
    }

    class TestingFlow(
        val testParams: TestParameters,
        val testFlowCoroutine: CompletableJob,
        val testsStarted: AtomicInteger = AtomicInteger(1),
        val testsFinished: AtomicInteger = AtomicInteger(0)
    )

    private fun launchTestCycle(
        descriptor: ServiceDescriptor,
        stuff: ServiceWithApiAndAdditional,
    ) {
        val logger = LoggerWrapper(log, descriptor.name)
        val serviceName = descriptor.name
        val testingFlow = runningTests[serviceName] ?: return

        val params = testingFlow.testParams
        val rateLimiter = SlowStartRateLimiter(params.ratePerSecond, TimeUnit.SECONDS, slowStartOn = true)

        val testStages = mutableListOf<TestStage>().also {
            it.add(choosingUserAccountStage.asErrorFree().asMetricRecordable())
            it.add(orderCreationStage.asErrorFree().asMetricRecordable())
            if (!testingFlow.testParams.stopAfterOrderCreation) {
                it.add(orderPaymentStage.asErrorFree().asMetricRecordable())
            }
        }

        for (i in 1..100) {
            testLaunchScope.launch(
                TestContext(
                    serviceName = serviceName,
                    launchTestsRatePerSec = testingFlow.testParams.ratePerSecond,
                    totalTestsNumber = testingFlow.testParams.numberOfTests,
                    testSuccessByThePaymentFact = testingFlow.testParams.testSuccessByThePaymentFact,
                    stopAfterOrderCreation = testingFlow.testParams.stopAfterOrderCreation
                )
            ) {
                while (true) {
                    val testNum = testingFlow.testsStarted.getAndIncrement()
                    if (testNum > params.numberOfTests) {
                        logger.error("Wrapping up test flow. Number of tests exceeded")
                        runningTests.remove(serviceName)
                        return@launch
                    }

                    rateLimiter.tickBlocking()
                    logger.info("Starting $testNum test for service $serviceName, parent job is ${testingFlow.testFlowCoroutine}")
                    launchNewTestFlow(serviceName, testingFlow, descriptor, stuff, testStages)
                }
            }
        }
    }

    private suspend fun launchNewTestFlow(
        serviceName: String,
        testingFlow: TestingFlow,
        descriptor: ServiceDescriptor,
        stuff: ServiceWithApiAndAdditional,
        testStages: MutableList<TestStage>
    ) {
        val logger = LoggerWrapper(log, descriptor.name)

        val testStartTime = System.currentTimeMillis()
//        testInvokationScope.launch(
//            testingFlow.testFlowCoroutine + TestContext(
//                serviceName = serviceName,
//                launchTestsRatePerSec = testingFlow.testParams.ratePerSecond,
//                totalTestsNumber = testingFlow.testParams.numberOfTests,
//                testSuccessByThePaymentFact = testingFlow.testParams.testSuccessByThePaymentFact,
//                stopAfterOrderCreation = testingFlow.testParams.stopAfterOrderCreation
//            )
//        ) {
        try {
            testStages.forEach { stage ->
                val stageResult = stage.run(stuff.userManagement, stuff.api)
                if (stageResult != CONTINUE) {
                    PromMetrics.testDurationRecord(
                        serviceName,
                        stageResult.name,
                        System.currentTimeMillis() - testStartTime
                    )
                    return
                }
            }

            PromMetrics.testDurationRecord(serviceName, "SUCCESS", System.currentTimeMillis() - testStartTime)
        } catch (th: Throwable) {
            logger.error("Unexpected fail in test", th)
            PromMetrics.testDurationRecord(serviceName, "UNEXPECTED_FAIL", System.currentTimeMillis() - testStartTime)
            logger.info("Test ${testingFlow.testsFinished.incrementAndGet()} finished")
        }
//        }.invokeOnCompletion { th ->
//            if (th != null) {
//                logger.error("Unexpected fail in test", th)
//
//                Metrics
//                    .withTags(Metrics.serviceLabel to serviceName, "testOutcome" to "UNEXPECTED_FAIL")
//                    .testDurationRecord(System.currentTimeMillis() - testStartTime)
//            }
//
//            logger.info("Test ${testingFlow.testsFinished.incrementAndGet()} finished")
//        }
    }
}

object TestCtxKey : CoroutineContext.Key<TestContext>

data class TestContext(
    val testId: UUID = UUID.randomUUID(),
    val serviceName: String,
    var userId: UUID? = null,
    var orderId: UUID? = null,
    var paymentDetails: PaymentDetails = PaymentDetails(),
    var stagesComplete: MutableList<String> = mutableListOf(),
    var wasChangedAfterFinalization: Boolean = false,
    var launchTestsRatePerSec: Int,
    var totalTestsNumber: Int,
    val testSuccessByThePaymentFact: Boolean = false,
    val stopAfterOrderCreation: Boolean = false,
    val testStartTime: Long = System.currentTimeMillis()
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<TestContext>
        get() = TestCtxKey

    fun finalizationNeeded() = OrderFinalizingStage::class.java.simpleName !in stagesComplete ||
        (OrderChangeItemsAfterFinalizationStage::class.java.simpleName in stagesComplete
            && wasChangedAfterFinalization)
}

data class PaymentDetails(
//    var startedAt: Long? = null,
//    var failedAt: Long? = null,
//    var finishedAt: Long? = null,
    var attempt: Int = 0,
//    var amount: Int? = null,
)

data class TestParameters(
    val serviceName: String,
    val numberOfUsers: Int,
//    val parallelProcessesNumber: Int,
    val numberOfTests: Int = 100,
    val ratePerSecond: Int = 10,
    val testSuccessByThePaymentFact: Boolean = false,
    val stopAfterOrderCreation: Boolean = false,
)