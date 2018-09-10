package org.change.v2.runners.experiments

import java.io.{File, FileFilter, FileInputStream}

import org.change.v2.abstractnet.mat.tree.Node
import org.change.v2.abstractnet.mat.tree.Node.Forest
import org.change.v2.abstractnet.optimized.router.OptimizedRouter
import org.change.v2.analysis.expression.concrete.ConstantValue
import org.change.v2.analysis.expression.concrete.nonprimitive.:||:
import org.change.v2.analysis.processingmodels.Instruction
import org.change.v2.analysis.processingmodels.instructions._
import org.change.v2.util.canonicalnames._
import org.change.v2.abstractnet.mat.condition.{Range => CRange}
import org.change.v2.analysis.constraint.{AND, Constraint, GTE_E, LTE_E, NOT, OR}
import org.change.v2.util.conversion.RepresentationConversion
import org.change.v2.util.regexes._

import scala.io.Source
import scala.util.Random
import play.api.libs.json._

/**
  * This package contains the boilerplate code for the benchmark of the various router models.
  */
package object routerexperiments {

  type RoutingEntries = Seq[((Long, Long), String)]

  def getRandomRoutingEntries(allEntries: RoutingEntries, howMany: Int): RoutingEntries = {
    val r = new Random()
    val chosenIndices = {
      for {
        _ <- 0 until howMany
      } yield r.nextInt(allEntries.length)
    }.toSet

    allEntries.zipWithIndex.filter(chosenIndices contains _._2).unzip._1
  }

  type RoutingModelFactory = RoutingEntries => Instruction

  def buildIfElseChainModel(entries: RoutingEntries): Instruction = {
    entries.foldRight(NoOp: Instruction)( (i, crtCode) =>
      If(Constrain(IPDst, :&:(:>=:(ConstantValue(i._1._1)), :<=:(ConstantValue(i._1._2)))),
        Forward(i._2),
        crtCode
      )
    )
  }

  def selectNRandomRoutingEntries(f: String, count: Option[Int] = None): RoutingEntries = {
    val entries = count match {
      case None => OptimizedRouter.getRoutingEntries(new File(f))
      case Some(howMany) => getRandomRoutingEntries(OptimizedRouter.getRoutingEntries(new File(f)), howMany)
    }

    assert(!entries.zip(entries.tail).exists(twoEntries => {
      val fstWidth = twoEntries._1._1._2 - twoEntries._1._1._1
      val sndWidth = twoEntries._2._1._2 - twoEntries._2._1._1
      fstWidth > sndWidth
    }))

    entries
  }

  def buildBasicForkModel(entries: RoutingEntries): Instruction = {

    val f = Fork( entries.zipWithIndex.map(i => {
      val ((l,u), port) = i._1

      (port, AND(List(GTE_E(ConstantValue(l)), LTE_E(ConstantValue(u))) ++
        {
          val conflicts = entries filter (other => {
            val ((otherL, otherU), otherPort) = other
            port != otherPort &&
              l <= otherL &&
              u >= otherU
          })

          if (conflicts.nonEmpty)
            Seq(NOT(OR(conflicts.map( conflictual => {
              AND(List(GTE_E(ConstantValue(conflictual._1._1)), LTE_E(ConstantValue(conflictual._1._2))))
            }).toList)))
          else Nil
        }))
    }).groupBy(_._1).map( kv =>
      InstructionBlock(
        Assert(IPDst, OR(kv._2.map(_._2).toList)),
        Forward(kv._1)))
    )

    f
  }

  def buildPerPortIfElse(entries: RoutingEntries): Instruction = {
    entries.groupBy(_._2)
      .mapValues(portEntries =>
        :|:(portEntries.map( entry => :&:(:>=:(ConstantValue(entry._1._1)), :<=:(ConstantValue(entry._1._2)))).toList))
      .foldRight(NoOp: Instruction)( (port, crtCode) =>
        If(Constrain(IPDst, port._2),
          Forward(port._1),
          crtCode
        )
      )
  }

  def buildImprovedFork(rawEntries: RoutingEntries): Instruction = {
    val entries = rawEntries.reverse
    assert(entries.unzip._1.toSet.size == entries.length, "No duplicates")

    val portToRangeMapping = entries.groupBy(_._2)
    val forest: Forest[CRange] = Node.makeForest[CRange](entries.map(entry => CRange(entry._1._1, entry._1._2)))
    val constraints = getAllConstraints(forest)

//    assert(entries.length == Node.forestSize(forest), "Not enough nodes produced")
//    assert(entries.length == constraints.size, "Same number of constraints produced")
//    assert(portToRangeMapping.size == entries.map(_._2).toSet.size)

    val f = Fork(
      portToRangeMapping.map( portToRangeMapping => {
        val port = portToRangeMapping._1
        val mergedConstraints = OR(
            portToRangeMapping._2.map( entry => {
              constraints.get(entry._1)
          }).filter(_.nonEmpty).map(_.get).toList
        )

        InstructionBlock(
          Assert(IPDst, mergedConstraints),
          Forward(port)
        )
      })
    )

//    assert(f.forkBlocks.size == portToRangeMapping.size, "Fork has all ports")

    f
  }

  private def getAllConstraints(f: Forest[CRange]): Map[(Long, Long), Constraint] =
    f.flatMap(getAllConstraints).toMap

  private def getAllConstraints(n: Node[CRange]): Map[(Long, Long), Constraint] = {
    val nodeConstraint = if (n.children.nonEmpty || n.lateral.nonEmpty)
      AND(List(
        rangeToConstraint(n.condition),
        NOT(OR((n.children ++ n.lateral).map(conflict => rangeToConstraint(conflict.condition)))))
      ) else rangeToConstraint(n.condition)

    getAllConstraints(n.children) + ((n.condition.lower, n.condition.upper) -> nodeConstraint)
  }

  private def rangeToConstraint(r: CRange): Constraint =
    AND(List(GTE_E(ConstantValue(r.lower)), LTE_E(ConstantValue(r.upper))))

  def fibFolderToSEFL(fibs: File,
                      fibParser: (File, Boolean) => Seq[((Long, Long), String)],
                      extension: String): Map[String, Instruction] = {
    {
      for {
        fib <- fibs.listFiles(new FileFilter {
          override def accept(file: File): Boolean = file.getName.endsWith("." + extension)
        }).take(10)
        entries = fibParser(fib, true)
      } yield fib.getName.split("\\.")(0) + "-in" -> buildBasicForkModel(entries)
    }.toMap
  }

  def getRoutingEntriesBatfish(file: File, prependFileName: Boolean = false): Seq[((Long, Long), String)] = {
    (for {
      line <- scala.io.Source.fromFile(file).getLines()
      tokens = line.split("\\s+")
      if tokens.length >= 2
      if tokens(0) != ""
      matchPattern = tokens(0)
      forwardingPort = tokens(1)
    } yield (
      matchPattern match {
        case ipv4netmaskRegex() => {
          val netMaskTokens = matchPattern.split("/")
          val netAddr = netMaskTokens(0)
          val mask = netMaskTokens(1)
          val (l, u) = RepresentationConversion.ipAndMaskToInterval(netAddr, mask)
          (l,u)
        }
      },
      if (!prependFileName) forwardingPort
      else file.getName.split("\\.")(0) + "-" + forwardingPort
    )).toSeq.sortBy(i => i._1._2 - i._1._1)
  }

  def getBatfishLinks(linksFile: File): Map[String, String] = {
    val json = Source.fromFile(linksFile)
    // parse
    val parsedJson: JsArray = Json.parse(new FileInputStream(linksFile)).asInstanceOf[JsArray]

    {
      for {
        link <- parsedJson.value
      } yield (link("node1").toString() + link("node1interface").toString()) -> (link("node2").toString() + "-in")
    }.toMap
  }

  def batfishFibsToSEFL(
                       fibFolder: File,
                       linksFile: File
                       ): (Map[String, Instruction], Map[String, String]) =
  (
    fibFolderToSEFL(fibFolder, getRoutingEntriesBatfish, "fib"),
    getBatfishLinks(linksFile)
  )

  def ciscoFibsToSEFL(
                       fibFolder: File
//                       ,linksFile: File
  ): (Map[String, Instruction], Map[String, String]) =
    (
      fibFolderToSEFL(fibFolder, OptimizedRouter.getRoutingEntries, "out"),
      Map.empty
    )

}
