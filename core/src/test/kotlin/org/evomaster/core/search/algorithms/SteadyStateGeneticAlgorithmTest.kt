package org.evomaster.core.search.algorithms

import com.google.inject.Injector
import com.google.inject.Key
import com.google.inject.Module
import com.google.inject.TypeLiteral
import com.netflix.governator.guice.LifecycleInjector
import org.evomaster.core.BaseModule
import org.evomaster.core.EMConfig
import org.evomaster.core.TestUtils
import org.evomaster.core.search.algorithms.onemax.OneMaxIndividual
import org.evomaster.core.search.algorithms.onemax.OneMaxModule
import org.evomaster.core.search.algorithms.onemax.OneMaxSampler
import org.evomaster.core.search.algorithms.observer.GARecorder
import org.evomaster.core.search.algorithms.strategy.FixedSelectionStrategy
import org.evomaster.core.search.service.ExecutionPhaseController
import org.evomaster.core.search.service.Randomness
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SteadyStateGeneticAlgorithmTest {

    private val injector: Injector = LifecycleInjector.builder()
        .withModules(* arrayOf<Module>(OneMaxModule(), BaseModule()))
        .build().createInjector()

    // Verifies that the Steady-State GA can find the optimal solution for the OneMax problem
    @Test
    fun testSteadyStateGeneticAlgorithmFindsOptimum() {
        TestUtils.handleFlaky {
            val ssga = injector.getInstance(
                Key.get(
                    object : TypeLiteral<SteadyStateGeneticAlgorithm<OneMaxIndividual>>() {})
            )

            val config = injector.getInstance(EMConfig::class.java)
            config.maxEvaluations = 10_000
            config.stoppingCriterion = EMConfig.StoppingCriterion.ACTION_EVALUATIONS

            val epc = injector.getInstance(ExecutionPhaseController::class.java)
            if (epc.isInSearch()) epc.finishSearch()

            val solution = try {
                epc.startSearch()
                ssga.search()
            } finally {
                epc.finishSearch()
            }

            assertTrue(solution.individuals.size == 1)
            assertEquals(OneMaxSampler.DEFAULT_N.toDouble(), solution.overall.computeFitnessScore(), 0.001)
        }
    }

    // Edge Case: CrossoverProbability=0 on SSGA
    @Test
    fun testNoCrossoverWhenProbabilityZero_SSGA() {
        TestUtils.handleFlaky {
            val fixedSel = FixedSelectionStrategy()
            val (ga, localInjector) = createSSGAWithSelection(fixedSel)

            val rec = GARecorder<OneMaxIndividual>()
            ga.addObserver(rec)

            val config = localInjector.getInstance(EMConfig::class.java)
            val epc = localInjector.getInstance(ExecutionPhaseController::class.java)
            localInjector.getInstance(Randomness::class.java).updateSeed(42)

            config.populationSize = 4
            config.xoverProbability = 0.0 // disable crossover
            config.fixedRateMutation = 1.0 // force mutation
            config.gaSolutionSource = EMConfig.GASolutionSource.POPULATION
            config.maxEvaluations = 100_000
            config.stoppingCriterion = EMConfig.StoppingCriterion.ACTION_EVALUATIONS

            if (epc.isInSearch()) epc.finishSearch()
            try {
                epc.startSearch()
                ga.setupBeforeSearch()
                ga.searchOnce()

                // population size preserved
                val nextPop = ga.populationSnapshot()
                assertEquals(config.populationSize, nextPop.size)

                // exactly two selections in one steady-state step
                assertEquals(2, rec.selections.size)
                // crossover disabled
                assertEquals(0, rec.xoCalls.size)
                // two mutations (one per offspring)
                assertEquals(2, rec.mutated.size)
            } finally {
                epc.finishSearch()
            }
        }
    }

    // Edge Case: MutationProbability=0 on SSGA
    @Test
    fun testNoMutationWhenProbabilityZero_SSGA() {
        TestUtils.handleFlaky {
            val fixedSel = FixedSelectionStrategy()
            val (ga, localInjector) = createSSGAWithSelection(fixedSel)

            val rec = GARecorder<OneMaxIndividual>()
            ga.addObserver(rec)

            val config = localInjector.getInstance(EMConfig::class.java)
            val epc = localInjector.getInstance(ExecutionPhaseController::class.java)
            localInjector.getInstance(Randomness::class.java).updateSeed(42)

            config.populationSize = 4
            config.xoverProbability = 1.0 // force crossover
            config.fixedRateMutation = 0.0 // disable mutation
            config.gaSolutionSource = EMConfig.GASolutionSource.POPULATION
            config.maxEvaluations = 100_000
            config.stoppingCriterion = EMConfig.StoppingCriterion.ACTION_EVALUATIONS

            if (epc.isInSearch()) epc.finishSearch()
            try {
                epc.startSearch()
                ga.setupBeforeSearch()
                ga.searchOnce()

                val nextPop = ga.populationSnapshot()
                assertEquals(config.populationSize, nextPop.size)

                // two selections, one crossover, zero mutations
                assertEquals(2, rec.selections.size)
                assertEquals(1, rec.xoCalls.size)
                assertEquals(0, rec.mutated.size)
            } finally {
                epc.finishSearch()
            }
        }
    }
}

// --- Test helpers ---

private fun createSSGAWithSelection(
    fixedSel: FixedSelectionStrategy
): Pair<SteadyStateGeneticAlgorithm<OneMaxIndividual>, Injector> {
    val testModule = object : com.google.inject.AbstractModule() {
        override fun configure() {
            bind(org.evomaster.core.search.algorithms.strategy.SelectionStrategy::class.java)
                .toInstance(fixedSel)
        }
    }

    val injector = LifecycleInjector.builder()
        .withModules(* arrayOf<Module>(
            OneMaxModule(),
            com.google.inject.util.Modules.override(BaseModule()).with(testModule)
        ))
        .build().createInjector()

    val ga = injector.getInstance(
        Key.get(object : TypeLiteral<SteadyStateGeneticAlgorithm<OneMaxIndividual>>() {})
    )
    return ga to injector
}


