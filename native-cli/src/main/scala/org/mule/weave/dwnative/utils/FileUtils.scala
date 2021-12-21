package org.mule.weave.dwnative.utils

import java.io.File
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object FileUtils {

  def tree(file: File): Array[File] = {
    if (file.isDirectory) {
      file.listFiles().flatMap((f) => tree(f))
    } else {
      Array(file)
    }
  }

  @throws[IOException]
  def copyFolder(source: Path, target: Path, options: CopyOption*): Unit = {
    Files.walkFileTree(source, new SimpleFileVisitor[Path]() {
      @throws[IOException]
      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.createDirectories(target.resolve(source.relativize(dir)))
        FileVisitResult.CONTINUE
      }

      @throws[IOException]
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.copy(file, target.resolve(source.relativize(file)), options: _*)
        FileVisitResult.CONTINUE
      }
    })
  }
}
