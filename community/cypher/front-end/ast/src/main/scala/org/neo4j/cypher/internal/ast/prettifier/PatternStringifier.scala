/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.ast.prettifier

import org.neo4j.cypher.internal.expressions.EveryPath
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.NamedPatternPart
import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.Pattern
import org.neo4j.cypher.internal.expressions.PatternElement
import org.neo4j.cypher.internal.expressions.PatternPart
import org.neo4j.cypher.internal.expressions.Range
import org.neo4j.cypher.internal.expressions.RelationshipChain
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.expressions.ShortestPaths

case class PatternStringifier(expr: ExpressionStringifier) {

  def apply(p: Pattern): String =
    p.patternParts.map(apply).mkString(", ")

  def apply(p: PatternPart): String = p match {
    case e: EveryPath        => apply(e.element)
    case s: ShortestPaths    => s"${s.name}(${apply(s.element)})"
    case n: NamedPatternPart => s"${expr(n.variable)} = ${apply(n.patternPart)}"
  }

  def apply(element: PatternElement): String = element match {
    case r: RelationshipChain => apply(r)
    case n: NodePattern       => apply(n)
  }

  def apply(nodePattern: NodePattern): String = {
    val variable = nodePattern.variable.map(expr(_))

    val labels =
      Some(nodePattern.labels)
        .filter(_.nonEmpty)
        .map(_.map(expr(_)).mkString(":", ":", ""))

    val body =
      concatenate(" ", Seq(
        concatenate("", Seq(variable, labels)),
        nodePattern.properties.map(expr(_)),
        nodePattern.predicate.map(stringifyPredicate),
      )).getOrElse("")

    s"($body)"
  }

  def apply(relationshipChain: RelationshipChain): String = {
    val r = apply(relationshipChain.rightNode)
    val middle = apply(relationshipChain.relationship)
    val l = apply(relationshipChain.element)

    s"$l$middle$r"
  }

  def apply(relationship: RelationshipPattern): String = {
    val variable = relationship.variable.map(expr(_))

    val types =
      Some(relationship.types)
        .filter(_.nonEmpty)
        .map(_.map(expr(_)).mkString(":", "|", ""))

    val length = relationship.length match {
      case None => None
      case Some(None) => Some("*")
      case Some(Some(range)) => Some(stringifyRange(range))
    }

    val body = concatenate(" ", Seq(
      concatenate("", Seq(variable, types, length)),
      relationship.properties.map(expr(_)),
      relationship.predicate.map(stringifyPredicate),
    )).fold("")(inner => s"[$inner]")

    relationship.direction match {
      case SemanticDirection.OUTGOING => s"-$body->"
      case SemanticDirection.INCOMING => s"<-$body-"
      case SemanticDirection.BOTH => s"-$body-"
    }
  }

  def concatenate(separator: String, fragments: Seq[Option[String]]): Option[String] =
    Some(fragments.flatten)
      .filter(_.nonEmpty) // ensures that there is at least one fragment
      .map(_.mkString(separator))

  private def stringifyRange(range: Range): String = {
    val lower = range.lower.fold("")(_.stringVal)
    val upper = range.upper.fold("")(_.stringVal)
    s"*$lower..$upper"
  }

  private def stringifyPredicate(predicate: Expression): String =
    s"WHERE ${expr(predicate)}"

}
