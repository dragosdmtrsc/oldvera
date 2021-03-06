package org.change.parser.p4.buffer

import java.util.UUID

import org.change.parser.p4.InitializeCode
import org.change.parser.p4.parser.{DFSState, Graph}
import org.change.v2.analysis.expression.abst.FloatingExpression
import org.change.v2.analysis.expression.concrete.{ConstantStringValue, ConstantValue}
import org.change.v2.analysis.expression.concrete.nonprimitive.:@
import org.change.v2.analysis.memory.{State, Tag}
import org.change.v2.analysis.processingmodels.Instruction
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.p4.model._
import org.change.v2.p4.model.parser._

import scala.collection.JavaConversions._
import scala.collection.immutable.Stack

/**
  * Created by dragos on 15.09.2017.
  */
class BufferMechanism(switch: Switch, switchInstance: ISwitchInstance) {
  private val forwardingHelper = new ForwardingHelper(switch)

  def clone(v : String, fllist : FieldList) : Instruction = {
    val initcode = new InitializeCode[ISwitchInstance](switchInstance, switch)
    val defaultFields = List("standard_metadata.ingres_port")
    InstructionBlock(
      initcode.initializeFields(),
      initcode.initializeMetadata((if (fllist == null) Nil else fllist.getFields.toList) ++ defaultFields)
    )
  }

  def cloneCase(instanceType : InstanceType): InstructionBlock = {
    InstructionBlock(
      switchInstance.getCloneSpec2EgressSpec.
        foldRight(Fail("No clone mapping found. Resolve to drop") : Instruction)((x, acc) => {
          If (Constrain(s"${switchInstance.getName}.clone_session", :==:(ConstantValue(x._1.longValue()))),
            Assign("standard_metadata.egress_port", ConstantValue(x._2.longValue())),
            acc
          )
        }),
      forwardingHelper.handleFieldList("standard_metadata.clone_spec", allowEmpty = false, clone),
      Assign("standard_metadata.instance_type", ConstantValue(instanceType.value)),
      Forward(s"${switchInstance.getName}.control.egress")
    )
  }

  def normalCase(): Instruction = switchInstance.getIfaceSpec.keySet().
    foldRight(
      If (Constrain("standard_metadata.egress_spec", :==:(ConstantValue(511l))),
        Drop("Egress spec set to 511 <=> dropping packet post ingress"),
        Fail("Egress spec not set. Resolve to drop")
      ) : Instruction)((x, acc) => {
    If (Constrain("standard_metadata.egress_spec", :==:(ConstantValue(x.longValue()))),
      Assign("standard_metadata.egress_port", ConstantValue(x.longValue())),
      acc
    )
  })

  def resub(v : String, fllist : FieldList) : Instruction = {
    val initcode = new InitializeCode[ISwitchInstance](switchInstance, switch)
    val defaultFields = List("standard_metadata.ingres_port")
    InstructionBlock(
      initcode.initializeFields(),
      initcode.initializeMetadata((if (fllist == null) Nil else fllist.getFields.toList) ++ defaultFields)
    )
  }
  def zeroizeFwMetas() = InstructionBlock(
    Assign("standard_metadata.egress_spec", ConstantValue(0)),
    Assign("standard_metadata.clone_spec", ConstantValue(0)),
    Assign("standard_metadata.clone_session", ConstantValue(0)),
    Assign("standard_metadata.resubmit_flag", ConstantValue(0)),
    Assign("standard_metadata.recirculate_flag", ConstantValue(0))
  )

  def symnetCode() : Instruction = InstructionBlock(
    If (Constrain("standard_metadata.clone_session", :~:(:==:(ConstantValue(0)))),
      Fork(NoOp, cloneCase(InstanceType.PKT_INSTANCE_TYPE_INGRESS_CLONE))
    ),
    If (Constrain("standard_metadata.resubmit_flag", :~:(:==:(ConstantValue(0)))),
      InstructionBlock(
        forwardingHelper.handleFieldList("standard_metadata.resubmit_flag", allowEmpty = true, resub),
        Assign("standard_metada.instance_type", ConstantValue(InstanceType.PKT_INSTANCE_TYPE_RESUBMIT.value)),
        Forward(switchInstance.getName + ".parser")
      ),
      InstructionBlock(
        normalCase(),
        out()
      )
    ),
    zeroizeFwMetas()
  )
  def out() = Forward(outName())

  def outName() = s"${switchInstance.getName}.buffer.out"

  def outputCode() : Instruction = {
    InstructionBlock(
      If (Constrain("standard_metadata.clone_session", :~:(:==:(ConstantValue(0)))),
        Fork(NoOp,
          cloneCase(InstanceType.PKT_INSTANCE_TYPE_EGRESS_CLONE)
        )
      ),
      If (Constrain("standard_metadata.recirculate_flag", :~:(:==:(ConstantValue(0)))),
        InstructionBlock(
          forwardingHelper.handleFieldList("standard_metadata.recirculate_flag", allowEmpty = true, resub),
          Assign("standard_metada.instance_type", ConstantValue(InstanceType.PKT_INSTANCE_TYPE_RECIRC.value)),
          Forward(switchInstance.getName + ".parser")
        ),
        If (Constrain("standard_metadata.egress_spec", :==:(ConstantValue(511))),
          Drop("standard metadata set to 511 at the end of egress"),
          InstructionBlock(
            switchInstance.getIfaceSpec.foldRight(
              Fail("Cannot find egress_port match for current interfaces") : Instruction)((x, acc) => {
              If (Constrain("standard_metadata.egress_port", :==:(ConstantValue(x._1.longValue()))),
                Forward(s"${switchInstance.getName}.output.${x._1}"),
                acc
              )
            })
          )
        )
      ),
      zeroizeFwMetas()
    )
  }

}

class ForwardingHelper(switch: Switch) {
  def handleFieldList(fieldName : String,
                      allowEmpty : Boolean,
                      f : Function2[String, FieldList, Instruction]) : Instruction = {
    val actualList = if (!allowEmpty) switch.getFieldListMap.toIterable
    else (switch.getFieldListMap + ("" -> null))
    val justBefore = Assign("tmp", :@(fieldName))
    val rn = s"tmp${UUID.randomUUID()}"
    InstructionBlock(
      Allocate(rn),
      justBefore,
      actualList.foldRight(Fail("field list not found") : Instruction)((x, acc) => {
        If (Constrain(rn, :==:(ConstantStringValue(x._1))),
          f(x._1, x._2),
          acc
        )
      })
    )
  }
}


class DeparserRev(switch: Switch, switchInstance : ISwitchInstance) {

  val isV2 = true

  def generateCode2(crt : String, offset : Int,
                    nodes : Map[String, Iterable[String]],
                    edges : Map[String, Iterable[String]]) : Instruction = {
    val extracts = nodes.getOrElse(crt, Nil)
    val outgoing = edges.getOrElse(crt, Nil)
    generateHeaderCode(extracts.toList, offset, off => {
      if (outgoing.isEmpty)
        NoOp
      else
        Fork(outgoing.map(r => {
          generateCode2(r, off, nodes, edges)
        }))
    })
  }
  def generateHeaderCode(headerInstances : List[String], off : Int,
                         continueWith : Int => Instruction) : Instruction = headerInstances match {
    case Nil => continueWith(off)
    case h2 :: t2 =>
      val ib = switch.getInstance(h2).getLayout.getFields.
        foldLeft((off, List[Instruction]()))((acc, f) => {
          (acc._1 + f.getLength,  acc._2 ++ List[Instruction](
            Allocate(Tag("START") + acc._1, f.getLength),
            Assign(Tag("START") + acc._1, :@(s"$h2.${f.getName}"))
          ))
        })
      If (Constrain(s"$h2.IsValid", :==:(ConstantValue(1))),
        InstructionBlock(
          ib._2 :+ generateHeaderCode(t2, ib._1, continueWith)
        ),
        Fail("Deparser error: No such packet layout exists")
      )
  }

  def generateCode(stack : List[String], offset : Int,
                   nodes : Map[String, Iterable[String]],
                   edges : Iterable[(String, String)]) : Instruction = stack match {
    case Nil => NoOp
    case head :: tail =>
      val headers = nodes(head)
      generateHeaderCode(headers.toList, offset, off => generateCode(tail, off, nodes, edges))
  }

  def symnetCode() : Instruction = {
    val asList = switch.parserStates().toList
    val (edges, nodes) = asList.foldLeft((List[(String, String)](), Map[String, Iterable[String]]()))((acc, x) => {
      val (edges, extracts) = parserStatesAndEdges(switch.getParserState(x))
      (acc._1 ++ edges, acc._2 + (x -> extracts))
    })
    val graph = new Graph(asList.size)
    val indexedSeq = asList.zipWithIndex.toMap
    for (e <- edges)
      if (e._2 != "ingress")
        graph.addEdge(indexedSeq(e._1), indexedSeq(e._2))
    if (!isV2) {
      val popper = graph.topologicalSort()
      val ins = InstructionBlock(
        DestroyPacket(),
        generateCode(popper.map(_.intValue()).reverse.map(asList(_)).toList, 0, nodes, edges),
        Forward(outName())
      )
      ins
    } else {
      val popper = graph.topologicalSort()
      val startAt = asList(popper.reverse.head)
      val ins = InstructionBlock(
        DestroyPacket(),
        generateCode2(startAt, 0, nodes, edges.groupBy(f => f._1).map(r => r._1 -> r._2.map(h => h._2))),
        Forward(outName())
      )
      ins
    }
  }

  private def parserStatesAndEdges(ss : org.change.v2.p4.model.parser.State):
  (Iterable[(String, String)], Iterable[String]) = {
    val edges = ss.getStatements.
      filter(x => x.isInstanceOf[ReturnStatement] || x.isInstanceOf[ReturnSelectStatement]).
      flatMap {
      case rs : ReturnStatement => List[(String, String)]((ss.getName, rs.getWhere))
      case rss : ReturnSelectStatement => rss.getCaseEntryList.map(x => {
        (ss.getName, x.getReturnStatement.getWhere)
      })
    }
    val extracts = ss.getStatements.collect({
      case v : ExtractStatement => v.getExpression
    }).collect({
      case v : StringRef => v.getRef
    })
    (edges, extracts)
  }

  def outName() = s"${switchInstance.getName}.deparser.out"
}


class Deparser(switchInstance: SwitchInstance, expd : List[DFSState]) {
  def symnetCode() : Instruction = {
    InstructionBlock(new Instruction() {
      // TODO: MASSIVE HACK here => let's figure out a clean way for this one
      override def apply(s: State, verbose: Boolean): (List[State], List[State]) = (List[State](s.destroyPacket()), Nil)
    }, Fork(expd.map(x => {
      InstructionBlock(
        x.history.tail.reverse.map {
          case ce: CaseEntry =>
            val e = ce.getExpressions
            InstructionBlock(
              e.zip(ce.getValues).map(r => {
                r._1 match {
                  case ref: StringRef => Constrain(ref.getRef, :==:(ConstantValue(r._2.getValue)))
                  case _ => NoOp
                }
              })
            )
          case es: ExtractStatement =>
            val ref = es.getExpression.asInstanceOf[StringRef].getRef
            Constrain(s"$ref.IsValid", :==:(ConstantValue(1)))
          case _ => NoOp
        } ++ x.history.tail.reverse.flatMap {
          case es : ExtractStatement =>
            val ref = es.getExpression.asInstanceOf[StringRef].getRef
            val instance = switchInstance.getSwitchSpec.getInstance(ref)
            instance.getLayout.getFields.foldLeft((List[Instruction](), 0))((acc, r) => {
              (acc._1 ++ List[Instruction](
                Allocate(Tag("START") + acc._2, r.getLength),
                Assign(Tag("START") + acc._2, :@(ref + "." + r.getName))
              ), acc._2 + r.getLength)
            })._1
          case _ => List[Instruction](NoOp)
        }
      )
      })), Forward(outName())
    )
  }
  def outName() = s"${switchInstance.getName}.deparser.out"
}