package org.jetbrains.sbt.settings

import com.intellij.openapi.externalSystem.util.{ExternalSystemSettingsControl, ExternalSystemUiUtil, PaintAwarePanel}

class SbtSettingsControl(settings: SbtSettings) extends ExternalSystemSettingsControl[SbtSettings] {

  private val pane = new SbtSettingsPane(settings.getProject)

  override def isModified: Boolean = ! {
    pane.isCustomLauncher == settings.customLauncherEnabled &&
      pane.getLauncherPath == settings.customLauncherPath &&
      pane.getMaximumHeapSize == settings.maximumHeapSize &&
      pane.getVmParameters == settings.vmParameters &&
      pane.getSbtCommandArgs == settings.sbtOptions &&
      pane.isCustomVM == settings.customVMEnabled &&
      pane.getCustomVMPath == settings.customVMPath &&
      pane.getSbtEnvironment == settings.sbtEnvironment &&
      pane.getSbtPassParentEnvironment == settings.sbtPassParentEnvironment

  }

  override def showUi(show: Boolean): Unit =
    pane.getContentPanel.setVisible(show)

  override def fillUi(canvas: PaintAwarePanel, indentLevel: Int): Unit =
    canvas.add(pane.getContentPanel, ExternalSystemUiUtil.getFillLineConstraints(indentLevel))

  override def disposeUIResources(): Unit = {}

  override def apply(settings: SbtSettings): Unit = {
    settings.customLauncherEnabled = pane.isCustomLauncher
    settings.customLauncherPath = pane.getLauncherPath
    settings.maximumHeapSize = pane.getMaximumHeapSize
    settings.vmParameters = pane.getVmParameters
    settings.sbtOptions = pane.getSbtCommandArgs
    settings.customVMEnabled = pane.isCustomVM
    settings.customVMPath = pane.getCustomVMPath
    settings.sbtEnvironment = pane.getSbtEnvironment
    settings.sbtPassParentEnvironment = pane.getSbtPassParentEnvironment
  }

  override def reset(): Unit = {
    pane.setCustomLauncherEnabled(settings.customLauncherEnabled, settings.customLauncherPath)
    pane.setMaximumHeapSize(settings.maximumHeapSize)
    pane.setMyVmParameters(settings.vmParameters)
    pane.setSbtCommandArgs(settings.sbtOptions)
    pane.setCustomVMPath(settings.customVMPath, settings.customVMEnabled)
    pane.setSbtEnvironment(settings.sbtEnvironment)
    pane.setSbtPassParentEnvironment(settings.sbtPassParentEnvironment)
  }

  override def validate(settings: SbtSettings) = true
}