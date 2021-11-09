package org.evomaster.core.problem.rest.service

import com.google.inject.Inject
import org.evomaster.core.Lazy
import org.evomaster.core.database.DbAction
import org.evomaster.core.problem.httpws.service.HttpWsStructureMutator
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.RestIndividual
import org.evomaster.core.problem.httpws.service.auth.AuthenticationInfo
import org.evomaster.core.problem.rest.resource.ResourceImpactOfIndividual
import org.evomaster.core.problem.rest.resource.RestResourceCalls
import org.evomaster.core.search.Action
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.Individual
import org.evomaster.core.search.ActionFilter
import org.evomaster.core.search.ActionFilter.*
import org.evomaster.core.search.gene.sql.SqlForeignKeyGene
import org.evomaster.core.search.gene.sql.SqlPrimaryKeyGene
import org.evomaster.core.search.impact.impactinfocollection.value.numeric.IntegerGeneImpact
import org.evomaster.core.search.service.mutator.MutatedGeneSpecification
import org.evomaster.core.search.service.mutator.MutationWeightControl
import org.evomaster.core.search.service.mutator.genemutation.ArchiveImpactSelector
import kotlin.math.max
import kotlin.math.min
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

class RestResourceStructureMutator : HttpWsStructureMutator() {

    @Inject
    private lateinit var rm : ResourceManageService

    @Inject
    private lateinit var dm : ResourceDepManageService

    @Inject
    private lateinit var sampler : ResourceSampler

    @Inject
    protected lateinit var mwc : MutationWeightControl

    @Inject
    protected lateinit var archiveImpactSelector : ArchiveImpactSelector

    companion object{
        private val log : Logger = LoggerFactory.getLogger(RestResourceStructureMutator::class.java)
    }

    override fun mutateStructure(individual: Individual, evaluatedIndividual: EvaluatedIndividual<*>, mutatedGenes: MutatedGeneSpecification?, targets: Set<Int>) {
        if(individual !is RestIndividual && evaluatedIndividual.individual is RestIndividual)
            throw IllegalArgumentException("Invalid individual type")

        evaluatedIndividual as EvaluatedIndividual<RestIndividual>

        // man: shall we only handle size only during fs?
        val dohandleSize =  (config.maxSizeOfHandlingResource > 0) && randomness.nextBoolean(config.probOfHandlingLength)
        val mutationType = if (dohandleSize)
            decideMutationType(evaluatedIndividual, targets)
        else null

        mutateRestResourceCalls(individual as RestIndividual, mutationType, mutatedGenes = mutatedGenes, evaluatedIndividual, targets, dohandleSize)
        if (config.trackingEnabled()) tag(individual, time.evaluatedIndividuals)
    }

    private fun decideMutationType(evaluatedIndividual: EvaluatedIndividual<RestIndividual>, targets: Set<Int>) : MutationType?{
        // with adaptive structure mutator selection, we enable adding multiple same resource with rest action
        val candidates = getAvailableMutator(evaluatedIndividual.individual, true)
        // during focused search, we only involve the mutator which could change the size of resources
        val lengthMutator = candidates.filter {
            // relatively low probability of applying rest actions to manipulate length
            it == MutationType.ADD && randomness.nextBoolean(0.2)
        }
        if (lengthMutator.isEmpty())
            return null

        if (!config.enableAdaptiveResourceStructureMutation)
            return randomness.choose(lengthMutator)

        val impact = ((evaluatedIndividual.impactInfo?:throw IllegalStateException("lack impact info"))
                as? ResourceImpactOfIndividual)?:throw IllegalStateException("mismatched impact type, it should be ResourceImpactOfIndividual")
        val impacts = lengthMutator.map { type ->
            when (type) {
                MutationType.ADD, MutationType.DELETE -> impact.anyResourceSizeImpact
                MutationType.SQL_ADD, MutationType.SQL_REMOVE -> impact.anySqlTableSizeImpact
                else -> throw IllegalStateException("$type should be handled before")
            }
        }

        val weights = archiveImpactSelector.impactBasedOnWeights(impacts, targets)
        val impactMap = lengthMutator.mapIndexed { index, type -> type to weights[index] }.toMap()
        val types = mwc.selectSubsetWithWeight(impactMap, true, 1.0)
        return if (types.size == 1) types.first() else randomness.choose(types)
    }

    override fun canApplyStructureMutator(individual: Individual): Boolean {
        if(individual !is RestIndividual)
            throw IllegalArgumentException("Invalid individual type")

        return super.canApplyStructureMutator(individual) && getAvailableMutator(individual).isNotEmpty()
    }

    fun mutateRestResourceCalls(ind: RestIndividual,
                                specified : MutationType?=null,
                                mutatedGenes: MutatedGeneSpecification? = null,
                                evaluatedIndividual: EvaluatedIndividual<RestIndividual>?=null, targets: Set<Int>?=null, dohandleSize: Boolean=false) {

        val executedStructureMutator = specified?: randomness.choose(getAvailableMutator(ind))

        when(executedStructureMutator){
            MutationType.ADD -> handleAdd(ind, mutatedGenes, evaluatedIndividual, targets, dohandleSize)
            MutationType.DELETE -> handleDelete(ind, mutatedGenes, evaluatedIndividual, targets, dohandleSize)
            MutationType.SWAP -> handleSwap(ind, mutatedGenes)
            MutationType.REPLACE -> handleReplace(ind, mutatedGenes)
            MutationType.MODIFY -> handleModify(ind, mutatedGenes)
            MutationType.SQL_REMOVE -> handleRemoveSQL(ind, mutatedGenes, evaluatedIndividual, targets)
            MutationType.SQL_ADD -> handleAddSQL(ind, mutatedGenes, evaluatedIndividual, targets)
        }
    }

    private fun getAvailableMutator(ind: RestIndividual, handleSize: Boolean = false) : List<MutationType>{
        val num = ind.getResourceCalls().size
        val sqlNum = ind.seeResource(RestIndividual.ResourceFilter.ONLY_SQL_INSERTION).size
        return MutationType.values()
            .filter {  num >= it.minSize && sqlNum >= it.minSQLSize && isMutationTypeApplicable(it, ind, handleSize)}

    }

    private fun isMutationTypeApplicable(type: MutationType, ind : RestIndividual, handleSize: Boolean): Boolean{
        val delSize = ind.getResourceCalls().filter(RestResourceCalls::isDeletable).size
        return when(type){
            MutationType.SWAP -> ind.extractSwapCandidates().isNotEmpty() && (!handleSize)
            MutationType.REPLACE -> !rm.cluster.doesCoverAll(ind) && delSize > 0 && (!handleSize)
            MutationType.MODIFY -> delSize > 0 && (!handleSize)
            MutationType.ADD -> ind.seeActions().size < config.maxTestSize && (!rm.cluster.doesCoverAll(ind) || handleSize)
            MutationType.DELETE -> delSize > 0 && ind.getResourceCalls().size >=2
            // SQL_ADD and SQL_REMOVE are enabled only if handling size is enabled
            MutationType.SQL_ADD -> handleSize && rm.getTableInfo().isNotEmpty()
            MutationType.SQL_REMOVE -> handleSize && rm.getTableInfo().isNotEmpty() && ind.seeInitializingActions().isNotEmpty()
        }
    }

    /**
     * the class defines possible methods to mutate ResourceRestIndividual regarding its resources
     * @param minSize is a minimum number of rest actions in order to apply the mutation
     * @param minSQLSize is a minimum number of db actions in order to apply the mutation
     */
    enum class MutationType(val minSize: Int, val minSQLSize : Int = 0){
        /**
         * remove a resource
         */
        DELETE(2),

        /**
         * swap two resources
         */
        SWAP(2),

        /**
         * add a new resource
         */
        ADD(1),

        /**
         * replace current resource with another one
         */
        REPLACE(1),

        /**
         * change a resource with different resource template
         */
        MODIFY(1),

        /**
         * remove insertions to table
         */
        SQL_REMOVE(1, 1),

        /**
         * add insertions to table
         */
        SQL_ADD(1, 1)
    }

    /**
     * add resources with SQL to [ind]
     * a number of resources to be added is related to EMConfig.maxSqlInitActionsPerResource
     */
    private fun handleAddSQL(ind: RestIndividual, mutatedGenes: MutatedGeneSpecification?, evaluatedIndividual: EvaluatedIndividual<RestIndividual>?, targets: Set<Int>?){
        if (config.maxSizeOfHandlingResource == 0)
            throw IllegalStateException("this method should not be invoked when config.maxSqlInitActionsPerResource is 0")
        val numOfResource = randomness.nextInt(1, rm.getMaxNumOfResourceSizeHandling())
        val candidates = if (doesApplyDependencyHeuristics())
            dm.identifyRelatedSQL(ind)
        else
            ind.seeInitializingActions().map { it.table.name }.toSet() // adding an unrelated table would waste budget, then we add existing ones

        val selectedAdded = if (config.enableAdaptiveResourceStructureMutation){
            adaptiveSelectResource(evaluatedIndividual, bySQL = true, candidates.toList(), targets)
        }else{
            randomness.choose(candidates)
        }
        val added = dm.createDbActions(selectedAdded, numOfResource)

        ind.addInitializingActions(actions = added.flatten())
        mutatedGenes?.addedDbActions?.addAll(added)
    }


    private fun adaptiveSelectResource(evaluatedIndividual: EvaluatedIndividual<RestIndividual>?, bySQL: Boolean, candidates: List<String>, targets: Set<Int>?): String{
        evaluatedIndividual?: throw IllegalStateException("lack of impact with specified evaluated individual")
        targets?:throw IllegalStateException("targets must be specified if adaptive resource selection is applied")
        if (evaluatedIndividual.impactInfo == null || evaluatedIndividual.impactInfo !is ResourceImpactOfIndividual)
            throw IllegalStateException("lack of impact info or mismatched impact type (type: ${evaluatedIndividual.impactInfo?.javaClass?.simpleName?:"null"})")
        val impacts = candidates.map {
            if (bySQL){
                evaluatedIndividual.impactInfo.sqlTableSizeImpact[it] ?:IntegerGeneImpact("size")
            }else{
                evaluatedIndividual.impactInfo.resourceSizeImpact[it] ?:IntegerGeneImpact("size")
            }
        }

        val weights = archiveImpactSelector.impactBasedOnWeights(impacts, targets)
        val impactMap = candidates.mapIndexed { index, type -> type to weights[index] }.toMap()
        val selected = mwc.selectSubsetWithWeight(impactMap, true, 1.0)
        return if (selected.size == 1) selected.first() else randomness.choose(selected)
    }

    /**
     * remove one resource which are created by SQL
     *
     * Man: shall we remove SQLs which represents existing data?
     * It might be useful to reduce the useless db genes.
     */
    private fun handleRemoveSQL(ind: RestIndividual, mutatedGenes: MutatedGeneSpecification?, evaluatedIndividual: EvaluatedIndividual<RestIndividual>?, targets: Set<Int>?){
        // remove unrelated tables
        val candidates = if (doesApplyDependencyHeuristics()) dm.identifyUnRelatedSqlTable(ind) else ind.seeInitializingActions().map { it.table.name }
        val selected = if (config.enableAdaptiveResourceStructureMutation) adaptiveSelectResource(evaluatedIndividual, true, candidates.toList(), targets) else randomness.choose(candidates)
        val total = candidates.count { it == selected }
        val num = randomness.nextInt(1, max(1, min(rm.getMaxNumOfResourceSizeHandling(), min(total, ind.seeInitializingActions().size - 1))))
        val remove = randomness.choose(ind.seeInitializingActions().filter { it.table.name == selected }, num)
        val relatedRemove = mutableListOf<DbAction>()
        relatedRemove.addAll(remove)
        remove.forEach {
            getRelatedRemoveDbActions(ind, it, relatedRemove)
        }
        val set = relatedRemove.toSet().toMutableList()
        mutatedGenes?.removedDbActions?.addAll(set.map { it to ind.seeInitializingActions().indexOf(it) })
        ind.removeInitDbActions(set)
    }

    private fun getRelatedRemoveDbActions(ind: RestIndividual, remove : DbAction, relatedRemove: MutableList<DbAction>){
        val pks = remove.seeGenes().flatMap { it.flatView() }.filterIsInstance<SqlPrimaryKeyGene>()
        val index = ind.seeInitializingActions().indexOf(remove)
        if (index < ind.seeInitializingActions().size - 1 && pks.isNotEmpty()){
            val removeDbFKs = ind.seeInitializingActions().subList(index + 1, ind.seeInitializingActions().size).filter {
                it.seeGenes().flatMap { g-> g.flatView() }.filterIsInstance<SqlForeignKeyGene>()
                    .any {fk-> pks.any {pk->fk.uniqueIdOfPrimaryKey == pk.uniqueId} } }
            relatedRemove.addAll(removeDbFKs)
            removeDbFKs.forEach {
                getRelatedRemoveDbActions(ind, it, relatedRemove)
            }
        }
    }

    private fun doesApplyDependencyHeuristics() : Boolean{
        return dm.isDependencyNotEmpty()
                && randomness.nextBoolean(config.probOfEnablingResourceDependencyHeuristics)
    }

    /**
     * delete one or more resource call
     */
    private fun handleDelete(ind: RestIndividual, mutatedGenes: MutatedGeneSpecification?, evaluatedIndividual: EvaluatedIndividual<RestIndividual>?, targets: Set<Int>?, dohandleSize: Boolean){

        val candidates = if (doesApplyDependencyHeuristics()) dm.identifyDelNonDepResource(ind) else ind.getResourceCalls().filter(RestResourceCalls::isDeletable)

        val removedRes = if (config.enableAdaptiveResourceStructureMutation){
            adaptiveSelectResource(evaluatedIndividual, bySQL = false, candidates.map { it.getResourceKey() }.toSet().toList(), targets)
        }else{
            randomness.choose(candidates).getResourceKey()
        }

        val removedCandidates = candidates.filter { it.getResourceKey() == removedRes }
        val num = if (!dohandleSize) 1 else randomness.nextInt(1, max(1, min(rm.getMaxNumOfResourceSizeHandling(), min(removedCandidates.size, ind.getResourceCalls().size - 1))))
        val removes = randomness.choose(removedCandidates, num)

        removes.forEach { removed->
            val pos = ind.getResourceCalls().indexOf(removed)

            val removedActions = ind.getResourceCalls()[pos].seeActions(ALL)
            removedActions.forEach {
                mutatedGenes?.addRemovedOrAddedByAction(
                        it,
                        ind.seeActions(NO_INIT).indexOf(it),
                        true,
                        resourcePosition = pos
                )
            }
        }
        ind.removeResourceCall(removes)
    }

    /**
     * swap two resource calls
     */
    private fun handleSwap(ind: RestIndividual, mutatedGenes: MutatedGeneSpecification?){
        val candidates = ind.extractSwapCandidates()

        if (candidates.isEmpty()){
            throw IllegalStateException("the individual cannot apply swap mutator!")
        }

        val fromDependency = doesApplyDependencyHeuristics()

        if(fromDependency){
            val pair = dm.handleSwapDepResource(ind, candidates)
            if(pair!=null){
                mutatedGenes?.swapAction(pair.first, ind.getActionIndexes(NO_INIT, pair.first), ind.getActionIndexes(NO_INIT, pair.second))
                ind.swapResourceCall(pair.first, pair.second)
                return
            }
        }

        val randPair = randomizeSwapCandidates(candidates)
        val chosen = randPair.first
        val moveTo = randPair.second
        mutatedGenes?.swapAction(moveTo, ind.getActionIndexes(NO_INIT, chosen), ind.getActionIndexes(NO_INIT, moveTo))
        if(chosen < moveTo) ind.swapResourceCall(chosen, moveTo)
        else ind.swapResourceCall(moveTo, chosen)

    }

    private fun randomizeSwapCandidates(candidates: Map<Int, Set<Int>>): Pair<Int, Int>{
        return randomness.choose(candidates.keys).run {
            this to randomness.choose(candidates[this]!!)
        }
    }

    /**
     * add new resource call
     *
     * Note that if dependency is enabled,
     * the added resource can be its dependent resource with a probability i.e.,[config.probOfEnablingResourceDependencyHeuristics]
     */
    private fun handleAdd(ind: RestIndividual, mutatedGenes: MutatedGeneSpecification?, evaluatedIndividual: EvaluatedIndividual<RestIndividual>?, targets: Set<Int>?, dohandleSize: Boolean){
        val auth = ind.seeActions().map { it.auth }.run {
            if (isEmpty()) null
            else randomness.choose(this)
        }

        val sizeOfCalls = ind.getResourceCalls().size

        var max = config.maxTestSize
        ind.getResourceCalls().forEach { max -= it.seeActions(NO_SQL).size }
        if (max == 0){
            handleDelete(ind, mutatedGenes, evaluatedIndividual, targets, dohandleSize)
            return
        }

        if (dohandleSize){
            // only add existing resource, and there is no need to bind handling between resources
            val candnodes = ind.getResourceCalls().filter { it.node?.getTemplates()?.keys?.contains("POST") == true }.map { it.getResourceKey() }.toSet()
            if (candnodes.isNotEmpty()){
                val selected = if (config.enableAdaptiveResourceStructureMutation)
                    adaptiveSelectResource(evaluatedIndividual, bySQL = false, candnodes.toList(), targets)
                else randomness.choose(candnodes)

                val node = rm.getResourceNodeFromCluster(selected)
                val calls = node.sampleRestResourceCalls("POST", randomness, max)
                val num =  randomness.nextInt(1, max(1, min(rm.getMaxNumOfResourceSizeHandling(), (max*1.0/calls.seeActionSize(NO_INIT)).roundToInt())))
                (0 until num).forEach { pos->
                    if (max > 0){
                        val added = if (pos == 0) calls else node.sampleRestResourceCalls("POST", randomness, max)
                        maintainAuth(auth, added)
                        ind.addResourceCall( pos, added)

                        added.apply {
                            seeActions(ALL).forEach {
                                mutatedGenes?.addRemovedOrAddedByAction(
                                        it,
                                        ind.seeActions(NO_INIT).indexOf(it),
                                        false,
                                        resourcePosition = pos
                                )
                            }
                        }
                        max -= added.seeActionSize(NO_INIT)
                    }
                }
                return
            }
        }

        val fromDependency = doesApplyDependencyHeuristics()

        val pair = if(fromDependency){
                        dm.handleAddDepResource(ind, max)
                    }else null

        if(pair == null){
            val randomCall =  rm.handleAddResource(ind, max)
            val pos = randomness.nextInt(0, ind.getResourceCalls().size)

            maintainAuth(auth, randomCall)
            ind.addResourceCall(pos, randomCall)

            randomCall.seeActions(ALL).forEach {
                mutatedGenes?.addRemovedOrAddedByAction(
                    it,
                    ind.seeActions(NO_INIT).indexOf(it),
                    false,
                    resourcePosition = pos
                )
            }

        }else{
            var addPos : Int? = null
            if(pair.first != null){
                val pos = ind.getResourceCalls().indexOf(pair.first!!)
                pair.first!!.bindWithOtherRestResourceCalls(mutableListOf(pair.second), rm.cluster,true)
                addPos = randomness.nextInt(0, pos)
            }
            if (addPos == null) addPos = randomness.nextInt(0, ind.getResourceCalls().size)

            maintainAuth(auth, pair.second)
            ind.addResourceCall( addPos, pair.second)

            pair.second.apply {
                seeActions(ALL).forEach {
                    mutatedGenes?.addRemovedOrAddedByAction(
                        it,
                        ind.seeActions(NO_INIT).indexOf(it),
                        false,
                        resourcePosition = addPos
                    )
                }
            }
        }

        Lazy.assert { sizeOfCalls == ind.getResourceCalls().size - 1 }
    }

    /**
     * replace one of resource call with other resource
     */
    private fun handleReplace(ind: RestIndividual, mutatedGenes: MutatedGeneSpecification?){
        val auth = ind.seeActions().map { it.auth }.run {
            if (isEmpty()) null
            else randomness.choose(this)
        }

        var max = config.maxTestSize
        ind.getResourceCalls().forEach { max -= it.seeActionSize(NO_SQL) }

        val fromDependency = doesApplyDependencyHeuristics()

        val pos = if(fromDependency){
            dm.handleDelNonDepResource(ind).run {
                ind.getResourceCalls().indexOf(this)
            }
        }else{
            ind.getResourceCalls().indexOf(randomness.choose(ind.getResourceCalls().filter(RestResourceCalls::isDeletable)))
        }


        max += ind.getResourceCalls()[pos].seeActionSize(NO_SQL)

        val pair = if(fromDependency && pos != ind.getResourceCalls().size -1){
                        dm.handleAddDepResource(ind, max, if (pos == ind.getResourceCalls().size-1) mutableListOf() else ind.getResourceCalls().subList(pos+1, ind.getResourceCalls().size).toMutableList())
                    }else null

        var call = pair?.second
        if(pair == null){
            call =  rm.handleAddResource(ind, max)
        }else{
            if(pair.first != null){
                pair.first!!.bindWithOtherRestResourceCalls(mutableListOf(pair.second), rm.cluster,true)
            }
        }

       ind.getResourceCalls()[pos].seeActions(ALL).forEach {
           mutatedGenes?.addRemovedOrAddedByAction(
               it,
               ind.seeActions(NO_INIT).indexOf(it),
               true,
               resourcePosition = pos
           )
       }

        ind.removeResourceCall(pos)

        maintainAuth(auth, call!!)
        ind.addResourceCall(pos, call)

        call.seeActions(ALL).forEach {
            mutatedGenes?.addRemovedOrAddedByAction(
                it,
                ind.seeActions(NO_INIT).indexOf(it),
                false,
                resourcePosition = pos
            )
        }
    }

    /**
     *  modify one of resource call with other template
     */
    private fun handleModify(ind: RestIndividual, mutatedGenes: MutatedGeneSpecification?){
        val auth = ind.seeActions().map { it.auth }.run {
            if (isEmpty()) null
            else randomness.choose(this)
        }

        val pos = randomness.choose(ind.getResourceCalls().filter { it.isDeletable }.map { ind.getResourceCalls().indexOf(it) })

        val old = ind.getResourceCalls()[pos]
        var max = config.maxTestSize
        ind.getResourceCalls().forEach { max -= it.seeActionSize(NO_SQL)}
        max += ind.getResourceCalls()[pos].seeActionSize(NO_SQL)
        var new = old.getResourceNode().generateAnother(old, randomness, max)
        if(new == null){
            new = old.getResourceNode().sampleOneAction(null, randomness)
        }
        maintainAuth(auth, new)

        //record removed
        ind.getResourceCalls()[pos].seeActions(ALL).forEach {
            mutatedGenes?.addRemovedOrAddedByAction(
                it,
                ind.seeActions(NO_INIT).indexOf(it),
                true,
                resourcePosition = pos
            )
        }

        ind.replaceResourceCall(pos, new)

        //record replaced
        new.seeActions(ALL).forEach {
            mutatedGenes?.addRemovedOrAddedByAction(
                it,
                ind.seeActions(NO_INIT).indexOf(it),
                false,
                resourcePosition = pos
            )
        }
    }

    /**
     * for ResourceRestIndividual, dbaction(s) has been distributed to each resource call [ResourceRestCalls]
     */
    override fun addInitializingActions(individual: EvaluatedIndividual<*>, mutatedGenes: MutatedGeneSpecification?) {
        if (!config.shouldGenerateSqlData()) {
            return
        }

        val ind = individual.individual as? RestIndividual
            ?: throw IllegalArgumentException("Invalid individual type")

        val fw = individual.fitness.getViewOfAggregatedFailedWhere()
            //TODO likely to remove/change once we ll support VIEWs
            .filter { sampler.canInsertInto(it.key) }

        if (fw.isEmpty()) {
            return
        }

        val old = mutableListOf<Action>().plus(ind.seeInitializingActions())

        val addedInsertions = handleFailedWhereSQL(ind, fw, mutatedGenes, sampler)

        ind.repairInitializationActions(randomness)
        // update impact based on added genes
        if(mutatedGenes != null && config.isEnabledArchiveGeneSelection()){
            individual.updateImpactGeneDueToAddedInitializationGenes(
                mutatedGenes,
                old,
                addedInsertions
            )
        }
    }

    private fun maintainAuth(authInfo: AuthenticationInfo?, mutated: RestResourceCalls){
        authInfo?.let { auth->
            mutated.seeActions(NO_SQL).forEach { if(it is RestCallAction) it.auth = auth }
        }
    }

}