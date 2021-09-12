package com.itmo.microservices.demo.test

import com.itmo.microservices.demo.test.OrderStatus.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass


interface ServiceApi {
    suspend fun createUser(name: String, accountAmount: Int): User
    suspend fun getFinancialHistory(userId: UUID, orderId: UUID): List<FinancialLogRecord>

    suspend fun getUser(id: UUID): User

    suspend fun createOrder(userId: UUID): Order
    suspend fun getOrder(id: UUID): Order
    suspend fun getBucketAliveLogRecord(id: UUID): List<BucketLogRecord>
    suspend fun getDeliverySlots(id: UUID): List<Int>
    suspend fun setDeliveryTime(id: UUID, time: Long)
    suspend fun payOrder(userId: UUID, orderId: UUID): Order
    suspend fun finalizeOrder(orderId: UUID): BookingDto //синхронный
    suspend fun addItem(orderId: UUID, itemId: UUID, amount: Amount): Boolean
    suspend fun getBookingHistory(bookingId: UUID): List<BookingLogRecord>

    suspend fun getItems(): List<Item>
}
//todo add booking history

typealias Amount = Int

data class User(
        val id: UUID = UUID.randomUUID(),
        val name: String,
        val accountAmount: Amount
)

data class FinancialLogRecord( // todo sukhoa think of renaming
        val type: FinancialOperationType,
        val amount: Amount,
        val orderId: UUID? = null,
        val timestamp: Long = System.currentTimeMillis()
)

data class BookingDto(
        val id: UUID,
        val failedItems: Set<UUID> = emptySet()
)

enum class FinancialOperationType {
    DEPOSIT,
    WITHDRAW
}

data class Item(
        val id: UUID = UUID.randomUUID(),
        val title: String,
        val price: Int = 100,
        val amount: Amount, // number of items allowed for booking
)

class BookingLogRecord(
        val bookingId: UUID,
        val itemId: UUID,
        val status: BookingStatus,
        val amount: Amount,
        val timestamp: Long = System.currentTimeMillis(),
)

sealed class OrderStatus {
    object OrderCollecting : OrderStatus()
    object OrderDiscarded : OrderStatus()
    object OrderBooked : OrderStatus()
    class OrderPayed(val paymentTime: Long) : OrderStatus()
    class OrderInDelivery(val deliveryStartTime: Long) : OrderStatus()
    class OrderDelivered(val deliveryStartTime: Long, deliveryFinishTime: Long) : OrderStatus()
    class OrderFailed(reason: String, previousStatus: OrderStatus) : OrderStatus()
}

val orderStateMachine = OrderStatusStateMachine(listOf(
        OrderCollecting::class to OrderBooked::class,
        OrderCollecting::class to OrderDiscarded::class,
        OrderBooked::class to OrderCollecting::class, // payment haven't succeeded withing given time period or booking was cancelled
        OrderBooked::class to OrderBooked::class, // still haven't been payed but timeout haven't passed
        OrderBooked::class to OrderPayed::class,
))

class OrderStatusStateMachine(legalTransitions: List<Pair<KClass<out OrderStatus>, KClass<out OrderStatus>>>) {
    private val transitions = ConcurrentHashMap<KClass<out OrderStatus>, MutableSet<KClass<out OrderStatus>>>()

    init {
        legalTransitions.forEach { (from, to) ->
            transitions.computeIfAbsent(from) { mutableSetOf() }.add(to)
        }
    }

    fun isTransitionAllowed(from: OrderStatus, to: OrderStatus): Boolean {
        return transitions[from::class]?.contains(to::class) ?: throw IllegalStateException("No such from status : $from")
    }
}

data class Order(
        val id: UUID = UUID.randomUUID(),
        val timeCreated: Long = System.currentTimeMillis(),
        val status: OrderStatus = OrderCollecting,
        val itemsMap: Map<Item, Amount> = emptyMap(),
        val deliveryDuration: Int? = null,
        val paymentHistory: List<PaymentLogRecord> = listOf()
)

data class BucketLogRecord(
        val transactionId: UUID = UUID.randomUUID(),
        val timestamp: Long,
        val userInteracted: Boolean
)

class PaymentLogRecord(
        val timestamp: Long,
        val status: PaymentStatus,
        val amount: Amount
)

enum class PaymentStatus {
    FAILED,
    FAILED_NOT_ENOUGH_MONEY, // todo sukhoa Elina, rename
    SUCCESS
}

enum class BookingStatus {
    FAILED,
    SUCCESS
}


