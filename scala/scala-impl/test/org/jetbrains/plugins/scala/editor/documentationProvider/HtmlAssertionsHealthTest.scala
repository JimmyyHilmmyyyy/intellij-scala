package org.jetbrains.plugins.scala.editor.documentationProvider
import org.junit.Assert._

import scala.util.{Failure, Success, Try}

class HtmlAssertionsHealthTest extends HtmlAssertions {

  def testEnsureAssertDocHtmlWorksAsExpected(): Unit = {
    assertDocHtml(
      "<body>some text</body>",
      "<body>  some   text  </body>"
    )

    assertDocHtml(
      "<body>some text <p> some text 1</body>",
      "<body>  some text<p>some text 1  </body>"
    )
  }

  import org.jetbrains.plugins.scala.util.assertions.assertFails

  def testEnsureAssertDocHtmlWorksAsExpected_Failing(): Unit = {
    assertFails {
      assertDocHtml(
        "<pre>preformatted</pre)",
        "<pre> preformatted </pre)"
      )
    }

    assertFails {
      assertDocHtml(
        "<pre>preformatted text  with   spaces</pre>)",
        "<pre>preformatted text  with spaces</pre>)"
      )
    }

    assertFails {
      assertDocHtml(
        """<pre>preformatted</pre)""",
        """<pre>
          |preformatted
          |</pre)""".stripMargin
      )
    }
  }
}
