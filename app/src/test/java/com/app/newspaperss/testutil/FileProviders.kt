package com.app.newspaperss.testutil

/**
 * FileProvider caches each authority's paths in a static map, but Robolectric gives every test
 * its own files directory in the same JVM: after one test shares a file, the next test's files
 * are "outside the configured root". Call this around tests that share files.
 */
fun clearFileProviderCache() {
    val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    synchronized(cache.get(null)!!) { (cache.get(null) as MutableMap<*, *>).clear() }
}
