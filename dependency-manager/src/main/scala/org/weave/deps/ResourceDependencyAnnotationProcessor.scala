package org.weave.deps

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.ExecutorService

import org.mule.weave.v2.parser.DefaultMessage
import org.mule.weave.v2.parser.MessageCollector
import org.mule.weave.v2.parser.ParsingPhaseCategory
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNodeHelper
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.AnnotationProcessor
import org.mule.weave.v2.scope.AstNavigator
import org.mule.weave.v2.scope.ScopesNavigator

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future

class ResourceDependencyAnnotationProcessor(resourceCache: File, weavePathUpdater: DependencyManagerController, executor: ExecutorService) extends AnnotationProcessor {

  val context: ExecutionContextExecutor = ExecutionContext.fromExecutor(executor)

  /**
    * Run the annotation processing logic on the scope navigation phase
    */
  override def run(annotatedNode: AstNode, astNavigator: AstNavigator, scopeNavigator: ScopesNavigator, messageCollector: MessageCollector, annotation: AnnotationNode): Unit = {
    val maybeUrl: Option[String] = AnnotationNodeHelper.argString("url", annotation)
    val unzip: Option[Boolean] = AnnotationNodeHelper.argString("unzip", annotation).map(_.toBoolean)
    if (maybeUrl.isDefined && unzip.isDefined) {
      val url = maybeUrl.get
      val shouldUnzip = unzip.get
      retrieve(url, shouldUnzip, (message) => {
        messageCollector.error(new DefaultMessage(message, ParsingPhaseCategory), annotatedNode.location())
      })
    }
  }

  def retrieve(url: String, shouldUnzip: Boolean, errorMessage: (String) => Unit): Unit = {
    if (weavePathUpdater.shouldDownload(url, "resource")) {
      val filename = URLEncoder.encode(url, "UTF-8")
      resourceCache.mkdirs()
      val theCachedFile = new File(resourceCache, filename)
      val downloadedArtifact: Future[Seq[Artifact]] =
        if (theCachedFile.exists()) {
          Future.successful(Seq(Artifact(theCachedFile)))
        } else {
          val resourceUrl = new URL(url)
          Future({
            try {
              val connection: URLConnection = resourceUrl.openConnection()
              val stream: InputStream = connection.getInputStream
              try {
                Files.copy(stream, theCachedFile.toPath)
                Seq(Artifact(theCachedFile))
              } finally {
                stream.close()
              }
            } catch {
              case io: IOException => {
                errorMessage(io.getMessage)
                Seq()
              }
            }
          })(context)
        }


      val unzippedArtifact: Future[Seq[Artifact]] =
        if (shouldUnzip) {
          downloadedArtifact.map((artifact) => {
            val filename = URLEncoder.encode("unzip:" + url, "UTF-8")
            val theCachedFile = new File(resourceCache, filename)
            if (theCachedFile.exists()) {
              Seq(Artifact(theCachedFile))
            } else if (artifact.nonEmpty) {
              val zipStream = new FileInputStream(artifact.head.file)
              try {
                UnzipHelper.unZipIt(zipStream, theCachedFile)
              } finally {
                zipStream.close()
              }
              Seq(Artifact(theCachedFile, isDirectory = true))
            } else {
              Seq()
            }
          })(context)
        } else {
          downloadedArtifact
        }

      weavePathUpdater.downloaded(url, "resource", unzippedArtifact)

    }
  }
}


object ResourceDependencyAnnotationProcessor {

  def apply(resourceCache: File, controller: DependencyManagerController, executor: ExecutorService): ResourceDependencyAnnotationProcessor = {
    new ResourceDependencyAnnotationProcessor(resourceCache, controller, executor)
  }

  val ANNOTATION_NAME: NameIdentifier = NameIdentifier("dw::deps::Deps::ResourceDependency")
}


