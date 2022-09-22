/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.physicalplanning

import org.neo4j.cypher.internal
import org.neo4j.cypher.internal.ast.ProcedureResultItem
import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.expressions.CachedHasProperty
import org.neo4j.cypher.internal.expressions.CachedProperty
import org.neo4j.cypher.internal.expressions.Equals
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.PathExpression
import org.neo4j.cypher.internal.expressions.RelationshipChain
import org.neo4j.cypher.internal.ir.CreatePattern
import org.neo4j.cypher.internal.ir.HasHeaders
import org.neo4j.cypher.internal.ir.NoHeaders
import org.neo4j.cypher.internal.ir.ShortestPathPattern
import org.neo4j.cypher.internal.logical.plans.AbstractSelectOrSemiApply
import org.neo4j.cypher.internal.logical.plans.AbstractSemiApply
import org.neo4j.cypher.internal.logical.plans.Aggregation
import org.neo4j.cypher.internal.logical.plans.Anti
import org.neo4j.cypher.internal.logical.plans.AntiConditionalApply
import org.neo4j.cypher.internal.logical.plans.Apply
import org.neo4j.cypher.internal.logical.plans.ApplyPlan
import org.neo4j.cypher.internal.logical.plans.Argument
import org.neo4j.cypher.internal.logical.plans.ArgumentTracker
import org.neo4j.cypher.internal.logical.plans.AssertSameNode
import org.neo4j.cypher.internal.logical.plans.AssertingMultiNodeIndexSeek
import org.neo4j.cypher.internal.logical.plans.BFSPruningVarExpand
import org.neo4j.cypher.internal.logical.plans.CacheProperties
import org.neo4j.cypher.internal.logical.plans.CartesianProduct
import org.neo4j.cypher.internal.logical.plans.CommandLogicalPlan
import org.neo4j.cypher.internal.logical.plans.ConditionalApply
import org.neo4j.cypher.internal.logical.plans.Create
import org.neo4j.cypher.internal.logical.plans.DeleteExpression
import org.neo4j.cypher.internal.logical.plans.DeleteNode
import org.neo4j.cypher.internal.logical.plans.DeletePath
import org.neo4j.cypher.internal.logical.plans.DeleteRelationship
import org.neo4j.cypher.internal.logical.plans.DetachDeleteExpression
import org.neo4j.cypher.internal.logical.plans.DetachDeleteNode
import org.neo4j.cypher.internal.logical.plans.DetachDeletePath
import org.neo4j.cypher.internal.logical.plans.Eager
import org.neo4j.cypher.internal.logical.plans.EmptyResult
import org.neo4j.cypher.internal.logical.plans.ErrorPlan
import org.neo4j.cypher.internal.logical.plans.ExhaustiveLimit
import org.neo4j.cypher.internal.logical.plans.Expand
import org.neo4j.cypher.internal.logical.plans.ExpandAll
import org.neo4j.cypher.internal.logical.plans.ExpandInto
import org.neo4j.cypher.internal.logical.plans.FindShortestPaths
import org.neo4j.cypher.internal.logical.plans.Foreach
import org.neo4j.cypher.internal.logical.plans.ForeachApply
import org.neo4j.cypher.internal.logical.plans.InjectCompilationError
import org.neo4j.cypher.internal.logical.plans.Input
import org.neo4j.cypher.internal.logical.plans.LeftOuterHashJoin
import org.neo4j.cypher.internal.logical.plans.LetAntiSemiApply
import org.neo4j.cypher.internal.logical.plans.LetSelectOrAntiSemiApply
import org.neo4j.cypher.internal.logical.plans.LetSelectOrSemiApply
import org.neo4j.cypher.internal.logical.plans.LetSemiApply
import org.neo4j.cypher.internal.logical.plans.Limit
import org.neo4j.cypher.internal.logical.plans.LoadCSV
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.Merge
import org.neo4j.cypher.internal.logical.plans.MultiNodeIndexSeek
import org.neo4j.cypher.internal.logical.plans.NestedPlanCollectExpression
import org.neo4j.cypher.internal.logical.plans.NestedPlanExpression
import org.neo4j.cypher.internal.logical.plans.NodeCountFromCountStore
import org.neo4j.cypher.internal.logical.plans.NodeHashJoin
import org.neo4j.cypher.internal.logical.plans.NodeIndexLeafPlan
import org.neo4j.cypher.internal.logical.plans.NodeLogicalLeafPlan
import org.neo4j.cypher.internal.logical.plans.NonFuseable
import org.neo4j.cypher.internal.logical.plans.NonPipelined
import org.neo4j.cypher.internal.logical.plans.Optional
import org.neo4j.cypher.internal.logical.plans.OptionalExpand
import org.neo4j.cypher.internal.logical.plans.OrderedAggregation
import org.neo4j.cypher.internal.logical.plans.OrderedUnion
import org.neo4j.cypher.internal.logical.plans.PartialSort
import org.neo4j.cypher.internal.logical.plans.PartialTop
import org.neo4j.cypher.internal.logical.plans.PreserveOrder
import org.neo4j.cypher.internal.logical.plans.Prober
import org.neo4j.cypher.internal.logical.plans.ProcedureCall
import org.neo4j.cypher.internal.logical.plans.ProduceResult
import org.neo4j.cypher.internal.logical.plans.ProjectEndpoints
import org.neo4j.cypher.internal.logical.plans.ProjectingPlan
import org.neo4j.cypher.internal.logical.plans.PruningVarExpand
import org.neo4j.cypher.internal.logical.plans.RelationshipCountFromCountStore
import org.neo4j.cypher.internal.logical.plans.RelationshipIndexLeafPlan
import org.neo4j.cypher.internal.logical.plans.RelationshipLogicalLeafPlan
import org.neo4j.cypher.internal.logical.plans.RemoveLabels
import org.neo4j.cypher.internal.logical.plans.ResolvedCall
import org.neo4j.cypher.internal.logical.plans.RightOuterHashJoin
import org.neo4j.cypher.internal.logical.plans.RollUpApply
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.logical.plans.SetLabels
import org.neo4j.cypher.internal.logical.plans.SetNodeProperties
import org.neo4j.cypher.internal.logical.plans.SetNodePropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetNodeProperty
import org.neo4j.cypher.internal.logical.plans.SetProperties
import org.neo4j.cypher.internal.logical.plans.SetPropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetProperty
import org.neo4j.cypher.internal.logical.plans.SetRelationshipProperties
import org.neo4j.cypher.internal.logical.plans.SetRelationshipPropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetRelationshipProperty
import org.neo4j.cypher.internal.logical.plans.Skip
import org.neo4j.cypher.internal.logical.plans.Sort
import org.neo4j.cypher.internal.logical.plans.SubqueryForeach
import org.neo4j.cypher.internal.logical.plans.Top
import org.neo4j.cypher.internal.logical.plans.Top1WithTies
import org.neo4j.cypher.internal.logical.plans.Trail
import org.neo4j.cypher.internal.logical.plans.TransactionApply
import org.neo4j.cypher.internal.logical.plans.TransactionForeach
import org.neo4j.cypher.internal.logical.plans.TriadicBuild
import org.neo4j.cypher.internal.logical.plans.TriadicFilter
import org.neo4j.cypher.internal.logical.plans.TriadicSelection
import org.neo4j.cypher.internal.logical.plans.Union
import org.neo4j.cypher.internal.logical.plans.UnwindCollection
import org.neo4j.cypher.internal.logical.plans.ValueHashJoin
import org.neo4j.cypher.internal.logical.plans.VarExpand
import org.neo4j.cypher.internal.physicalplanning.PhysicalPlanningAttributes.ApplyPlans
import org.neo4j.cypher.internal.physicalplanning.PhysicalPlanningAttributes.ArgumentSizes
import org.neo4j.cypher.internal.physicalplanning.PhysicalPlanningAttributes.NestedPlanArgumentConfigurations
import org.neo4j.cypher.internal.physicalplanning.PhysicalPlanningAttributes.SlotConfigurations
import org.neo4j.cypher.internal.physicalplanning.SlotAllocation.LOAD_CSV_METADATA_KEY
import org.neo4j.cypher.internal.physicalplanning.SlotAllocation.NO_ARGUMENT
import org.neo4j.cypher.internal.physicalplanning.SlotAllocation.SlotMetaData
import org.neo4j.cypher.internal.physicalplanning.SlotAllocation.SlotsAndArgument
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.ApplyPlanSlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.CachedPropertySlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.MetaDataSlotKey
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.Size
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.SlotWithKeyAndAliases
import org.neo4j.cypher.internal.physicalplanning.SlotConfiguration.VariableSlotKey
import org.neo4j.cypher.internal.runtime.CypherRuntimeConfiguration
import org.neo4j.cypher.internal.runtime.expressionVariableAllocation.AvailableExpressionVariables
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.Foldable.SkipChildren
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.CTBoolean
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.symbols.CTList
import org.neo4j.cypher.internal.util.symbols.CTMap
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTPath
import org.neo4j.cypher.internal.util.symbols.CTRelationship
import org.neo4j.cypher.internal.util.symbols.ListType
import org.neo4j.exceptions.InternalException

import java.util

import scala.util.Try

/**
 * This object knows how to configure slots for a logical plan tree.
 *
 * The structure of the code is built this maybe weird way instead of being recursive to avoid the JVM execution stack
 * and instead handle the stacks manually here. Some queries we have seen are deep enough to crash the VM if not
 * configured carefully.
 *
 * The knowledge about how to actually allocate slots for each logical plan lives in the three `allocate` methods,
 * whereas the knowledge of how to traverse the plan tree is store in the while loops and stacks in the `populate`
 * method.
 **/
object SlotAllocation {

  /**
   * Case class containing information about the argument at a particular point during slot allocation.
   *
   * @param slotConfiguration the slot configuration of the argument. Might contain more slot than the argument.
   * @param argumentSize the prefix size of `slotConfiguration` that holds the argument.
   * @param argumentPlan the plan which introduced this argument
   */
  case class SlotsAndArgument(slotConfiguration: SlotConfiguration, argumentSize: Size, argumentPlan: Id)

  case class SlotMetaData(
    slotConfigurations: SlotConfigurations,
    argumentSizes: ArgumentSizes,
    applyPlans: ApplyPlans,
    nestedPlanArgumentConfigurations: NestedPlanArgumentConfigurations
  )

  private[physicalplanning] def NO_ARGUMENT(allocateArgumentSlots: Boolean): SlotsAndArgument = {
    val slots = SlotConfiguration.empty
    if (allocateArgumentSlots) {
      slots.newArgument(Id.INVALID_ID)
    }
    SlotsAndArgument(slots, Size.zero, Id.INVALID_ID)
  }

  final val INITIAL_SLOT_CONFIGURATION: SlotConfiguration = NO_ARGUMENT(true).slotConfiguration

  def allocateSlots(
    lp: LogicalPlan,
    semanticTable: SemanticTable,
    breakingPolicy: PipelineBreakingPolicy,
    availableExpressionVariables: AvailableExpressionVariables,
    config: CypherRuntimeConfiguration,
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator,
    allocateArgumentSlots: Boolean = false
  ): SlotMetaData =
    new SingleQuerySlotAllocator(
      allocateArgumentSlots,
      breakingPolicy,
      availableExpressionVariables,
      config,
      anonymousVariableNameGenerator
    ).allocateSlots(lp, semanticTable, None)

  final val LOAD_CSV_METADATA_KEY: String = "csv"
}

/**
 * Single shot slot allocator. Will break if used on two logical plans.
 */
//noinspection NameBooleanParameters,RedundantDefaultArgument
class SingleQuerySlotAllocator private[physicalplanning] (
  allocateArgumentSlots: Boolean,
  breakingPolicy: PipelineBreakingPolicy,
  availableExpressionVariables: AvailableExpressionVariables,
  config: CypherRuntimeConfiguration,
  anonymousVariableNameGenerator: AnonymousVariableNameGenerator,
  private val allocations: SlotConfigurations = new SlotConfigurations,
  private val argumentSizes: ArgumentSizes = new ArgumentSizes,
  private val applyPlans: ApplyPlans = new ApplyPlans,
  private val nestedPlanArgumentConfigurations: NestedPlanArgumentConfigurations = new NestedPlanArgumentConfigurations
) {

  /**
   * We need argument row id slots for cartesian product in the pipelines runtime
   */
  private def argumentRowIdSlotForCartesianProductNeeded(plan: LogicalPlan): Boolean =
    allocateArgumentSlots && plan.isInstanceOf[CartesianProduct]

  /**
   * Allocate slot for every operator in the logical plan tree `lp`.
   *
   * @param lp the logical plan to process.
   * @return the slot configurations of every operator.
   */
  def allocateSlots(
    lp: LogicalPlan,
    semanticTable: SemanticTable,
    initialSlotsAndArgument: Option[SlotsAndArgument]
  ): SlotMetaData = {

    val planStack = new util.ArrayDeque[(Boolean, LogicalPlan)]()
    val resultStack = new util.ArrayDeque[SlotConfiguration]()
    val argumentStack = new util.ArrayDeque[SlotsAndArgument]()
    initialSlotsAndArgument.foreach(argumentStack.push)
    var comingFrom = lp

    def recordArgument(plan: LogicalPlan, argument: SlotsAndArgument): Unit = {
      argumentSizes.set(plan.id, argument.argumentSize)
    }

    /**
     * Eagerly populate the stack using all the lhs children.
     */
    def populate(plan: LogicalPlan, nullIn: Boolean): Unit = {
      var nullable = nullIn
      var current = plan
      while (!current.isLeaf) {
        if (current.isInstanceOf[Optional]) {
          nullable = true
        }
        planStack.push((nullable, current))

        current = current.lhs.get // this should not fail unless we are on a leaf
      }
      comingFrom = current
      planStack.push((nullable, current))
    }

    populate(lp, nullIn = false)

    while (!planStack.isEmpty) {
      val (nullable, current) = planStack.pop()

      val outerApplyPlan = if (argumentStack.isEmpty) Id.INVALID_ID else argumentStack.getFirst.argumentPlan
      applyPlans.set(current.id, outerApplyPlan)

      (current.lhs, current.rhs) match {
        case (None, None) =>
          val argument =
            if (argumentStack.isEmpty) {
              NO_ARGUMENT(allocateArgumentSlots)
            } else {
              argumentStack.getFirst
            }
          recordArgument(current, argument)

          val slots = breakingPolicy.invoke(
            current,
            argument.slotConfiguration,
            argument.slotConfiguration,
            applyPlans(current.id)
          )

          allocateExpressionsOneChild(current, nullable, slots, semanticTable)
          allocateLeaf(current, nullable, slots)
          allocations.set(current.id, slots)
          resultStack.push(slots)

        case (Some(_), None) =>
          val sourceSlots = resultStack.pop()
          val argument =
            if (argumentStack.isEmpty) {
              NO_ARGUMENT(allocateArgumentSlots)
            } else {
              argumentStack.getFirst
            }
          allocateExpressionsOneChildOnInput(current, nullable, sourceSlots, semanticTable)

          val slots = breakingPolicy.invoke(current, sourceSlots, argument.slotConfiguration, applyPlans(current.id))
          allocateOneChild(current, nullable, sourceSlots, slots, recordArgument(_, argument), semanticTable)
          allocateExpressionsOneChildOnOutput(current, nullable, slots, semanticTable)
          allocations.set(current.id, slots)
          resultStack.push(slots)

        case (Some(left), Some(right)) if (comingFrom eq left) && current.isInstanceOf[ApplyPlan] =>
          planStack.push((nullable, current))
          val argumentSlots = resultStack.getFirst
          if (allocateArgumentSlots) {
            argumentSlots.newArgument(current.id)
          }
          allocateLhsOfApply(current, nullable, argumentSlots, semanticTable)
          val lhsSlots = allocations.get(left.id)
          allocateExpressionsTwoChild(current, lhsSlots, semanticTable, comingFromLeft = true)
          argumentStack.push(SlotsAndArgument(argumentSlots, argumentSlots.size(), current.id))
          populate(right, nullable)

        case (Some(left), Some(right)) if comingFrom eq left =>
          planStack.push((nullable, current))
          if (argumentRowIdSlotForCartesianProductNeeded(current)) {
            val previousArgument =
              if (argumentStack.isEmpty) NO_ARGUMENT(allocateArgumentSlots) else argumentStack.getFirst
            // We put a new argument on the argument stack, but in contrast to Apply, we create a copy of the previous argument, because
            // the RHS does not need any slots from the LHS.
            val newArgument = previousArgument.slotConfiguration.copy().newArgument(current.id)
            argumentStack.push(SlotsAndArgument(newArgument, newArgument.size(), current.id))
          }
          val lhsSlots = allocations.get(left.id)
          allocateExpressionsTwoChild(current, lhsSlots, semanticTable, comingFromLeft = true)

          populate(right, nullable)

        case (Some(_), Some(right)) if comingFrom eq right =>
          val rhsSlots = resultStack.pop()
          val lhsSlots = resultStack.pop()
          val argument =
            if (argumentStack.isEmpty) {
              NO_ARGUMENT(allocateArgumentSlots)
            } else {
              argumentStack.getFirst
            }
          // NOTE: If we introduce a two sourced logical plan with an expression that needs to be evaluated in a
          //       particular scope (lhs or rhs) we need to add handling of it to allocateExpressionsTwoChild.
          allocateExpressionsTwoChild(current, rhsSlots, semanticTable, comingFromLeft = false)

          val result = allocateTwoChild(current, nullable, lhsSlots, rhsSlots, recordArgument(_, argument), argument)
          allocations.set(current.id, result)
          if (current.isInstanceOf[ApplyPlan] || argumentRowIdSlotForCartesianProductNeeded(current)) {
            argumentStack.pop()
          }
          resultStack.push(result)
      }

      comingFrom = current
    }

    SlotMetaData(allocations, argumentSizes, applyPlans, nestedPlanArgumentConfigurations)
  }

  case class Accumulator(doNotTraverseExpression: Option[Expression])

  private def allocateExpressionsOneChildOnInput(
    plan: LogicalPlan,
    nullable: Boolean,
    slots: SlotConfiguration,
    semanticTable: SemanticTable
  ): Unit = plan match {
    case _: OptionalExpand =>
    case _                 => allocateExpressionsOneChild(plan, nullable, slots, semanticTable)
  }

  private def allocateExpressionsOneChildOnOutput(
    plan: LogicalPlan,
    nullable: Boolean,
    slots: SlotConfiguration,
    semanticTable: SemanticTable
  ): Unit = plan match {
    case _: OptionalExpand => allocateExpressionsOneChild(plan, nullable, slots, semanticTable)
    case _                 =>
  }

  private def allocateExpressionsOneChild(
    plan: LogicalPlan,
    nullable: Boolean,
    slots: SlotConfiguration,
    semanticTable: SemanticTable
  ): Unit = {

    plan.folder.treeFold[Accumulator](Accumulator(doNotTraverseExpression = None)) {
      case otherPlan: LogicalPlan if otherPlan.id != plan.id =>
        acc: Accumulator =>
          SkipChildren(acc) // Do not traverse the logical plan tree! We are only looking at the given lp

      case FindShortestPaths(_, shortestPathPattern, _, _, _) =>
        acc: Accumulator => {
          allocateShortestPathPattern(shortestPathPattern, slots, nullable, anonymousVariableNameGenerator)
          TraverseChildren(acc)
        }

      case ProjectEndpoints(_, _, start, startInScope, end, endInScope, _, _, _) =>
        acc: Accumulator => {
          if (!startInScope) {
            slots.newLong(start, nullable, CTNode)
          }
          if (!endInScope) {
            slots.newLong(end, nullable, CTNode)
          }
          TraverseChildren(acc)
        }

      case e: Expression =>
        allocateExpressionsInternal(e, slots, semanticTable, plan.id)
        acc: Accumulator =>
          SkipChildren(acc)
    }
  }

  private def allocateExpressionsTwoChild(
    plan: LogicalPlan,
    slots: SlotConfiguration,
    semanticTable: SemanticTable,
    comingFromLeft: Boolean
  ): Unit = {
    plan.folder.treeFold[Accumulator](Accumulator(doNotTraverseExpression = None)) {
      case otherPlan: LogicalPlan if otherPlan.id != plan.id =>
        acc: Accumulator =>
          SkipChildren(acc) // Do not traverse the logical plan tree! We are only looking at the given lp

      case ValueHashJoin(_, _, Equals(_, rhsExpression)) if comingFromLeft =>
        _: Accumulator =>
          TraverseChildren(Accumulator(doNotTraverseExpression = Some(rhsExpression))) // Only look at lhsExpression

      case ValueHashJoin(_, _, Equals(lhsExpression, _)) if !comingFromLeft =>
        _: Accumulator =>
          TraverseChildren(Accumulator(doNotTraverseExpression = Some(lhsExpression))) // Only look at rhsExpression

      // Only allocate expression on the LHS for these other two-child plans (which have expressions)
      case _: ApplyPlan if !comingFromLeft =>
        acc: Accumulator => SkipChildren(acc)

      case e: Expression =>
        acc: Accumulator =>
          allocateExpressionsInternal(e, slots, semanticTable, plan.id, acc)
          SkipChildren(acc)
    }
  }

  private def allocateExpressionsInternal(
    expression: Expression,
    slots: SlotConfiguration,
    semanticTable: SemanticTable,
    planId: Id,
    acc: Accumulator = Accumulator(doNotTraverseExpression = None)
  ): Unit = {
    expression.folder.treeFold[Accumulator](acc) {
      case otherPlan: LogicalPlan if otherPlan.id != planId =>
        acc: Accumulator =>
          SkipChildren(acc) // Do not traverse the logical plan tree! We are only looking at the given lp

      case e: NestedPlanExpression =>
        acc: Accumulator => {
          if (acc.doNotTraverseExpression.contains(e)) {
            SkipChildren(acc)
          } else {
            val argumentSlotConfiguration = slots.copy()
            availableExpressionVariables(e.plan.id).foreach { expVar =>
              argumentSlotConfiguration.newReference(expVar.name, nullable = true, CTAny)
            }
            nestedPlanArgumentConfigurations.set(e.plan.id, argumentSlotConfiguration)

            /*
             * TODO:
             *
             * The correct value of `argumentPlanId` is `applyPlans(planId)`
             * but in the current implementation that causes a failure in
             * Unchangable because we're not done writing that value if we're
             * evaluating the expression on an ApplyPlan, e.g. SelectOrSemiApply.
             *
             * Although not strictly correct, this workaround is currently
             * harmless. We always plan nested plan expressions with slotted
             * breaking policy and the apply plan id is currently only used
             * during slot allocation within PipelinedPipelineBreakingPolicy.
             *
             */
            val argumentPlanId = Id.INVALID_ID
            val slotsAndArgument =
              SlotsAndArgument(argumentSlotConfiguration.copy(), argumentSlotConfiguration.size(), argumentPlanId)

            // Allocate slots for nested plan
            // Pass in mutable attributes to be modified by recursive call
            // disable argument allocation since this will always be solved by slotted
            val nestedPhysicalPlan =
              withoutArgumentAllocationAndWithBreakingPolicy(breakingPolicy.nestedPlanBreakingPolicy).allocateSlots(
                e.plan,
                semanticTable,
                Some(slotsAndArgument)
              )

            // Allocate slots for the projection expression, based on the resulting slot configuration
            // from the inner plan
            val nestedSlots = nestedPhysicalPlan.slotConfigurations(e.plan.id)
            e match {
              case NestedPlanCollectExpression(_, projection, _) =>
                allocateExpressionsInternal(projection, nestedSlots, semanticTable, planId)
              case _ => // do nothing
            }

            // Since we did allocation for nested plan and projection explicitly we do not need to traverse into children
            // The inner slot configuration does not need to affect the accumulated result of the outer plan
            SkipChildren(acc)
          }
        }

      case e: Expression =>
        acc: Accumulator => {
          if (acc.doNotTraverseExpression.contains(e)) {
            SkipChildren(acc)
          } else {
            e match {
              case c: CachedProperty =>
                slots.newCachedProperty(c.runtimeKey)
              case c: CachedHasProperty =>
                slots.newCachedProperty(c.runtimeKey)
              case _ => // Do nothing
            }
            TraverseChildren(acc)
          }
        }
    }
  }

  private def withoutArgumentAllocationAndWithBreakingPolicy(newBreakingPolicy: PipelineBreakingPolicy) =
    new SingleQuerySlotAllocator(
      allocateArgumentSlots = false,
      newBreakingPolicy,
      availableExpressionVariables,
      config,
      anonymousVariableNameGenerator,
      allocations,
      argumentSizes,
      applyPlans,
      nestedPlanArgumentConfigurations
    )

  /**
   * Compute the slot configuration of a leaf logical plan operator `lp`.
   *
   * @param lp the operator to compute slots for.
   * @param nullable true if new slots are nullable
   * @param slots the slot configuration of lp
   */
  private def allocateLeaf(lp: LogicalPlan, nullable: Boolean, slots: SlotConfiguration): Unit =
    lp match {
      case MultiNodeIndexSeek(leafPlans) =>
        leafPlans.foreach { p =>
          allocateLeaf(p, nullable, slots)
          allocations.set(p.id, slots)
        }

      case AssertingMultiNodeIndexSeek(_, leafPlans) =>
        leafPlans.foreach { p =>
          allocateLeaf(p, nullable, slots)
          allocations.set(p.id, slots)
        }

      case leaf: NodeIndexLeafPlan =>
        slots.newLong(leaf.idName, nullable, CTNode)
        leaf.cachedProperties.foreach(cp => slots.newCachedProperty(cp.runtimeKey))

      case leaf: RelationshipIndexLeafPlan =>
        slots.newLong(leaf.idName, nullable, CTRelationship)
        slots.newLong(leaf.leftNode, nullable, CTNode)
        slots.newLong(leaf.rightNode, nullable, CTNode)
        leaf.cachedProperties.foreach(cp => slots.newCachedProperty(cp.runtimeKey))

      case leaf: NodeLogicalLeafPlan =>
        slots.newLong(leaf.idName, nullable, CTNode)

      case leaf: RelationshipLogicalLeafPlan =>
        slots.newLong(leaf.idName, nullable, CTRelationship)
        slots.newLong(leaf.leftNode, nullable, CTNode)
        slots.newLong(leaf.rightNode, nullable, CTNode)

      case _: Argument =>

      case leaf: NodeCountFromCountStore =>
        slots.newReference(leaf.idName, false, CTInteger)

      case leaf: RelationshipCountFromCountStore =>
        slots.newReference(leaf.idName, false, CTInteger)

      case leaf: CommandLogicalPlan =>
        for (v <- leaf.availableSymbols ++ leaf.defaultColumns.map(_.name))
          slots.newReference(v, false, CTAny)

      case Input(nodes, relationships, variables, nullableInput) =>
        for (v <- nodes)
          slots.newLong(v, nullableInput, CTNode)
        for (v <- relationships)
          slots.newLong(v, nullableInput, CTRelationship)
        for (v <- variables)
          slots.newReference(v, nullableInput, CTAny)

      case p => throw new SlotAllocationFailed(s"Don't know how to handle $p")
    }

  /**
   * Compute the slot configuration of a single source logical plan operator `lp`.
   *
   * @param lp the operator to compute slots for.
   * @param nullable true if new slots are nullable
   * @param source the slot configuration of the source operator.
   * @param slots the slot configuration of lp.
   * @param recordArgument function which records the argument size for the given operator
   */
  private def allocateOneChild(
    lp: LogicalPlan,
    nullable: Boolean,
    source: SlotConfiguration,
    slots: SlotConfiguration,
    recordArgument: LogicalPlan => Unit,
    semanticTable: SemanticTable
  ): Unit =
    lp match {

      case Aggregation(_, groupingExpressions, aggregationExpressions) =>
        addGroupingSlots(groupingExpressions, source, slots)
        aggregationExpressions foreach {
          case (key, _) =>
            slots.newReference(key, nullable = true, CTAny)
        }

      case OrderedAggregation(_, groupingExpressions, aggregationExpressions, _) =>
        addGroupingSlots(groupingExpressions, source, slots)
        aggregationExpressions foreach {
          case (key, _) =>
            slots.newReference(key, nullable = true, CTAny)
        }
        recordArgument(lp)

      case Expand(_, from, _, _, to, relName, ExpandAll) =>
        slots.newLong(relName, nullable, CTRelationship)
        slots.newLong(to, nullable, CTNode)

      case Expand(_, _, _, _, _, relName, ExpandInto) =>
        slots.newLong(relName, nullable, CTRelationship)

      case Optional(_, _) =>
        recordArgument(lp)

      case Anti(_) =>
        recordArgument(lp)

      case _: ProduceResult |
        _: Selection |
        _: Limit |
        _: ExhaustiveLimit |
        _: Skip |
        _: Sort |
        _: PartialSort |
        _: Top |
        _: Top1WithTies |
        _: PartialTop |
        _: CacheProperties |
        _: NonFuseable |
        _: InjectCompilationError |
        _: NonPipelined |
        _: Prober |
        _: TriadicBuild |
        _: TriadicFilter |
        _: PreserveOrder |
        _: EmptyResult |
        _: ArgumentTracker =>

      case p: ProjectingPlan =>
        p.projectExpressions foreach {
          case (key, internal.expressions.Variable(ident)) if key == ident =>
          // it's already there. no need to add a new slot for it

          case (newKey, internal.expressions.Variable(ident)) if newKey != ident =>
            slots.addAlias(newKey, ident)

          case (key, _: PathExpression) =>
            slots.newReference(key, nullable = true, CTPath)

          case (key, _) =>
            slots.newReference(key, nullable = true, CTAny)
        }

      case OptionalExpand(_, _, _, _, to, rel, ExpandAll, _) =>
        // Note that OptionalExpand only is optional on the expand and not on incoming rows, so
        // we do not need to record the argument here.
        slots.newLong(rel, nullable = true, CTRelationship)
        slots.newLong(to, nullable = true, CTNode)

      case OptionalExpand(_, _, _, _, _, rel, ExpandInto, _) =>
        // Note that OptionalExpand only is optional on the expand and not on incoming rows, so
        // we do not need to record the argument here.
        slots.newLong(rel, nullable = true, CTRelationship)

      case VarExpand(_, _, _, _, _, to, relationship, _, expansionMode, _, _) =>
        if (expansionMode == ExpandAll) {
          slots.newLong(to, nullable, CTNode)
        }
        slots.newReference(relationship, nullable, CTList(CTRelationship))

      case PruningVarExpand(_, _, _, _, to, _, _, _, _) =>
        slots.newLong(to, nullable, CTNode)

      case expand: BFSPruningVarExpand =>
        slots.newLong(expand.to, nullable, CTNode)

      case Create(_, nodes, relationships) =>
        nodes.foreach(n => slots.newLong(n.idName, nullable = false, CTNode))
        relationships.foreach(r => slots.newLong(r.idName, nullable = config.lenientCreateRelationship, CTRelationship))

      case _: EmptyResult |
        _: ErrorPlan |
        _: Eager =>

      case UnwindCollection(_, variable, _) =>
        slots.newReference(variable, nullable = true, CTAny)

      case _: DeleteNode |
        _: DeleteRelationship |
        _: DeletePath |
        _: DeleteExpression |
        _: DetachDeleteNode |
        _: DetachDeletePath |
        _: DetachDeleteExpression =>

      case _: SetLabels |
        _: SetNodeProperty |
        _: SetNodeProperties |
        _: SetNodePropertiesFromMap |
        _: SetRelationshipProperty |
        _: SetRelationshipProperties |
        _: SetRelationshipPropertiesFromMap |
        _: SetProperties |
        _: SetProperty |
        _: SetPropertiesFromMap |
        _: RemoveLabels =>

      case ProjectEndpoints(_, _, start, startInScope, end, endInScope, _, _, _) =>
      // Because of the way the interpreted pipe works, we already have to do the necessary allocations in allocateExpressions(), before the pipeline breaking.
      // Legacy interpreted pipes write directly to the incoming context, so to support pipeline breaking, the slots have to be allocated
      // on the source slot configuration.

      case LoadCSV(_, _, variableName, NoHeaders, _, _, _) =>
        slots.newReference(variableName, nullable, CTList(CTAny))
        slots.newMetaData(LOAD_CSV_METADATA_KEY)

      case LoadCSV(_, _, variableName, HasHeaders, _, _, _) =>
        slots.newReference(variableName, nullable, CTMap)
        slots.newMetaData(LOAD_CSV_METADATA_KEY)

      case ProcedureCall(_, ResolvedCall(_, _, callResults, _, _, _)) =>
        callResults.foreach {
          case ProcedureResultItem(_, variable) =>
            slots.newReference(variable.name, true, CTAny)
        }

      case _: Merge =>
        recordArgument(lp)

      case _: FindShortestPaths =>
      // Because of the way the interpreted pipe works, we already have to do the necessary allocations in allocateExpressions(), before the pipeline breaking.
      // Legacy interpreted pipes write directly to the incoming context, so to support pipeline breaking, the slots have to be allocated
      // on the source slot configuration.

      case Foreach(_, variableName, listExpression, mutations) =>
        mutations.foreach {
          case CreatePattern(nodes, relationships) =>
            nodes.foreach(n => slots.newLong(n.idName, false, CTNode))
            relationships.foreach(r => slots.newLong(r.idName, false, CTRelationship))
          case _ =>
        }
        val maybeTypeSpec = Try(semanticTable.getActualTypeFor(listExpression)).toOption
        val listOfNodes = maybeTypeSpec.exists(_.contains(ListType(CTNode)))
        val listOfRels = maybeTypeSpec.exists(_.contains(ListType(CTRelationship)))

        (listOfNodes, listOfRels) match {
          case (true, false) => slots.newLong(variableName, true, CTNode)
          case (false, true) => slots.newLong(variableName, true, CTRelationship)
          case _             => slots.newReference(variableName, true, CTAny)
        }

      case p =>
        throw new SlotAllocationFailed(s"Don't know how to handle $p")
    }

  /**
   * Compute the slot configuration of a branching logical plan operator `lp`.
   *
   * @param lp the operator to compute slots for.
   * @param nullable true if new slots are nullable
   * @param lhs the slot configuration of the left hand side operator.
   * @param rhs the slot configuration of the right hand side operator.
   * @return the slot configuration of lp
   */
  private def allocateTwoChild(
    lp: LogicalPlan,
    nullable: Boolean,
    lhs: SlotConfiguration,
    rhs: SlotConfiguration,
    recordArgument: LogicalPlan => Unit,
    argument: SlotsAndArgument
  ): SlotConfiguration =
    lp match {
      case _: Apply =>
        rhs

      case _: TriadicSelection =>
        // TriadicSelection is essentially a special Apply which performs filtering.
        // All the slots are allocated by it's left and right children
        rhs

      case _: AbstractSemiApply =>
        lhs

      case _: AntiConditionalApply |
        _: ConditionalApply |
        _: AbstractSelectOrSemiApply =>
        // A new pipeline is not strictly needed here unless we have batching/vectorization
        recordArgument(lp)
        val result = breakingPolicy.invoke(lp, rhs, argument.slotConfiguration, applyPlans(lp.id))
        rhs.foreachSlotAndAliases {
          case SlotWithKeyAndAliases(VariableSlotKey(key), slot, _) if slot.offset >= lhs.numberOfLongs =>
            result.add(key, slot.asNullable)
          case _ => // do nothing
        }
        result

      case LetSemiApply(_, _, name) =>
        lhs.newReference(name, false, CTBoolean)
        lhs

      case LetAntiSemiApply(_, _, name) =>
        lhs.newReference(name, false, CTBoolean)
        lhs

      case LetSelectOrSemiApply(_, _, name, _) =>
        lhs.newReference(name, false, CTBoolean)
        lhs

      case LetSelectOrAntiSemiApply(_, _, name, _) =>
        lhs.newReference(name, false, CTBoolean)
        lhs

      case _: CartesianProduct =>
        // A new pipeline is not strictly needed here unless we have batching/vectorization
        recordArgument(lp)
        val result = breakingPolicy.invoke(lp, lhs, argument.slotConfiguration, applyPlans(lp.id))
        // For the implementation of the slotted pipe to use array copy
        // it is very important that we add the slots in the same order
        rhs.addAllSlotsInOrderTo(result, argument.argumentSize)
        result

      case RightOuterHashJoin(nodes, _, _) =>
        // A new pipeline is not strictly needed here unless we have batching/vectorization
        recordArgument(lp)
        val result = breakingPolicy.invoke(lp, rhs, argument.slotConfiguration, applyPlans(lp.id))

        lhs.foreachSlotAndAliasesOrdered {
          case SlotWithKeyAndAliases(VariableSlotKey(key), slot, aliases) =>
            // If the column is one of the join columns there is no need to add it again
            if (!nodes(key)) {
              result.add(key, slot.asNullable)
            }
            aliases.foreach(alias => result.addAlias(alias, key))

          case SlotWithKeyAndAliases(CachedPropertySlotKey(key), _, _) =>
            result.newCachedProperty(key)

          case SlotWithKeyAndAliases(MetaDataSlotKey(key), _, _) =>
            result.newMetaData(key)

          case SlotWithKeyAndAliases(_: ApplyPlanSlotKey, _, _) =>
          // apply plan slots are already in the argument, and don't have to be added here
        }
        result

      case LeftOuterHashJoin(nodes, _, _) =>
        // A new pipeline is not strictly needed here unless we have batching/vectorization
        recordArgument(lp)
        val result = breakingPolicy.invoke(lp, lhs, argument.slotConfiguration, applyPlans(lp.id))

        rhs.foreachSlotAndAliasesOrdered {
          case SlotWithKeyAndAliases(VariableSlotKey(key), slot, aliases) =>
            // If the column is one of the join columns there is no need to add it again
            if (!nodes(key)) {
              result.add(key, slot.asNullable)
            }
            aliases.foreach(alias => result.addAlias(alias, key))

          case SlotWithKeyAndAliases(CachedPropertySlotKey(key), _, _) =>
            result.newCachedProperty(key)

          case SlotWithKeyAndAliases(MetaDataSlotKey(key), _, _) =>
            result.newMetaData(key)

          case SlotWithKeyAndAliases(_: ApplyPlanSlotKey, _, _) =>
          // apply plan slots are already in the argument, and don't have to be added here
        }
        result

      case NodeHashJoin(nodes, _, _) =>
        // A new pipeline is not strictly needed here unless we have batching/vectorization
        recordArgument(lp)
        val result = breakingPolicy.invoke(lp, lhs, argument.slotConfiguration, applyPlans(lp.id))

        rhs.foreachSlotAndAliasesOrdered {
          case SlotWithKeyAndAliases(VariableSlotKey(key), slot, aliases) =>
            // If the column is one of the join columns there is no need to add it again
            if (!nodes(key)) {
              result.add(key, slot)
            }
            aliases.foreach(alias => result.addAlias(alias, key))

          case SlotWithKeyAndAliases(CachedPropertySlotKey(key), _, _) =>
            result.newCachedProperty(key)

          case SlotWithKeyAndAliases(MetaDataSlotKey(key), _, _) =>
            result.newMetaData(key)

          case SlotWithKeyAndAliases(_: ApplyPlanSlotKey, _, _) =>
          // apply plan slots are already in the argument, and don't have to be added here
        }
        result

      case _: ValueHashJoin =>
        // A new pipeline is not strictly needed here unless we have batching/vectorization
        recordArgument(lp)
        val result = breakingPolicy.invoke(lp, lhs, argument.slotConfiguration, applyPlans(lp.id))
        // For the implementation of the slotted pipe to use array copy
        // it is very important that we add the slots in the same order
        rhs.foreachSlotAndAliasesOrdered(
          {
            case SlotWithKeyAndAliases(VariableSlotKey(key), slot, aliases) =>
              result.add(key, slot)
              aliases.foreach(alias => result.addAlias(alias, key))
            case SlotWithKeyAndAliases(CachedPropertySlotKey(key), _, _) =>
              result.newCachedProperty(key, shouldDuplicate = true)
            case SlotWithKeyAndAliases(MetaDataSlotKey(key), _, _) =>
              result.newMetaData(key)
            case SlotWithKeyAndAliases(_: ApplyPlanSlotKey, _, _) =>
            // apply plan slots are already in the argument, and don't have to be added here
          },
          skipFirst = argument.argumentSize
        )
        result

      case RollUpApply(_, _, collectionName, _) =>
        lhs.newReference(collectionName, nullable, CTList(CTAny))
        lhs

      case _: ForeachApply =>
        lhs

      case _: Union |
        _: OrderedUnion =>
        // The result slot configuration should only contain the variables we join on.
        // If both lhs and rhs has a long slot with the same type the result should
        // also use a long slot, otherwise we use a ref slot.
        val result = SlotConfiguration.empty
        def addVariableToResult(key: String, slot: Slot): Unit = slot match {
          case lhsSlot: LongSlot =>
            // find all shared variables and look for other long slots with same type
            rhs.get(key).foreach {
              case LongSlot(_, rhsNullable, typ) if typ == lhsSlot.typ =>
                result.newLong(key, lhsSlot.nullable || rhsNullable, typ)
              case rhsSlot =>
                val newType = if (lhsSlot.typ == rhsSlot.typ) lhsSlot.typ else CTAny
                result.newReference(key, lhsSlot.nullable || rhsSlot.nullable, newType)
            }
          case lhsSlot =>
            // We know lhs uses a ref slot so just look for shared variables.
            rhs.get(key).foreach {
              rhsSlot =>
                val newType = if (lhsSlot.typ == rhsSlot.typ) lhsSlot.typ else CTAny
                result.newReference(key, lhsSlot.nullable || rhsSlot.nullable, newType)
            }
        }

        // First, add original variable names, cached properties and apply plan slots in order
        lhs.foreachSlotAndAliasesOrdered({
          case SlotWithKeyAndAliases(VariableSlotKey(key), slot, _) => addVariableToResult(key, slot)
          case SlotWithKeyAndAliases(CachedPropertySlotKey(key), _, _) =>
            if (rhs.hasCachedPropertySlot(key)) {
              result.newCachedProperty(key)
            }
          case SlotWithKeyAndAliases(MetaDataSlotKey(key), _, _) =>
            if (rhs.hasMetaDataSlot(key)) {
              result.newMetaData(key)
            }
          case SlotWithKeyAndAliases(ApplyPlanSlotKey(id), _, _) =>
            // apply plan slots need to be copied if both sides have them,
            // i.e. if the union sits _under_ the apply with this id
            if (rhs.hasArgumentSlot(id)) {
              result.newArgument(id)
            }
        })

        // Second, add aliases in order. Aliases get their own slots after a union.
        lhs.foreachSlotAndAliasesOrdered({
          case SlotWithKeyAndAliases(VariableSlotKey(key), slot, aliases) =>
            slot match {
              case LongSlot(offset, _, _) if offset >= argument.argumentSize.nLongs =>
                aliases.foreach(addVariableToResult(_, slot))
              case RefSlot(offset, _, _) if offset >= argument.argumentSize.nReferences =>
                aliases.foreach(addVariableToResult(_, slot))
              case _ =>
                // Argument slot
                aliases.foreach(alias =>
                  if (rhs.get(alias).contains(slot)) {
                    result.addAlias(alias, key)
                  } else {
                    addVariableToResult(alias, slot)
                  }
                )
            }
          case _ =>
        })
        result

      case _: AssertSameNode =>
        lhs

      case _: SubqueryForeach =>
        lhs

      case _: TransactionForeach =>
        lhs

      case _: TransactionApply =>
        rhs

      case Trail(_, _, _, _, end, _, _, groupNodes, groupRelationships, _, _, _) =>
        recordArgument(lp)
        val result = breakingPolicy.invoke(lp, lhs, argument.slotConfiguration, applyPlans(lp.id))
        result.newLong(end, nullable, CTNode)
        groupNodes.foreach(n => result.newReference(n.groupName, false, CTList(CTNode)))
        groupRelationships.foreach(r => result.newReference(r.groupName, false, CTList(CTRelationship)))
        result

      case p =>
        throw new SlotAllocationFailed(s"Don't know how to handle $p")
    }

  private def allocateLhsOfApply(
    plan: LogicalPlan,
    nullable: Boolean,
    lhs: SlotConfiguration,
    semanticTable: SemanticTable
  ): Unit =
    plan match {
      case ForeachApply(_, _, variableName, listExpression) =>
        // The slot for the iteration variable of foreach needs to be available as an argument on the rhs of the apply
        // so we allocate it on the lhs (even though its value will not be needed after the foreach is done)
        val maybeTypeSpec = Try(semanticTable.getActualTypeFor(listExpression)).toOption
        val listOfNodes = maybeTypeSpec.exists(_.contains(ListType(CTNode)))
        val listOfRels = maybeTypeSpec.exists(_.contains(ListType(CTRelationship)))

        (listOfNodes, listOfRels) match {
          case (true, false) => lhs.newLong(variableName, true, CTNode)
          case (false, true) => lhs.newLong(variableName, true, CTRelationship)
          case _             => lhs.newReference(variableName, true, CTAny)
        }

      case Trail(_, _, _, _, _, innerStart, _, _, _, _, _, _) =>
        // The slot for the per-repetition inner node variable of Trail needs to be available as an argument on the RHS of the Trail
        // so we allocate it on the LHS (even though its value will not be needed after the Trail is done).
        lhs.newLong(innerStart, false, CTNode) // nullable?

      case _ =>
    }

  private def addGroupingSlots(
    groupingExpressions: Map[String, Expression],
    incoming: SlotConfiguration,
    outgoing: SlotConfiguration
  ): Unit = {
    groupingExpressions foreach {
      case (key, internal.expressions.Variable(ident)) =>
        val slotInfo = incoming(ident)
        slotInfo.typ match {
          case CTNode | CTRelationship =>
            outgoing.newLong(key, slotInfo.nullable, slotInfo.typ)
          case _ =>
            outgoing.newReference(key, slotInfo.nullable, slotInfo.typ)
        }
      case (key, _) =>
        outgoing.newReference(key, nullable = true, CTAny)
    }
  }

  private def allocateShortestPathPattern(
    shortestPathPattern: ShortestPathPattern,
    slots: SlotConfiguration,
    nullable: Boolean,
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator
  ) = {
    val maybePathName = shortestPathPattern.name
    val part = shortestPathPattern.expr
    val pathName = maybePathName.getOrElse(anonymousVariableNameGenerator.nextName)
    val rel = part.element match {
      case RelationshipChain(_, relationshipPattern, _) =>
        relationshipPattern
      case _ =>
        throw new IllegalStateException("This should be caught during semantic checking")
    }
    val relIteratorName = rel.variable.map(_.name)

    // Allocate slots
    slots.newReference(pathName, nullable, CTPath)
    if (relIteratorName.isDefined) {
      slots.newReference(relIteratorName.get, nullable, CTList(CTRelationship))
    }
  }
}

class SlotAllocationFailed(str: String) extends InternalException(str)