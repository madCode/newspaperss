package com.app.newspaperss.testutil

import org.junit.rules.ExternalResource

/**
 * Runs [close] after every inner rule has finished. Give it the lowest order so the compose rule
 * tears the screen down first: closed in an @After, a database can still be queried by a screen
 * that's alive, and the error lands in the test.
 */
fun closeAfter(close: () -> Unit) = object : ExternalResource() {
    override fun after() = close()
}
