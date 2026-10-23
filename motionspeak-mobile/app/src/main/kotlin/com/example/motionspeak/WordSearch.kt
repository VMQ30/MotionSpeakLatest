package com.example.motionspeak

/**
 * The search every word list uses: English label, Filipino translation, or
 * description, ignoring case. An empty query matches everything.
 */
fun WordBankWord.matchesSearch(query: String): Boolean {
    if (query.isEmpty()) return true
    val cleanQuery = query.replace('_', ' ')
    val cleanLabel = label.replace('_', ' ')
    return label.contains(query, ignoreCase = true) ||
        cleanLabel.contains(cleanQuery, ignoreCase = true) ||
        filipino_translation?.contains(query, ignoreCase = true) == true ||
        description?.contains(query, ignoreCase = true) == true
}
