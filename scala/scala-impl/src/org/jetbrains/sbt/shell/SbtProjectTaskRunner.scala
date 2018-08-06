package org.jetbrains.sbt.shell

import java.io.File
import java.util
import java.util.UUID

import com.intellij.build.events.{SuccessResult, Warning}
import com.intellij.compiler.impl.CompilerUtil
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.debugger.ui.HotSwapUI
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.compiler.ex.CompilerPathsEx
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.{ExternalSystemUtil, ExternalSystemApiUtil => ES}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.{Module, ModuleType}
import com.intellij.openapi.progress.{PerformInBackgroundOption, ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.task._
import org.jetbrains.annotations.Nullable
import org.jetbrains.plugins.scala.build.{BuildMessages, BuildWarning, IndicatorReporter}
import org.jetbrains.plugins.scala.extensions
import org.jetbrains.sbt.SbtUtil
import org.jetbrains.sbt.project.SbtProjectSystem
import org.jetbrains.sbt.project.module.SbtModuleType
import org.jetbrains.sbt.settings.SbtSystemSettings
import org.jetbrains.sbt.shell.SbtShellCommunication._

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
  * Created by jast on 2016-11-25.
  */
class SbtProjectTaskRunner extends ProjectTaskRunner {

  // will override the usual jps build thingies
  override def canRun(projectTask: ProjectTask): Boolean = projectTask match {
    case task: ModuleBuildTask =>
      val module = task.getModule
      val project = task.getModule.getProject
      val projectSettings = SbtSystemSettings.getInstance(project).getLinkedProjectSettings(module)

      projectSettings.exists(_.useSbtShellForBuild) &&
      ES.isExternalSystemAwareModule(SbtProjectSystem.Id, module)

    case _: ExecuteRunConfigurationTask =>
      // TODO this includes tests (and what else?). sbt should handle it and test output should be parsed
      false
    case _ => false
  }

  override def run(project: Project,
                   context: ProjectTaskContext,
                   callback: ProjectTaskNotification,
                   tasks: util.Collection[_ <: ProjectTask]): Unit = {

    val validTasks = tasks.asScala.collect {
      // TODO Android AARs are currently imported as modules. need a way to filter them away before building
      case task: ModuleBuildTask
        // SbtModuleType actually denotes `-build` modules, which are not part of the regular build
        if ModuleType.get(task.getModule).getId != SbtModuleType.Id =>
          task
    }

    // the "build" button in IDEA always runs the build for all individual modules,
    // and may work differently than just calling the products task from the main module in sbt
    val moduleCommands = validTasks.flatMap(buildCommands)
    val modules = validTasks.map(_.getModule)

    // don't run anything if there's no module to run a build for
    // TODO user feedback
    val callbackOpt = Option(callback)
    if (moduleCommands.isEmpty){
      val taskResult = new ProjectTaskResult(false, 0, 0)
      callbackOpt.foreach(_.finished(taskResult))
    } else {

      val command =
        if (moduleCommands.size == 1) moduleCommands.head
        else moduleCommands.mkString("all ", " ", "")

      FileDocumentManager.getInstance().saveAllDocuments()

      // run this as a task (which blocks a thread) because it seems non-trivial to just update indicators asynchronously?
      val task = new CommandTask(project, modules.toArray, command, callbackOpt)
      ProgressManager.getInstance().run(task)
    }
  }

  private def buildCommands(task: ModuleBuildTask): Seq[String] = {
    // TODO sensible way to find out what scopes to run it for besides compile and test?
    // TODO make tasks should be user-configurable
    SbtUtil.getSbtModuleData(task.getModule).toSeq.flatMap { sbtModuleData =>
      val scope = SbtUtil.makeSbtProjectId(sbtModuleData)
      // `products` task is a little more general than just `compile`
      Seq(s"$scope/products", s"$scope/test:products")
    }
  }

  @Nullable
  override def createExecutionEnvironment(project: Project,
                                          task: ExecuteRunConfigurationTask,
                                          executor: Executor): ExecutionEnvironment = {

    val taskSettings = new ExternalSystemTaskExecutionSettings
    val executorId = Option(executor).map(_.getId).getOrElse(DefaultRunExecutor.EXECUTOR_ID)

    ExternalSystemUtil.createExecutionEnvironment(
      project,
      SbtProjectSystem.Id,
      taskSettings, executorId
    )
  }

}

private class CommandTask(project: Project, modules: Array[Module], command: String, callbackOpt: Option[ProjectTaskNotification]) extends
  Task.Backgroundable(project, "sbt build", false, PerformInBackgroundOption.ALWAYS_BACKGROUND) {

  import CommandTask._

  private val taskId: UUID = UUID.randomUUID()
  private val shellRunner: SbtShellRunner = SbtProcessManager.forProject(project).acquireShellRunner

  private def showShell(): Unit =
    shellRunner.openShell(false)

  override def run(indicator: ProgressIndicator): Unit = {
    import org.jetbrains.plugins.scala.lang.macros.expansion.ReflectExpansionsCollector

    val report = new IndicatorReporter(indicator)
    val shell = SbtShellCommunication.forProject(project)
    val collector = ReflectExpansionsCollector.getInstance(project)

    report.start()
    collector.compilationStarted()

    // TODO build events instead of indicator
    val resultAggregator: (BuildMessages, ShellEvent) => BuildMessages = { (messages,event) =>

      event match {
        case TaskStart =>
          // handled for main task
          messages
        case TaskComplete =>
          // handled for main task
          messages
        case ErrorWaitForInput =>
          // can only actually happen during reload, but handle it here to be sure
          showShell()
          report.error("build interrupted", None)
          messages.addError("ERROR: build interrupted")
          messages
        case Output(raw) =>
          val text = raw.trim

          val messagesWithErrors = if (text startsWith ERROR_PREFIX) {
            val msg = text.stripPrefix(ERROR_PREFIX)
            // only report first error until we can get a good mapping message -> error
            if (messages.errors.isEmpty) {
              showShell()
              report.error("errors in build", None)
            }
            messages.addError(msg)
          } else if (text startsWith WARN_PREFIX) {
            val msg = text.stripPrefix(WARN_PREFIX)
            // only report first warning
            if (messages.warnings.isEmpty) {
              report.warning("warnings in build", None)
            }
            messages.addWarning(msg)
          } else messages

          collector.processCompilerMessage(text)

          report.log(text)

          messagesWithErrors.appendMessage(text)
      }
    }

    // TODO consider running module build tasks separately
    // may require collecting results individually and aggregating
    val commandFuture = shell.command(command, BuildMessages.empty, resultAggregator, showShell = true)

    // block thread to make indicator available :(
    val buildMessages = Await.ready(commandFuture, Duration.Inf).value.get

    // build effects
    refreshRoots(modules, indicator)

    // handle callback
    buildMessages match {
      case Success(messages) =>
        val taskResult = new ProjectTaskResult(messages.aborted, messages.errors.size, messages.warnings.size)
        callbackOpt.foreach(_.finished(taskResult))
      case Failure(_) =>
        val failedResult = new ProjectTaskResult(true, 1, 0)
        callbackOpt.foreach(_.finished(failedResult))
    }

    // build state reporting
    buildMessages match {
      case Success(messages) => report.finish(messages)
      case Failure(err) => report.finishWithFailure(err)
    }

    // reload changed classes
    val debuggerSession = DebuggerManagerEx.getInstanceEx(project).getContext.getDebuggerSession
    val debuggerSettings = DebuggerSettings.getInstance
    if (debuggerSession != null &&
      debuggerSession.isAttached &&
      debuggerSettings.RUN_HOTSWAP_AFTER_COMPILE == DebuggerSettings.RUN_HOTSWAP_ALWAYS) {
      extensions.invokeLater {
        HotSwapUI.getInstance(project).reloadChangedClasses(debuggerSession, false)
      }
    }

    collector.compilationFinished()
  }


  // remove this if/when external system handles this refresh on its own
  private def refreshRoots(modules: Array[Module], indicator: ProgressIndicator): Unit = {
    indicator.setText("Synchronizing output directories...")

    // simply refresh all the source roots to catch any generated files -- this MAY have a performance impact
    // in which case it might be necessary to receive the generated sources directly from sbt and refresh them (see BuildManager)
    val info = ProjectDataManager.getInstance().getExternalProjectData(project,SbtProjectSystem.Id, project.getBasePath)
    val allSourceRoots = ES.findAllRecursively(info.getExternalProjectStructure, ProjectKeys.CONTENT_ROOT)
    val generatedSourceRoots = allSourceRoots.asScala.flatMap { node =>
      val data = node.getData
      // sbt-side generated sources are still imported as regular sources
      val generated = data.getPaths(ExternalSystemSourceType.SOURCE_GENERATED).asScala
      val regular = data.getPaths(ExternalSystemSourceType.SOURCE).asScala
      generated ++ regular
    }.map(_.getPath).toSeq.distinct

    val outputRoots = CompilerPathsEx.getOutputPaths(modules)
    val toRefresh = generatedSourceRoots ++ outputRoots

    CompilerUtil.refreshOutputRoots(toRefresh.asJavaCollection)
    val toRefreshFiles = toRefresh.map(new File(_)).asJava
    LocalFileSystem.getInstance().refreshIoFiles(toRefreshFiles, true, true, null)

    indicator.setText("")
  }
}

object CommandTask {

  // some code duplication here with SbtStructureDump
  private val WARN_PREFIX = "[warn]"
  private val ERROR_PREFIX = "[error]"

}

private case class SbtBuildResult(warnings: Seq[String] = Seq.empty) extends SuccessResult {
  override def isUpToDate = false
  override def getWarnings: util.List[Warning] = warnings.map(BuildWarning.apply(_) : Warning).asJava
}
