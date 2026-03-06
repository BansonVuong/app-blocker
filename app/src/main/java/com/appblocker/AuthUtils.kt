package com.appblocker

import java.security.SecureRandom

object AuthUtils {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val secureRandom = SecureRandom()

    fun generateRandomPassword(length: Int): String {
        val safeLength = length.coerceAtLeast(1)
        val chars = CharArray(safeLength)
        for (i in chars.indices) {
            chars[i] = ALPHABET[secureRandom.nextInt(ALPHABET.length)]
        }
        return String(chars)
    }
}

