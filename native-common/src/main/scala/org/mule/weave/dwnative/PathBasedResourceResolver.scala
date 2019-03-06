package org.mule.weave.dwnative

import java.io.File
import java.util.zip.ZipFile

import org.mule.weave.v2.interpreted.extension.MultiWeaveResourceResolver
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.sdk.WeaveResource
import org.mule.weave.v2.sdk.WeaveResourceResolver
import org.mule.weave.v2.utils.WeaveConstants
import org.mule.weave.v2.utils.WeaveFile

import scala.collection.mutable
import scala.io.Source

class PathBasedResourceResolver(paths: Seq[File]) extends WeaveResourceResolver with MultiWeaveResourceResolver {

  def loadResources(): Map[NameIdentifier, Seq[WeaveResource]] = {

    val result = mutable.ArrayBuffer[(NameIdentifier, WeaveResource)]()
    paths
      .foreach(f = (pathEntryFile) => {
        if (pathEntryFile.isFile) {
          val zipFile = new ZipFile(pathEntryFile)
          val entries = zipFile.entries
          while (entries.hasMoreElements) {
            val entry = entries.nextElement
            val path = entry.getName
            if (!entry.isDirectory && path.endsWith(WeaveFile.fileExtension)) {
              val identifier = NameIdentifier.fromPath(path)
              val stream = zipFile.getInputStream(entry)
              val source = Source.fromInputStream(stream, WeaveConstants.default_encoding)
              try {
                result.+=((identifier, WeaveResource(path, source.mkString)))
              } finally {
                source.close()
              }
            }
          }
        } else if (pathEntryFile.isDirectory) {
          //Load file from directory
          val rootPath = pathEntryFile.getAbsolutePath
          recursiveListFiles(
            pathEntryFile,
            (f) => {
              val relativeName = f.getAbsolutePath.substring(rootPath.length + 1)
              val source = Source.fromFile(f, WeaveConstants.default_encoding)
              try {
                val resource = WeaveResource.apply(f.getAbsolutePath, source.mkString)
                result.+=((NameIdentifier.fromPath(relativeName), resource))
              } finally {
                source.close()
              }
            })
        }
      })
    val identifierToResources = result.groupBy(_._1).mapValues(_.map(_._2))

    identifierToResources
  }

  def recursiveListFiles[T](f: File, callback: (File) => Unit): Unit = {
    val files = f.listFiles
    files.foreach((f) =>
      if (f.isFile && f.getName.endsWith(WeaveFile.fileExtension)) {
        callback(f)
      } else if (f.isDirectory) {
        recursiveListFiles(f, callback)
      })

  }

  lazy val entries: Map[NameIdentifier, Seq[WeaveResource]] = loadResources()

  override def resolve(name: NameIdentifier): Option[WeaveResource] = {
    entries.get(name).flatMap(_.headOption)
  }

  override def resolveAll(name: NameIdentifier): Seq[WeaveResource] = {
    entries.getOrElse(name, Seq())
  }
}

object PathBasedResourceResolver {
  def apply(libDir: File): PathBasedResourceResolver = {
    if (libDir.exists()) {
      new PathBasedResourceResolver(libDir.listFiles())
    } else {
      new PathBasedResourceResolver(Seq())
    }
  }

  def apply(paths: Seq[File]): PathBasedResourceResolver = new PathBasedResourceResolver(paths)
}