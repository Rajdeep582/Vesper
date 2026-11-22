package com.rajdeep.vesper.data

/** WhatsApp-style @mentions in the Hall: `@username` pings that member, `@all` (or `@everyone`) pings everyone. */
object Mentions {
    /** `@name` not glued to a word or e-mail; usernames are a–z 0–9 _ . and never end with a dot. */
    val RX = Regex("""(?<![\w@.])@([A-Za-z0-9_.]*[A-Za-z0-9_])""")
    val EVERYONE = setOf("all", "everyone")

    fun names(text: String?): Set<String> = text?.let { t -> RX.findAll(t).map { it.groupValues[1].lowercase() }.toSet() }.orEmpty()

    /** Does this message call [me] by name or everyone? */
    fun hits(text: String?, me: String): Boolean {
        val n = names(text)
        return n.isNotEmpty() && (me.lowercase() in n || n.any { it in EVERYONE })
    }

    fun everyone(text: String?): Boolean = names(text).any { it in EVERYONE }
}
