package org.jetbrains.plugins.scala.testingSupport.scalatest.scala2_10.scalatest1_9_2

import org.jetbrains.plugins.scala.SlowTests
import org.jetbrains.plugins.scala.testingSupport.scalatest.fileStructureView._
import org.junit.experimental.categories.Category

/**
 * @author Roman.Shein
 * @since 21.04.2015.
 */
@Category(Array(classOf[SlowTests]))
class Scalatest2_10_1_9_2_StructureViewTest extends Scalatest2_10_1_9_2_Base with FeatureSpecFileStructureViewTest with FlatSpecFileStructureViewTest
with FreeSpecFileStructureViewTest with FunSuiteFileStructureViewTest with PropSpecFileStructureViewTest
