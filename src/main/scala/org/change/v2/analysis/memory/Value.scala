package org.change.v2.analysis.memory

import org.change.v2.analysis.constraint.Constraint
import org.change.v2.analysis.expression.abst.Expression
import org.change.v2.analysis.types.{LongType, NumericType}
import org.change.v2.analysis.z3.Z3Able
import spray.json._
import z3.scala.{Z3AST, Z3Solver}
import org.change.v2.analysis.memory.jsonformatters.ValueToJson._

/**
  * Author: Radu Stoenescu
  * Don't be a stranger,  symnetic.7.radustoe@spamgourmet.com
  *
  * A value is a typed expression, together with its constraints.
  */
case class Value(e: Expression, eType: NumericType = LongType, cts: List[Constraint] = Nil)
  extends Z3Able {

  private var isStale: Boolean = true
  private var computed: Option[Long] = None

  def setComputed(v: Long) {
    computed = Some(v)
  }

  override def toZ3(solver: Option[Z3Solver] = None): (Z3AST, Option[Z3Solver]) = {
    val (ast, afterAstBuildSolver) = e.toZ3(solver)

    for {
      s <- afterAstBuildSolver
      c <- cts
    } {
      s.assertCnstr(c.z3Constrain(ast))
    }

    (ast, afterAstBuildSolver)
  }

  def constrain(c: Constraint): Value = Value(e, eType, c :: cts)

  def handmadeString = s"Value {\n" +
    s"Expression: $e\n" +
    s"Type: $eType\n" +
    s"Constraints:\n\t ${cts.mkString("\n")}\n} End Of Value Desc\n"

  def jsonString = this.toJson.prettyPrint

  override def toString = jsonString

}



