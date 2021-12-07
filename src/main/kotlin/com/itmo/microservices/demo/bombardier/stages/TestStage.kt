package com.itmo.microservices.demo.bombardier.stages

import com.fasterxml.jackson.annotation.JsonValue
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.flow.TestCtxKey
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.UserNotableEvents
import kotlin.coroutines.coroutineContext

enum class TestStageSetKind(@get:JsonValue val stringVal: String) {
    DEFAULT("default"), DDOS("ddos")
}

interface TestStage {
    suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi): TestContinuationType
    suspend fun testCtx() = coroutineContext[TestCtxKey]!!
    fun name(): String = this::class.simpleName!!

    fun stageSetKind() = TestStageSetKind.DEFAULT

    class RetryableTestStage(private val wrapped: TestStage) : TestStage {
        override suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi): TestContinuationType {
            repeat(5) {
                when (val state = wrapped.run(userManagement, externalServiceApi)) {
                    TestContinuationType.CONTINUE -> return state
                    TestContinuationType.FAIL -> return state
                    TestContinuationType.RETRY -> Unit
                }
            }
            return TestContinuationType.RETRY
        }

        override fun name(): String {
            return wrapped.name()
        }
    }

    private interface DecoratingStage {
        val wrapped: TestStage
    }

    class ExceptionFreeTestStage(override val wrapped: TestStage) : TestStage, DecoratingStage {
        override suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi) = try {
            wrapped.run(userManagement, externalServiceApi)
        } catch (failedException: TestStageFailedException) {
            TestContinuationType.FAIL
        } catch (th: Throwable) {
            var decoratedStage = wrapped
            while (decoratedStage is DecoratingStage) {
                decoratedStage = decoratedStage.wrapped
            }
            ChoosingUserAccountStage.eventLogger.error(UserNotableEvents.E_UNEXPECTED_EXCEPTION, wrapped::class.simpleName, th)
            TestContinuationType.ERROR
        }

        override fun name(): String {
            return wrapped.name()
        }
    }

    class TestStageFailedException(message: String) : IllegalStateException(message)

    enum class TestContinuationType {
        CONTINUE,
        FAIL,
        ERROR,
        RETRY,
        STOP;

        fun iSFailState(): Boolean {
            return this == FAIL || this == ERROR
        }
    }
}


fun TestStage.asRetryable() = TestStage.RetryableTestStage(this)

fun TestStage.asErrorFree() = TestStage.ExceptionFreeTestStage(this)