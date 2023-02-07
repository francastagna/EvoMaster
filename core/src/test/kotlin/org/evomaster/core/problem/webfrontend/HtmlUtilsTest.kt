package org.evomaster.core.problem.webfrontend

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class HtmlUtilsTest{

    private fun body(s: String) = "<#document><html><head></head><body>$s</body></html></#document>"


    @Test
    fun testBaseShape(){
        val html = "<a></a><b><c />"
        val res = HtmlUtils.computeIdentifyingShape(html)
        assertEquals(body("<a></a><b><c></c></b>"), res)
    }

    @Test
    fun testShapeWithText(){
        val html = "<p>Hello World!!!</p>"
        val res = HtmlUtils.computeIdentifyingShape(html)
        assertEquals(body("<p>text</p>"), res)
    }

    @Test
    fun testShapeWithAttributes(){
        val html = "<p x=3>hello there <span foo='fdfd'>!!!</span><a href='www.evomaster.org'></a></p>"
        val res = HtmlUtils.computeIdentifyingShape(html)
        assertEquals(body("<p x>text<span foo>text</span><a href></a></p>"), res)
    }
}