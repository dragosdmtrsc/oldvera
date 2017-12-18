package org.change.parser.p4

import java.util

import org.change.parser.p4.buffer.{BufferMechanism, DeparserRev, OutputMechanism}
import org.change.parser.p4.parser.{DFSState, StateExpander}
import org.change.v2.analysis.executor.InstructionExecutor
import org.change.v2.analysis.memory.State
import org.change.v2.analysis.processingmodels._
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.p4.model.{ISwitchInstance, Switch, SwitchInstance}

import scala.collection.JavaConversions._
import P4PrettyPrinter._
import org.change.parser.p4.factories.{FullTableFactory, GlobalInitFactory, InitCodeFactory}

/**
  * Created by dragos on 07.09.2017.
  */
class ControlFlowInterpreter[T<:ISwitchInstance](val switchInstance: T,
                                                 val switch: Switch,
                                                 val additionalInitCode : (T, Int) => Instruction,
                                                 val tableFactory : (T, String, String) => Instruction,
                                                 val initFactory : (T) => Instruction) {
  def this(switchInstance: T, switch: Switch) = this(
    switchInstance, switch,
    InitCodeFactory.get(switchInstance.getClass.asInstanceOf[Class[T]]),
    FullTableFactory.get(switchInstance.getClass.asInstanceOf[Class[T]]),
    GlobalInitFactory.get(switchInstance.getClass.asInstanceOf[Class[T]])
  )

  private val initializeCode = new InitializeCode(switchInstance, switch, additionalInitCode, initFactory)
  private lazy val expd = new StateExpander(switch, "start").doDFS(DFSState(0))

  private val controlFlowInstructions = switch.getControlFlowInstructions.toMap
  private val controlFlowLinks = switch.getControlFlowLinks.toMap

  private val tableExactMatcher = "table\\.(.*?)\\.out\\.(.*)".r
  val plugTables: Map[String, Instruction] = controlFlowLinks.
    filter(r => tableExactMatcher.findFirstMatchIn(r._1).isDefined).flatMap(r => {
      tableExactMatcher.findFirstMatchIn(r._1).map(x => {
        val tabName = x.group(1)
        val id = x.group(2)
        s"table.$tabName.in.$id" -> tableFactory(switchInstance, tabName, id)
      })
    })

//
//  for (l <- switch.getCtx.links) {
//    if (l._1.contains("table.")) {
//      val tableExactMatcher = "table\\.(.*?)\\.out\\.(.*)".r
//      tableExactMatcher.findAllMatchIn(l._1).foreach(x => {
//        val tabName = x.group(1)
//        val id = x.group(2)
//        switch.getCtx.instructions.put(s"table.$tabName.in.$id", new FullTable(tabName, switchInstance, id).fullAction())
//      })
//    }
//  }
  // plug in the buffer mechanism
  private lazy val bufferMechanism = new BufferMechanism(switchInstance)
//  switch.getCtx.instructions.put(s"${switchInstance.getName}.buffer.in", bufferMechanism.symnetCode())
//  switch.getCtx.links.put(bufferMechanism.outName(), "control.egress")
  private val bufferPlug = Map[String, Instruction](s"${switchInstance.getName}.buffer.in" -> bufferMechanism.symnetCode())
  private val bufferOutLink = Map[String, String](bufferMechanism.outName() -> "control.egress")

  // plug in the output mechanism
  private lazy val outputMechanism = new OutputMechanism(switchInstance)
//  switch.getCtx.instructions.put(s"${switchInstance.getName}.output.in", outputMechanism.symnetCode())
//  switch.getCtx.links.put("control.egress.out", s"${switchInstance.getName}.deparser.in")
  //plug egress.out -> <sw>.deparser
  private val outputPlug = Map[String, Instruction](s"${switchInstance.getName}.output.in" -> outputMechanism.symnetCode())
  private val egressOutLink = Map[String, String]("control.egress.out" -> s"${switchInstance.getName}.deparser.in")


  private lazy val deparser = new DeparserRev(switch, switchInstance)
  // plug in the deparser
  private val deparserPlug = Map[String, Instruction](s"${switchInstance.getName}.deparser.in" -> deparser.symnetCode())
//  switch.getCtx.instructions.put(s"${switchInstance.getName}.deparser.in", deparser.symnetCode())
  // link deparser -> <sw>.output.in
//  switch.getCtx.links.put(deparser.outName(), s"${switchInstance.getName}.output.in")
  private val deparserOutLink = Map[String, String](deparser.outName() -> s"${switchInstance.getName}.output.in")
  //plug in the parser
//  switch.getCtx.instructions.put(s"${switchInstance.getName}.parser", parserCode())
  private val parserPlug = Map[String, Instruction](s"${switchInstance.getName}.parser" -> parserCode())
  // plug in the switch instances
//  for (x <- switchInstance.getIfaceSpec.keySet()) {
//    switch.getCtx.instructions.put(s"${switchInstance.getName}.input.$x", initialize(x))
//    // connect <sw>.input.x.out -> <sw>.parser
//    switch.getCtx.links.put(s"${switchInstance.getName}.input.$x.out", s"${switchInstance.getName}.parser")
//  }
  private val ifacePlug = switchInstance.getIfaceSpec.keys.flatMap(x => {
    Map[String, Instruction](s"${switchInstance.getName}.input.$x" -> initialize(x))
  })

  private val inputOutLink = switchInstance.getIfaceSpec.keys.flatMap(x => {
    Map[String, String](s"${switchInstance.getName}.input.$x.out" -> s"${switchInstance.getName}.parser")
  })

  // plug control.ingress.out -> <sw>.buffer
//  switch.getCtx.links.put(s"control.ingress.out", s"${switchInstance.getName}.buffer.in")
  private val ingressOutLink = Map[String, String](s"control.ingress.out" -> s"${switchInstance.getName}.buffer.in")

  private val instructionsCached = controlFlowInstructions ++ parserPlug ++ outputPlug ++ deparserPlug ++ ifacePlug
  private val linksCached = controlFlowLinks ++ deparserOutLink ++ inputOutLink ++ ingressOutLink ++ bufferOutLink
  def instructions() : Map[String, Instruction] = {
    instructionsCached
  }
  def links() : Map[String, String] = {
    linksCached
  }

  def initialize(port : Int): Instruction = initializeCode.switchInitializePacketEnter(port)
  def initializeGlobally(): Instruction = initializeCode.switchInitializeGlobally()

  def allParserStatesInstruction(): InstructionBlock = StateExpander.generateAllPossiblePackets(expd, switch)
  def parserCode(): Fork = StateExpander.parseStateMachine(expd, switch)

}

case class P4ExecutionContext(instructions: Map[LocationId, Instruction],
  links: Map[LocationId, LocationId],
  okStates: List[State],
  executor: InstructionExecutor,
  failedStates: List[State] = Nil,
  stuckStates: List[State] = Nil) {

  /**
    * Is there any state further explorable ?
    * @return
    */
  def isDone: Boolean = okStates.isEmpty

  /**
    * Calls execute until nothing can be explored further more. (The result is a done Execution Context)
    * @param verbose
    * @return
    */
  def untilDone(verbose: Boolean): P4ExecutionContext = if (isDone) {
    //    ClickExecutionContext.executorService.shutdownNow()
    this
  } else this.execute(verbose).untilDone(verbose)

  private def navigate(s : State) = {
    var current = s
    while (links contains current.location) {
      current = current.forwardTo(links(current.location))
    }
    current
  }

  def execute(verbose: Boolean = false): P4ExecutionContext = {
    val (ok, fail, stuck) = (for {
      sPrime <- okStates
      s = navigate(sPrime)
      stateLocation = s.location
    } yield {
      if (instructions contains stateLocation) {
        //          Apply instructions
        val i = instructions(stateLocation)
        val r1 = executor.execute(i, s, verbose)
        //          Apply check instructions on output ports

        (r1._1, r1._2, Nil)
      } else
        (Nil, Nil, List(s))
    }).unzip3

    copy(
      okStates = ok.flatten,
      failedStates = failedStates ++ fail.flatten,
      stuckStates = stuckStates ++ stuck.flatten
    )
  }
}


object ControlFlowInterpreter {
  def apply(switchInstance: SwitchInstance): ControlFlowInterpreter[SwitchInstance] = new ControlFlowInterpreter(switchInstance,
    switchInstance.getSwitchSpec)

  def apply(p4File: String, dataplane: String, ifaces: Map[Int, String], name : String = ""): ControlFlowInterpreter[SwitchInstance] =
    ControlFlowInterpreter(SwitchInstance.fromP4AndDataplane(p4File, dataplane, name, ifaces.foldLeft(new util.HashMap[Integer, String]())((acc, x) => {
      acc.put(x._1, x._2)
      acc
    })))

}
