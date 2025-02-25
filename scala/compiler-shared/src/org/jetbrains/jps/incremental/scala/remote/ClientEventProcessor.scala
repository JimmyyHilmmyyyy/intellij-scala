package org.jetbrains.jps.incremental.scala
package remote

/**
 * @see `org.jetbrains.jps.incremental.scala.remote.EventGeneratingClient`
 */
class ClientEventProcessor(client: Client) {

  def process(event: Event): Unit = {
    event match {
      case MessageEvent(kind, text, source, pointer, problemStart, problemEnd) =>
        client.message(kind, text, source, pointer, problemStart, problemEnd)

      case ProgressEvent(text, done) =>
        client.progress(text, done)

      case InternalDebugEvent(text) =>
        client.internalDebug(text)

      case InternalInfoEvent(text) =>
        client.internalInfo(text)

      case InternalTraceEvent(text) =>
        client.internalTrace(text)

      case TraceEvent(exceptionClassName, message, stackTrace) =>
        client.trace(new ServerException(exceptionClassName, message, stackTrace))

      case GeneratedEvent(source, module, name) =>
        client.generated(source, module, name)

      case DeletedEvent(module) =>
        client.deleted(module)

      case CompilationStartEvent() =>
        client.compilationStart()

      case CompilationPhaseEvent(name) =>
        client.compilationPhase(name)

      case CompilationUnitEvent(path) =>
        client.compilationUnit(path)

      case CompilationEndEvent(sources) =>
        client.compilationEnd(sources)

      case ProcessingEndEvent() =>
        client.processingEnd()

      case WorksheetOutputEvent(text) =>
        client.worksheetOutput(text)

      case CompilationStartedInSbtEvent(file) =>
        client.sourceStarted(file)

      case MetricsEvent(metrics) =>
        client.metrics(metrics)
    }
  }
}

// field is called `stackTraceElements` not to confuse with `Throwable.stackTrace`
private class ServerException(exceptionClassName: String,
                              message: String,
                              stackTraceElements: Array[StackTraceElement]) extends Exception(message, null) {

  setStackTrace(stackTraceElements)

  override def toString: String = {
    val message = getLocalizedMessage
    val reason = if (message != null) s": $message" else ""
    s"$exceptionClassName$reason"
  }
}
