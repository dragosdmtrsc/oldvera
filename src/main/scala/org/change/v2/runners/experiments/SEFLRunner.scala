package org.change.v2.runners.experiments

import java.io.{FileOutputStream, PrintStream, File}
import org.change.v2.analysis.expression.concrete.nonprimitive._
import org.change.v2.analysis.expression.concrete.{ConstantValue, SymbolicValue}
import org.change.v2.analysis.memory.State
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.executor.clickabstractnetwork.ClickExecutionContext
import org.change.v2.analysis.memory.TagExp._
import org.change.v2.analysis.memory.Tag
import org.change.v2.util.conversion.RepresentationConversion._
import org.change.utils.prettifier.StatePrettifier
import org.change.utils.prettifier.SomeWriter
import org.change.v2.analysis.processingmodels.instructions.CreateTag
import org.change.v2.analysis.processingmodels.instructions.If
import org.change.v2.analysis.processingmodels.instructions.Fail
import org.change.v2.analysis.processingmodels.instructions.CreateTag
import org.change.v2.analysis.processingmodels.instructions.If
import org.change.v2.analysis.processingmodels.instructions.Fail
import java.util.HashMap
import org.change.v2.analysis.expression.concrete.nonprimitive.:+:
object SEFLRunner {

  lazy val output = new PrintStream(new FileOutputStream(new File("outputs/pretty/sefl0.output")))

  lazy val jOutput = new PrintStream(new FileOutputStream(new File("outputs/pretty/sefl0.json")))
  lazy val output2 = new PrintStream(new FileOutputStream(new File("outputs/pretty/sefl1.output")))
  lazy val output3 = new PrintStream(new FileOutputStream(new File("outputs/pretty/sefl2.output")))
  lazy val output4 = new PrintStream(new FileOutputStream(new File("outputs/pretty/sefl3.output")))

  def testGeneric (output : PrintStream)(block : => (List[State], List[State])) = {
    val (successful, failed) = block
    var i = 0
    for (s <- successful) {
      output.println(StatePrettifier(s))
      i = i + 1
    }
    
    
    
    i = 0
    for (s <- failed) {
      output.println(StatePrettifier(s))
    }
  }
  
  def main (args: Array[String]){

    testGeneric(output)(ex0)
    testGeneric(output2)(ex1)
    testGeneric(output3)(ex2)
    testGeneric(output4)(ex3)
    
//    output.println(s"OK States (${successful.length}}):\n" + ClickExecutionContext.verboselyStringifyStates(successful))
//    output.println(s"\nFailed States (${failed.length}}):\n" + ClickExecutionContext.verboselyStringifyStates(failed))

    println("Check output @ sefl.output")
  }


  def t01: (List[State], List[State]) = {
    /*
    SEFL is just like any imperative language
    variables have no type
    values are integers
    x = 0;
    y = x + 1;
    z = x - y
    */

    val code = InstructionBlock(
      Assign("x",ConstantValue(0)),
      Assign("y",:+:(:@("x"),ConstantValue(1))),
      Assign("z",:-:(:@("x"),:@("y")))
    )
    code(State.clean, true)
  }
  def t02: (List[State], List[State]) = {
    /*
    Apart from variables, SEFL works with an unconstrained linear memory which models a packet.
    Suppose we want to create an L3 packet
      At address 0 the L3 header starts
      CreateTag("L3HeaderStart", 0),

      Also mark IP Src and IP Dst fields and allocate memory
      CreateTag("IPSrc", Tag("L3HeaderStart") + 96),

      For raw memory access (via tags or ints), space has to be allocated beforehand.
      Allocate(Tag("IPSrc"), 32),
      CreateTag("IPDst", Tag("L3HeaderStart") + 128),
      Allocate(Tag("IPDst"), 32),


      shorthand syntax:
      -----------------
      CreateTag(L3HeaderStart, 0);

      CreateTag(IPSrc, L3HeaderStart + 96);
      Allocate(IPSrc, 32);

      CreateTag(IPDst, L3HeaderStart + 128);
      Allocate(IPDst, 32)

    */

    val code = InstructionBlock(
        CreateTag("L3HeaderStart",0),
        CreateTag("IPSrc",Tag("L3HeaderStart")+96),
        Allocate(Tag("IPSrc"),32),
        CreateTag("IPDst",Tag("L3HeaderStart")+128),
        Allocate(Tag("IPDst"),32)
      )

    code(State.clean, true)
  }

  def t03: (List[State], List[State]) = {
    /*
    Each variable has a stack
    Allocate(x);
    x = 1;
    Allocate(x);
    x = 2;
    Deallocate(x)

    */

    val code = InstructionBlock(
      Allocate("x"),
      Assign("x",ConstantValue(1)),
      Allocate("x"),
      Assign("x",ConstantValue(2)),
      Deallocate("x")
    )
    code(State.clean, true)
  }


  def t04: (List[State], List[State]) = {
    /*
    Constraints work similarly to assertions
    x > 2;
    x < 3;
    y == x + 1;
    z > x + y

    */

    val code = InstructionBlock(
      Constrain("x",:>:(ConstantValue(2))),
      Constrain("x",:<:(ConstantValue(3))),
      Constrain("y",:==:(:+:(:@("x"),ConstantValue(1)))),
      Constrain("z",:>:(:+:(:@("x"),:@("y"))))
    )
    code(State.clean, true)
  }

  def t05: (List[State], List[State]) = {
    /*
    One can also use if, as illustrated below:

    if (x>0) {x=1} {x=2}
    the "then" and "else" bodies of the if must be separated by curled brackets.

    Note that below one needs two sets of brackets, to help the parser with the structure of the program:
    {{if (x>0) {x=1} {x=2} ; y = 1} || y = 2}

    */

    
    val code = Fork(
      InstructionBlock(
        If(Constrain("x",:>:(ConstantValue(0))),Assign("x",ConstantValue(1)),Assign("x",ConstantValue(2))),
        Assign("y",ConstantValue(1))
      ),
      Assign("y",ConstantValue(2))
    )



    code(State.clean, true)

  }



  def ex0: (List[State], List[State]) = {
    val code = InstructionBlock (
      Assign("a", SymbolicValue()),
      Assign("zero", ConstantValue(0)),
      // State that a is positive
      Constrain("a", :>:(:@("zero"))),
      // Compute the sum
      Assign("sum", :+:(:@("a"), :@("zero"))),
      // We want the sum to be 0, meaning a should also be zero - impossible
      Constrain("sum", :==:(:@("zero")))
    )

    code(State.clean, true)
  }

  def ex1: (List[State], List[State]) = {
    val code = InstructionBlock (
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

    code(State.clean, false)
  }

  /**
   * A simple IP filtering and forwarding example.
   */
  def ex2: (List[State], List[State]) = {
    val code = InstructionBlock (
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
  
  /**
   * A simple IP filtering and forwarding example.
   */
  def ex3: (List[State], List[State]) = {
    val tagL3 = Tag("L3HeaderStart")
    val ipSrc = Tag("IPSrc")
    val ipDst = Tag("IPDst")
    val some = :+:(ConstantValue(21), ConstantValue(22))
    val other = :+:(SymbolicValue(), SymbolicValue())
    val code = InstructionBlock (
      // At address 0 the L3 header starts
      CreateTag("L3HeaderStart", 0),
      // Also mark IP Src and IP Dst fields and allocate memory
      CreateTag("IPSrc", tagL3 + 96),
      // For raw memory access (via tags or ints), space has to be allocated beforehand.
      Allocate(ipSrc, 32),
      CreateTag("IPDst", tagL3 + 128),
      Allocate(ipDst, 32),


      //Initialize IPSrc and IPDst
      Assign(ipSrc, "127.0.0.1"),
      Assign(ipDst, SymbolicValue()),

      Assign("Ceva", :+:(SymbolicValue(), SymbolicValue())),
      
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


