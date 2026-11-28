package io.github.capsopasme.assistant.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class AttachmentsTest {

    @Test fun decodesCommonEncodings() {
        val s = "你好，世界 hello"
        assertEquals(s, Attachments.decode(s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, Attachments.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, Attachments.decode(s.toByteArray(Charset.forName("GB18030"))))
        assertEquals(s, Attachments.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun stripsWordXml() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>第一段</w:t></w:r><w:r><w:t xml:space="preserve"> 继续</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t>A &amp; B &lt;C&gt;</w:t></w:r></w:p></w:body></w:document>"""
        assertEquals("第一段 继续\nA & B <C>", Attachments.stripMarkup(xml))
    }

    @Test fun stripsHtml() {
        val html = "<html><head><title>x</title><style>p{}</style></head><body><h1>标题</h1><p>正文&#x4E2D;&#25991;</p><script>var a</script></body></html>"
        val t = Attachments.stripMarkup(html)
        assertTrue(t, t.startsWith("标题\n"))
        assertTrue(t, t.contains("正文中文"))
        assertTrue(t, !t.contains("var a") && !t.contains("p{}"))
    }
}
