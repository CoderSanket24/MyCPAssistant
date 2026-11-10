package com.github.codersanket24.mycpassistant

import com.github.codersanket24.mycpassistant.generator.ProblemFileGenerator
import com.github.codersanket24.mycpassistant.model.ProblemData
import com.github.codersanket24.mycpassistant.runner.TestResult
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MyPluginTest {

    @Test
    fun testSanitizeName() {
        assertEquals("Watermelon", ProblemFileGenerator.sanitizeName("A. Watermelon"))
        assertEquals("LuckyNumber", ProblemFileGenerator.sanitizeName("1234A - Lucky Number"))
        assertEquals("TwoSum", ProblemFileGenerator.sanitizeName("Two Sum"))
        assertEquals("ProblemName", ProblemFileGenerator.sanitizeName("Problem B: Problem Name"))
        assertEquals("_99Bottles", ProblemFileGenerator.sanitizeName("99 Bottles"))
        assertEquals("A", ProblemFileGenerator.sanitizeName("A"))
        assertEquals("Solution", ProblemFileGenerator.sanitizeName(""))
    }

    @Test
    fun testProblemDataDeserialization() {
        val json = """
            {
              "name": "A. Watermelon",
              "group": "Codeforces Round 4",
              "url": "https://codeforces.com/problemset/problem/4/A",
              "timeLimit": 2000,
              "memoryLimit": 64,
              "tests": [
                { "input": "4\n", "output": "YES\n" },
                { "input": "3\n", "output": "NO\n" }
              ]
            }
        """.trimIndent()

        val problem = Gson().fromJson(json, ProblemData::class.java)
        assertNotNull(problem)
        assertEquals("A. Watermelon", problem.name)
        assertEquals("Codeforces Round 4", problem.group)
        assertEquals(2000L, problem.timeLimit)
        assertEquals(64, problem.memoryLimit)
        assertEquals(2, problem.tests.size)
        assertEquals("4\n", problem.tests[0].input)
        assertEquals("YES\n", problem.tests[0].output)
    }

    @Test
    fun testTestResultStatus() {
        val result = TestResult(
            index = 1,
            input = "4",
            expected = "YES",
            got = "YES",
            status = TestResult.Status.PASS,
            timeMs = 42L
        )
        assertEquals(TestResult.Status.PASS, result.status)
        assertEquals("PASS", result.status.label)
        assertEquals(42L, result.timeMs)
    }
}
