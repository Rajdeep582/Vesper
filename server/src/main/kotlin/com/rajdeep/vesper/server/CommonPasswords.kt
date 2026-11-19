package com.rajdeep.vesper.server

/** Blocks passwords from the bundled SecLists 10k-most-common list (entries of length >= 8, lower-cased). */
object CommonPasswords {
    private val set: Set<String> by lazy {
        CommonPasswords::class.java.getResourceAsStream("/common-passwords.txt")?.bufferedReader()?.useLines { l -> l.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet() } ?: emptySet()
    }
    fun contains(p: String) = p.lowercase() in set
}
