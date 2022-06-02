package org.mule.weave.dwnative.cli.highlighting

import org.jline.builtins.Nano
import org.jline.utils.AttributedString

trait SyntaxHighlighterProvider {
  def hightlighterFor(mimeTypeExtension: String): SyntaxHighlighter
}

class NanoHighlighterProvider() extends SyntaxHighlighterProvider {

  override def hightlighterFor(mimeTypeExtension: String): SyntaxHighlighter = {
    var highLighter: SyntaxHighlighter = NoneSyntaxHighlighter()
    val url = getClass.getResource(s"/syntaxes/${mimeTypeExtension}.nanorc")
    if (url != null) {
      highLighter = NanoSyntaxHighlighter(Nano.SyntaxHighlighter.build(url.toString))
    }
    highLighter
  }
}

trait SyntaxHighlighter {

  def highlight(code: String): AttributedString

}

object NanoSyntaxHighlighter {

  def apply(highlighter: Nano.SyntaxHighlighter): SyntaxHighlighter = new NanoSyntaxHighlighter(highlighter)

  class NanoSyntaxHighlighter(highlighter: Nano.SyntaxHighlighter) extends SyntaxHighlighter {

    override def highlight(code: String): AttributedString = {
      highlighter.highlight(code)
    }

  }
}

object NoneSyntaxHighlighter {
  def apply(): SyntaxHighlighter = new NoneSyntaxHighlighter()

  class NoneSyntaxHighlighter() extends SyntaxHighlighter {

    override def highlight(code: String): AttributedString = {
      new AttributedString(code)
    }
  }
}




