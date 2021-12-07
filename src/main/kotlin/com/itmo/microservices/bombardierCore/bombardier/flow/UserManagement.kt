package com.itmo.microservices.bombardierCore.bombardier.flow

import com.itmo.microservices.bombardierCore.bombardier.external.ExternalServiceApi
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.NoSuchElementException

//@Component
class UserManagement(
    private val externalServiceApi: ExternalServiceApi
) {
    companion object {
        val log = LoggerFactory.getLogger(UserManagement::class.java)
    }

    private val serviceName = externalServiceApi.descriptor.name

    private val userIdsByService = mutableListOf<UUID>()

    suspend fun createUsersPool(numberOfUsers: Int): List<UUID> {
        repeat(numberOfUsers) { index ->
            kotlin.runCatching {
                externalServiceApi.createUser("service-${serviceName}-user-$index-${System.currentTimeMillis()}")
            }.onSuccess { user ->
                userIdsByService.add(user.id)
            }.onFailure {
                log.error("User has not been created", it)
            }
        }
        return userIdsByService
    }

    fun getRandomUserId(service: String): UUID {
        return try {
            userIdsByService.random()
        }
        catch (t: NoSuchElementException) {
            throw IllegalStateException("There are no users for service $service")
        }
    }
}