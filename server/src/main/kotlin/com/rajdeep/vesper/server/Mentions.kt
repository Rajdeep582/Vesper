package com.rajdeep.vesper.server

/** Same @mention rules as the app (com.rajdeep.vesper.data.Mentions). */
object HallMentions {
    private val RX = Regex("""(?<![\w@.])@([A-Za-z0-9_.]*[A-Za-z0-9_])""")
    val EVERYONE = setOf("all", "everyone")
    fun names(text: String?): Set<String> = text?.let { t -> RX.findAll(t).map { it.groupValues[1].lowercase() }.toSet() }.orEmpty()
}
