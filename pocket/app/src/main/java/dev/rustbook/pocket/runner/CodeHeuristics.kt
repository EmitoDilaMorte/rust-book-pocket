package dev.rustbook.pocket.runner

/**
 * Best-effort static checks used before handing a snippet to the local
 * toolchain.
 *
 * The bundled compiler only ships `std`, so snippets that pull in `rand` (the
 * guessing game), `trpl` (the async chapters) or any other crate have to go to
 * the playground. This scanner deliberately errs on the side of "needs the
 * network": a false positive costs an upload, a false negative shows the
 * reader a confusing "can't find crate" error.
 */
object CodeHeuristics {

    private val externalCrateUse = Regex(
        """\b(?:use|extern\s+crate)\s+(rand|trpl|tokio|tokio_stream|futures|reqwest|serde|scraper)\b""",
    )

    private val externalCratePath = Regex(
        """\b(rand|trpl|tokio|tokio_stream|futures|reqwest|serde|scraper)::""",
    )

    fun requiresExternalCrates(code: String): Boolean {
        val stripped = stripCommentsAndStrings(code)
        return externalCrateUse.containsMatchIn(stripped) || externalCratePath.containsMatchIn(stripped)
    }

    /**
     * Removes line comments, block comments and string literals so that prose
     * such as `println!("use rand")` does not look like a dependency.
     */
    internal fun stripCommentsAndStrings(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0

        while (i < code.length) {
            val c = code[i]
            when {
                c == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                    while (i < code.length && code[i] != '\n') i++
                }

                c == '/' && i + 1 < code.length && code[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < code.length && !(code[i] == '*' && code[i + 1] == '/')) i++
                    i = minOf(i + 2, code.length)
                }

                c == 'r' && i + 1 < code.length && (code[i + 1] == '"' || code[i + 1] == '#') -> {
                    var j = i + 1
                    var hashes = 0
                    while (j < code.length && code[j] == '#') {
                        hashes++
                        j++
                    }
                    if (j < code.length && code[j] == '"') {
                        j++
                        val terminator = "\"" + "#".repeat(hashes)
                        val end = code.indexOf(terminator, startIndex = j)
                        i = if (end == -1) code.length else end + terminator.length
                        out.append("\"\"")
                    } else {
                        out.append(c)
                        i++
                    }
                }

                c == '"' -> {
                    i++
                    while (i < code.length) {
                        when (code[i]) {
                            '\\' -> i += 2
                            '"' -> {
                                i++
                                break
                            }

                            else -> i++
                        }
                    }
                    out.append("\"\"")
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }

        return out.toString()
    }
}
