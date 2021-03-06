package parser.p4.test

import java.io.PrintStream
import java.util.UUID

import org.change.parser.p4.ControlFlowInterpreter
import org.change.v2.analysis.executor.CodeAwareInstructionExecutor
import org.change.v2.analysis.executor.solvers.Z3BVSolver
import org.change.v2.analysis.expression.concrete.{ConstantStringValue, ConstantValue}
import org.change.v2.analysis.expression.concrete.nonprimitive.:@
import org.change.v2.analysis.memory.State
import org.change.v2.analysis.processingmodels.Instruction
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.p4.model.{Switch, SwitchInstance}
import org.change.v2.util.conversion.RepresentationConversion
import org.scalatest.FunSuite
import org.change.parser.p4.anonymizeAndForward
import org.change.parser.p4.tables.SymbolicSwitchInstance
import org.change.v2.verification.{MapState, PolicyLogger}

class P4Nat extends FunSuite {

  test("INTEGRATION - simple-nat test no entries") {
    val dir = "inputs/simple-nat-testing/"
    val p4 = s"$dir/simple_nat-ppc.p4"
    val dataplane = s"$dir/commands.txt"
    val res = ControlFlowInterpreter(p4, dataplane, Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"), "router")
    val port = 1
    val ib = InstructionBlock(
      Forward(s"router.input.$port")
    )
    val codeAwareInstructionExecutor = CodeAwareInstructionExecutor(res.instructions(), res.links(), solver = new Z3BVSolver)
    val (initial, _) = codeAwareInstructionExecutor.
      runToCompletion(InstructionBlock(
        res.allParserStatesInstruction()
      ), State.clean, verbose = true)
    val (ok: List[State], failed: List[State]) = executeAndPrintStats(ib, initial, codeAwareInstructionExecutor)
    printResults(dir, port, ok, failed, "soso")
    assert(ok.forall(r => r.history.head == "router.output.11"))
  }

  test("INTEGRATION - simple-nat egress all drop no entries") {
    val dir = "inputs/simple-nat-testing/"
    val p4 = s"$dir/simple_nat-ppc.p4"
    val dataplane = s"$dir/commands.txt"
    val res = ControlFlowInterpreter(p4, dataplane, Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"), "router")
    val port = 2
    val ib = InstructionBlock(
      Forward(s"router.input.$port")
    )
    val codeAwareInstructionExecutor = CodeAwareInstructionExecutor(res.instructions(), res.links(), solver = new Z3BVSolver)
    val (initial, _) = codeAwareInstructionExecutor.
      runToCompletion(InstructionBlock(
        res.allParserStatesInstruction()
      ), State.clean, verbose = true)
    val (ok: List[State], failed: List[State]) = executeAndPrintStats(ib, initial, codeAwareInstructionExecutor)
    printResults(dir, port, ok, failed, "egress")

    assert(ok.isEmpty)
  }

  test("simple-nat - entries ingress") {
    val dir = "inputs/simple-nat-populated/"
    val p4 = s"$dir/simple_nat-ppc.p4"
    val dataplane = s"$dir/commands.txt"
    val res = ControlFlowInterpreter(p4, dataplane, Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"), "router")
    val port = 1
    val ib = InstructionBlock(
      Forward(s"router.input.$port")
    )

    println(res.instructions()("router.deparser.in"))

    val codeAwareInstructionExecutor = CodeAwareInstructionExecutor(res.instructions(), res.links(), solver = new Z3BVSolver)
    val parserout = codeAwareInstructionExecutor.program("router.control.ingress")
    val newparserout = InstructionBlock(
      Constrain("ethernet.IsValid", :==:(ConstantValue(1))),
      Constrain("ethernet.srcAddr", :~:(:==:(:@("ethernet.dstAddr")))),
//      Constrain("ethernet.dstAddr", :==:(ConstantValue(0xaabb000004l))),
      Constrain("ipv4.IsValid", :==:(ConstantValue(1))),
      Constrain("ipv4.srcAddr", :~:(:==:(:@("ipv4.dstAddr")))),
//      Constrain("ipv4.srcAddr", :==:(ConstantValue(0x0a00000al))),
//      Constrain("tcp.IsValid", :==:(ConstantValue(1))),
//      Constrain("tcp.srcPort", :==:(ConstantValue(0x86b6l))),
      Forward("router.control.ingress.new")
    )
    import org.change.v2.analysis.memory.TagExp.IntImprovements
    val newinstrs = (codeAwareInstructionExecutor.program + ("router.control.ingress" -> newparserout)) + ("router.control.ingress.new" -> parserout)
    val (initial, _) = codeAwareInstructionExecutor.
      runToCompletion(InstructionBlock(
        InstructionBlock(
          CreateTag("START", 0),
          Call("router.generator.parse_ethernet.parse_ipv4.parse_tcp")
        )
      ), State.clean, verbose = true)
    val (ok: List[State], failed: List[State]) = executeAndPrintStats(ib, initial,
      CodeAwareInstructionExecutor(newinstrs, Map.empty, solver = new Z3BVSolver))
    printResults(dir, port, ok, failed, "ingress")
    assert(ok.nonEmpty && ok.exists(_.history.head == "router.output.2"))
  }

  test("simple-nat - entries egress") {
    val dir = "inputs/simple-nat-populated/"
    val p4 = s"$dir/simple_nat-ppc.p4"
    val dataplane = s"$dir/commands.txt"
    val res = ControlFlowInterpreter(p4, dataplane, Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"), "router")
    val port = 2
    val ib = InstructionBlock(
      Forward(s"router.input.$port")
    )
    import org.change.v2.analysis.memory.TagExp.IntImprovements
    val codeAwareInstructionExecutor = CodeAwareInstructionExecutor(res.instructions(), res.links(), solver = new Z3BVSolver)
    val newinstrs = postParserInject(InstructionBlock(
      Constrain("ethernet.IsValid", :==:(ConstantValue(1))),
      Constrain("ethernet.srcAddr", :~:(:==:(:@("ethernet.dstAddr")))),
      Constrain("ipv4.IsValid", :==:(ConstantValue(1))),
      Constrain("tcp.IsValid", :==:(ConstantValue(1))),
      Constrain("ipv4.srcAddr", :~:(:==:(:@("ipv4.dstAddr")))),
      Assign("tmp", :@("ipv4.dstAddr"))), codeAwareInstructionExecutor.program, "router")
    val (initial, _) = codeAwareInstructionExecutor.
      runToCompletion(InstructionBlock(
        InstructionBlock(
          CreateTag("START", 0),
          Call("router.generator.parse_ethernet.parse_ipv4.parse_tcp")
        )
      ), State.clean, verbose = true)
    val (ok: List[State], failed: List[State]) = executeAndPrintStats(ib, initial,
      CodeAwareInstructionExecutor(newinstrs, Map.empty, solver = new Z3BVSolver))
    println(RepresentationConversion.numberToIP(0xc0a80001l))
    printResults(dir, port, ok, failed.filter(r => {
      codeAwareInstructionExecutor.execute(
        InstructionBlock(
          Constrain("ipv4.ttl", :>:(ConstantValue(0))),
          Constrain("tmp", :~:(:==:(ConstantValue(0xc0a80001l))))
        ), r, verbose = true)._1.nonEmpty
    }), "egress")
    assert(ok.nonEmpty && ok.forall(_.history.head == "router.output.1"))
  }


  test("simple-nat - back and forth") {
    val dir = "inputs/simple-nat-populated/"
    val p4 = s"$dir/simple_nat-ppc.p4"
    val dataplane = s"$dir/commands.txt"
    val res = ControlFlowInterpreter(p4, dataplane, Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"), "router")
    val port = 1
    val (ib: InstructionBlock, initial: List[State], fullInstrs: Map[String, Instruction]) = natAndReverse(res, port)
    val (ok: List[State], failed: List[State]) = executeAndPrintStats(ib, initial,
      CodeAwareInstructionExecutor(fullInstrs, Map.empty, solver = new Z3BVSolver))
    printResults(dir, port, ok, failed, "backnforth")
    assert(ok.nonEmpty && ok.exists(_.history.head == "router.output.1"))
  }

  test("simple-nat - back and forth wrong dataplane") {
    val dir = "inputs/simple-nat-populated/"
    val p4 = s"$dir/simple_nat-ppc.p4"
    val dataplane = s"$dir/commands-bad.txt"
    val port = 1
    val res = ControlFlowInterpreter(p4, dataplane, Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"), "router")
    val (ib: InstructionBlock, initial: List[State], fullInstrs: Map[String, Instruction]) = natAndReverse(res, port)
    val (ok: List[State], failed: List[State]) = executeAndPrintStats(ib, initial,
      CodeAwareInstructionExecutor(fullInstrs, Map.empty, solver = new Z3BVSolver))
    printResults(dir, port, ok, failed, "backnforth-wrong")
    assert(!ok.exists(_.history.head == "router.output.1"))
  }

  private def natAndReverse(res: ControlFlowInterpreter[SwitchInstance], port: Int) = {
    val ib = InstructionBlock(
      Forward(s"router.input.$port")
    )
    import org.change.v2.analysis.memory.TagExp.IntImprovements
    val codeAwareInstructionExecutor = CodeAwareInstructionExecutor(res.instructions(), res.links(), solver = new Z3BVSolver)
    val newinstrs = postParserInject(InstructionBlock(
      Constrain("ethernet.IsValid", :==:(ConstantValue(1))),
      Constrain("ethernet.srcAddr", :~:(:==:(:@("ethernet.dstAddr")))),
      Constrain("ipv4.IsValid", :==:(ConstantValue(1))),
      Constrain("tcp.IsValid", :==:(ConstantValue(1))),
      Constrain("ipv4.srcAddr", :~:(:==:(:@("ipv4.dstAddr"))))), codeAwareInstructionExecutor.program, "router")
    val (initial, _) = codeAwareInstructionExecutor.runToCompletion(
      InstructionBlock(
        CreateTag("START", 0),
        Call("router.generator.parse_ethernet.parse_ipv4.parse_tcp")
      ), State.clean, verbose = true)

    val reverseBlock = createReverse("reverse")
    val fullInstrs = (reverseBlock ++ newinstrs) + ("router.output.2" -> anonymizeAndForward("reverse.input.1")) +
      ("reverse.output.1" -> anonymizeAndForward("router.input.2"))
    (ib, initial, fullInstrs)
  }

  test("nat with stars") {
      val dir = "inputs/simple-nat-testing"
      val p4 = s"$dir/simple_nat-ppc.p4"
      val dataplane = s"$dir/commandsx.txt"
      val switchInstance = SymbolicSwitchInstance.fromFileWithSyms("router", Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"),
        Map[Int, Int](250 -> 11), Switch.fromFile(p4), dataplane)

      val res = ControlFlowInterpreter.buildSymbolicInterpreter(switchInstance, switchInstance.switch)
      val ib = InstructionBlock(
        res.allParserStatesInstruction(),
        res.initFactory(switchInstance),
        Forward("router.input.1")
      )
      val  codeAwareInstructionExecutor = CodeAwareInstructionExecutor(res.instructions(), res.links(), solver = new Z3BVSolver)
      var init = System.currentTimeMillis()
      val (ok, failed) = codeAwareInstructionExecutor.execute(ib, State.clean, verbose = true)
      println(s"Failed # ${failed.size}, Ok # ${ok.size}")

      println(s"Time is ${System.currentTimeMillis() - init}ms")
      printResults(dir, 1, ok, failed, "x")
  }

  test("policy wrong") {
    val dir = "inputs/simple-nat-populated/"
    val p4 = s"$dir/simple_nat-ppc.p4"
    val dataplane = s"$dir/commands.txt"
    val switchInstance = SymbolicSwitchInstance.fromFileWithSyms("router", Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"),
      Map[Int, Int](250 -> 11), Switch.fromFile(p4), dataplane)

    val res = ControlFlowInterpreter.buildSymbolicInterpreter(switchInstance, switchInstance.switch)

    val what = res.instructions().find((r) => r._1.startsWith("router.table.nat.in.")).get
    println("Instruction " + what)

    import org.change.v2.analysis.memory.TagExp.IntImprovements
    var ib = InstructionBlock(
      CreateTag("START", 0),
      Call("router.generator.parse_ethernet.parse_ipv4.parse_tcp"),
      res.initFactory(switchInstance)
    )
    import org.change.v2.verification.Policy._

//    System.setOut(ps)
//    check(EF(Fail), res.instructions()("router.input.1"), new MapState(res.instructions(), res.links(), State.clean,
//      CodeAwareInstructionExecutor(res.instructions(), res.links(), new Z3BVSolver)),
//      new PolicyLogger("router.input.1"))
    var log_list = verifyP4(AF(Constrain("CurrentPort",:==:(ConstantStringValue("router.output.1")))),"router.input.2",ib,res)
  }

  test("reverse run") {
    val dir = "inputs/simple-nat-populated/"
    val p4 = s"$dir/reverse.p4"
    val dataplane = s"$dir/commands-rev.txt"
    val res = ControlFlowInterpreter(p4, dataplane, Map[Int, String](1 -> "veth0", 2 -> "veth1", 11 -> "cpu"), "router")
    val port = 1
    val ib = InstructionBlock(
      Forward(s"router.input.$port")
    )
    val codeAwareInstructionExecutor = CodeAwareInstructionExecutor(res.instructions(), res.links(), solver = new Z3BVSolver)
    import org.change.v2.analysis.memory.TagExp.IntImprovements
    val newinstrs = postParserInject(
      InstructionBlock(
        Allocate("tmp111", 48),
        Assign("tmp111", :@("ethernet.dstAddr"))
      ),
      codeAwareInstructionExecutor.program, "router")
    val (initial, _) = codeAwareInstructionExecutor.
      runToCompletion(InstructionBlock(
        InstructionBlock(
          CreateTag("START", 0),
          Call("router.generator.parse_ethernet.parse_ipv4.parse_tcp")
        )
      ), State.clean, verbose = true)
    val (ok: List[State], failed: List[State]) = executeAndPrintStats(ib, initial,
      CodeAwareInstructionExecutor(newinstrs, Map.empty, solver = new Z3BVSolver))
    printResults(dir, port, ok, failed, "reverse")
    // ok should output the reversed packet
    val nondropped = ok.filter(!_.dropped())
    assert(nondropped.nonEmpty && nondropped.forall(_.history.head == "router.output.1"))
    nondropped.foreach(s => {
      val check1 = codeAwareInstructionExecutor.runToCompletion(Constrain("tmp111", :==:(:@("ethernet.srcAddr"))), s, true)
      val check2 = codeAwareInstructionExecutor.runToCompletion(Constrain("tmp111", :~:(:==:(:@("ethernet.srcAddr")))), s, true)
      assert(check1._1.nonEmpty && check2._1.isEmpty)
    })
  }
}
