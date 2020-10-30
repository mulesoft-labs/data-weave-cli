package org.mule.weave.dwnative.cli

import java.io.File
import java.util.Timer
import java.util.TimerTask

import scala.collection.mutable.ArrayBuffer

class FileWatcher(filesToWatch: Seq[FileState], pollingInterval: Long) {
  val listeners: ArrayBuffer[FileListener] = ArrayBuffer()
  val timer = new Timer

  def startWatching(): Unit = {
    timer.scheduleAtFixedRate(new TimerTask {
      override def run(): Unit = {
        watch()
      }
    }, 0, pollingInterval)
  }

  private def watch(): Unit = {
    filesToWatch.foreach((f) => {
      if (f.hasChanged()) {
        listeners.foreach((l) => l.onFileChanged(f.file))
      }
    })
  }

  def addListener(listener: FileListener): FileWatcher = {
    listeners += (listener)
    this
  }

}

object FileWatcher {
  def apply(filesToWatch: Seq[File], pollingInterval: Long = 1000): FileWatcher = new FileWatcher(filesToWatch.map(FileState(_)), pollingInterval)
}

trait FileListener {
  def onFileChanged(file: File)
}


class FileState(val file: File, var lastModifiedStampTamp: Long) {
  /**
    * Returns true if this file was modified from last check
    *
    * @return True if it was modified from last time it was checked
    */
  def hasChanged(): Boolean = {
    if (file.lastModified() != lastModifiedStampTamp) {
      lastModifiedStampTamp = file.lastModified()
      true
    } else {
      false
    }
  }

}

object FileState {
  def apply(file: File): FileState = new FileState(file, file.lastModified())
}