package com.app.newspaperss.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.ttrss.TtrssClient
import kotlinx.coroutines.flow.first
import java.util.Base64

/** A tt-rss login. [apiUrl] is the API endpoint, as [TtrssClient.apiUrl] makes it. */
data class TtrssAccount(val apiUrl: String, val user: String, val password: String) {
    fun client(http: HttpClient) = TtrssClient(http, apiUrl, user, password)

    // Keeps the password out of logs and crash reports that print the account.
    override fun toString() = "TtrssAccount(apiUrl=$apiUrl, user=$user)"
}

sealed interface StoredAccount {
    data object None : StoredAccount
    data class Ready(val account: TtrssAccount) : StoredAccount
    /** Saved, but the password can't be decrypted: the Keystore has lost its key. */
    data object Locked : StoredAccount
}

// Excluded from backups (backup_rules.xml): the password's key stays in this phone's Keystore.
private val Context.ttrssStore by preferencesDataStore(
    name = "ttrss",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The tt-rss account: address and username in DataStore, the password sealed by [cipher]. */
class TtrssAccountStore(private val store: DataStore<Preferences>, private val cipher: SecretCipher) {
    constructor(context: Context, cipher: SecretCipher) : this(context.ttrssStore, cipher)

    private object Keys {
        val url = stringPreferencesKey("api_url")
        val user = stringPreferencesKey("user")
        val password = stringPreferencesKey("password_sealed")
    }

    suspend fun load(): StoredAccount {
        val p = store.data.first()
        val url = p[Keys.url] ?: return StoredAccount.None
        val sealed = p[Keys.password] ?: return StoredAccount.Locked
        val password = try {
            String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
        } catch (e: Exception) {
            // GeneralSecurityException, bad Base64, or a device Keystore's own runtime exceptions:
            // any of them means the password can't be read, and the reader signs in again.
            return StoredAccount.Locked
        }
        return StoredAccount.Ready(TtrssAccount(url, p[Keys.user] ?: "", password))
    }

    suspend fun save(account: TtrssAccount) {
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(account.password.toByteArray(Charsets.UTF_8)))
        store.edit {
            it[Keys.url] = account.apiUrl
            it[Keys.user] = account.user
            it[Keys.password] = sealed
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
