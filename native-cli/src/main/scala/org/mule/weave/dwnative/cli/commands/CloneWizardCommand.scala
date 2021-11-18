package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils.buildRepoUrl
import org.mule.weave.dwnative.cli.utils.SpellsUtils.grimoireFolder

class CloneWizardCommand(config: CloneWizardConfig, logger: Console) extends WeaveCommand {
  override def exec(): Int = {
    val wizard = config.wizardName
    logger.info(s"Downloading Grimoire From The Wise: `$wizard`.")
    val wizardName = if (wizard == null) "DW" else wizard
    val wizardFolder = grimoireFolder(wizard)
    if (wizardFolder.exists()) {
      logger.error(s"Wizard `${wizard}` was already added.")
      -1
    } else {
      logger.info(s"Fetching `$wizardName's` Grimoire.")
      val url: String = buildRepoUrl(wizard)
      val processBuilder = new ProcessBuilder("git", "clone", url, wizardFolder.getAbsolutePath)
      processBuilder.inheritIO()
      processBuilder.start().waitFor()
    }
  }
}

case class CloneWizardConfig(wizardName: String)
