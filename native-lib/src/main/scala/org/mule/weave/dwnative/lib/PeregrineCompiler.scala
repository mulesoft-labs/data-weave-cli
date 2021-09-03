package org.mule.weave.dwnative.lib

import org.mule.weave.v2.codegen.CodeGenerator
import org.mule.weave.v2.parser.MappingParser
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.conditional.{DefaultNode, IfNode}
import org.mule.weave.v2.parser.ast.functions.{FunctionCallNode, FunctionCallParametersNode}
import org.mule.weave.v2.parser.ast.header.directives.{ImportDirective, VersionDirective}
import org.mule.weave.v2.parser.ast.logical.{AndNode, OrNode}
import org.mule.weave.v2.parser.ast.operators.{BinaryOpNode, UnaryOpNode}
import org.mule.weave.v2.parser.ast.selectors.{NullSafeNode, NullUnSafeNode}
import org.mule.weave.v2.parser.ast.structure.{ArrayNode, BooleanNode, DocumentNode, LocalDateNode, LocalTimeNode, NameNode, NumberNode, StringNode}
import org.mule.weave.v2.parser.ast.variables.{NameIdentifier, VariableReferenceNode}
import org.mule.weave.v2.sdk.{ParsingContextFactory, WeaveResource}

import scala.annotation.tailrec

class PeregrineCompiler {

  val Q = "\""
  val dw2pel = Map("Dynamic Selector" -> ".", "Value Selector" -> ".", "not" -> "!")

  def compile(script: String): PeregrineCompilationResult = {
    val parse = MappingParser.parse(MappingParser.parsingPhase, WeaveResource.anonymous(script), ParsingContextFactory.createParsingContext)
    if (parse.hasErrors()) {
      FailurePeregrineCompilationResult(parse.messages().errorMessageString())
    } else {
      val astNode = parse.getResult().astNode
      compile(astNode)
    }
  }

  private def pelId(dwName: String): String = {
    dw2pel.getOrElse(dwName, dwName)
  }

  @tailrec
  private def compile(astNode: AstNode): PeregrineCompilationResult = astNode match {
    case dn: DocumentNode =>
      val invalidHeaders = dn.header.directives.filter(dn => dn match {
        case _: VersionDirective => false
        case id: ImportDirective if id.importedModule.elementName == NameIdentifier.CORE_MODULE => false
        case _ => true
      })
      if (invalidHeaders.nonEmpty) {
        FailurePeregrineCompilationResult(s"Directives are not supported in Peregrine Expression Language. Invalid directives:\n${invalidHeaders.map(_.location().locationString).mkString("\n")}")
      } else {
        compile(dn.root)
      }

    case ns: NullSafeNode =>
      compile(ns.selector)

    case ns: NullUnSafeNode =>
      //Should we do a warning here? as the semantics of null unsafe are not going to remain in PEL
      compile(ns.selector)

    case UnaryOpNode(opId, rhs) =>
      val right = compileUnsafe(rhs)
      SuccessPeregrineCompilationResult(s"[$Q${pelId(opId.name)}$Q, $right]")

    case BinaryOpNode(opId, lhs, rhs) =>
      val left = compileUnsafe(lhs)
      val right = compileUnsafe(rhs)
      SuccessPeregrineCompilationResult(s"[$Q${pelId(opId.name)}$Q, $left, $right]")

    case AndNode(lhs, rhs) =>
      val left = compileUnsafe(lhs)
      val right = compileUnsafe(rhs)
      SuccessPeregrineCompilationResult(s"[${Q}and$Q, $left, $right]")

    case OrNode(lhs, rhs) =>
      val left = compileUnsafe(lhs)
      val right = compileUnsafe(rhs)
      SuccessPeregrineCompilationResult(s"[${Q}or$Q, $left, $right]")

    case FunctionCallNode(VariableReferenceNode(fun), FunctionCallParametersNode(args)) =>
      val params = args.map(compileUnsafe).mkString(", ")
      val separator = if (params.isEmpty) "" else ", "
      SuccessPeregrineCompilationResult(s"[$Q${fun.name}$Q$separator$params]")

    case IfNode(ifExpr, condition, elseExpr) =>
      val ife = compileUnsafe(ifExpr)
      val cond = compileUnsafe(condition)
      val elsee = compileUnsafe(elseExpr)
      SuccessPeregrineCompilationResult(s"[${Q}if$Q, $cond, $ife, $elsee]")

    case DefaultNode(lhs, rhs) =>
      val left = compileUnsafe(lhs)
      val right = compileUnsafe(rhs)
      SuccessPeregrineCompilationResult(s"[${Q}default$Q, $left, $right]")

    case NameNode(node, _) => compile(node)

    case vr: VariableReferenceNode => SuccessPeregrineCompilationResult(s"[$Q:ref$Q, $Q${vr.variable.name}$Q]")

    case ArrayNode(elements) =>
      val items = elements.map(compileUnsafe).mkString(", ")
      SuccessPeregrineCompilationResult(s"[$Q:array$Q, $items]")

    case NumberNode(n) => SuccessPeregrineCompilationResult(s"[$Q:nbr$Q, $Q$n$Q]")
    case StringNode(s) => SuccessPeregrineCompilationResult(s"[$Q:str$Q, $Q$s$Q]")
    case BooleanNode(b) => SuccessPeregrineCompilationResult(s"[$Q:bool$Q, $Q$b$Q]")
    case LocalDateNode(d, _) => SuccessPeregrineCompilationResult(s"[$Q:dt$Q, $Q$d$Q]")
    case LocalTimeNode(t) => SuccessPeregrineCompilationResult(s"[$Q:dt$Q, $Q$t$Q]")

    case _ => FailurePeregrineCompilationResult(s"Unable to compile: ${CodeGenerator.generate(astNode)} to PEL.")
  }

  def compileUnsafe(node: AstNode): String = {
    compile(node) match {
      case SuccessPeregrineCompilationResult(pelExpression) => pelExpression
      case FailurePeregrineCompilationResult(reason) => throw new RuntimeException(s"compilation failure: $reason")
    }
  }

}

sealed trait PeregrineCompilationResult

case class SuccessPeregrineCompilationResult(pelExpression: String) extends PeregrineCompilationResult

case class FailurePeregrineCompilationResult(reason: String) extends PeregrineCompilationResult


object Test extends App {
  private val peregrineCompiler = new PeregrineCompiler
  private var pass = 0
  private var fail = 0

  println("\n// literals")
  check("\"hi\"", "[\":str\", \"hi\"]")
  check("123", "[\":nbr\", \"123\"]")
  check("-12.34", "[\":nbr\", \"-12.34\"]")
  check("true", "[\":bool\", \"true\"]")
  check("false", "[\":bool\", \"false\"]")
  check("|2021-09-02|", "[\":dt\", \"2021-09-02\"]")
  check("|23:57:59|", "[\":dt\", \"23:57:59\"]")
  check("[\"one\", 2, three]", "[\":array\", [\":str\", \"one\"], [\":nbr\", \"2\"], [\":ref\", \"three\"]]")

  println("\n// operators")
  check("a > b", "[\">\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a < b", "[\"<\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a >= b", "[\">=\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a <= b", "[\"<=\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a == b", "[\"==\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a != b", "[\"!=\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a ~= b", "[\"~=\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a and b", "[\"and\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("a or b", "[\"or\", [\":ref\", \"a\"], [\":ref\", \"b\"]]")
  check("!a", "[\"!\", [\":ref\", \"a\"]]")
  check("not a", "[\"!\", [\":ref\", \"a\"]]")

  println("\n// selectors")
  check("a", "[\":ref\", \"a\"]")
  check("a.b", "[\".\", [\":ref\", \"a\"], [\":str\", \"b\"]]")
  check("a.b.c", "[\".\", [\".\", [\":ref\", \"a\"], [\":str\", \"b\"]], [\":str\", \"c\"]]")
  check("a[0]", "[\".\", [\":ref\", \"a\"], [\":nbr\", \"0\"]]")
  check("a['b'][0]", "[\".\", [\".\", [\":ref\", \"a\"], [\":str\", \"b\"]], [\":nbr\", \"0\"]]")

  println("\n// functions")
  check("uuid()", "[\"uuid\"]")
  check("upper(\"hi\")", "[\"upper\", [\":str\", \"hi\"]]")
  check("1 to 5", "[\"to\", [\":nbr\", \"1\"], [\":nbr\", \"5\"]]")
  check("\"hi\" ++ \"by\"", "[\"++\", [\":str\", \"hi\"], [\":str\", \"by\"]]")
  check("upper(\"hi\" ++ [1, false])", "[\"upper\", [\"++\", [\":str\", \"hi\"], [\":array\", [\":nbr\", \"1\"], [\":bool\", \"false\"]]]]")

  println("\n// flow control")
  check("if (false) \"a\" else \"b\"", "[\"if\", [\":bool\", \"false\"], [\":str\", \"a\"], [\":str\", \"b\"]]")

  println("\n// misc")
  check("a default \"b\"", "[\"default\", [\":ref\", \"a\"], [\":str\", \"b\"]]")

  println("\n// mixed")
  check("upper(\"hi\" ++ a.b.c ++ now())", "[\"upper\", [\"++\", [\"++\", [\":str\", \"hi\"], [\".\", [\".\", [\":ref\", \"a\"], [\":str\", \"b\"]], [\":str\", \"c\"]]], [\"now\"]]]")

  println()
  println("=" * 30)
  println(s"Summary: $pass passed - $fail failed")
  println("=" * 30)
  println()

  def check(source: String, expectedTarget: String): Unit = {
    peregrineCompiler.compile(source) match {
      case SuccessPeregrineCompilationResult(pelExpression) =>
        def outputOk = {
          if (expectedTarget.equals(pelExpression)) {
            pass += 1; true
          } else {
            fail += 1; false
          }
        }

        println(if (outputOk) s"PASS|:)  DW: $source\n\t\tPEL: $expectedTarget\n"
        else s"FAIL|:(  DW: $source\n\t\t\t PEL: $pelExpression\n\t\tExpected: $expectedTarget\n")

      case FailurePeregrineCompilationResult(reason) => fail += 1; println(s"FAIL|:(  DW: $source -> compilation error: $reason\n")
    }
  }
}
