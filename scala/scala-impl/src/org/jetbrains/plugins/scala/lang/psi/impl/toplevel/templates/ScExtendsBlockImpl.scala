package org.jetbrains.plugins.scala.lang.psi.impl.toplevel
package templates

import com.intellij.lang.ASTNode
import com.intellij.psi.{PsiClass, PsiNamedElement}
import org.jetbrains.plugins.scala.JavaArrayFactoryUtil.{ScDerivesClauseFactory, ScTemplateParentsFactory}
import org.jetbrains.plugins.scala.caches.{BlockModificationTracker, ModTracker, cached, cachedInUserData}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType._
import org.jetbrains.plugins.scala.lang.psi.api.base.types._
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScNewTemplateDefinition
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScEnumCase, ScEnumCases, ScExtension, ScFunction, ScTypeAlias, ScTypeAliasDefinition, ScValueOrVariable}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScEarlyDefinitions, ScNamedElement}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.ScSyntheticClass
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.templates.ScExtendsBlockImpl._
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.SyntheticMembersInjector
import org.jetbrains.plugins.scala.lang.psi.impl.{ScalaPsiElementFactory, ScalaPsiManager, ScalaStubBasedElementImpl}
import org.jetbrains.plugins.scala.lang.psi.stubs.ScExtendsBlockStub
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.ScDesignatorType
import org.jetbrains.plugins.scala.lang.psi.types._
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult
import org.jetbrains.plugins.scala.project.{ProjectContext, ScalaLanguageLevel}

import scala.collection.mutable.ArrayBuffer

class ScExtendsBlockImpl private(stub: ScExtendsBlockStub, node: ASTNode)
  extends ScalaStubBasedElementImpl(stub, EXTENDS_BLOCK, node) with ScExtendsBlock {

  def this(node: ASTNode) = this(null, node)

  def this(stub: ScExtendsBlockStub) = this(stub, null)

  override def toString: String = "ExtendsBlock"

  override def templateBody: Option[ScTemplateBody] = _templateBody()

  private val _templateBody = cached("ScExtendsBlockImpl.templateBody", ModTracker.anyScalaPsiChange, () => {
    def childStubTemplate(stub: ScExtendsBlockStub) =
      Option(stub.findChildStubByType(TEMPLATE_BODY))
        .map(_.getPsi)

    def lastChildTemplateBody = getLastChild match {
      case tb: ScTemplateBody => Some(tb)
      case _ => None
    }

    byPsiOrStub(lastChildTemplateBody)(childStubTemplate)
  })

  override def getOrCreateTemplateBody: ScTemplateBody = templateBody.getOrElse(createEmptyTemplateBody)

  override def empty: Boolean = getNode.getFirstChildNode == null

  override def selfType: Option[ScType] =
    selfTypeElement.flatMap {
      _.typeElement
    }.flatMap {
      _.`type`().toOption
    }

  override def superTypes: List[ScType] = cachedInUserData("ScExtendsBlockImpl.superTypes", this, ModTracker.libraryAware(this)) {
    val buffer = ArrayBuffer.empty[ScType]

    val stdTypes = projectContext.stdTypes
    import stdTypes._

    def addType(t: ScType): Unit = {
      t match {
        case ScCompoundType(comps, _, _) => comps.foreach(addType)
        case _ => buffer += t
      }
    }

    templateParents match {
      case Some(parents: ScTemplateParents) => parents.superTypes.foreach(addType)
      case _ => syntheticTypeElements.map(_.`type`().getOrAny).foreach(addType)
    }

    if (isUnderCaseClass && !isInDesugaredEnumCase) {
      buffer ++= scalaProduct
      buffer ++= scalaSerializable
    }

    if (isEnumDefinition) {
      buffer ++= scalaReflectEnum
    }

    def extract(scType: ScType): Boolean = {
      scType.extractClass match {
        case Some(_: ScObject) => true
        case Some(_: ScTrait) => false
        case Some(_: ScClass) => true
        case Some(c: PsiClass) if !c.isInterface => true
        case _ => false
      }
    }

    val findResult = buffer.find {
      case AnyVal | AnyRef | Any => true
      case t                     => extract(t)
    }

    findResult match {
      case Some(AnyVal) => //do nothing
      case res@(Some(AnyRef) | Some(Any)) =>
        buffer -= res.get
        buffer ++= javaObject
      case Some(_) => //do nothing
      case _ =>
        buffer ++= javaObject
    }
    buffer.toList
  }

  private def cachedClass(name: String): Option[PsiClass] =
    ScalaPsiManager.instance(getProject).getCachedClass(getResolveScope, name)

  private def scalaProductClass: Option[PsiClass] = cachedClass(ScalaProduct)

  private def scalaSerializableClass: Option[PsiClass] =
    if (this.scalaLanguageLevelOrDefault >= ScalaLanguageLevel.Scala_2_13)
      cachedClass(JavaSerializable)
    else cachedClass(ScalaSerializable)

  private def scalaSerializable: Option[ScType] = scalaSerializableClass.map(ScalaType.designator)

  private def javaObjectClass: Option[PsiClass]        = cachedClass(JavaLangObject)
  private def scalaReflectEnumClass: Option[PsiClass]  = cachedClass(ScalaReflectEnum)

  private def scalaProduct: Option[ScType]         = scalaProductClass.map(ScalaType.designator)
  private def scalaReflectEnum: Option[ScType]     = scalaReflectEnumClass.map(ScalaType.designator)
  private def javaObject: Option[ScDesignatorType] = javaObjectClass.map(ScDesignatorType(_))

  override def isAnonymousClass: Boolean =
    getParent match {
      case _: ScNewTemplateDefinition => templateBody.isDefined
      case _ => false
    }

  def syntheticTypeElements: Seq[ScTypeElement] = _syntheticTypeElements()

  private val _syntheticTypeElements = cached("ScExtendsBlockImpl.syntheticTypeElements", BlockModificationTracker(this), () => {
    if (templateParents.nonEmpty) Seq.empty //will be handled separately
    else getContext match {
      case td: ScTypeDefinition => SyntheticMembersInjector.injectSupers(td)
      case _ => Seq.empty
    }
  })

  override def supers: Seq[PsiClass] = cachedInUserData("ScExtendsBlockImpl.supers", this, ModTracker.libraryAware(this)) {
    val typeElements = templateParents.fold(syntheticTypeElements) {
      _.allTypeElements
    }

    val buffer = ArrayBuffer[PsiClass]()
    buffer ++= ScExtendsBlockImpl.extractSupers(typeElements)

    if (isUnderCaseClass) {
      buffer ++= scalaProductClass
      buffer ++= scalaSerializableClass
    }

    if (isEnumDefinition) buffer ++= scalaReflectEnumClass

    buffer.find {
      case _: ScSyntheticClass => true
      case _: ScObject => true
      case _: ScTrait => false
      case _: ScClass => true
      case c: PsiClass if !c.isInterface => true
      case _ => false
    } match {
      case Some(s: ScSyntheticClass) if s.stdType.isAnyVal => //do nothing
      case Some(s: ScSyntheticClass) if s.stdType.isAnyRef || s.stdType.isAny =>
        buffer -= s
        buffer ++= javaObjectClass
      case Some(_: PsiClass) => //do nothing
      case _ =>
        buffer ++= javaObjectClass
    }
    buffer.toSeq
  }

  def nameId: Null = null

  override def members: Seq[ScMember] =
    templateBodies.flatMap(_.members) ++
      earlyDefinitions.toSeq.flatMap(_.members)

  override def typeDefinitions: Seq[ScTypeDefinition]     = templateBodies.flatMap(_.typeDefinitions)
  override def aliases: Seq[ScTypeAlias]                  = templateBodies.flatMap(_.aliases)
  override def cases: Seq[ScEnumCases]                    = templateBodies.flatMap(_.cases)
  override def functions: Seq[ScFunction]                 = templateBodies.flatMap(_.functions)
  override def properties: Seq[ScValueOrVariable]         = templateBodies.flatMap(_.properties)
  override def selfTypeElement: Option[ScSelfTypeElement] = templateBody.flatMap(_.selfTypeElement)
  override def extensions: Seq[ScExtension]               = templateBodies.flatMap(_.extensions)

  override def templateParents: Option[ScTemplateParents] =
    getStubOrPsiChildren(TEMPLATE_PARENTS, ScTemplateParentsFactory).headOption

  override def derivesClause: Option[ScDerivesClause] =
    getStubOrPsiChildren(DERIVES_CLAUSE, ScDerivesClauseFactory).headOption

  override def earlyDefinitions: Option[ScEarlyDefinitions] =
    this.stubOrPsiChild(EARLY_DEFINITIONS)

  override def addEarlyDefinitions(): ScEarlyDefinitions = {
    earlyDefinitions.getOrElse {
      val text = "class A extends {} with B {}"
      val templDef = ScalaPsiElementFactory.createTemplateDefinitionFromText(text, getParentByStub.getContext, getParentByStub)
      val extBlock = templDef.extendsBlock
      val kExtends = extBlock.children.find(_.getNode.getElementType == ScalaTokenTypes.kEXTENDS).get
      val kWith = extBlock.children.find(_.getNode.getElementType == ScalaTokenTypes.kWITH).get
      val firstElem = if (templateParents.isEmpty) kExtends else kExtends.getNextSibling
      val anchor = if (templateParents.isEmpty) getFirstChild else templateParents.get
      this.addRangeBefore(firstElem, kWith, anchor)

      earlyDefinitions.get
    }
  }

  override def isUnderCaseClass: Boolean = getParentByStub match {
    case td: ScTypeDefinition if td.isCase => true
    case _                                 => false
  }

  override def isEnumDefinition: Boolean = getParentByStub match {
    case _: ScEnum => true
    case _         => false
  }

  private def isInDesugaredEnumCase: Boolean = getParentByStub match {
    case cse: ScEnumCase       => true
    case named: ScNamedElement => ScEnumCase.isDesugaredEnumCase(named)
    case _                     => false
  }

  private def templateBodies = templateBody.toSeq

  private def createEmptyTemplateBody: ScTemplateBody =
    add(ScalaPsiElementFactory.createTemplateBody(getParentByStub.is[ScGivenDefinition], this))
      .asInstanceOf[ScTemplateBody]
}

object ScExtendsBlockImpl {
  private val ScalaProduct      = "scala.Product"
  private val ScalaSerializable = "scala.Serializable"
  private val JavaSerializable  = "java.io.Serializable"
  private val ScalaReflectEnum  = "scala.reflect.Enum"
  private val JavaLangObject    = "java.lang.Object"

  private def extractSupers(typeElements: Seq[ScTypeElement])
                           (implicit project: ProjectContext): Seq[PsiClass] =
    typeElements.flatMap {
      case typeElement@ScSimpleTypeElement.unwrapped(reference) =>
        reference.resolveNoConstructor match {
          case Array(ScalaResolveResult(clazz: PsiClass, _)) =>
            Some(clazz)
          case Array(ScalaResolveResult(typeAlias: ScTypeAliasDefinition, subst)) =>
            tail(typeAlias.aliasedType.map(subst))
          case _ =>
            tail(typeElement.`type`())
        }
      case element => tail(element.`type`())
    }

  private[this] def tail(typeResult: result.TypeResult) =
    typeResult.toOption.flatMap(_.extractClass)
}
