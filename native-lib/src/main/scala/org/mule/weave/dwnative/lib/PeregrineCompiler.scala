package org.mule.weave.dwnative.lib

import org.mule.weave.v2.codegen.CodeGenerator
import org.mule.weave.v2.grammar.BinaryOpIdentifier
import org.mule.weave.v2.grammar.DynamicSelectorOpId
import org.mule.weave.v2.grammar.ValueSelectorOpId
import org.mule.weave.v2.parser.MappingParser
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.header.directives.ImportDirective
import org.mule.weave.v2.parser.ast.header.directives.VersionDirective
import org.mule.weave.v2.parser.ast.operators.BinaryOpNode
import org.mule.weave.v2.parser.ast.selectors.NullSafeNode
import org.mule.weave.v2.parser.ast.selectors.NullUnSafeNode
import org.mule.weave.v2.parser.ast.structure.DocumentNode
import org.mule.weave.v2.parser.ast.structure.NameNode
import org.mule.weave.v2.parser.ast.structure.StringNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.ast.variables.VariableReferenceNode
import org.mule.weave.v2.sdk.ParsingContextFactory
import org.mule.weave.v2.sdk.WeaveResource

class PeregrineCompiler {

  val ATTRIBUTES_NAME = "attributes"
  val HEADERS_NAME = "headers"

  def compile(script: String): PeregrineCompilationResult = {
    val parse = MappingParser.parse(MappingParser.parsingPhase, WeaveResource.anonymous(script), ParsingContextFactory.createParsingContext)
    if (parse.hasErrors()) {
      FailurePeregrineCompilationResult(parse.messages().errorMessageString())
    } else {
      val astNode = parse.getResult().astNode
      compile(astNode)
    }
  }

  def isValueSelector(opId: BinaryOpIdentifier, rhs: AstNode): Boolean = {
    opId match {
      case DynamicSelectorOpId => rhs.isInstanceOf[StringNode]
      case ValueSelectorOpId => true
      case _ => false
    }
  }

  def isSelectingAttribute(lhs: AstNode, attributeName: String): Boolean = {
    lhs match {
      case BinaryOpNode(opId, lhs, rhs) if (isValueSelector(opId, rhs)) => {
        lhs match {
          case VariableReferenceNode(variable) if (variable.name == ATTRIBUTES_NAME) => {
            rhs match {
              case StringNode(`attributeName`) => true
              case NameNode(StringNode(`attributeName`), None) => true
              case _ => false
            }
          }
          case _ => false
        }
      }
      case _ => false
    }
  }

  private def compile(astNode: AstNode): PeregrineCompilationResult = {
    astNode match {
      case dn: DocumentNode => {
        val invalidHeaders = dn.header.directives.filter((dn) => dn match {
          case _: VersionDirective => false
          case id: ImportDirective if (id.importedModule.elementName == NameIdentifier.CORE_MODULE) => false
          case _ => true
        })
        if (invalidHeaders.nonEmpty) {
          FailurePeregrineCompilationResult(s"Directives are not supported in Peregrine Expression Language. Invalid directives:\n${invalidHeaders.map(_.location().locationString).mkString("\n")}")
        } else {
          compile(dn.root)
        }
      }
      case ns: NullSafeNode => {
        compile(ns.selector)
      }
      case ns: NullUnSafeNode => {
        //Should we do a warning here? as the semantics of null unsafe are not going to remain in PEL
        compile(ns.selector)
      }
      case BinaryOpNode(opId, lhs, rhs) if (isValueSelector(opId, rhs)) => {
        val name = rhs match {
          case StringNode(value) => Some(value)
          case NameNode(StringNode(value), _) => Some(value)
          case _ => None
        }
        if (name.isDefined && isSelectingAttribute(lhs, HEADERS_NAME)) {
          SuccessPeregrineCompilationResult("{\"header\": \"" + name.get + "\"}")
        } else if (name.isDefined && isSelectingAttribute(lhs, "queryParams")) {
          SuccessPeregrineCompilationResult("{\"queryParams\": \"" + name.get + "\"")
        } else {
          FailurePeregrineCompilationResult(s"Unable to compile: ${CodeGenerator.generate(astNode)} to PEL.")
        }
      }
      case _ => FailurePeregrineCompilationResult(s"Unable to compile: ${CodeGenerator.generate(astNode)} to PEL.")
    }
  }
}

sealed trait PeregrineCompilationResult

case class SuccessPeregrineCompilationResult(pelExpression: String) extends PeregrineCompilationResult

case class FailurePeregrineCompilationResult(reason: String) extends PeregrineCompilationResult


//object Test extends App {
//  private val peregrineCompiler = new PeregrineCompiler
//  println(peregrineCompiler.compile("attributes.queryParams['myParam']"))
//  println(peregrineCompiler.compile("attributes.headers['CORS']"))
//  println(peregrineCompiler.compile("attributes.headers.myParam"))
//  println(peregrineCompiler.compile("attributes.headers.'myParam'"))
//  println(peregrineCompiler.compile("attributes['headers']'myParam'"))
//  println(peregrineCompiler.compile("attributes['headers'][vars.a]"))
//}
