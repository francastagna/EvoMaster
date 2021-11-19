package org.evomaster.core.search.gene.geometric

import org.evomaster.core.search.gene.*
import org.evomaster.core.logging.LoggingUtil
import org.evomaster.core.output.OutputFormat
import org.evomaster.core.search.service.AdaptiveParameterControl
import org.evomaster.core.search.service.Randomness
import org.evomaster.core.search.service.mutator.genemutation.AdditionalGeneMutationInfo
import org.evomaster.core.search.service.mutator.genemutation.SubsetGeneSelectionStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PathGene(
    name: String,
    val points: ArrayGene<PointGene> = ArrayGene(name = "points", template = PointGene("p"))
) : Gene(name, mutableListOf(points)) {

    companion object {
        val log: Logger = LoggerFactory.getLogger(PathGene::class.java)
    }

    init {
        /*
         * Paths must be non-empty lists
         */
        points.addElements(PointGene("p1"))
    }

    override fun getChildren(): MutableList<Gene> = mutableListOf(points)

    override fun copyContent(): Gene = PathGene(
        name,
        points.copyContent() as ArrayGene<PointGene>
    )

    override fun randomize(randomness: Randomness, forceNewValue: Boolean, allGenes: List<Gene>) {
        points.randomize(randomness, forceNewValue, allGenes)
        /*
         * A geometric path must be always a non-empty list
         */
        if (points.getAllElements().isEmpty()) {
            points.addElements(PointGene("p"))
        }
    }

    override fun candidatesInternalGenes(
        randomness: Randomness,
        apc: AdaptiveParameterControl,
        allGenes: List<Gene>,
        selectionStrategy: SubsetGeneSelectionStrategy,
        enableAdaptiveGeneMutation: Boolean,
        additionalGeneMutationInfo: AdditionalGeneMutationInfo?
    ): List<Gene> {
        return listOf(points)
    }

    override fun getValueAsPrintableString(
        previousGenes: List<Gene>,
        mode: GeneUtils.EscapeMode?,
        targetFormat: OutputFormat?,
        extraCheck: Boolean
    ): String {
        return "\" ( ${
            points.getAllElements()
                .map { it.getValueAsRawString() }
                .joinToString(" , ")
        } ) \""
    }

    override fun getValueAsRawString(): String {
        return "( ${
            points.getAllElements()
                .map { it.getValueAsRawString() }
                .joinToString(" , ")
        } ) "
    }

    override fun copyValueFrom(other: Gene) {
        if (other !is PathGene) {
            throw IllegalArgumentException("Invalid gene type ${other.javaClass}")
        }
        this.points.copyValueFrom(other.points)
    }

    override fun containsSameValueAs(other: Gene): Boolean {
        if (other !is PathGene) {
            throw IllegalArgumentException("Invalid gene type ${other.javaClass}")
        }
        return this.points.containsSameValueAs(other.points)
    }

    override fun flatView(excludePredicate: (Gene) -> Boolean): List<Gene> {
        return if (excludePredicate(this)) listOf(this) else
            listOf(this).plus(points.flatView(excludePredicate))
    }

    override fun innerGene(): List<Gene> = listOf(points)

    override fun bindValueBasedOn(gene: Gene): Boolean {
        return when {
            gene is PathGene -> {
                points.bindValueBasedOn(gene.points)
            }
            else -> {
                LoggingUtil.uniqueWarn(log, "cannot bind PathGene with ${gene::class.java.simpleName}")
                false
            }
        }
    }


}