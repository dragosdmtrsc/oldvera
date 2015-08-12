package org.change.v2.abstractnet.generic

import org.change.symbolicexec.blocks.{NoopProcessingBlock, ProcessingBlock}
import org.change.v2.analysis.processingmodels.Instruction

/**
 * Generic element, defined by an id (the name), an element type and the input
 * and output port sets.
 *
 * @param name
 * @param elementType
 */
case class GenericElement(name: String,
                   elementType: String,
                   inputPorts: List[Port] = Nil,
                   outputPorts: List[Port] = Nil,
                   configParameters: List[ConfigParameter] = Nil) {

  def inputPortCount = inputPorts.length
  def outputPortCount = outputPorts.length

  def inputPortName(which: Int = 0): String = s"$name-in"
  def outputPortName(which: Int = 0): String = s"$name-out"

  override def toString = s"\n[ $name $elementType\n$inputPorts\n$outputPorts\n$configParameters]\n"

  def toProcessingBlock: ProcessingBlock = new NoopProcessingBlock(name, inputPortCount, outputPortCount)

  def instructions: Map[Int, (Instruction, String)] = Map()

  def namePortToInt(port: Int): Int = (name, port).hashCode()
}

class GenericElementBuilder(name: String, elementType: String)
  extends ElementBuilder(name, elementType) {

  override def buildElement: GenericElement = {
    new GenericElement(name,
          elementType,
          getInputPorts,
          getOutputPorts,
          getConfigParameters)

  }
}

object GenericElement {

  private var unnamedCount = 0

  private val genericElementName = "id"

  private def increment {
    unnamedCount += 1
  }

  def getBuilder(name: String, elementType: String): ElementBuilder = {
    increment ; new GenericElementBuilder(name, elementType)
  }

  def getBuilder(elementType: String): ElementBuilder =
    getBuilder(s"$genericElementName-$unnamedCount", elementType)
}