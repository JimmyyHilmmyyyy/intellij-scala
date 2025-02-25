package org.jetbrains.plugins.scala
package debugger
package evaluation

import com.intellij.debugger.impl.{DebuggerManagerListener, DebuggerSession}
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.jps.incremental.scala.remote.CommandIds
import org.jetbrains.jps.incremental.scala.{Client, DummyClient, MessageKind}
import org.jetbrains.plugins.scala.compiler.{CompileServerLauncher, RemoteServerConnectorBase, RemoteServerRunner}
import org.jetbrains.plugins.scala.settings.ScalaCompileServerSettings

import java.io.File
import scala.annotation.tailrec
import scala.collection.mutable

//noinspection ApiStatus
class ScalaEvaluatorCompileHelper(project: Project) extends EvaluatorCompileHelper {

  private val tempFiles = mutable.Set[File]()

  private def clearTempFiles(): Unit = {
    tempFiles.foreach(FileUtil.delete)
    tempFiles.clear()
  }

  def tempDir(): File = {
    val dir = FileUtil.createTempDirectory("classfilesForDebugger", null, true)
    tempFiles += dir
    dir
  }

  def tempFile(): File = {
    val file = FileUtil.createTempFile("FileToCompile", ".scala", true)
    tempFiles += file
    file
  }

  override def compile(fileText: String, module: Module): Array[(File, String)] = {
    compile(fileText, module, tempDir())
  }

  def compile(files: Seq[File], module: Module, outputDir: File): Array[(File, String)] = {
    if (EvaluatorCompileHelper.needCompileServer) {
      CompileServerLauncher.ensureServerRunning(project)
    }
    val connector = new ServerConnector(module, files, outputDir)
    try {
      connector.compile() match {
        case Right(output) => output
        case Left(errors) => throw EvaluationException(NlsString.force(errors.mkString("\n")))
      }
    }
    catch {
      case e: Exception => throw EvaluationException(DebuggerBundle.message("could.not.compile", e.getMessage))
    }
  }

  def compile(fileText: String, module: Module, outputDir: File): Array[(File, String)] = {
    compile(Seq(writeToTempFile(fileText)), module, outputDir)
  }

  def writeToTempFile(text: String): File = {
    val file = tempFile()
    FileUtil.writeToFile(file, text)
    file
  }
}

object ScalaEvaluatorCompileHelper {
  def instance(project: Project): ScalaEvaluatorCompileHelper =
    project.getService(classOf[ScalaEvaluatorCompileHelper])

  private class Listener(project: Project) extends DebuggerManagerListener {

    override def sessionDetached(session: DebuggerSession): Unit = {
      instance(project).clearTempFiles()

      if (!ScalaCompileServerSettings.getInstance().COMPILE_SERVER_ENABLED && EvaluatorCompileHelper.needCompileServer) {
        CompileServerLauncher.ensureServerNotRunning()
      }
    }
  }
}


private class ServerConnector(module: Module, filesToCompile: Seq[File], outputDir: File)
  extends RemoteServerConnectorBase(module, Some(filesToCompile), outputDir) {

  private val errors = Seq.newBuilder[NlsString]

  private val client: Client = new DummyClient {
    override def message(msg: Client.ClientMsg): Unit =
      if (msg.kind == MessageKind.Error) errors += NlsString(msg.text)
  }

  @tailrec
  private def classfiles(dir: File, namePrefix: String = ""): Array[(File, String)] = dir.listFiles() match {
    case Array(d) if d.isDirectory => classfiles(d, s"$namePrefix${d.getName}.")
    case files => files.map(f => (f, s"$namePrefix${f.getName}".stripSuffix(".class")))
  }

  type CompileResult = Either[Seq[NlsString], Array[(File, String)]]
  def compile(): CompileResult = {
    val compilationProcess = new RemoteServerRunner().buildProcess(CommandIds.Compile, arguments.asStrings, client)
    var result: CompileResult = Left(Seq(NlsString(DebuggerBundle.message("compilation.failed"))))
    compilationProcess.addTerminationCallback { _ => // TODO: do not ignore possible exception
      val foundErrors = errors.result()
      result = if (foundErrors.nonEmpty) Left(foundErrors) else Right(classfiles(outputDir))
    }
    compilationProcess.run()
    result
  }
}
