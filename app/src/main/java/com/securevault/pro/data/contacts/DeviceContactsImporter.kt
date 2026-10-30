package com.securevault.pro.data.contacts

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceContact(
    val name: String,
    val phone: String = "",
    val email: String = ""
)

data class ContactReadResult(
    val contacts: List<DeviceContact>,
    val phoneRows: Int,
    val emailRows: Int
)

@Singleton
class DeviceContactsImporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun readContacts(
        onProgress: (Int) -> Unit = {}
    ): Result<ContactReadResult> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val merged = LinkedHashMap<Long, MutableContact>()
            var phoneRows = 0
            var emailRows = 0

            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                ),
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val entry = merged.getOrPut(contactId) { MutableContact(name) }
                    if (entry.name.isBlank()) entry.name = name
                }
            }

            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    phoneRows++
                    val contactId = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val phone = cursor.getString(numberIndex).orEmpty().trim()
                    val entry = merged.getOrPut(contactId) { MutableContact(name) }
                    if (entry.name.isBlank()) entry.name = name
                    if (entry.phone.isBlank() && phone.isNotBlank()) entry.phone = phone
                    if (phoneRows % 40 == 0) onProgress(phoneRows)
                }
            }

            resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY + " ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY)
                val emailIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext()) {
                    emailRows++
                    val contactId = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val email = cursor.getString(emailIndex).orEmpty().trim()
                    val entry = merged.getOrPut(contactId) { MutableContact(name) }
                    if (entry.name.isBlank()) entry.name = name
                    if (entry.email.isBlank() && email.isNotBlank()) entry.email = email
                    if (emailRows % 40 == 0) onProgress(phoneRows + emailRows)
                }
            }

            val contacts = merged.values.mapNotNull { contact ->
                val name = contact.name.trim().ifBlank {
                    contact.phone.takeIf { it.isNotBlank() }
                        ?: contact.email.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                }
                DeviceContact(
                    name = name,
                    phone = contact.phone.trim(),
                    email = contact.email.trim()
                )
            }.distinctBy { it.name.lowercase() + "|" + it.phone + "|" + it.email.lowercase() }

            Result.success(
                ContactReadResult(
                    contacts = contacts,
                    phoneRows = phoneRows,
                    emailRows = emailRows
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Contacts permission is required to import the address book.", e))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private class MutableContact(
        var name: String,
        var phone: String = "",
        var email: String = ""
    )
}
