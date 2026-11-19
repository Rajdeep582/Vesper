package com.rajdeep.vesper.server

/** All configuration comes from the environment. Missing required values fail fast at startup. */
data class Config(
    val port: Int,
    val mongoUri: String,
    val mongoDb: String,
    val jwtSecret: String,
    val hallKeyset: String,
    val adminUsername: String,
    val adminPasswordHash: String,
    val mediaProvider: String,
    val cloudinaryCloud: String?,
    val cloudinaryKey: String?,
    val cloudinarySecret: String?,
    val cloudinaryFolder: String,
    val publicBaseUrl: String,
    val devMode: Boolean,
    val localMediaDir: String,
    val fcmServiceAccountB64: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val missing = mutableListOf<String>()
            fun req(k: String): String = env[k]?.takeIf { it.isNotBlank() } ?: run { missing += k; "" }
            fun opt(k: String): String? = env[k]?.takeIf { it.isNotBlank() }
            val provider = opt("MEDIA_PROVIDER") ?: "cloudinary"
            val cfg = Config(
                port = opt("PORT")?.toInt() ?: 8080,
                mongoUri = req("MONGO_URI"),
                mongoDb = opt("MONGO_DB") ?: "vesper",
                jwtSecret = req("JWT_SECRET"),
                hallKeyset = req("HALL_DATA_KEYSET"),
                adminUsername = req("ADMIN_USERNAME").trim().lowercase(),
                adminPasswordHash = req("ADMIN_PASSWORD_ARGON2"),
                mediaProvider = provider,
                cloudinaryCloud = if (provider == "cloudinary") req("CLOUDINARY_CLOUD_NAME") else opt("CLOUDINARY_CLOUD_NAME"),
                cloudinaryKey = if (provider == "cloudinary") req("CLOUDINARY_API_KEY") else opt("CLOUDINARY_API_KEY"),
                cloudinarySecret = if (provider == "cloudinary") req("CLOUDINARY_API_SECRET") else opt("CLOUDINARY_API_SECRET"),
                cloudinaryFolder = opt("CLOUDINARY_FOLDER") ?: "vesper/m",
                publicBaseUrl = (opt("PUBLIC_BASE_URL") ?: "http://localhost:8080").trimEnd('/'),
                devMode = opt("VESPER_DEV") == "true",
                localMediaDir = opt("LOCAL_MEDIA_DIR") ?: ".dev/media",
                fcmServiceAccountB64 = opt("FCM_SERVICE_ACCOUNT_JSON_B64"),
            )
            require(missing.isEmpty()) { "Missing required environment variables: ${missing.joinToString()}" }
            require(cfg.jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
            require(provider in setOf("cloudinary", "local")) { "MEDIA_PROVIDER must be cloudinary or local" }
            require(provider != "local" || cfg.devMode) { "MEDIA_PROVIDER=local is only allowed with VESPER_DEV=true" }
            return cfg
        }
    }
}

/** Server clock. In dev mode an offset can be applied to time-travel expiry tests. */
object ServerClock {
    @Volatile var offsetMs: Long = 0
    fun now(): Long = System.currentTimeMillis() + offsetMs
}
