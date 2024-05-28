package org.evomaster.e2etests.spring.openapi.v3.bodyundefined

import com.foo.rest.examples.spring.openapi.v3.bodyundefined.BodyUndefinedController
import org.evomaster.core.problem.rest.HttpVerb
import org.evomaster.e2etests.spring.openapi.v3.SpringTestBase
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class BodyUndefinedEMTest : SpringTestBase() {

    companion object {
        @BeforeAll
        @JvmStatic
        fun init() {
            initClass(BodyUndefinedController())
        }
    }

    @Test
    fun testRunEM() {

        runTestHandlingFlakyAndCompilation(
                "BodyUndefinedEM",
                20
        ) { args: MutableList<String> ->

            val solution = initAndRun(args)

            Assertions.assertTrue(solution.individuals.size >= 1)
            assertHasAtLeastOne(solution, HttpVerb.DELETE, 400, "/api/bodyundefined", null)
            assertHasAtLeastOne(solution, HttpVerb.DELETE, 200, "/api/bodyundefined", null)

            /*
                There is some weird bug in Jersey that it looks like it transform the GET into a POST ?!?
                When we upgrade Jersey (once moving ot JDK 11), need to fix AbstractRestFitness and RestActionBuilderV3.
                Then, we will need to check if this fails, and if so, change
                into a 400 and 200 instead of 415
             */
            assertHasAtLeastOne(solution, HttpVerb.GET, 415, "/api/bodyundefined", null)
        }
    }
}