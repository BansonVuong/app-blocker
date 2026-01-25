package com.appblocker

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.appblocker.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.security.SecureRandom
import android.text.InputType
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast

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
        if (authMode == Storage.OVERRIDE_AUTH_PASSWORD) {
            showSettingsPasswordDialog()
            return false
        }
        showRandomSettingsPasswordDialog(authMode)
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

    private fun setupSettingsPasswordField() {
        binding.editSettingsPassword.setText(storage.getSettingsPassword())
        binding.editSettingsPassword.doAfterTextChanged { text ->
            storage.setSettingsPassword(text?.toString() ?: "")
        }
    }

    private fun showSettingsPasswordDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.settings_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_access_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString() ?: ""
                val stored = storage.getSettingsPassword()
                if (entered != stored) {
                    Toast.makeText(
                        this,
                        getString(R.string.settings_password_incorrect),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                unlocked = true
                setupOverrideAuthDropdown()
                setupPasswordField()
                setupSettingsAuthDropdown()
                setupSettingsPasswordField()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            .show()
    }

    private fun showRandomSettingsPasswordDialog(authMode: Int) {
        val randomPassword = generateRandomPassword(authMode)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = getString(R.string.settings_password_label)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(inputLayout)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_access_title)
            .setMessage(getString(R.string.settings_random_password_message, randomPassword))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString() ?: ""
                if (entered != randomPassword) {
                    Toast.makeText(
                        this,
                        getString(R.string.settings_password_incorrect),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                unlocked = true
                setupOverrideAuthDropdown()
                setupPasswordField()
                setupSettingsAuthDropdown()
                setupSettingsPasswordField()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            .show()
    }

    private fun generateRandomPassword(mode: Int): String {
        val length = when (mode) {
            Storage.OVERRIDE_AUTH_RANDOM_64 -> 64
            Storage.OVERRIDE_AUTH_RANDOM_128 -> 128
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
