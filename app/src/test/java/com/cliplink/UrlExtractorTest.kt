package com.cliplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UrlExtractor] 的单元测试。
 *
 * 覆盖主要的误判来源：代码片段、文件名、版本号、纯 IP、内网地址、非 http 协议。
 * 方法名保持英文——含中文的反引号方法名在 JVM 上会生成非 ASCII 的类成员名，
 * 部分测试运行器无法正确加载。
 */
class UrlExtractorTest {

    // ---------------------------------------------------------- 正常识别

    @Test
    fun detectsFullUrlWithScheme() {
        assertEquals(
            "https://www.example.com/path?q=1#frag",
            UrlExtractor.extractFirst("https://www.example.com/path?q=1#frag")
        )
    }

    @Test
    fun addsHttpsToBareDomain() {
        assertEquals("https://github.com", UrlExtractor.extractFirst("github.com"))
    }

    @Test
    fun detectsBareDomainWithPath() {
        assertEquals(
            "https://github.com/torvalds/linux",
            UrlExtractor.extractFirst("github.com/torvalds/linux")
        )
    }

    @Test
    fun detectsUrlInsideChineseSentence() {
        assertEquals(
            "https://www.baidu.com",
            UrlExtractor.extractFirst("快看这个 https://www.baidu.com 很好用")
        )
    }

    @Test
    fun stripsTrailingChinesePunctuation() {
        assertEquals("https://example.com", UrlExtractor.extractFirst("链接是 https://example.com。"))
    }

    @Test
    fun stripsTrailingEnglishPunctuation() {
        assertEquals("https://example.com", UrlExtractor.extractFirst("Visit https://example.com."))
    }

    @Test
    fun stripsWrappingCharacters() {
        assertEquals("https://example.com", UrlExtractor.extractFirst("<https://example.com>"))
        assertEquals("https://example.com", UrlExtractor.extractFirst("\"https://example.com\""))
    }

    @Test
    fun keepsHttpScheme() {
        assertEquals("http://example.com", UrlExtractor.extractFirst("http://example.com"))
    }

    @Test
    fun detectsUrlWithPort() {
        assertEquals("https://example.com:8443/x", UrlExtractor.extractFirst("https://example.com:8443/x"))
    }

    @Test
    fun returnsFirstOfMultipleUrls() {
        val text = "先看 https://a.com 再看 https://b.org"
        assertEquals("https://a.com", UrlExtractor.extractFirst(text))
    }

    @Test
    fun extractsAllUrlsAndDeduplicates() {
        val result = UrlExtractor.extractAll("https://a.com https://b.com https://a.com")
        assertEquals(listOf("https://a.com", "https://b.com"), result)
    }

    @Test
    fun detectsCommonChineseSiteSuffix() {
        assertEquals("https://www.qq.com", UrlExtractor.extractFirst("www.qq.com"))
        assertEquals("https://example.com.cn/x", UrlExtractor.extractFirst("https://example.com.cn/x"))
    }

    // ---------------------------------------------------------- 粘连文字截断（回归）

    @Test
    fun truncatesChineseTextGluedAfterPath() {
        assertEquals(
            "https://example.com/abc",
            UrlExtractor.extractFirst("https://example.com/abc后面的说明文字")
        )
    }

    @Test
    fun truncatesChineseTextGluedAfterHost() {
        assertEquals(
            "https://example.com",
            UrlExtractor.extractFirst("https://example.com后面的说明文字")
        )
    }

    @Test
    fun truncatesGluedTextAfterWwwBareDomain() {
        assertEquals(
            "https://www.example.com/abc",
            UrlExtractor.extractFirst("www.example.com/abc后面的文字")
        )
    }

    @Test
    fun truncatesSentenceRightAfterUrl() {
        assertEquals(
            "https://example.com/abc",
            UrlExtractor.extractFirst("https://example.com/abc。后面还有一句话")
        )
        assertEquals(
            "https://example.com/abc",
            UrlExtractor.extractFirst("https://example.com/abc，这是分享说明")
        )
    }

    @Test
    fun truncatesMarkdownGlue() {
        assertEquals(
            "https://example.com/abc",
            UrlExtractor.extractFirst("https://example.com/abc**加粗说明**")
        )
    }

    @Test
    fun keepsPercentEncodedChinese() {
        assertEquals(
            "https://www.baidu.com/s?wd=%E4%BD%A0%E5%A5%BD",
            UrlExtractor.extractFirst("https://www.baidu.com/s?wd=%E4%BD%A0%E5%A5%BD")
        )
    }

    @Test
    fun keepsChineseIdnDomain() {
        assertEquals("https://百度.中国", UrlExtractor.extractFirst("百度.中国"))
        assertEquals("https://例子.中国", UrlExtractor.extractFirst("https://例子.中国"))
    }

    // ---------------------------------------------------------- 误判拦截

    @Test
    fun rejectsBareIpAddress() {
        assertNull(UrlExtractor.extractFirst("192.168.1.1"))
        assertNull(UrlExtractor.extractFirst("https://192.168.1.1/admin"))
    }

    @Test
    fun rejectsFileNames() {
        assertNull(UrlExtractor.extractFirst("MainActivity.kt"))
        assertNull(UrlExtractor.extractFirst("build.gradle.kts"))
        assertNull(UrlExtractor.extractFirst("com.cliplink.MainActivity"))
    }

    @Test
    fun rejectsUnknownTld() {
        assertNull(UrlExtractor.extractFirst("hello.notatld"))
        assertNull(UrlExtractor.extractFirst("version.1"))
    }

    @Test
    fun rejectsPlainNumbers() {
        assertNull(UrlExtractor.extractFirst("12345"))
        assertNull(UrlExtractor.extractFirst("3.14159"))
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertNull(UrlExtractor.extractFirst("mailto:someone@example.com"))
        assertNull(UrlExtractor.extractFirst("tel:10086"))
        assertNull(UrlExtractor.extractFirst("javascript:alert(1)"))
        assertNull(UrlExtractor.extractFirst("file:///C:/Windows"))
        assertNull(UrlExtractor.extractFirst("ftp://example.com/file"))
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(UrlExtractor.extractFirst(null))
        assertNull(UrlExtractor.extractFirst(""))
        assertNull(UrlExtractor.extractFirst("   \n\t  "))
    }

    @Test
    fun rejectsPlainChineseSentence() {
        assertNull(UrlExtractor.extractFirst("今天天气不错，我们去吃饭吧。"))
    }

    @Test
    fun rejectsPlainEnglishWords() {
        assertNull(UrlExtractor.extractFirst("hello world"))
    }

    @Test
    fun rejectsSingleLabelHost() {
        assertNull(UrlExtractor.extractFirst("localhost"))
        assertNull(UrlExtractor.extractFirst("http://localhost:8080"))
        assertNull(UrlExtractor.extractFirst("myserver.internal"))
    }

    @Test
    fun handlesVeryLongTextWithoutHanging() {
        val text = "a".repeat(50_000)
        assertNull(UrlExtractor.extractFirst(text))
    }

    @Test
    fun limitsResultCountForManyUrls() {
        val text = (1..100).joinToString(" ") { "https://site$it.com" }
        val result = UrlExtractor.extractAll(text)
        assertTrue("结果数量应被限制，实际 ${result.size}", result.size <= 20)
    }

    @Test
    fun normalizeReturnsNullForBlankInput() {
        assertNull(UrlExtractor.normalize(null))
        assertNull(UrlExtractor.normalize(""))
        assertNull(UrlExtractor.normalize("   "))
    }

    // ---------------------------------------------------------- 边界格式

    @Test
    fun handlesUrlWithUserInfo() {
        assertEquals(
            "https://user:pass@example.com/path",
            UrlExtractor.extractFirst("https://user:pass@example.com/path")
        )
    }

    @Test
    fun handlesUrlInsideParentheses() {
        assertEquals(
            "https://example.com/a",
            UrlExtractor.extractFirst("(https://example.com/a)")
        )
    }

    @Test
    fun handlesMultiLineText() {
        val text = "第一行\nhttps://example.com/x\n第三行"
        assertEquals("https://example.com/x", UrlExtractor.extractFirst(text))
    }

    @Test
    fun handlesUrlWithHyphenatedHost() {
        assertEquals(
            "https://my-site.example.com",
            UrlExtractor.extractFirst("https://my-site.example.com")
        )
    }
}
