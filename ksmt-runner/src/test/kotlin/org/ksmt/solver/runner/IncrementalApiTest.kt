package org.ksmt.solver.runner

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.ksmt.KContext
import org.ksmt.solver.KSolver
import org.ksmt.solver.KSolverStatus
import org.ksmt.solver.z3.KZ3Solver
import org.ksmt.utils.mkConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class IncrementalApiTest {
    private lateinit var context: KContext
    private lateinit var solver: KSolver

    @BeforeEach
    fun createNewEnvironment() {
        context = KContext()
        solver = solverManager.createSolver(context, KZ3Solver::class)
    }

    @AfterEach
    fun clearResources() {
        solver.close()
        context.close()
    }

    companion object {
        private lateinit var solverManager: KSolverRunnerManager

        @BeforeAll
        @JvmStatic
        fun initSolverManager() {
            solverManager = KSolverRunnerManager(workerPoolSize = 1)
        }

        @AfterAll
        @JvmStatic
        fun closeSolverManager() {
            solverManager.close()
        }
    }

    @Test
    fun testUnsatCoreGeneration(): Unit = with(context) {
        val a = boolSort.mkConst("a")
        val b = boolSort.mkConst("b")
        val c = boolSort.mkConst("c")

        val e1 = (a and b) or c
        val e2 = !(a and b)
        val e3 = !c

        solver.assert(e1)
        val e2Track = solver.assertAndTrack(e2)
        val status = solver.checkWithAssumptions(listOf(e3))
        assertEquals(KSolverStatus.UNSAT, status)
        val core = solver.unsatCore()
        assertEquals(2, core.size)
        assertTrue(e2Track in core)
        assertTrue(e3 in core)
    }

    @Test
    fun testUnsatCoreGenerationNoAssumptions(): Unit = with(context) {
        val a = boolSort.mkConst("a")
        val b = boolSort.mkConst("b")

        val e1 = (a and b)
        val e2 = !(a and b)

        solver.assert(e1)
        val e2Track = solver.assertAndTrack(e2)
        val status = solver.check()
        assertEquals(KSolverStatus.UNSAT, status)
        val core = solver.unsatCore()
        assertEquals(1, core.size)
        assertTrue(e2Track in core)
    }

    @Test
    fun testPushPop(): Unit = with(context) {
        val a = boolSort.mkConst("a")
        solver.assert(a)
        solver.push()
        val track = solver.assertAndTrack(!a)
        var status = solver.check()
        assertEquals(KSolverStatus.UNSAT, status)
        val core = solver.unsatCore()
        assertEquals(1, core.size)
        assertTrue(track in core)
        solver.pop()
        status = solver.check()
        assertEquals(KSolverStatus.SAT, status)
    }

    @Test
    fun testTimeout(): Unit = with(context) {
        val array = mkArraySort(intSort, mkArraySort(intSort, intSort)).mkConst("array")
        val result = mkArraySort(intSort, intSort).mkConst("result")

        val i = intSort.mkConst("i")
        val j = intSort.mkConst("i")
        val idx = mkIte((i mod j) eq mkIntNum(100), i, j)
        val body = result.select(idx) eq array.select(i).select(j)
        val rule = mkUniversalQuantifier(body, listOf(i.decl, j.decl))
        solver.assert(rule)

        val x = intSort.mkConst("x")
        val queryBody = result.select(x) gt result.select(x + mkIntNum(10))
        val query = mkUniversalQuantifier(queryBody, listOf(x.decl))
        solver.assert(query)

        val status = solver.checkWithAssumptions(emptyList(), timeout = 1.milliseconds)
        assertEquals(KSolverStatus.UNKNOWN, status)
        assertEquals("timeout", solver.reasonOfUnknown())
    }
}
