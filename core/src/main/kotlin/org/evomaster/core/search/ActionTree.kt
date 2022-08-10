package org.evomaster.core.search


/**
 * Tree/group of actions that are strongly related.
 * This group can have internal groups, ie internal [ActionTree].
 *
 * When executing a test case, such tree-structure groups need to be flattened
 */
abstract class ActionTree(
        children: MutableList<out ActionComponent>,
        groups : GroupsOfChildren<ActionComponent>? = null) : ActionComponent(children, groups){

    override fun flatten(): List<Action> {
        return children.flatMap { (it as ActionComponent).flatten()}
    }
}