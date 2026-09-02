package com.kitsune.app.core

/**
 * Utility for multi-token AND search logic used across Kitsune libraries.
 */
object SearchUtils {

    /**
     * Checks if all criteria in the query match at least one of the provided searchable fields.
     * Implements COMMA-SEPARATED MULTI-CRITERIA AND SEARCH.
     * 
     * @param query The raw search query from user.
     * @param searchableFields List of strings (title, author, tags, etc) to search within.
     * @return True if query is empty or every comma-separated criterion is found in at least one field.
     */
    fun matches(query: String, searchableFields: List<String?>): Boolean {
        // 1. Split by comma and clean up criteria
        val criteria = query.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        // 2. Empty query or just commas/whitespace returns all results
        if (criteria.isEmpty()) return true

        // 3. AND Logic: Every criterion must be satisfied
        return criteria.all { criterion ->
            // 4. Any field match: Criterion must be in at least one field
            searchableFields.any { field ->
                field?.contains(criterion, ignoreCase = true) == true
            }
        }
    }
}
