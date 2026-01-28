package com.appblocker

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.appblocker.databinding.ActivitySettingsBinding
import java.security.SecureRandom
import android.text.InputType

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var storage: Storage
    private val secureRandom = SecureRandom()
    private var unlocked = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = App.instance.storage

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (!ensureSettingsAccess()) {
            return
        }

        setupOverrideAuthDropdown()
        setupPasswordField()
        setupLockdownAuthDropdown()
        setupLockdownPasswordField()
        setupSettingsAuthDropdown()
        setupSettingsPasswordField()
    }

    private fun ensureSettingsAccess(): Boolean {
        if (unlocked) return true
        val authMode = storage.getSettingsAuthMode()
        if (authMode == Storage.OVERRIDE_AUTH_NONE) {
            unlocked = true
            return true
        }
        showSettingsAccessDialog(authMode)
        return false
    }

    private fun setupOverrideAuthDropdown() {
        val options = listOf(
            getString(R.string.override_auth_mode_none),
            getString(R.string.override_auth_mode_password),
            getString(R.string.override_auth_mode_random_32),
            getString(R.string.override_auth_mode_random_64),
            getString(R.string.override_auth_mode_random_128)
        )
        val adapter = ArrayAdapter(this, R.layout.dropdown_item_two_line, options)
        adapter.setDropDownViewResource(R.layout.dropdown_item_two_line)
        binding.dropdownOverrideAuth.setAdapter(adapter)

        val currentMode = storage.getOverrideAuthMode()
        val initialIndex = when (currentMode) {
            Storage.OVERRIDE_AUTH_PASSWORD -> 1
            Storage.OVERRIDE_AUTH_RANDOM_32 -> 2
            Storage.OVERRIDE_AUTH_RANDOM_64 -> 3
            Storage.OVERRIDE_AUTH_RANDOM_128 -> 4
            else -> 0
        }
        binding.dropdownOverrideAuth.setText(options[initialIndex], false)

        binding.dropdownOverrideAuth.setOnClickListener {
            binding.dropdownOverrideAuth.showDropDown()
        }
        binding.dropdownOverrideAuth.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.dropdownOverrideAuth.showDropDown()
            }
        }

        binding.dropdownOverrideAuth.setOnItemClickListener { _, _, position, _ ->
            val mode = when (position) {
                1 -> Storage.OVERRIDE_AUTH_PASSWORD
                2 -> Storage.OVERRIDE_AUTH_RANDOM_32
                3 -> Storage.OVERRIDE_AUTH_RANDOM_64
                4 -> Storage.OVERRIDE_AUTH_RANDOM_128
                else -> Storage.OVERRIDE_AUTH_NONE
            }
            storage.setOverrideAuthMode(mode)
        }
    }

    private fun setupPasswordField() {
        binding.editOverridePassword.setText(storage.getOverridePassword())
        binding.editOverridePassword.doAfterTextChanged { text ->
            storage.setOverridePassword(text?.toString() ?: "")
        }
    }

    private fun setupSettingsAuthDropdown() {
        val options = listOf(
            getString(R.string.override_auth_mode_none),
            getString(R.string.override_auth_mode_password),
            getString(R.string.override_auth_mode_random_32),
            getString(R.string.override_auth_mode_random_64),
            getString(R.string.override_auth_mode_random_128)
        )
        val adapter = ArrayAdapter(this, R.layout.dropdown_item_two_line, options)
        adapter.setDropDownViewResource(R.layout.dropdown_item_two_line)
        binding.dropdownSettingsAuth.setAdapter(adapter)

        val currentMode = storage.getSettingsAuthMode()
        val initialIndex = when (currentMode) {
            Storage.OVERRIDE_AUTH_PASSWORD -> 1
            Storage.OVERRIDE_AUTH_RANDOM_32 -> 2
            Storage.OVERRIDE_AUTH_RANDOM_64 -> 3
            Storage.OVERRIDE_AUTH_RANDOM_128 -> 4
            else -> 0
        }
        binding.dropdownSettingsAuth.setText(options[initialIndex], false)

        binding.dropdownSettingsAuth.setOnClickListener {
            binding.dropdownSettingsAuth.showDropDown()
        }
        binding.dropdownSettingsAuth.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.dropdownSettingsAuth.showDropDown()
            }
        }

        binding.dropdownSettingsAuth.setOnItemClickListener { _, _, position, _ ->
            val mode = when (position) {
                1 -> Storage.OVERRIDE_AUTH_PASSWORD
                2 -> Storage.OVERRIDE_AUTH_RANDOM_32
                3 -> Storage.OVERRIDE_AUTH_RANDOM_64
                4 -> Storage.OVERRIDE_AUTH_RANDOM_128
                else -> Storage.OVERRIDE_AUTH_NONE
            }
            storage.setSettingsAuthMode(mode)
        }
    }

    private fun setupLockdownAuthDropdown() {
        val options = listOf(
            getString(R.string.lockdown_cancel_disabled),
            getString(R.string.lockdown_cancel_password),
            getString(R.string.lockdown_cancel_random_32),
            getString(R.string.lockdown_cancel_random_64),
            getString(R.string.lockdown_cancel_random_128)
        )
        val adapter = ArrayAdapter(this, R.layout.dropdown_item_two_line, options)
        adapter.setDropDownViewResource(R.layout.dropdown_item_two_line)
        binding.dropdownLockdownAuth.setAdapter(adapter)

        val currentMode = storage.getLockdownCancelAuthMode()
        val initialIndex = when (currentMode) {
            Storage.LOCKDOWN_CANCEL_PASSWORD -> 1
            Storage.LOCKDOWN_CANCEL_RANDOM_32 -> 2
            Storage.LOCKDOWN_CANCEL_RANDOM_64 -> 3
            Storage.LOCKDOWN_CANCEL_RANDOM_128 -> 4
            else -> 0
        }
        binding.dropdownLockdownAuth.setText(options[initialIndex], false)

        binding.dropdownLockdownAuth.setOnClickListener {
            binding.dropdownLockdownAuth.showDropDown()
        }
        binding.dropdownLockdownAuth.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.dropdownLockdownAuth.showDropDown()
            }
        }

        binding.dropdownLockdownAuth.setOnItemClickListener { _, _, position, _ ->
            val mode = when (position) {
                1 -> Storage.LOCKDOWN_CANCEL_PASSWORD
                2 -> Storage.LOCKDOWN_CANCEL_RANDOM_32
                3 -> Storage.LOCKDOWN_CANCEL_RANDOM_64
                4 -> Storage.LOCKDOWN_CANCEL_RANDOM_128
                else -> Storage.LOCKDOWN_CANCEL_DISABLED
            }
            storage.setLockdownCancelAuthMode(mode)
        }
    }

    private fun setupLockdownPasswordField() {
        binding.editLockdownPassword.setText(storage.getLockdownPassword())
        binding.editLockdownPassword.doAfterTextChanged { text ->
            storage.setLockdownPassword(text?.toString() ?: "")
        }
    }

    private fun setupSettingsPasswordField() {
        binding.editSettingsPassword.setText(storage.getSettingsPassword())
        binding.editSettingsPassword.doAfterTextChanged { text ->
            storage.setSettingsPassword(text?.toString() ?: "")
        }
    }

    private fun showSettingsAccessDialog(authMode: Int) {
        val (message, expectedPassword, displayPassword) = if (authMode == Storage.OVERRIDE_AUTH_PASSWORD) {
            Triple(
                getString(R.string.enter_password_to_continue),
                storage.getSettingsPassword(),
                false
            )
        } else {
            val randomPassword = generateRandomPassword(authMode)
            Triple(
                getString(R.string.settings_random_password_message),
                randomPassword,
                true
            )
        }
        showPasswordDialog(
            headerResId = R.string.settings_access_title,
            message = message,
            expectedPassword = expectedPassword,
            displayPassword = displayPassword,
            incorrectToastResId = R.string.settings_password_incorrect,
            positiveButtonResId = R.string.continue_label,
            inputType = InputType.TYPE_CLASS_TEXT,
            onAuthorized = {
                unlocked = true
                setupOverrideAuthDropdown()
                setupPasswordField()
                setupLockdownAuthDropdown()
                setupLockdownPasswordField()
                setupSettingsAuthDropdown()
                setupSettingsPasswordField()
            },
            onCancelled = { finish() }
        )
    }

    private fun generateRandomPassword(mode: Int): String {
        val length = when {
            mode == Storage.OVERRIDE_AUTH_RANDOM_64 || mode == Storage.LOCKDOWN_CANCEL_RANDOM_64 -> 64
            mode == Storage.OVERRIDE_AUTH_RANDOM_128 || mode == Storage.LOCKDOWN_CANCEL_RANDOM_128 -> 128
            else -> 32
        }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = alphabet[secureRandom.nextInt(alphabet.length)]
        }
        return String(chars)
    }
}
