package org.weave.deps

import java.io.File

import coursier.LocalRepositories.Dangerous
import coursier.MavenRepository
import coursier._
import coursier.cache.ArtifactError
import coursier.cache.Cache
import coursier.util.Gather
import coursier.util.Task
import org.mule.weave.v2.parser.MessageCollector
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.LiteralValueAstNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.AnnotationProcessor
import org.mule.weave.v2.scope.AstNavigator
import org.mule.weave.v2.scope.ScopesNavigator

import scala.concurrent.ExecutionContext.Implicits.global

class MavenDependencyAnnotationProcessor(weavePathUpdater: (File) => Unit) extends AnnotationProcessor {

  /**
    * Run the annotation processing logic on the scope navigation phase
    */
  override def run(annotatedNode: AstNode, astNavigator: AstNavigator, scopeNavigator: ScopesNavigator, messageCollector: MessageCollector, annotation: AnnotationNode): Unit = {
    val repositories = Seq(
      Dangerous.maven2Local,
      MavenRepository("http://repository.mulesoft.org/nexus/content/repositories/snapshots/"),
      MavenRepository("http://repository.mulesoft.org/nexus/content/repositories/releases/", changing = Some(false)),
      MavenRepository("https://repo1.maven.org/maven2"),
      Repositories.jitpack,
      Repositories.sonatype("releases")
    )
    val parts: Array[String] = annotation.args.get.args.head.value.asInstanceOf[LiteralValueAstNode].literalValue.split(':')
    println("Trying to Downloading: " + parts.mkString(" - "))
    val start = Resolution(Seq(Dependency(Module(Organization(parts(0)), ModuleName(parts(1))), parts(2))))
    val fetch = ResolutionProcess.fetch(repositories, Cache.default.fetch)
    val resolution = start.process.run(fetch).unsafeRun()
    val localArtifacts: Either[ArtifactError, File] =
      Gather[Task]
        .gather(resolution.artifacts().map(Cache.default.file(_).run))
        .unsafeRun()
        .head

    localArtifacts match {
      case Left(ae) => {
        println(ae.message)
      }
      case Right(file) => {
        weavePathUpdater(file)
      }
    }
  }
}

object MavenDependencyAnnotationProcessor {

  def apply(weavePathUpdater: (File) => Unit): MavenDependencyAnnotationProcessor = new MavenDependencyAnnotationProcessor(weavePathUpdater)

  val ANNOTATION_NAME: NameIdentifier = NameIdentifier("dw::deps::Deps::MavenDependency")
}
