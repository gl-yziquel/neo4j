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
package org.neo4j.cypher.internal.logical.plans

import org.neo4j.common.EntityType
import org.neo4j.cypher.internal.expressions.CachedProperty
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LabelToken
import org.neo4j.cypher.internal.expressions.PropertyKeyToken
import org.neo4j.cypher.internal.expressions.RelationshipTypeToken
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.logical.plans.LogicalPlan.VERBOSE_TO_STRING
import org.neo4j.cypher.internal.util.Foldable
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.Rewritable
import org.neo4j.cypher.internal.util.Rewritable.IteratorEq
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.cypher.internal.util.attribution.IdGen
import org.neo4j.cypher.internal.util.attribution.Identifiable
import org.neo4j.cypher.internal.util.attribution.SameId
import org.neo4j.exceptions.InternalException
import org.neo4j.graphdb.schema.IndexType

import java.lang.reflect.Method
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.hashing.MurmurHash3

object LogicalPlan {
  val LOWEST_TX_LAYER = 0
  val VERBOSE_TO_STRING = false
}

/*
A LogicalPlan is an algebraic query, which is represented by a query tree whose leaves are database relations and
non-leaf nodes are algebraic operators like selections, projections, and joins. An intermediate node indicates the
application of the corresponding operator on the relations generated by its children, the result of which is then sent
further up. Thus, the edges of a tree represent data flow from bottom to top, i.e., from the leaves, which correspond
to data in the database, to the root, which is the final operator producing the query answer. */
abstract class LogicalPlan(idGen: IdGen)
    extends Product
    with Foldable
    with Rewritable
    with Identifiable {

  self =>

  def lhs: Option[LogicalPlan]
  def rhs: Option[LogicalPlan]
  def availableSymbols: Set[String]

  override val id: Id = idGen.id()

  override val hashCode: Int = MurmurHash3.productHash(self)

  override def equals(obj: scala.Any): Boolean = {
    if (!obj.isInstanceOf[LogicalPlan]) false
    else {
      val otherPlan = obj.asInstanceOf[LogicalPlan]
      if (this.eq(otherPlan)) return true
      if (this.getClass != otherPlan.getClass) return false
      val stack = new mutable.ArrayStack[(Iterator[Any], Iterator[Any])]()
      var p1 = this.productIterator
      var p2 = otherPlan.productIterator
      while (p1.hasNext && p2.hasNext) {
        val continue =
          (p1.next, p2.next) match {
            case (lp1: LogicalPlan, lp2: LogicalPlan) =>
              if (lp1.getClass != lp2.getClass) {
                false
              } else {
                stack.push((p1, p2))
                p1 = lp1.productIterator
                p2 = lp2.productIterator
                true
              }
            case (_: LogicalPlan, _) => false
            case (_, _: LogicalPlan) => false
            case (a1, a2)            => a1 == a2
          }

        if (!continue) return false
        while (!p1.hasNext && !p2.hasNext && stack.nonEmpty) {
          val (p1New, p2New) = stack.pop
          p1 = p1New
          p2 = p2New
        }
      }
      p1.isEmpty && p2.isEmpty
    }
  }

  def leaves: Seq[LogicalPlan] = this.folder.treeFold(Seq.empty[LogicalPlan]) {
    case plan: LogicalPlan if plan.lhs.isEmpty && plan.rhs.isEmpty => acc => TraverseChildren(acc :+ plan)
  }

  @tailrec
  final def leftmostLeaf: LogicalPlan = lhs match {
    case Some(plan) => plan.leftmostLeaf
    case None       => this
  }

  def copyPlanWithIdGen(idGen: IdGen): LogicalPlan = {
    try {
      val arguments = this.treeChildren.toList :+ idGen
      copyConstructor.invoke(this, arguments: _*).asInstanceOf[this.type]
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("wrong number of arguments") =>
        throw new InternalException(
          "Logical plans need to be case classes, and have the IdGen in a separate constructor",
          e
        )
    }
  }

  lazy val copyConstructor: Method = this.getClass.getMethods.find(_.getName == "copy").get

  def dup(children: Seq[AnyRef]): this.type =
    if (children.iterator eqElements this.treeChildren) {
      this
    } else {
      val constructor = this.copyConstructor
      val params = constructor.getParameterTypes
      val args = children.toIndexedSeq
      val resultingPlan =
        if (
          params.length == args.length + 1
          && params.last.isAssignableFrom(classOf[IdGen])
        )
          constructor.invoke(this, args :+ SameId(this.id): _*).asInstanceOf[this.type]
        else if (
          (params.length == args.length + 2)
          && params(params.length - 2).isAssignableFrom(classOf[SinglePlannerQuery])
          && params(params.length - 1).isAssignableFrom(classOf[IdGen])
        )
          constructor.invoke(this, args :+ SameId(this.id): _*).asInstanceOf[this.type]
        else
          constructor.invoke(this, args: _*).asInstanceOf[this.type]
      resultingPlan
    }

  def isLeaf: Boolean = lhs.isEmpty && rhs.isEmpty

  override def toString: String = {
    if (VERBOSE_TO_STRING) {
      verboseToString
    } else {
      LogicalPlanToPlanBuilderString(this)
    }
  }

  def verboseToString: String = {
    def planRepresentation(plan: LogicalPlan): String = {
      val children = plan.lhs.toIndexedSeq ++ plan.rhs.toIndexedSeq
      val nonChildFields = plan.productIterator.filterNot(children.contains).mkString(", ")
      val prodPrefix = plan.productPrefix
      s"$prodPrefix($nonChildFields)"
    }

    LogicalPlanTreeRenderer.render(this, "| ", planRepresentation)
  }

  def satisfiesExpressionDependencies(e: Expression): Boolean =
    e.dependencies.map(_.name).forall(availableSymbols.contains)

  def debugId: String = f"0x$hashCode%08x"

  def flatten: Seq[LogicalPlan] = Flattener.create(this)

  def indexUsage(): Seq[IndexUsage] = {
    this.folder.fold(Seq.empty[IndexUsage]) {
      case MultiNodeIndexSeek(indexPlans) =>
        acc => acc ++ indexPlans.flatMap(_.indexUsage())
      case NodeByLabelScan(idName, _, _, _) =>
        acc => acc :+ SchemaIndexLookupUsage(idName, EntityType.NODE)
      case DirectedRelationshipTypeScan(idName, _, _, _, _, _) =>
        acc => acc :+ SchemaIndexLookupUsage(idName, EntityType.RELATIONSHIP)
      case UndirectedRelationshipTypeScan(idName, _, _, _, _, _) =>
        acc => acc :+ SchemaIndexLookupUsage(idName, EntityType.RELATIONSHIP)
      case relIndexScan: RelationshipIndexLeafPlan =>
        acc =>
          acc :+
            SchemaRelationshipIndexUsage(
              relIndexScan.idName,
              relIndexScan.typeToken.nameId.id,
              relIndexScan.typeToken.name,
              relIndexScan.properties.map(_.propertyKeyToken)
            )
      case nodeIndexPlan: NodeIndexLeafPlan =>
        acc =>
          acc :+
            SchemaLabelIndexUsage(
              nodeIndexPlan.idName,
              nodeIndexPlan.label.nameId.id,
              nodeIndexPlan.label.name,
              nodeIndexPlan.properties.map(_.propertyKeyToken)
            )
    }
  }
}

// Marker interface for all plans that aggregate inputs.
trait AggregatingPlan extends LogicalPlan {
  def groupingExpressions: Map[String, Expression]
  def aggregationExpressions: Map[String, Expression]
}

// Marker interface for all plans that performs updates
trait UpdatingPlan extends LogicalUnaryPlan {
  override def withLhs(source: LogicalPlan)(idGen: IdGen): UpdatingPlan
}

// Marker trait for relationship type scans
trait RelationshipTypeScan {
  def idName: String
}

abstract class LogicalBinaryPlan(idGen: IdGen) extends LogicalPlan(idGen) {
  final lazy val hasUpdatingRhs: Boolean = right.folder.treeExists { case _: UpdatingPlan => true }
  final def lhs: Option[LogicalPlan] = Some(left)
  final def rhs: Option[LogicalPlan] = Some(right)

  def left: LogicalPlan
  def right: LogicalPlan

  /**
   * A copy of this plan with a new LHS
   */
  def withLhs(newLHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan

  /**
   * A copy of this plan with a new LHS
   */
  def withRhs(newRHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan
}

abstract class LogicalUnaryPlan(idGen: IdGen) extends LogicalPlan(idGen) {
  final def lhs: Option[LogicalPlan] = Some(source)
  final def rhs: Option[LogicalPlan] = None

  def source: LogicalPlan

  /**
   * A copy of this plan with a new LHS
   */
  def withLhs(newLHS: LogicalPlan)(idGen: IdGen): LogicalUnaryPlan
}

abstract class LogicalLeafPlan(idGen: IdGen) extends LogicalPlan(idGen) {
  final def lhs: Option[LogicalPlan] = None
  final def rhs: Option[LogicalPlan] = None
  def argumentIds: Set[String]

  def usedVariables: Set[String]

  def withoutArgumentIds(argsToExclude: Set[String]): LogicalLeafPlan
}

abstract class NodeLogicalLeafPlan(idGen: IdGen) extends LogicalLeafPlan(idGen) {
  def idName: String
}

abstract class RelationshipLogicalLeafPlan(idGen: IdGen) extends LogicalLeafPlan(idGen) {
  def idName: String
  def leftNode: String
  def rightNode: String
}

abstract class MultiNodeLogicalLeafPlan(idGen: IdGen) extends LogicalLeafPlan(idGen) {
  def idNames: Set[String]
}

trait IndexedPropertyProvidingPlan {

  /**
   * All properties
   */
  def properties: Seq[IndexedProperty]

  /**
   * Indexed properties that will be retrieved from the index and cached in the row.
   */
  def cachedProperties: Seq[CachedProperty]

  /**
   * Create a copy of this plan, swapping out the properties
   */
  def withMappedProperties(f: IndexedProperty => IndexedProperty): IndexedPropertyProvidingPlan

  /**
   * Get a copy of this index plan where getting values is disabled
   */
  def copyWithoutGettingValues: IndexedPropertyProvidingPlan
}

abstract class NodeIndexLeafPlan(idGen: IdGen) extends NodeLogicalLeafPlan(idGen) with IndexedPropertyProvidingPlan {
  def label: LabelToken

  override def cachedProperties: Seq[CachedProperty] = properties.flatMap(_.maybeCachedProperty(idName))

  override def withMappedProperties(f: IndexedProperty => IndexedProperty): NodeIndexLeafPlan

  override def copyWithoutGettingValues: NodeIndexLeafPlan

  def indexType: IndexType
}

abstract class RelationshipIndexLeafPlan(idGen: IdGen) extends RelationshipLogicalLeafPlan(idGen)
    with IndexedPropertyProvidingPlan {
  def typeToken: RelationshipTypeToken

  override def cachedProperties: Seq[CachedProperty] = properties.flatMap(_.maybeCachedProperty(idName))

  override def withMappedProperties(f: IndexedProperty => IndexedProperty): RelationshipIndexLeafPlan

  override def copyWithoutGettingValues: RelationshipIndexLeafPlan

  def indexType: IndexType
}

abstract class MultiNodeIndexLeafPlan(idGen: IdGen) extends MultiNodeLogicalLeafPlan(idGen)
    with IndexedPropertyProvidingPlan {}

abstract class NodeIndexSeekLeafPlan(idGen: IdGen) extends NodeIndexLeafPlan(idGen) {

  def valueExpr: QueryExpression[Expression]

  def properties: Seq[IndexedProperty]

  def indexOrder: IndexOrder

  override def withMappedProperties(f: IndexedProperty => IndexedProperty): NodeIndexSeekLeafPlan
}

case object Flattener extends LogicalPlans.Mapper[Seq[LogicalPlan]] {
  override def onLeaf(plan: LogicalPlan): Seq[LogicalPlan] = Seq(plan)

  override def onOneChildPlan(plan: LogicalPlan, source: Seq[LogicalPlan]): Seq[LogicalPlan] = plan +: source

  override def onTwoChildPlan(plan: LogicalPlan, lhs: Seq[LogicalPlan], rhs: Seq[LogicalPlan]): Seq[LogicalPlan] =
    (plan +: lhs) ++ rhs

  def create(plan: LogicalPlan): Seq[LogicalPlan] =
    LogicalPlans.map(plan, this)
}

sealed trait IndexUsage {
  def identifier: String
}

final case class SchemaLabelIndexUsage(
  identifier: String,
  labelId: Int,
  label: String,
  propertyTokens: Seq[PropertyKeyToken]
) extends IndexUsage

final case class SchemaRelationshipIndexUsage(
  identifier: String,
  relTypeId: Int,
  relType: String,
  propertyTokens: Seq[PropertyKeyToken]
) extends IndexUsage
final case class SchemaIndexLookupUsage(identifier: String, entityType: EntityType) extends IndexUsage
