package com.appblocker

data class AuthPrompt(
    val message: String,
    val expectedPassword: String,
    val displayPassword: Boolean
)

object AuthFlow {
    fun promptForPasswordOrRandomCode(
        mode: Int,
        passwordMode: Int,
        randomMode: Int,
        password: String,
        randomCodeLength: Int,
        passwordMessage: String,
        randomCodeMessage: String
    ): AuthPrompt? {
        return when (mode) {
            passwordMode -> AuthPrompt(
                message = passwordMessage,
                expectedPassword = password,
                displayPassword = false
            )
            randomMode -> AuthPrompt(
                message = randomCodeMessage,
                expectedPassword = AuthUtils.generateRandomPassword(randomCodeLength),
                displayPassword = true
            )
            else -> null
        }
    }

    fun promptForRandomCode(
        mode: Int,
        randomMode: Int,
        randomCodeLength: Int,
        randomCodeMessage: String
    ): AuthPrompt? {
        if (mode != randomMode) return null
        return AuthPrompt(
            message = randomCodeMessage,
            expectedPassword = AuthUtils.generateRandomPassword(randomCodeLength),
            displayPassword = true
        )
    }
}

