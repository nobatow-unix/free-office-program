package com.icqnew.app.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.icqnew.app.domain.PhoneBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads phone numbers from the system address book; only the last 8 digits leave the phone. */
class PhoneContacts(private val context: Context) : PhoneBook {

    private fun granted() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    override suspend fun readKeys(): List<String> = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext emptyList<String>()
        val keys = HashSet<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )
        cursor?.use { c ->
            val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val raw = if (idx >= 0) c.getString(idx) else null
                if (raw != null) {
                    val digits = raw.filter { it.isDigit() }
                    if (digits.length >= 8) keys.add(digits.takeLast(8))
                }
            }
        }
        keys.toList()
    }
}
