package io.github.capsopasme.assistant.memory

import io.github.capsopasme.assistant.screen.ScreenIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryParserTest {

    private fun remember(s: String) = MemoryParser.parse(s) as MemoryCommand.Remember

    @Test fun remembersExplicitly() {
        assertEquals(MemoryCommand.Remember("车位", "我的车位在B2-117"), MemoryParser.parse("记住我的车位在B2-117"))
        assertEquals(MemoryCommand.Remember("车位", "我的车停在B2 117号"), MemoryParser.parse("帮我记一下，我的车停在B2 117号。"))
        assertEquals("钥匙", remember("记住：我把钥匙放在鞋柜里了").subject)
        assertEquals("我把钥匙放在鞋柜里了", remember("记住：我把钥匙放在鞋柜里了").content)
        assertEquals("老婆生日", remember("你记着老婆生日是3月5号").subject)
        assertEquals("车位", remember("记下来车子停在负二层C区").subject)
    }

    @Test fun recallQuestions() {
        assertTrue(MemoryParser.parse("我的车位在哪") is MemoryCommand.Recall)
        assertTrue(MemoryParser.parse("车停哪了") is MemoryCommand.Recall)
        assertTrue(MemoryParser.parse("记得我的车位在哪吗") is MemoryCommand.Recall)
        assertTrue(MemoryParser.parse("我忘了车停哪了") is MemoryCommand.Recall)
        assertTrue(MemoryParser.parse("忘了车停哪了") is MemoryCommand.Recall)
        // not memory commands at all
        assertNull(MemoryParser.parse("打开蓝牙"))
        assertNull(MemoryParser.parse("明早七点叫我"))
        assertNull(MemoryParser.parse("现在几点"))
    }

    @Test fun forget() {
        assertEquals(MemoryCommand.Forget("车位"), MemoryParser.parse("忘掉车位"))
        assertEquals(MemoryCommand.Forget("车位"), MemoryParser.parse("删除关于车位的记忆"))
        assertEquals(MemoryCommand.Forget("", all = true), MemoryParser.parse("清空记忆"))
        assertEquals(MemoryCommand.Forget("", all = true), MemoryParser.parse("忘掉所有记忆"))
    }

    @Test fun matching() {
        val subj = "车位"
        val content = "我的车位在B2-117"
        assertEquals(1.0, MemoryParser.score("我的车位在哪", subj, content), 0.001)
        assertEquals(1.0, MemoryParser.score("车停哪了", subj, content), 0.001)
        assertEquals(1.0, MemoryParser.score("我车停在哪儿了", subj, content), 0.001)
        assertTrue(MemoryParser.score("钥匙在哪", "车钥匙", "车钥匙在包里") >= MemoryParser.RECALL_MIN)
        // unrelated questions stay below the threshold
        assertTrue(MemoryParser.score("北京在哪", subj, content) < MemoryParser.RECALL_MIN)
        assertTrue(MemoryParser.score("公司在哪", "公司wifi", "公司wifi叫Office5G") < MemoryParser.RECALL_MIN)
        assertTrue(MemoryParser.score("车站在哪", subj, content) < MemoryParser.RECALL_MIN)
    }

    @Test fun secrets() {
        assertTrue(MemoryParser.looksSecret("wifi密码是12345678"))
        assertTrue(MemoryParser.looksSecret("我的银行卡号6222"))
        assertFalse(MemoryParser.looksSecret("我的车位在B2-117"))
    }

    @Test fun screenIntent() {
        assertTrue(ScreenIntent.matches("看看屏幕上写的什么"))
        assertTrue(ScreenIntent.matches("这段话什么意思"))
        assertTrue(ScreenIntent.matches("翻译一下"))
        assertTrue(ScreenIntent.matches("帮我总结一下这篇"))
        assertFalse(ScreenIntent.matches("屏幕亮一点"))
        assertFalse(ScreenIntent.matches("关闭屏幕"))
        assertFalse(ScreenIntent.matches("打开蓝牙"))
        assertFalse(ScreenIntent.matches("翻译一下hello world"))
        assertTrue(ScreenIntent.wantsFresh("再看一下"))
    }
}
