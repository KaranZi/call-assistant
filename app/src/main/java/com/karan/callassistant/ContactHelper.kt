package com.karan.callassistant

import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Looks up caller numbers against the address book and, when a number is unknown,
 * saves it as a new contact so future calls are recognised.
 */
object ContactHelper {

    /** Most recent number from the call log — a fallback when the state listener didn't give us one. */
    fun latestCallLogNumber(context: Context): String? {
        if (!has(context, android.Manifest.permission.READ_CALL_LOG)) return null
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "call log read denied", e); null
        }
    }

    fun isKnown(context: Context, number: String): Boolean = labelForNumber(context, number) != null

    /** Display name for a number, or null if it isn't in contacts. */
    fun labelForNumber(context: Context, number: String?): String {
        if (number.isNullOrBlank()) return "Unknown"
        if (!has(context, android.Manifest.permission.READ_CONTACTS)) return number
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return try {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else number
            } ?: number
        } catch (e: SecurityException) {
            number
        }
    }

    /**
     * Saves an unknown caller as a new raw contact named after the number.
     * Phase 4 can replace the auto-name with a prompt in the overlay.
     */
    fun maybeSaveUnknown(context: Context, number: String) {
        if (!has(context, android.Manifest.permission.WRITE_CONTACTS)) return
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    "Call Assistant: $number"
                )
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                )
                .build()
        )
        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.i(TAG, "Saved unknown caller $number")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save contact", e)
        }
    }

    private fun has(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private const val TAG = "ContactHelper"
}
