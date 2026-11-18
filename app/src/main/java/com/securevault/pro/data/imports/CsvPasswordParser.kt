package com.securevault.pro.data.imports

/**
 * Universal CSV password parser for Google Chrome, Google Password Manager,
 * Bitwarden, LastPass, 1Password, Dashlane, KeePass, and generic CSV dumps.
 */
object CsvPasswordParser {
    data class Record(
        val title: String = "",
        val url: String = "",
        val username: String = "",
        val password: String = "",
        val notes: String = ""
    )

    fun parse(csvContent: String): List<Record> {
        val rows = parseCsvRows(csvContent)
        if (rows.isEmpty()) return emptyList()

        val firstRow = rows.first()
        val headerIndices = mapHeaders(firstRow)

        val hasRecognizedHeaders = headerIndices.passwordIndex != -1 ||
            (headerIndices.usernameIndex != -1 && headerIndices.titleIndex != -1)

        val dataRows = if (hasRecognizedHeaders) rows.drop(1) else rows
        val effectiveIndices = if (hasRecognizedHeaders) headerIndices else guessIndices(firstRow)

        val results = mutableListOf<Record>()
        for (row in dataRows) {
            if (row.all { it.isBlank() }) continue
            val title = effectiveIndices.titleIndex.takeIf { it in row.indices }?.let { row[it] }.orEmpty().trim()
            val url = effectiveIndices.urlIndex.takeIf { it in row.indices }?.let { row[it] }.orEmpty().trim()
            val username = effectiveIndices.usernameIndex.takeIf { it in row.indices }?.let { row[it] }.orEmpty().trim()
            val password = effectiveIndices.passwordIndex.takeIf { it in row.indices }?.let { row[it] }.orEmpty()
            val notes = effectiveIndices.notesIndex.takeIf { it in row.indices }?.let { row[it] }.orEmpty().trim()

            if (password.isNotBlank() || username.isNotBlank() || title.isNotBlank() || url.isNotBlank()) {
                val cleanTitle = when {
                    title.isNotBlank() -> title
                    url.isNotBlank() -> extractHost(url)
                    else -> "Account ${results.size + 1}"
                }
                results += Record(
                    title = cleanTitle,
                    url = url,
                    username = username,
                    password = password,
                    notes = notes
                )
            }
        }
        return results
    }

    private data class ColumnMapping(
        val titleIndex: Int = -1,
        val urlIndex: Int = -1,
        val usernameIndex: Int = -1,
        val passwordIndex: Int = -1,
        val notesIndex: Int = -1
    )

    private fun mapHeaders(headerRow: List<String>): ColumnMapping {
        var titleIdx = -1
        var urlIdx = -1
        var usernameIdx = -1
        var passwordIdx = -1
        var notesIdx = -1

        headerRow.forEachIndexed { idx, raw ->
            val col = raw.lowercase().trim().replace("\"", "").replace(" ", "_")
            when {
                col in setOf("password", "login_password", "pass", "pwd", "userpassword") -> passwordIdx = idx
                col in setOf("username", "login_username", "user", "login", "email", "login_name") -> usernameIdx = idx
                col in setOf("url", "login_uri", "login_url", "website", "uri", "web_address", "site") -> urlIdx = idx
                col in setOf("name", "title", "account", "login_name", "app", "system") && titleIdx == -1 -> titleIdx = idx
                col in setOf("note", "notes", "comment", "comments", "extra", "description") -> notesIdx = idx
            }
        }
        return ColumnMapping(titleIdx, urlIdx, usernameIdx, passwordIdx, notesIdx)
    }

    private fun guessIndices(row: List<String>): ColumnMapping {
        if (row.size >= 4) {
            return ColumnMapping(titleIndex = 0, urlIndex = 1, usernameIndex = 2, passwordIndex = 3, notesIndex = 4.takeIf { row.size > 4 } ?: -1)
        }
        return ColumnMapping(titleIndex = 0, usernameIndex = 1.coerceAtMost(row.lastIndex), passwordIndex = 2.coerceAtMost(row.lastIndex))
    }

    private fun extractHost(url: String): String {
        return runCatching {
            val clean = if (url.contains("://")) url else "https://$url"
            java.net.URI(clean).host ?: url
        }.getOrDefault(url).removePrefix("www.")
    }

    fun parseCsvRows(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentField = StringBuilder()
        val currentRow = mutableListOf<String>()
        var inQuotes = false
        var i = 0

        while (i < input.length) {
            val c = input[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < input.length && input[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                }
                (c == '\n' || (c == '\r' && (i + 1 >= input.length || input[i + 1] != '\n'))) && !inQuotes -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                    rows.add(currentRow.toList())
                    currentRow.clear()
                }
                c == '\r' && i + 1 < input.length && input[i + 1] == '\n' && !inQuotes -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                    rows.add(currentRow.toList())
                    currentRow.clear()
                    i++
                }
                else -> {
                    currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow.toList())
        }
        return rows
    }
}
