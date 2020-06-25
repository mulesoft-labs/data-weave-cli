package org.weave.deps

import java.io.InputStream
import java.net.URL
import java.net.URLConnection

import org.mule.weave.v2.parser.MessageCollector
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNodeHelper
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.exception.WeaveRuntimeException
import org.mule.weave.v2.parser.phase.AnnotationProcessor
import org.mule.weave.v2.scope.AstNavigator
import org.mule.weave.v2.scope.ScopesNavigator

class ResourceDependencyAnnotationProcessor(weavePathUpdater: (InputStream, Boolean, String) => Unit) extends AnnotationProcessor {

  /**
    * Run the annotation processing logic on the scope navigation phase
    */
  override def run(annotatedNode: AstNode, astNavigator: AstNavigator, scopeNavigator: ScopesNavigator, messageCollector: MessageCollector, annotation: AnnotationNode): Unit = {
    val url = AnnotationNodeHelper.argString("url", annotation).getOrElse(throw new WeaveRuntimeException("Missing argument `url`", annotation.location()))
    val unzip = AnnotationNodeHelper.argString("unzip", annotation).getOrElse(throw new WeaveRuntimeException("Missing argument `unzip`", annotation.location())).toBoolean
    val resourceUrl = new URL(url)
    val connection: URLConnection = resourceUrl.openConnection()
    val stream: InputStream = connection.getInputStream
    try {
      weavePathUpdater(stream, unzip, url)
    } finally {
      stream.close()
    }
  }

}


object ResourceDependencyAnnotationProcessor {

  def apply(weavePathUpdater: (InputStream, Boolean, String) => Unit): ResourceDependencyAnnotationProcessor = new ResourceDependencyAnnotationProcessor(weavePathUpdater)

  val ANNOTATION_NAME: NameIdentifier = NameIdentifier("dw::deps::Deps::ResourceDependency")
}


