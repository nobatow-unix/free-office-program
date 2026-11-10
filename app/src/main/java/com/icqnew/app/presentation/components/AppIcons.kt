package com.icqnew.app.presentation.components

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color

/** Home-screen icons (activity-alias in the manifest). Only the first one is free; the rest come with Premium. */
object AppIcons {
    data class Icon(val alias: String, val title: String, val background: Color)

    val all = listOf(
        Icon("IconDefault", "Классика", Color(0xFF000000)),
        Icon("IconBlue", "Синий", Color(0xFF2F8CFF)),
        Icon("IconWhite", "Белый", Color(0xFFFFFFFF)),
        Icon("IconViolet", "Фиолетовый", Color(0xFF7C5CFF)),
    )

    private fun cn(ctx: Context, alias: String) = ComponentName(ctx.packageName, "com.icqnew.app.$alias")

    fun current(ctx: Context): String {
        val pm = ctx.packageManager
        return all.firstOrNull {
            when (pm.getComponentEnabledSetting(cn(ctx, it.alias))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> it.alias == "IconDefault"
                else -> false
            }
        }?.alias ?: "IconDefault"
    }

    /** Turns on one icon and off the others (the launcher may need a moment to refresh). */
    fun set(ctx: Context, alias: String) {
        val pm = ctx.packageManager
        // enable the new one first, so the app always has a launcher entry
        pm.setComponentEnabledSetting(cn(ctx, alias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        all.filter { it.alias != alias }.forEach {
            pm.setComponentEnabledSetting(cn(ctx, it.alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
