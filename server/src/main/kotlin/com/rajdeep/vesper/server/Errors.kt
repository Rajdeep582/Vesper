package com.rajdeep.vesper.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

fun bad(code: String, msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, msg)
fun unauthorized(msg: String = "Sign in again"): Nothing = throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", msg)
fun forbidden(code: String = "forbidden", msg: String = "Not allowed"): Nothing = throw ApiException(HttpStatusCode.Forbidden, code, msg)
fun notFound(msg: String = "Not found"): Nothing = throw ApiException(HttpStatusCode.NotFound, "not_found", msg)
fun conflict(code: String, msg: String): Nothing = throw ApiException(HttpStatusCode.Conflict, code, msg)
fun tooMany(msg: String = "Too many attempts. Try again shortly."): Nothing = throw ApiException(HttpStatusCode.TooManyRequests, "rate", msg)

data class Caller(val uid: String, val did: Int, val role: String) {
    val isAdmin get() = role == "admin"
}

fun ApplicationCall.caller(): Caller {
    val p = principal<JWTPrincipal>() ?: unauthorized()
    val uid = p.payload.getClaim("uid").asString() ?: unauthorized()
    val did = p.payload.getClaim("did").asInt() ?: unauthorized()
    val role = p.payload.getClaim("role").asString() ?: "member"
    return Caller(uid, did, role)
}
