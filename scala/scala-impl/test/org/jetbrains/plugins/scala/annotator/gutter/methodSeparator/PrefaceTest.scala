package org.jetbrains.plugins.scala.annotator.gutter.methodSeparator

class PrefaceTest extends MethodSeparatorLineMarkerTestBase {
  protected override def getBasePath = super.getBasePath + "/preface/"

  def testClassAndPackage(): Unit = doTest()
  def testClassAndPackageContainer(): Unit = doTest()
  def testPackageContainerAndClass(): Unit = doTest()
  def testImportInBetween(): Unit = doTest()
}