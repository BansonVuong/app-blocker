package com.appblocker

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

fun Context.showPasswordDialog(
    header: CharSequence,
    message: String,
    expectedPassword: String,
    displayPassword: Boolean,
    @StringRes incorrectToastResId: Int,
    @StringRes positiveButtonResId: Int,
    inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
    onAuthorized: () -> Unit,
    onCancelled: (() -> Unit)? = null
) {
    val padding = (16 * resources.displayMetrics.density).toInt()
    val messageView = TextView(this).apply {
        text = message
        setPadding(0, 0, 0, padding / 2)
    }
    val passwordView = TextView(this).apply {
        text = expectedPassword
        typeface = Typeface.MONOSPACE
        setPadding(0, 0, 0, padding / 2)
    }
    val inputLayout = TextInputLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hint = getString(R.string.password_label)
    }
    val input = TextInputEditText(this).apply {
        this.inputType = inputType
    }
    inputLayout.addView(input)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, 0)
        addView(messageView)
        if (displayPassword) {
            addView(passwordView)
        }
        addView(inputLayout)
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(header)
        .setView(container)
        .setPositiveButton(positiveButtonResId) { _, _ ->
            val entered = input.text?.toString() ?: ""
            if (entered != expectedPassword) {
                Toast.makeText(this, getString(incorrectToastResId), Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            onAuthorized()
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            onCancelled?.invoke()
        }
        .show()
}

fun Context.showPasswordDialog(
    @StringRes headerResId: Int,
    message: String,
    expectedPassword: String,
    displayPassword: Boolean,
    @StringRes incorrectToastResId: Int,
    @StringRes positiveButtonResId: Int,
    inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
    onAuthorized: () -> Unit,
    onCancelled: (() -> Unit)? = null
) = showPasswordDialog(
    header = getString(headerResId),
    message = message,
    expectedPassword = expectedPassword,
    displayPassword = displayPassword,
    incorrectToastResId = incorrectToastResId,
    positiveButtonResId = positiveButtonResId,
    inputType = inputType,
    onAuthorized = onAuthorized,
    onCancelled = onCancelled
)
