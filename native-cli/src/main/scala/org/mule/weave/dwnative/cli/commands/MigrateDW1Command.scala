package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.v2.V2LangMigrant

import java.io.File
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import scala.io.Source

class MigrateDW1Command(val oldScriptPath: String, console: Console) extends WeaveCommand {

  def exec(): Int = {
    var statusCode = ExitCodes.SUCCESS
    val oldScriptFile = new File(oldScriptPath)
    if (!oldScriptFile.exists()) {
      console.error(s"Unable to find dw1 script to migrate")
      statusCode = ExitCodes.FAILURE
    } else {
      val source = Source.fromFile(oldScriptFile, StandardCharsets.UTF_8.name())
      try {
        val str = V2LangMigrant.migrateToV2(source.mkString)
        console.out.write(str.getBytes(StandardCharsets.UTF_8))
      } finally {
        source.close()
      }
    }
    statusCode
  }


}
