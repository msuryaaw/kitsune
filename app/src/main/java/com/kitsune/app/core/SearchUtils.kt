package com.kitsune.app.core

/**
 * Utility for multi-token AND search logic used across Kitsune libraries.
 */
object SearchUtils {

    /**
     * Checks if all tokens in the query match at least one of the provided searchable fields.
     * Implements MULTI-TOKEN AND SEARCH semantics.
     * 
     * @param query The raw search query from user.
     * @param searchableFields List of strings (title, author, tags, etc) to search within.
     * @return True if query is empty or every token is found in at least one field.
     */
    fun matches(query: String, searchableFields: List<String?>): Boolean {
        if (query.isBlank()) return true
        
        // 1. Tokenize whitespace (treat multiple spaces as one)
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true

        // 2. AND Logic: Every token must be satisfied
        return tokens.all { token ->
            // 3. Any field match: Token must be in at least one field
            searchableFields.any { field ->
                field?.contains(token, ignoreCase = true) == true
            }
        }
    }
}
