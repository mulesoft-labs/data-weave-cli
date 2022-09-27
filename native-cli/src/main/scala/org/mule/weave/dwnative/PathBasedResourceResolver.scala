package org.mule.weave.dwnative

import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.sdk.NameIdentifierHelper
import org.mule.weave.v2.sdk.WeaveResource
import org.mule.weave.v2.sdk.WeaveResourceResolver

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source

class PathBasedResourceResolver(paths: mutable.ArrayBuffer[ContentResolver]) extends WeaveResourceResolver {

  def addContent(cr: ContentResolver): PathBasedResourceResolver = {
    paths.+=(cr)
    this
  }

  override def resolve(name: NameIdentifier): Option[WeaveResource] = {

    val iterator = paths.iterator
    while (iterator.hasNext) {
      val maybeResource: Option[InputStream] = iterator.next().resolve(name)
      if (maybeResource.isDefined) {
        val filePath = NameIdentifierHelper.toWeaveFilePath(name, "/") //Use unix based system
        return Some(WeaveResource(filePath, toString(maybeResource.get)))
      }
    }
    None
  }

  def toString(is: InputStream): String = {
    val source = Source.fromInputStream(is)(StandardCharsets.UTF_8)
    try {
      source.mkString
    } finally {
      source.close()
    }
  }

  def resolve(filePath: String): Option[InputStream] = {
    val ni = NameIdentifierHelper.fromWeaveFilePath(filePath, "/")
    val iterator = paths.iterator
    while (iterator.hasNext) {
      val maybeResource = iterator.next().resolve(ni)
      if (maybeResource.isDefined) {
        return maybeResource
      }
    }
    None
  }


  override def resolveAll(name: NameIdentifier): Seq[WeaveResource] = {
    paths
      .flatMap(_.resolve(name))
      .map((content) => {
        val path = NameIdentifierHelper.toWeaveFilePath(name, "/")
        WeaveResource(path, toString(content))
      })
  }
}

/**
  *
  */
trait ContentResolver {
  def resolve(path: NameIdentifier): Option[InputStream]
}


object ContentResolver {
  def apply(f: File): ContentResolver = {
    if (f.isDirectory) {
      new DirectoryContentResolver(f)
    } else {
      new JarContentResolver(f)
    }
  }
}

class DirectoryContentResolver(directory: File) extends ContentResolver {

  override def resolve(ni: NameIdentifier): Option[InputStream] = {
    val path = NameIdentifierHelper.toWeaveFilePath(ni, File.separator) //Use unix based system
    val file = new File(directory, path)
    if (file.isFile) {
      Some(new FileInputStream(file))
    } else {
      None
    }
  }
}

class JarContentResolver(jarFile: => File) extends ContentResolver {

  lazy val zipFile = new ZipFile(jarFile)

  override def resolve(ni: NameIdentifier): Option[InputStream] = {
    val path = NameIdentifierHelper.toWeaveFilePath(ni, "/") //Use unix based system
    println(s"Looking for ${path} in " + jarFile.getAbsolutePath)

    val zipEntry: String =
      if (path.startsWith("/")) {
        path.substring(1)
      } else {
        path
      }
    val pathEntry = zipFile.getEntry(zipEntry)
    if (pathEntry != null) {
      println("Found!!!")
      Some(zipFile.getInputStream(pathEntry))
    } else {
      println("Not found :( ")
      None
    }
  }
}

object PathBasedResourceResolver {
  def apply(libDir: File): PathBasedResourceResolver = {
    if (libDir.exists()) {
      val files = libDir.listFiles()
      if (files != null) {
        PathBasedResourceResolver(files.toSeq)
      } else {
        new PathBasedResourceResolver(ArrayBuffer())
      }
    } else {
      new PathBasedResourceResolver(ArrayBuffer())
    }
  }

  def apply(paths: Seq[File]): PathBasedResourceResolver = {
    new PathBasedResourceResolver(ArrayBuffer(
      paths.map((f) => {
        ContentResolver(f)
      }): _*
    ))
  }
}