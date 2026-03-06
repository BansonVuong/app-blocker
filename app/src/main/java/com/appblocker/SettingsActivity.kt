package com.appblocker

import android.os.Bundle
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.appblocker.databinding.ActivitySettingsBinding
import android.text.InputType

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var storage: Storage
    private var unlocked = false

    private data class AuthOption(val label: String, val mode: Int)
    private data class AuthSectionConfig(
        val dropdown: AutoCompleteTextView,
        val passwordField: EditText,
        val codeLengthField: EditText,
        val options: List<AuthOption>,
        val getCurrentMode: () -> Int,
        val setMode: (Int) -> Unit,
        val passwordMode: Int,
        val randomMode: Int,
        val setPasswordVisibility: (Int) -> Unit,
        val setCodeLengthVisibility: (Int) -> Unit,
        val getPassword: () -> String,
        val setPassword: (String) -> Unit,
        val getCodeLength: () -> Int,
        val setCodeLength: (Int) -> Unit
    )

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

        setupAllAuthSections()
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

    private fun setupAllAuthSections() {
        authSectionConfigs().forEach(::setupAuthSection)
    }

    private fun setupAuthSection(
        config: AuthSectionConfig
    ) {
        val labels = config.options.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.dropdown_item_two_line, labels)
        adapter.setDropDownViewResource(R.layout.dropdown_item_two_line)
        config.dropdown.setAdapter(adapter)

        val currentMode = config.getCurrentMode()
        val initialIndex = config.options.indexOfFirst { it.mode == currentMode }.takeIf { it >= 0 } ?: 0
        config.dropdown.setText(labels[initialIndex], false)
        updateAuthFieldVisibility(config, currentMode)

        config.dropdown.setOnClickListener { config.dropdown.showDropDown() }
        config.dropdown.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) config.dropdown.showDropDown() }
        config.dropdown.setOnItemClickListener { _, _, position, _ ->
            val mode = config.options.getOrNull(position)?.mode ?: config.options.first().mode
            config.setMode(mode)
            updateAuthFieldVisibility(config, mode)
        }

        config.passwordField.setText(config.getPassword())
        config.passwordField.doAfterTextChanged { text ->
            config.setPassword(text?.toString() ?: "")
        }

        config.codeLengthField.setText(config.getCodeLength().toString())
        config.codeLengthField.doAfterTextChanged { text ->
            val length = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (length > 0) config.setCodeLength(length)
        }
    }

    private fun updateAuthFieldVisibility(config: AuthSectionConfig, mode: Int) {
        config.setPasswordVisibility(if (mode == config.passwordMode) View.VISIBLE else View.GONE)
        config.setCodeLengthVisibility(if (mode == config.randomMode) View.VISIBLE else View.GONE)
    }

    private fun authSectionConfigs(): List<AuthSectionConfig> {
        return listOf(
            AuthSectionConfig(
                dropdown = binding.dropdownOverrideAuth,
                passwordField = binding.editOverridePassword,
                codeLengthField = binding.editOverrideCodeLength,
                options = listOf(
                    AuthOption(getString(R.string.override_auth_mode_none), Storage.OVERRIDE_AUTH_NONE),
                    AuthOption(getString(R.string.override_auth_mode_password), Storage.OVERRIDE_AUTH_PASSWORD),
                    AuthOption(getString(R.string.override_auth_mode_random), Storage.OVERRIDE_AUTH_RANDOM)
                ),
                getCurrentMode = { storage.getOverrideAuthMode() },
                setMode = { storage.setOverrideAuthMode(it) },
                passwordMode = Storage.OVERRIDE_AUTH_PASSWORD,
                randomMode = Storage.OVERRIDE_AUTH_RANDOM,
                setPasswordVisibility = { binding.layoutOverridePassword.visibility = it },
                setCodeLengthVisibility = { binding.layoutOverrideCodeLength.visibility = it },
                getPassword = { storage.getOverridePassword() },
                setPassword = { storage.setOverridePassword(it) },
                getCodeLength = { storage.getOverrideRandomCodeLength() },
                setCodeLength = { storage.setOverrideRandomCodeLength(it) }
            ),
            AuthSectionConfig(
                dropdown = binding.dropdownLockdownAuth,
                passwordField = binding.editLockdownPassword,
                codeLengthField = binding.editLockdownCodeLength,
                options = listOf(
                    AuthOption(getString(R.string.lockdown_cancel_disabled), Storage.LOCKDOWN_CANCEL_DISABLED),
                    AuthOption(getString(R.string.lockdown_cancel_password), Storage.LOCKDOWN_CANCEL_PASSWORD),
                    AuthOption(getString(R.string.lockdown_cancel_random), Storage.LOCKDOWN_CANCEL_RANDOM)
                ),
                getCurrentMode = { storage.getLockdownCancelAuthMode() },
                setMode = { storage.setLockdownCancelAuthMode(it) },
                passwordMode = Storage.LOCKDOWN_CANCEL_PASSWORD,
                randomMode = Storage.LOCKDOWN_CANCEL_RANDOM,
                setPasswordVisibility = { binding.layoutLockdownPassword.visibility = it },
                setCodeLengthVisibility = { binding.layoutLockdownCodeLength.visibility = it },
                getPassword = { storage.getLockdownPassword() },
                setPassword = { storage.setLockdownPassword(it) },
                getCodeLength = { storage.getLockdownRandomCodeLength() },
                setCodeLength = { storage.setLockdownRandomCodeLength(it) }
            ),
            AuthSectionConfig(
                dropdown = binding.dropdownSettingsAuth,
                passwordField = binding.editSettingsPassword,
                codeLengthField = binding.editSettingsCodeLength,
                options = listOf(
                    AuthOption(getString(R.string.settings_auth_mode_none), Storage.OVERRIDE_AUTH_NONE),
                    AuthOption(getString(R.string.settings_auth_mode_password), Storage.OVERRIDE_AUTH_PASSWORD),
                    AuthOption(getString(R.string.settings_auth_mode_random), Storage.OVERRIDE_AUTH_RANDOM)
                ),
                getCurrentMode = { storage.getSettingsAuthMode() },
                setMode = { storage.setSettingsAuthMode(it) },
                passwordMode = Storage.OVERRIDE_AUTH_PASSWORD,
                randomMode = Storage.OVERRIDE_AUTH_RANDOM,
                setPasswordVisibility = { binding.layoutSettingsPassword.visibility = it },
                setCodeLengthVisibility = { binding.layoutSettingsCodeLength.visibility = it },
                getPassword = { storage.getSettingsPassword() },
                setPassword = { storage.setSettingsPassword(it) },
                getCodeLength = { storage.getSettingsRandomCodeLength() },
                setCodeLength = { storage.setSettingsRandomCodeLength(it) }
            )
        )
    }

    private fun showSettingsAccessDialog(authMode: Int) {
        val prompt = AuthFlow.promptForPasswordOrRandomCode(
            mode = authMode,
            passwordMode = Storage.OVERRIDE_AUTH_PASSWORD,
            randomMode = Storage.OVERRIDE_AUTH_RANDOM,
            password = storage.getSettingsPassword(),
            randomCodeLength = storage.getSettingsRandomCodeLength(),
            passwordMessage = getString(R.string.enter_password_to_continue),
            randomCodeMessage = getString(R.string.settings_random_password_message)
        ) ?: run {
            finish()
            return
        }
        showPasswordDialog(
            headerResId = R.string.settings_access_title,
            message = prompt.message,
            expectedPassword = prompt.expectedPassword,
            displayPassword = prompt.displayPassword,
            incorrectToastResId = R.string.settings_password_incorrect,
            positiveButtonResId = R.string.continue_label,
            inputType = InputType.TYPE_CLASS_TEXT,
            onAuthorized = {
                unlocked = true
                setupAllAuthSections()
            },
            onCancelled = { finish() }
        )
    }
}
