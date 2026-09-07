package com.github.codersanket24.mycpassistant.model

/**
 * Maps directly to the JSON payload broadcast by the Competitive Companion browser extension.
 * Fields not used by the plugin are intentionally omitted; Gson ignores unknown JSON keys.
 *
 * Example (abbreviated) payload:
 * ```json
 * {
 *   "name": "A. Watermelon",
 *   "group": "Codeforces Round 4",
 *   "url": "https://codeforces.com/problemset/problem/4/A",
 *   "timeLimit": 2000,
 *   "memoryLimit": 64,
 *   "tests": [
 *     { "input": "4\n", "output": "YES\n" },
 *     { "input": "3\n", "output": "NO\n" }
 *   ]
 * }
 * ```
 */
data class ProblemData(
    /** Full problem name, e.g. "A. Watermelon". */
    val name: String = "",

    /** Contest / problem group, e.g. "Codeforces Round 4". */
    val group: String = "",

    /** Problem URL on the judge. */
    val url: String = "",

    /** Time limit in milliseconds. */
    val timeLimit: Long = 2000L,

    /** Memory limit in megabytes. */
    val memoryLimit: Int = 256,

    /** Sample test cases provided by Competitive Companion. */
    val tests: List<TestCase> = emptyList(),
)

/**
 * A single sample test case.
 */
data class TestCase(
    val input: String = "",
    val output: String = "",
)
