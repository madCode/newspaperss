package com.app.newspaperss.work

import androidx.work.Constraints
import androidx.work.NetworkType

/** Work that fetches or talks to a server waits for a connection. */
internal val CONNECTED_NETWORK: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
