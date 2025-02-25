package org.jetbrains.plugins.scala.lang.psi.impl.base
package types

import com.intellij.lang.ASTNode
import com.intellij.psi._
import com.intellij.psi.scope.PsiScopeProcessor
import org.jetbrains.plugins.scala.caches.{BlockModificationTracker, cached}
import org.jetbrains.plugins.scala.externalLibraries.kindProjector.KindProjectorUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.types._
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAliasDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypeParametersOwner
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory.createTypeElementFromText
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementImpl
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.ScSyntheticClass
import org.jetbrains.plugins.scala.lang.psi.types._
import org.jetbrains.plugins.scala.lang.psi.types.api.Any
import org.jetbrains.plugins.scala.lang.psi.types.recursiveUpdate.ScSubstitutor
import org.jetbrains.plugins.scala.lang.psi.types.result._
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult

import scala.annotation.tailrec

class ScParameterizedTypeElementImpl(node: ASTNode) extends ScalaPsiElementImpl(node) with ScParameterizedTypeElement {
  override def desugarizedText: String = {
    val inlineSyntaxIds = KindProjectorUtil.syntaxIdsFor(this).toSet

    def kindProjectorFunctionSyntax(elem: ScTypeElement): String = {
      def convertParameterized(param: ScParameterizedTypeElement): String = {
        param.typeElement.getText match {
          case v@("+" | "-") => //λ[(-[A], +[B]) => Function2[A, Int, B]]
            param.typeArgList.typeArgs match {
              case Seq(simple) => v ++ simple.getText
              case _           => "" //should have only one type arg
            }
          case _ => param.getText //it's a higher kind type
        }
      }

      def convertSimpleType(simple: ScSimpleTypeElement) = simple.getText.replaceAll("`", "")

      elem match {
        case fun: ScFunctionalTypeElement =>
          fun.returnTypeElement match {
            case Some(ret) =>
              val typeName = "Λ$"
              val paramText = fun.paramTypeElement match {
                case tuple: ScTupleTypeElement =>
                  val paramList = tuple.components.map {
                    case parameterized: ScParameterizedTypeElement => convertParameterized(parameterized)
                    case simple: ScSimpleTypeElement => convertSimpleType(simple)
                    case _ => return null //something went terribly wrong
                  }
                  paramList.mkString(sep = ", ")
                case simple: ScSimpleTypeElement => simple.getText.replaceAll("`", "")
                case parameterized: ScParameterizedTypeElement => convertParameterized(parameterized)
                case _ => return null
              }
              s"({type $typeName[$paramText] = ${ret.getText}})#$typeName"
            case _ => null
          }
        case _ => null
      }
    }

    def kindProjectorInlineSyntax = {
      def generateName(i: Int): String = {
        //kind projector generates names the same way
        val res = ('α' + (i % 25)).toChar.toString
        if (i < 25) res
        else res + (i / 25)
      }

      val (paramOpt: Seq[Option[String]], body: Seq[String]) = typeArgList.typeArgs.zipWithIndex.map {
        case (simple: ScSimpleTypeElement, i) if inlineSyntaxIds.contains(simple.getText) =>
          val name = generateName(i)
          val placeholderSymbol = simple.getText.takeRight(1)
          (Some(simple.getText.replace(placeholderSymbol, name)), name)
        case (param: ScParameterizedTypeElement, i) if inlineSyntaxIds.contains(param.typeElement.getText) =>
          val name = generateName(i)
          val placeholderSymbol = param.typeElement.getText.takeRight(1)
          (Some(param.getText.replace(placeholderSymbol, name)), name)
        case (a, _) => (None, a.getText)
      }.unzip
      val paramText = paramOpt.flatten.mkString(start = "[", sep = ", ", end = "]")
      val bodyText = body.mkString(start = "[", sep = ", ", end = "]")

      s"({type ${"Λ$"}$paramText = ${typeElement.getText}$bodyText})#${"Λ$"}"
    }

    def existentialType = {
      val forSomeBuilder = new StringBuilder
      var count = 1
      forSomeBuilder.append(" forSome {")
      val typeElements = typeArgList.typeArgs.map {
        case w: ScWildcardTypeElement =>
          forSomeBuilder.append("type _" + "$" + count +
            w.lowerTypeElement.fold("")(te => s" >: ${te.getText}") +
            w.upperTypeElement.fold("")(te => s" <: ${te.getText}"))
          forSomeBuilder.append("; ")
          val res = s"_$$$count"
          count += 1
          res
        case t => t.getText
      }
      forSomeBuilder.delete(forSomeBuilder.length - 2, forSomeBuilder.length)
      forSomeBuilder.append("}")
      s"(${typeElement.getText}${typeElements.mkString("[", ", ", "]")} ${forSomeBuilder.toString()})"
    }

    val kindProjectorEnabled = this.kindProjectorEnabled
    val isKindProjectorFunctionSyntax =
      typeElement.getText match {
        case "Lambda" | "λ" if kindProjectorEnabled => true
        case _                                      => false
      }

    @tailrec
    def isKindProjectorInlineSyntax(element: PsiElement): Boolean = {
      element match {
        case simple: ScSimpleTypeElement if kindProjectorEnabled && inlineSyntaxIds.contains(simple.getText) => true
        case parametrized: ScParameterizedTypeElement if kindProjectorEnabled =>
          isKindProjectorInlineSyntax(parametrized.typeElement)
        case _ => false
      }
    }

    typeArgList.typeArgs.find {
      case _: ScFunctionalTypeElement if isKindProjectorFunctionSyntax => true
      case e if isKindProjectorInlineSyntax(e)                         => true
      case _: ScWildcardTypeElementImpl                                => true
      case _                                                           => false
    } match {
      case Some(fun) if isKindProjectorFunctionSyntax => kindProjectorFunctionSyntax(fun)
      case Some(e) if isKindProjectorInlineSyntax(e)  => kindProjectorInlineSyntax
      case Some(_)                                    => existentialType
      case _                                          => null
    }
  }

  //computes desugarized type either for existential type or one of kind projector types
  override def computeDesugarizedType: Option[ScTypeElement] = _computeDesugarizedType()

  private val _computeDesugarizedType = cached("ScParameterizedTypeElementImpl.computeDesugarizedType", BlockModificationTracker(this), () => {
    Option(desugarizedText) match {
      case Some(text) => Option(createTypeElementFromText(text, getContext, this))
      case _ => None
    }
  })

  override protected def innerType: TypeResult = {
    computeDesugarizedType match {
      case Some(typeElement) =>
        return typeElement.`type`()
      case _ =>
    }
    val tr = typeElement.`type`()
    val res = tr.getOrElse(return tr)

    //todo: possible refactoring to remove parameterized type inference in simple type
    typeElement match {
      case s: ScSimpleTypeElement =>
        s.reference match {
          case Some(ref) =>
            if (ref.isConstructorReference) {
              ref.resolveNoConstructor match {
                case Array(ScalaResolveResult(to: ScTypeParametersOwner, _: ScSubstitutor))
                  if to.isInstanceOf[PsiNamedElement] =>
                  return tr //all things were done in ScSimpleTypeElementImpl.innerType
                case Array(ScalaResolveResult(to: PsiTypeParameterListOwner, _: ScSubstitutor))
                  if to.isInstanceOf[PsiNamedElement] =>
                  return tr //all things were done in ScSimpleTypeElementImpl.innerType
                case _ =>
              }
            }
            ref.bind() match {
              case Some(ScalaResolveResult(_: PsiMethod, _)) =>
                return tr //all things were done in ScSimpleTypeElementImpl.innerType
              case _ =>
            }
          case _ =>
        }
      case _ =>
    }

    val typeArgs = typeArgList.typeArgs.map(_.`type`().getOrAny)

    if (typeArgs.isEmpty) tr
    else                  Right(ScParameterizedType(res, typeArgs))
  }

  override protected def acceptScala(visitor: ScalaElementVisitor): Unit = {
    visitor.visitParameterizedTypeElement(this)
  }

  override def processDeclarations(processor: PsiScopeProcessor,
                                   state: ResolveState,
                                   lastParent: PsiElement,
                                   place: PsiElement): Boolean = {
    if (this.kindProjectorPluginEnabled) {
      computeDesugarizedType match {
        case Some(projection: ScTypeProjection) =>
          projection.typeElement match {
            case paren: ScParenthesisedTypeElement => paren.innerElement match {
              case Some(compound: ScCompoundTypeElement) =>
                compound.refinement match {
                  case Some(ref) => ref.types match {
                    case Seq(alias: ScTypeAliasDefinition) =>
                      for (tp <- alias.typeParameters) {
                        val text = tp.getText
                        val lowerBound = text.indexOf(">:")
                        val upperBound = text.indexOf("<:")
                        //we have to call processor execute so both `+A` and A resolve: Lambda[`+A` => (A, A)]
                        processor.execute(tp, state)
                        processor.execute(new ScSyntheticClass(s"`$text`", Any), state)
                        if (lowerBound < 0 && upperBound > 0) {
                          processor.execute(new ScSyntheticClass(text.substring(0, upperBound), Any), state)
                        } else if (upperBound < 0 && lowerBound > 0) {
                          processor.execute(new ScSyntheticClass(text.substring(0, lowerBound), Any), state)
                        } else if (upperBound > 0 && lowerBound > 0) {
                          val actualText = text.substring(0, math.min(lowerBound, upperBound))
                          processor.execute(new ScSyntheticClass(actualText, Any), state)
                        }
                      }
                    case _ =>
                  }
                  case _ =>
                }
              case _ =>
            }
            case _ =>
          }
          processor.execute(new ScSyntheticClass("+", Any), state)
          processor.execute(new ScSyntheticClass("-", Any), state)
        case _ =>
      }
    }
    super.processDeclarations(processor, state, lastParent, place)
  }
}
