package org.evomaster.core.search.gene

import org.evomaster.core.logging.LoggingUtil
import org.evomaster.core.output.OutputFormat
import org.evomaster.core.search.Individual
import org.evomaster.core.search.StructuralElement
import org.evomaster.core.search.service.AdaptiveParameterControl
import org.evomaster.core.search.service.Randomness
import org.evomaster.core.search.service.mutator.MutationWeightControl
import org.evomaster.core.search.service.mutator.genemutation.AdditionalGeneMutationInfo
import org.evomaster.core.search.service.mutator.genemutation.DifferentGeneInHistory
import org.evomaster.core.search.service.mutator.genemutation.SubsetGeneSelectionStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.evomaster.core.Lazy
import org.evomaster.core.search.RootElement


/**
 *
 * A building block representing one part of an Individual.
 * The terms "gene" comes from the evolutionary algorithm literature
 *
 * When creating a new Gene type, should not extend directly from this class, but rather
 * from [SimpleGene], [CompositeGene] or [CompositeFixedGene], or any of their subclasses.
 * There are test cases to impose this property.
 *
 *
 * TO enable adaptive hypermutation
 * 1. override [mutationWeight] if the gene is not simple gene, e.g., it is complex with many genes inside
 *
 * 2. if the gene has inner genes, then we need to collect impact info.
 * Implement an impact (a subclass of [GeneImpact]) for the new gene to collect impact info for gene mutation.
 * Impact here is referred to how the gene is influencing the fitness.
 * For instance, see [org.evomaster.core.search.impact.impactinfocollection.value.ObjectGeneImpact]
 *  we collect impacts for each field, then could guide on which field to be selected for mutation.
 * See more details in comments of [org.evomaster.core.search.impact.impactinfocollection.GeneImpact]
 *
 * 3. override [candidatesInternalGenes] to decide 1) whether to apply selection for the internal genes
 *  2) what candidates are in [this] gene to be selected for mutation, eg, mutable fields for ObjectGene.
 *      More info could be found with comments of the method.
 *
 * 4. with the collected impact info, override [adaptiveSelectSubset] to decide which gene to be selected
 *
 */
abstract class Gene(
        /**
         * The name for this gene, mainly needed for debugging.
         * One actual use is for binding, e.g., paremeters in HTTP requests
         */
        var name: String,
        /**
         * The direct node inside this Gene (excluding templates).
         * These children might have their own children.
         * Note that children while have links back to their "parents".
         */
        children: MutableList<out Gene>
) : StructuralElement(children){

    /*
        TODO Major refactoring still to do:
        - collections
        - mutation of gene (including hypermutation and innerGene)
        - impact of genes
        - validity / robustness testing

        - for binding, we ll need tests on Individual
     */

    companion object{
        private val log: Logger = LoggerFactory.getLogger(Gene::class.java)
    }

    var initialized : Boolean = false
        private set

    /**
     * In the scope of [Individual] and not genes in isolation.
     * The current gene could be "bound" to other genes.
     * This means that, if this gene is modified, then ALL the other
     * bound gene must be updated as well.
     * The type can be different, eg strings vs numbers, but still consistent.
     *
     * WARNING: genes are mutable, but here we check for references. this implies
     * NO gene can overridde hashcode.
     *
     * If A is bound to B, then as well B is bound to A.
     */
    private val bindingGenes: MutableSet<Gene> = mutableSetOf()

    init{
        if(name.isBlank()){
            throw IllegalArgumentException("Empty name for Gene")
        }
    }

    override fun hashCode(): Int {
        //Otherwise bindingGenes will not work
        return super.hashCode()
    }

    /*
        TODO: make sure to call it not only on mutation, but also on printing out
     */
    private fun checkInitialized(){
        if(! initialized)
            throw IllegalStateException("Trying to use a gene that is not initialized")
    }

    override  val children : MutableList<Gene>
        get() = super.children as MutableList<Gene>

    final override fun getViewOfChildren() : List<Gene> = children

    /**
     * Initialize this gene with random data, as well as initializing all
     * of its children, recursively.
     *
     * A gene cannot be used (eg, mutated or printed in the phenotype) before it is initialized
     *
     * if [rand] is provided, the gene will be randomized. this should always be provided, unless
     * you are building tests directly
     */
    fun doInitialize(rand: Randomness? = null){
        if(initialized){
            throw IllegalStateException("Gene already initialized")
        }
        if(rand != null) {
            randomize(rand, false)
        } else {
            //this should happen only in the tests
            log.warn("Initializing gene ${this.javaClass} without randomization")
        }
        markAllAsInitialized()
        Lazy.assert{isValid()}
    }

    //TODO needed for copies. check if can be refactored, eg if copyContent enforce the copy of initialized
    fun markAllAsInitialized(){
        flatView().forEach{it.initialized = true}
        initialized = true
    }

    /**
     * Make a copy of this gene.
     */
    final override fun copy() : Gene{
        val copy = super.copy()
        if (copy !is Gene)
            throw IllegalStateException("mismatched type: the type should be Gene, but it is ${this::class.java.simpleName}")
        copy.initialized = initialized
        copy.flatView().forEach{it.initialized = initialized}
        return copy
    }

    /*
     * override to force return type Gene
     */
    //TODO put back, once we have tests for it
    //abstract override  fun copyContent(): Gene

    override fun copyContent(): Gene {
        throw IllegalStateException("${this::class.java.simpleName}: copyContent() IS NOT IMPLEMENTED")
    }

    override fun postCopy(original: StructuralElement) {
        //rebuild the binding genes
        val root = getRoot()
        if(root is RootElement) {
            /*
                a gene can refer to other genes outside of its tree.
                when we make a copy we need to make sure that we refer to the new gene in the copied
                individual, not the original individual.
                so, this is applied only  when the root is an individual, otherswise skipped, because
                would not be able to find those genes anyway
             */
            val postBinding = (original as Gene).bindingGenes.map { b ->
                val found = root.find(b)
                found as? Gene
                        ?: throw IllegalStateException("mismatched type between template (${b::class.java.simpleName}) and found (${found::class.java.simpleName})")
            }
            bindingGenes.clear()
            bindingGenes.addAll(postBinding)
        } else {
            assert(bindingGenes.isEmpty())
        }


        super.postCopy(original)
    }

    /**
     * there might be a need to repair gene based on some constraints, e.g., DateGene and TimeGene
     */
    open fun repair(){
        //do nothing
    }

    /**
     * @return whether the gene is valid
     *  based on any specialized rule for different types of genes if there exist
     *
     * Note that the method is only used for debugging and testing purposes.
     *  e.g., for NumberGene, if min and max are specified, the value should be within min..max.
     *        for FloatGene with precision 2, the value 10.222 would not be considered as a valid gene.
     */
    open fun isValid() = true

    /**
     * mutated gene should pass the check if needed, eg, DateGene
     *
     * In some cases, we must have genes with 'valid' values.
     * For example, a date with month 42 would be invalid.
     * On the one hand, it can still be useful for robustness testing
     * to provide such invalid values in a HTTP call. On the other hand,
     * it would be pointless to try to add it directly into a database,
     * as that SQL command would simply fail without any SUT code involved.
     */
    open fun mutationCheck() : Boolean = true


    /*
        TODO shall we remove to default function implementation? to make sure new
        genes are forced to set them up, and not forget about them?
        or can we write invariants which will make tests fail in those cases?
     */

    /**
     * weight for mutation
     * For example, higher the weight, the higher the chances to be selected for mutation
     */
    open fun mutationWeight() : Double = 1.0

    /**
     * Specify if this gene can be mutated during the search.
     * Typically, it will be true, apart from some special cases.
     */
    open fun isMutable() = true

    /**
     * Specify if this gene should be printed in the output test.
     * In other words, if this genotype directly influences the
     * phenotype
     */
    open fun isPrintable() = true


    /**
     *   TODO this method is always needed to be called before the Gene is usable.
     *   there is no comment here, but I remember writing something like this...
     *   if so, must be called at least once, or should throw an exception.
     *
     *   TODO Or must we guarantee validity of constraints in constructor???
     *
     *   Randomize the content of this gene.
     *
     *   @param randomness the source of non-determinism
     *   @param forceNewValue whether we should force the change of value. When we do mutation,
     *          it could otherwise happen that a value is replace with itself
     *
     *   TODO likely deprecated, because we can traverse the tree upward now
     *   @param allGenes if the gene depends on the other (eg a Foreign Key in SQL databases),
     *          we need to refer to them
     */
    abstract fun randomize(
            randomness: Randomness,
            forceNewValue: Boolean,
            allGenes: List<Gene> = listOf())

    /**
     * A mutation is just a small change.
     * Apply a mutation to the current gene.
     * Regarding the gene,
     * 1) there might exist multiple internal genes i.e.,[candidatesInternalGenes].
     *  In this case, we first apply [selectSubset] to select a subset of internal genes.
     *  then apply mutation on each of the selected genes.
     * 2) When there is no need to do further selection, we apply [mutate] on the current gene.
     *
     *   @param randomness the source of non-determinism
     *   @param apc parameter control
     *   @param mwc mutation weight control
     *   @param allGenes if the gene depends on the other (eg a Foreign Key in SQL databases),
     *          we need to refer to them
     *   @param interalGeneSelectionStrategy a strategy to select internal genes to mutate
     *   @param enableAdaptiveMutation whether apply adaptive gene mutation, e.g., archive-based gene mutation
     *   @param additionalGeneMutationInfo contains additional info for gene mutation
     */
    fun standardMutation(
            randomness: Randomness,
            apc: AdaptiveParameterControl,
            mwc: MutationWeightControl,
            //TODO likely deprecated
            allGenes: List<Gene> = listOf(),
            internalGeneSelectionStrategy: SubsetGeneSelectionStrategy = SubsetGeneSelectionStrategy.DEFAULT,
            enableAdaptiveGeneMutation: Boolean = false,
            additionalGeneMutationInfo: AdditionalGeneMutationInfo? = null
    ){
        checkInitialized()

        //if impact is not able to obtain, adaptive-gene-mutation should also be disabled
        val internalGenes = candidatesInternalGenes(randomness, apc, allGenes, internalGeneSelectionStrategy, enableAdaptiveGeneMutation, additionalGeneMutationInfo)
        if (internalGenes.isEmpty()){
            val mutated = mutate(randomness, apc, mwc, allGenes, internalGeneSelectionStrategy, enableAdaptiveGeneMutation, additionalGeneMutationInfo)
            if (!mutated)
                throw IllegalStateException("leaf mutation is not implemented for ${this::class.java.simpleName}")
        }else{
            val selected = selectSubset(internalGenes, randomness, apc, mwc, allGenes, internalGeneSelectionStrategy, enableAdaptiveGeneMutation, additionalGeneMutationInfo)

            selected.forEach{
                var mutateCounter = 0
                do {
                    it.first.standardMutation(randomness, apc, mwc, allGenes, internalGeneSelectionStrategy, enableAdaptiveGeneMutation, it.second)
                    mutateCounter +=1
                }while (!mutationCheck() && mutateCounter <=3)
                if (!mutationCheck()){
                    if (log.isTraceEnabled)
                        log.trace("invoke GeneUtils.repairGenes")
                    GeneUtils.repairGenes(listOf(this))
                }
            }
        }

        //sync binding gene after value mutation
        syncBindingGenesBasedOnThis()
    }


    /**
     * @return a list of internal gene to be selected for mutation, eg, weight-based or adaptive weight-based gene selection
     * note that if return an empty list, [mutate] will be applied to mutate this gene
     *
     * For instance, see [ArrayGene.candidatesInternalGenes], with a probability, it returns an empty list.
     * the empty list means (see [ArrayGene.mutate]) that the mutation is applied to change the size of this array gene.
     *
     * A default implementation for "simple" genes would be to return "listOf<Gene>()"
     */
    abstract fun candidatesInternalGenes(randomness: Randomness,
                                         apc: AdaptiveParameterControl,
                                         //TODO remove deprecated
                                         allGenes: List<Gene>,
                                         selectionStrategy: SubsetGeneSelectionStrategy,
                                         enableAdaptiveGeneMutation: Boolean,
                                         additionalGeneMutationInfo: AdditionalGeneMutationInfo?
    ): List<Gene>


    /**
     * @return a subset of internal genes to apply mutations
     */
    private fun selectSubset(internalGenes: List<Gene>,
                          randomness: Randomness,
                          apc: AdaptiveParameterControl,
                          mwc: MutationWeightControl,
                          allGenes: List<Gene> = listOf(),
                          selectionStrategy: SubsetGeneSelectionStrategy,
                          enableAdaptiveGeneMutation: Boolean,
                          additionalGeneMutationInfo: AdditionalGeneMutationInfo?
    ): List<Pair<Gene, AdditionalGeneMutationInfo?>> {
        return  when(selectionStrategy){
            SubsetGeneSelectionStrategy.DEFAULT -> {
                val s = randomness.choose(internalGenes)
                listOf( s to additionalGeneMutationInfo?.copyFoInnerGene( null,s))
            }
            SubsetGeneSelectionStrategy.DETERMINISTIC_WEIGHT ->
                mwc.selectSubGene(candidateGenesToMutate = internalGenes, adaptiveWeight = false).map { it to additionalGeneMutationInfo?.copyFoInnerGene(null, it) }
            SubsetGeneSelectionStrategy.ADAPTIVE_WEIGHT -> {
                additionalGeneMutationInfo?: throw IllegalArgumentException("additionalGeneSelectionInfo should not be null")
                if (additionalGeneMutationInfo.impact == null)
                    mwc.selectSubGene(candidateGenesToMutate = internalGenes, adaptiveWeight = false).map { it to additionalGeneMutationInfo.copyFoInnerGene(null, it) }
                else
                    adaptiveSelectSubset(randomness, internalGenes, mwc, additionalGeneMutationInfo)
            }
        }.also {
            if (it.isEmpty())
                throw IllegalStateException("with $selectionStrategy strategy and ${internalGenes.size} candidates, none is selected to mutate")
            if (it.any { a -> a.second?.impact?.validate(a.first) == false})
                throw IllegalStateException("mismatched impact for gene ${it.filter { a -> a.second?.impact?.validate(a.first) == false}.map { "${it.first}:${it.second}" }.joinToString(",")}")
        }
    }

    /**
     * @param randomness
     * @param internalGenes is a set of candidates to be selected
     * @param mwc is mutation weight controller which can be used to select genes with given weights
     * @param additionalGeneMutationInfo contains impact info of [this] gene
     * @return a subset of [internalGenes] with corresponding impact info
     */
    open fun adaptiveSelectSubset(randomness: Randomness,
                                  internalGenes: List<Gene>,
                                  mwc: MutationWeightControl,
                                  additionalGeneMutationInfo: AdditionalGeneMutationInfo): List<Pair<Gene, AdditionalGeneMutationInfo?>> {
        throw IllegalStateException("adaptive gene selection is unavailable for the gene ${this::class.java.simpleName}")
    }

    /**
     * mutate the current gene if there is no need to apply selection, i.e., when [candidatesInternalGenes] is empty
     */
    open fun mutate(randomness: Randomness,
                    apc: AdaptiveParameterControl,
                    mwc: MutationWeightControl,
                    allGenes: List<Gene> = listOf(),
                    selectionStrategy: SubsetGeneSelectionStrategy,
                    enableAdaptiveGeneMutation: Boolean,
                    additionalGeneMutationInfo: AdditionalGeneMutationInfo?) : Boolean{
        if (enableAdaptiveGeneMutation){
            additionalGeneMutationInfo?:throw IllegalArgumentException("additional gene mutation info should not be null when adaptive gene mutation is enabled")
            if (additionalGeneMutationInfo.hasHistory()){
                try {
                    additionalGeneMutationInfo.archiveGeneMutator.historyBasedValueMutation(
                        additionalGeneMutationInfo,
                        this,
                        allGenes
                    )
                    return true
                }catch (e: DifferentGeneInHistory){
                    LoggingUtil.uniqueWarn(log, e.message?:"Fail to employ adaptive gene value mutation due to failure in handling its history")
                }
            }
        }

        return false
    }

    /**
     * Return the value as a printable string.
     * Once printed, it would be equivalent to the actual value, eg
     *
     * 1 -> "1" -> printed as 1
     *
     * "foo" -> "\"foo\"" -> printed as "foo"
     *
     * @param previousGenes previous genes which are necessary to look at
     * to determine the actual value of this gene
     * @param mode some genes could be printed in different ways, like an
     * object printed as JSON or XML
     * @param targetFormat different target formats may have different rules
     * regarding what characters need to be escaped (e.g. the $ char in Kotlin)
     * If the [targetFormat] is set to null, no characters are escaped.
     */
    abstract fun getValueAsPrintableString(
            previousGenes: List<Gene> = listOf(),
            mode: GeneUtils.EscapeMode? = null,
            targetFormat: OutputFormat? = null,
            /**
             * Generic boolean, used for extra info, if needed.
             *
             * This was introduced mainly to deal with the printing of objects in GraphQL.
             * Specify if the name of object should be printed or not, or just directly the
             * object {} definition, ie,
             * foo {...}
             * vs
             * {...}
             */
            extraCheck: Boolean = false
    ) : String


    open fun getValueAsRawString() = getValueAsPrintableString(targetFormat = null)
    /*
    Note: above, null target format means that no characters are escaped.
     */

    abstract fun copyValueFrom(other: Gene)

    /**
     * If this gene represents a variable, then return its name.
     */
    open fun getVariableName() = name

    /**
     * Genes might have other genes inside (eg, think of array).
     * But these are only the ones in "child-parent" hierarchy.
     * There might be cases like "template" genes inside, those will NOT be returned.
     *
     * @param excludePredicate is used to configure which genes you do not want to show genes inside.
     *      For instance, an excludePredicate is {gene : Gene -> (gene is TimeGene)}, then when flatView of a Gene including TimeGene,
     *      the genes inside e.g., hour: IntegerGene will be not viewed, but TimeGene will be viewed.
     * @return a recursive list of all nested genes, "this" included
     */
    fun flatView(excludePredicate: (Gene) -> Boolean = {false}): List<Gene>{
        return if (excludePredicate(this)) listOf(this) else
            listOf(this).plus(children.flatMap { g -> g.flatView(excludePredicate) })
    }

    /**
     * Genes might contain a value that is also stored
     * in another gene of the same type.
     */
    abstract fun containsSameValueAs(other: Gene): Boolean


    /**
     * TODO is this necessary considering children and flatView???
     *
     * @return internal genes
     */
    abstract fun innerGene() : List<Gene>

    /**
     * evaluate whether [this] and [gene] belong to one evolution during search
     */
    open fun possiblySame(gene : Gene) : Boolean = gene.name == name && gene::class == this::class


    //========================= handing binding genes ===================================



    /**
     * rebuild the binding relationship of [this] gene based on [copiedGene] which exists in [copiedIndividual]
     */
    fun rebuildBindingWithTemplate(newIndividual: Individual, copiedIndividual: Individual, copiedGene: Gene){
        if (bindingGenes.isNotEmpty())
            throw IllegalArgumentException("gene ($name) has been rebuilt")

        val list = copiedGene.bindingGenes.map { g->
            newIndividual.findGene(copiedIndividual, g)
                ?:throw IllegalArgumentException("cannot find the gene (${g.name}) in the copiedIndividual")
        }

        bindingGenes.addAll(list)
    }

    /**
     * sync [bindingGenes] based on [this]
     */
    fun syncBindingGenesBasedOnThis(all : MutableSet<Gene> = mutableSetOf()){
        if (bindingGenes.isEmpty()) return
        all.add(this)
        bindingGenes.filterNot { all.contains(it) }.forEach { b->
            all.add(b)
            if(!b.bindValueBasedOn(this))
                LoggingUtil.uniqueWarn(log, "fail to bind the gene (${b.name} with the type ${b::class.java.simpleName}) based on this gene (${this.name} with ${this::class.java.simpleName})")
            b.syncBindingGenesBasedOnThis(all)
        }

        innerGene().filterNot { all.contains(it) }.forEach { it.syncBindingGenesBasedOnThis(all) }
    }

    /**
     * get all binding genes of [this]
     */
    private fun getBindingGenes(all : MutableSet<Gene>){
        if (bindingGenes.isEmpty()) return
        all.add(this)
        bindingGenes.filterNot { all.contains(it) }.forEach { b->
            all.add(b)
            b.getBindingGenes(all)
        }
        innerGene().filterNot { all.contains(it) }.forEach { it.getBindingGenes(all) }
    }

    /**
     * remove [this] from its binding genes
     */
    fun removeThisFromItsBindingGenes(){
        val all = mutableSetOf<Gene>()
        getBindingGenes(all)
        all.forEach { b->
            b.removeBindingGene(this)
        }
    }

    /**
     * @return whether [this] gene is bound with any other gene
     */
    fun isBoundGene() = bindingGenes.isNotEmpty()

    /**
     * repair the broken binding reference e.g., the binding gene is removed from the current individual
     */
    fun cleanBrokenReference(all : List<Gene>) : Boolean{
        return bindingGenes.removeIf { !all.contains(it) }
    }

    /**
     * remove genes which has been removed from the root
     */
    fun cleanRemovedGenes(removed: List<Gene>): Boolean{
        return bindingGenes.removeIf{removed.contains(it)}
    }

    /**
     * @return whether [this] gene has same binding gene as [genes]
     *
     * it is useful for debugging/unit tests
     */
    fun isSameBinding(genes: Set<Gene>) = (genes.size == bindingGenes.size) && genes.containsAll(bindingGenes)

    /**
     * add [gene] as the binding gene
     */
    fun addBindingGene(gene: Gene) {
        bindingGenes.add(gene)
    }

    /**
     * remove [gene] as the binding gene
     */
    private fun removeBindingGene(gene: Gene): Boolean {
        return bindingGenes.remove(gene)
    }

    /**
     * @return whether the bindingGene is subset of the [set]
     */
    fun bindingGeneIsSubsetOf(set: List<Gene>) = set.containsAll(bindingGenes)

    /**
     * reset binding based on [genes]
     */
    fun resetBinding(genes: Set<Gene>) {
        bindingGenes.clear()
        bindingGenes.addAll(genes)
    }

    /**
     * @return whether [this] is bound with [gene]
     */
    fun isBoundWith(gene: Gene) = bindingGenes.contains(gene)


    /**
     * bind value of [this] gene based on [gene].
     * The type of genes can be different.
     * @return whether the binding performs successfully
     */
    abstract fun bindValueBasedOn(gene: Gene) : Boolean



}

