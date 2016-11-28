package org.change.v2.runners.experiments

import java.io.{File, FileOutputStream, PrintStream}

import org.change.parser.clickfile.ClickToAbstractNetwork
import org.change.v2.executor.clickabstractnetwork.ClickExecutionContext
import org.change.v2.executor.clickabstractnetwork.executionlogging.{ModelValidation, JsonLogger, OldStringifier}

/**
 * Author: Radu Stoenescu
 * Don't be a stranger,  symnetic.7.radustoe@spamgourmet.com
 */
object TemplateRunner {

  def main (args: Array[String]) {
    val clickConfig = "src/main/resources/click_test_files/Template.click"
    val absNet = ClickToAbstractNetwork.buildConfig(clickConfig)
    val executor = ClickExecutionContext.fromSingle(absNet).setLogger(JsonLogger)

    var crtExecutor = executor
    while (!crtExecutor.isDone) {
      crtExecutor = crtExecutor.execute(verbose = true)
    }

    val output = new PrintStream(new FileOutputStream(new File("template.output")))
    val (successful, failed) = (crtExecutor.stuckStates, crtExecutor.failedStates)

    output.println(
      successful.map(_.jsonString).mkString("Successful: {\n", "\n", "}\n") +
        failed.map(_.jsonString).mkString("Failed: {\n", "\n", "}\n")
    )

    output.close()

    println("Check output @ sefl.output")
    println("Done, check template.output")
  }
}
