package org.change.v2.analysis.processingmodels.instructions

import org.change.v2.analysis.memory.{State, TagExp, Intable}
import org.change.v2.analysis.processingmodels.Instruction

/**
 * Author: Radu Stoenescu
 * Don't be a stranger,  symnetic.7.radustoe@spamgourmet.com
 */
case class CreateTag(name: String, value: Intable)extends Instruction {
  /**
   *
   * A state processing block produces a set of new states based on a previous one.
   * After applying this instruction, one gets a new memory with the new tag in place
   * and a mapping created inside the memory
   * @param s
   * @return
   */
  override def apply(s: State, v: Boolean): (List[State], List[State]) = {
    value(s) match {
      case Some(int) => optionToStatePair(if (v) s.addInstructionToHistory(this) else s, s"Error during tagging of $name") (s => {
        s.memory.Tag(name, int)
      })
      case None => Fail(TagExp.brokenTagExpErrorMessage).apply(s, v)
    }
  }
}