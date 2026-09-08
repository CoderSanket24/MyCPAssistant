package com.github.codersanket24.mycpassistant.runner

/**
 * Result of running a single test case against the solution.
 *
 * @param index     1-based test case number.
 * @param input     Raw content of _inputN.txt.
 * @param expected  Raw content of _outputN.txt.
 * @param got       Stdout captured from the solution process.
 * @param status    Outcome of the test.
 * @param timeMs    Wall-clock execution time in milliseconds.
 * @param errorMsg  Optional extra detail for ERROR status (e.g., exception message).
 */
data class TestResult(
    val index: Int,
    val input: String,
    val expected: String,
    val got: String,
    val status: Status,
    val timeMs: Long,
    val errorMsg: String = "",
) {
    enum class Status {
        /** Output matched expected after trimming. */
        PASS,
        /** Output did not match expected. */
        FAIL,
        /** Process threw an exception or returned a non-zero exit code. */
        ERROR,
        /** Process did not finish within the configured time limit. */
        TIMEOUT;

        val label: String get() = when (this) {
            PASS    -> "PASS"
            FAIL    -> "FAIL"
            ERROR   -> "ERROR"
            TIMEOUT -> "TLE"
        }
    }
}
