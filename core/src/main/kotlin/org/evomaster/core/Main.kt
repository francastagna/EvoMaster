package org.evomaster.core

import com.google.inject.*
import com.netflix.governator.guice.LifecycleInjector
import org.evomaster.core.output.TestSuiteWriter
import org.evomaster.core.problem.rest.RestIndividual
import org.evomaster.core.problem.rest.service.RestModule
import org.evomaster.core.search.Solution
import org.evomaster.core.search.algorithms.MioAlgorithm
import org.evomaster.core.search.service.Randomness


/**
 * This will be the entry point of the tool when run from command line
 */
class Main {
    companion object {

        @JvmStatic
        fun main(args: Array<String>) {

            try {

                initAndRun(args)

            } catch (e: Exception) {
                LoggingUtil.getInfoLogger()
                        .error("ERROR: EvoMaster process terminated abruptly. Message: " + e.message, e)
            }
        }

        @JvmStatic
        fun initAndRun(args: Array<String>) : Solution<*>{

            val injector = init(args)

            val solution = run(injector)

            writeTests(injector, solution)

            return solution
        }


        fun init(args: Array<String>) : Injector{

            val injector: Injector = LifecycleInjector.builder()
                    .withModules(* arrayOf<Module>(
                            BaseModule(args),
                            RestModule()
                    ))
                    .build().createInjector()

            return injector
        }

        fun run(injector: Injector) : Solution<*>{

            //TODO check algorithm and problem type
            val mio = injector.getInstance(Key.get(
                    object : TypeLiteral<MioAlgorithm<RestIndividual>>() {}))

            val solution = mio.search()

            return solution
        }


        fun writeTests(injector: Injector, solution: Solution<*>){

            val config = injector.getInstance(EMConfig::class.java)

            if(! config.createTests){
                return
            }

            TestSuiteWriter.writeTests(
                    solution,
                    config.outputFormat,
                    config.outputFolder,
                    config.testSuiteFileName
            )
        }
    }
}



