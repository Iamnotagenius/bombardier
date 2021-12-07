package com.itmo.microservices.bombardierCore.bombardier.external.communicator

import java.net.URL

data class TokenResponse(val accessToken: String, val refreshToken: String)

fun TokenResponse.toExternalServiceToken(service: URL) = ExternalServiceToken(service, accessToken, refreshToken)