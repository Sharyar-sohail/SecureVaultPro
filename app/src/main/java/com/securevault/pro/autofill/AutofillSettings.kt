package com.securevault.pro.autofill

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager

object AutofillSettings {

    fun isEnabled(context: Context): Boolean {
        return runCatching {
            val manager = context.getSystemService(AutofillManager::class.java) ?: return false
            manager.hasEnabledAutofillServices()
        }.getOrDefault(false)
    }

    fun isOwnServiceEnabled(context: Context): Boolean {
        return runCatching {
            val manager = context.getSystemService(AutofillManager::class.java) ?: return false
            if (!manager.hasEnabledAutofillServices()) return false
            val expected = ComponentName(context, VaultAutofillService::class.java)
            val enabled = manager.autofillServiceComponentName ?: return false
            enabled == expected
        }.getOrDefault(false)
    }

    fun open(context: Context): Boolean {
        // The system picker takes the calling package as a data URI (see Settings docs);
        // there is no public component-name extra on this intent action.
        val request = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(request) }.isSuccess) return true
        val fallback = Intent("android.settings.AUTOFILL_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(fallback) }.isSuccess
    }
}
