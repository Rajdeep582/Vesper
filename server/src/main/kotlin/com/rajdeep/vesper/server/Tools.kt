package com.rajdeep.vesper.server

/** CLI helpers for producing secrets. Output goes to stdout only; nothing is stored. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "hash" -> {
            val pw = args.getOrNull(1) ?: System.console()?.readPassword("Admin password: ")?.let { String(it) }
                ?: error("Pass the password: ./gradlew :server:hashPassword --args=\"hash <password>\"")
            com.rajdeep.vesper.shared.Limits.passwordProblem(pw)?.let { error(it) }
            println(Passwords.hash(pw))
        }
        "keyset" -> println(AtRest.generateKeysetB64())
        "secret" -> println(b64url(randomBytes(48)))
        else -> println("usage: hash <password> | keyset | secret")
    }
}
