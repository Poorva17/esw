package esw.test.reporter

import java.io.{File, FileWriter}
import java.nio.file.Files

import org.scalatest.Reporter
import org.scalatest.events._

class TestReporter extends Reporter {
  var results: Set[StoryResult] = Set.empty

  override def apply(event: Event): Unit = {
    event match {
      case x: TestSucceeded => addResult(x.testName, "PASSED")
      case x: TestFailed    => addResult(x.testName, "FAILED")
      case x: TestIgnored   => addResult(x.testName, "IGNORED")
      case x: TestPending   => addResult(x.testName, "PENDING")
      case x: TestCanceled  => addResult(x.testName, "CANCELED")
      case x: RunCompleted  => generateReport()
      case _                =>
      //    case RunStarting(ordinal, testCount, configMap, formatter, location, payload, threadName, timeStamp) =>
    }
  }

  private def addResult(name: String, testStatus: String): Unit = {
    val i = name.lastIndexOf(Separators.PIPE)

    val (testName, stories) =
      if (i >= 0) name.splitAt(i)
      else (name, s"${Separators.PIPE} None")

    stories
      .drop(1)                 // Drop the "|"
      .split(Separators.COMMA) // multiple stories
      .foreach { x =>
        val s = x.strip()

        val testNameResult = TestResult(testName.strip(), testStatus)
        val storyResult    = results.find(_.name == s).getOrElse(StoryResult(s, List.empty))

        results -= storyResult
        results += storyResult.copy(tests = storyResult.tests :+ testNameResult)
      }
  }

  private val parentPath = "./target/testStoryMapping"
  private val reportFile = "/testStoryMapping.txt"
  private val indexPath  = "/index.html"

  private def createIndexFile(): Unit = {
    val writer = new FileWriter(parentPath + indexPath)

    val content = s"""
      |<html>
      | <body>
      |   <a href=".$reportFile">$reportFile</a>
      | </body>
      |</html>
      |""".stripMargin

    writer.write(content)
    writer.close()
  }

  private def generateReport(): Unit = {
    Files.createDirectories(new File(parentPath).toPath)
    createIndexFile()
    val file = new FileWriter(parentPath + reportFile, true)
    results.foreach(x => file.append(x.format(Separators.PIPE, Separators.NEWLINE)))
    file.close()
  }

  object Separators {
    val PIPE    = '|' // separator for test name and story number
    val NEWLINE = '\n'
    val COMMA   = ',' // separator for multiple story number
  }
}