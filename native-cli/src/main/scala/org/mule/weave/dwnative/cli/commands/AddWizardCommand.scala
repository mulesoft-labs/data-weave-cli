package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils

class AddWizardCommand(config: CloneWizardConfig, console: Console) extends WeaveCommand {

  private val utils = new SpellsUtils(console)

  override def exec(): Int = {
    val wizard = config.wizardName
    console.info(s"Downloading Grimoire From The Wise: `$wizard`.")
    val wizardName = if (wizard == null) "DW" else wizard
    val wizardFolder = utils.grimoireFolder(wizard)
    if (wizardFolder.exists()) {
      console.error(s"Wizard `${wizard}` was already added.")
      -1
    } else {
      console.info(s"Fetching `$wizardName's` Grimoire.")
      val url: String = utils.buildRepoUrl(wizard)
      val processBuilder = new ProcessBuilder("git", "clone", url, wizardFolder.getAbsolutePath)
      processBuilder.inheritIO()
      processBuilder.start().waitFor()
    }
  }
}

case class CloneWizardConfig(wizardName: String)
