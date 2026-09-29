package com.app.newspaperss.core

/** [count] with the noun that fits it: "1 article", "3 articles", "0 articles". */
fun plural(count: Int, one: String, many: String = "${one}s"): String = "$count ${if (count == 1) one else many}"
