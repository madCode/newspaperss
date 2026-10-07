package com.app.newspaperss.data

import com.app.newspaperss.testutil.TestDataStores
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.app.newspaperss.testutil.testCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

// Robolectric has no Android Keystore, so these use AesGcmCipher with a software key; only the
// Keystore key lookup in AesGcmCipher.androidKeystore() goes untested.
class TtrssAccountStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val stores = TestDataStores()

    private val dataStore by lazy { stores.preferences("ttrss") }
    private fun store(cipher: SecretCipher = testCipher()) = TtrssAccountStore(dataStore, cipher)
    private val account = TtrssAccount("https://rss.example.com/api/", "reader", "päss \"Zq9x\"")

    @Test
    fun anAccountSurvivesARoundTripWithoutThePasswordInTheClear() = runTest {
        val s = store()
        assertEquals(StoredAccount.None, s.load())
        s.save(account)
        assertEquals(StoredAccount.Ready(account), s.load())
        assertFalse(String(stores.file("ttrss").readBytes(), Charsets.ISO_8859_1).contains("Zq9x"))
        assertFalse(account.toString().contains(account.password))
    }

    @Test
    fun aPasswordTheKeyCantOpenLeavesTheAccountLocked() = runTest {
        store().save(account)
        val otherKey = AesGcmCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        assertEquals(StoredAccount.Locked, store(otherKey).load())
    }

    @Test
    fun clearForgetsTheAccount() = runTest {
        val s = store()
        s.save(account)
        s.clear()
        assertEquals(StoredAccount.None, s.load())
    }

    @Test
    fun aesGcmUsesAFreshIvAndRejectsTampering() {
        val cipher = testCipher()
        val a = cipher.encrypt("secret".toByteArray())
        val b = cipher.encrypt("secret".toByteArray())
        assertNotEquals(a.toList(), b.toList())
        assertEquals("secret", String(cipher.decrypt(a)))

        a[a.size - 1] = (a[a.size - 1] + 1).toByte()
        assertEquals(true, runCatching { cipher.decrypt(a) }.exceptionOrNull() is GeneralSecurityException)
        assertEquals(true, runCatching { cipher.decrypt(ByteArray(0)) }.exceptionOrNull() is GeneralSecurityException)
    }
}
