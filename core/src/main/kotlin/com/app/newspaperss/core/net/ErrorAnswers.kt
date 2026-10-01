package com.app.newspaperss.core.net

/** Plain words for a site's HTTP error answers, the same while adding a site and on its page later. */
object ErrorAnswers {
    /** Codes that mean the site refused the app: a Cloudflare wall answers 503, a paywall 402 (Le Monde's "Accès restreint"). */
    val BLOCKED = setOf(401, 402, 403, 429, 503)

    /**
     * What an error answer means, with the code kept for anyone who asks.
     *
     * @param address the address someone is adding, named in the message, whose advice is about
     *   that address; null for a site already added, which is called "the site".
     * @param curatedList the site is a curated list, whose page address is built into the app, so
     *   the reader can't fix a moved page by adding it again.
     */
    fun message(code: Int, address: String? = null, curatedList: Boolean = false): String {
        val site = address ?: "The site"
        return when (code) {
            404, 410 -> when {
                address != null -> "There's nothing at $address. Check the address."
                curatedList -> "The list's page isn't where newspapeRSS expects it any more (error $code). An update to newspapeRSS should fix it."
                // Adding the same feed address again would fail the same way; the home page finds the new one.
                else -> "The site's feed isn't there any more (error $code). It may have moved: add the site's home page again to find its new feed."
            }
            in BLOCKED ->
                "$site turned newspapeRSS away (error $code). Some sites block apps: " +
                    if (address != null) "try its feed or RSS link if it lists one, or try again later." else "try again later."
            in 500..599 -> "$site isn't working right now (error $code). Try again later."
            else -> "$site answered with error $code."
        }
    }
}
