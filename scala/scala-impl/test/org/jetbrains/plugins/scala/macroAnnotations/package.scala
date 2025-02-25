package org.jetbrains.plugins.scala

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import org.jetbrains.plugins.scala.caches.stats.Tracer
import org.junit.Assert._

import scala.jdk.CollectionConverters._

package object macroAnnotations {
  def incModCount(project: Project): Unit = {
    val manager = PsiManager.getInstance(project)
    // TODO manager.getModificationTracker.asInstanceOf[PsiModificationTrackerImpl].incCounter()
    manager.dropPsiCaches()
  }

  def checkTracer(name: String, totalCount: Int, actualCount: Int)(body: => Unit): Unit = {
    Tracer.clearAll()
    Tracer.setEnabled(true)
    try {
      body
      checkTracerHas(name, totalCount, actualCount)
    } finally {
      Tracer.setEnabled(false)
    }
  }

  private def checkTracerHas(name: String, totalCount: Int, actualCount: Int): Unit = {
    val dataWithName = Tracer.getCurrentData.asScala.filter(_.name == name)
    assertTrue(s"No tracer data with name $name", dataWithName.nonEmpty)
    for {
      data <- dataWithName
    } {
      assertEquals("Wrong total count:", totalCount, data.totalCount)
      assertEquals("Wrong number of actual computations:", actualCount, data.actualCount)
    }
  }

}
