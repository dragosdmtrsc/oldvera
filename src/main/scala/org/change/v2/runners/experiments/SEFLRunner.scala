package org.change.v2.runners.experiments

import java.io.{File, FileOutputStream, PrintStream}

import org.change.v2.analysis.expression.concrete.nonprimitive._
import org.change.v2.analysis.expression.concrete.{ConstantValue, SymbolicValue}
import org.change.v2.analysis.memory.{State, Tag}
import org.change.v2.analysis.memory.TagExp._
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.util.conversion.RepresentationConversion._

object SEFLRunner {

  lazy val output = new PrintStream(new FileOutputStream(new File("sefl.output")))

  def main(args: Array[String]): Unit = toJSONExample()

  def toJSONExample(): Unit = {
    val (successful, failed) = code(State.clean, true)

    output.println(
      successful.map(_.jsonString).mkString("Successful: {\n", "\n", "}\n") +
        failed.map(_.jsonString).mkString("Failed: {\n", "\n", "}\n")
    )

    output.close()

    println("Check output @ sefl.output")
  }

  val code = InstructionBlock(
    Assign("a", SymbolicValue()),
    Assign("zero", ConstantValue(0)),
    // State that a is positive
    Constrain("a", :>:(:@("zero"))),
    // Compute the sum
    Assign("sum", :+:(:@("a"), :@("zero"))),
    // We want the sum to be 0, meaning a should also be zero - impossible
    If(Constrain("sum", :==:(:@("zero"))),
      Fail("This should not be reachable"),
      Forward("exit 0"))
  )

  def ex0: (List[State], List[State]) = {
    code(State.clean, true)
  }

  def ex1: (List[State], List[State]) = {
    val code = InstructionBlock(
      Assign("a", SymbolicValue()),
      Assign("zero", ConstantValue(0)),
      // State that a is positive
      Constrain("a", :>:(:@("zero"))),
      // Compute the sum
      Assign("sum", :+:(:@("a"), :@("zero"))),
      // Now, for every branch there will be a path, one successful, one not.
      If(Constrain("sum", :==:(:@("zero"))), {
        // This instruction will never get executed.
        Fail("Impossible")
      }
      )
    )

    code(State.clean, true)
  }

  /**
    * A simple IP filtering and forwarding example.
    */
  def ex2: (List[State], List[State]) = {
    val code = InstructionBlock(
      // At address 0 the L3 header starts
      CreateTag("L3HeaderStart", 0),
      // Also mark IP Src and IP Dst fields and allocate memory
      CreateTag("IPSrc", Tag("L3HeaderStart") + 96),
      // For raw memory access (via tags or ints), space has to be allocated beforehand.
      Allocate(Tag("IPSrc"), 32),
      CreateTag("IPDst", Tag("L3HeaderStart") + 128),
      Allocate(Tag("IPDst"), 32),


      //Initialize IPSrc and IPDst
      Assign(Tag("IPSrc"), ConstantValue(ipToNumber("127.0.0.1"))),
      Assign(Tag("IPDst"), SymbolicValue()),

      // If destination is 8.8.8.8, rewrite the Src address and forward it
      // otherwise, drop it
      If(Constrain(Tag("IPDst"), :==:(ConstantValue(ipToNumber("8.8.8.8")))),
        Assign(Tag("IPSrc"), SymbolicValue()),
        Fail("Packet dropped")
      )
    )

    code(State.clean, true)
  }
}


