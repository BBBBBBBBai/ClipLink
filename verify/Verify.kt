@file:JvmName("Verify")

/**
 * UrlExtractor 的独立验证程序。
 *
 * 不依赖 Android 也不依赖 Gradle 测试任务，直接用 Kotlin 编译器编译后运行，
 * 用于确认核心识别逻辑的正确性。
 */
import com.cliplink.UrlExtractor

var passed = 0
var failed = 0

fun eq(actual: String?, expected: String?, name: String) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL  $name\n      期望: $expected\n      实际: $actual")
    }
}

fun main() {
    // 正常识别
    eq(UrlExtractor.extractFirst("https://www.example.com/path?q=1#frag"), "https://www.example.com/path?q=1#frag", "完整URL")
    eq(UrlExtractor.extractFirst("github.com"), "https://github.com", "裸域名补https")
    eq(UrlExtractor.extractFirst("github.com/torvalds/linux"), "https://github.com/torvalds/linux", "裸域名带路径")
    eq(UrlExtractor.extractFirst("快看这个 https://www.baidu.com 很好用"), "https://www.baidu.com", "中文句中提取")
    eq(UrlExtractor.extractFirst("链接是 https://example.com。"), "https://example.com", "剥离中文句号")
    eq(UrlExtractor.extractFirst("Visit https://example.com."), "https://example.com", "剥离英文句号")
    eq(UrlExtractor.extractFirst("<https://example.com>"), "https://example.com", "剥离尖括号")
    eq(UrlExtractor.extractFirst("\"https://example.com\""), "https://example.com", "剥离引号")
    eq(UrlExtractor.extractFirst("http://example.com"), "http://example.com", "保留http")
    eq(UrlExtractor.extractFirst("https://example.com:8443/x"), "https://example.com:8443/x", "带端口")
    eq(UrlExtractor.extractFirst("先看 https://a.com 再看 https://b.org"), "https://a.com", "多个取第一")
    eq(UrlExtractor.extractFirst("www.qq.com"), "https://www.qq.com", "www前缀")
    eq(UrlExtractor.extractFirst("https://example.com.cn/x"), "https://example.com.cn/x", "com.cn")
    eq(UrlExtractor.extractFirst("(https://example.com/a)"), "https://example.com/a", "圆括号包裹")
    eq(UrlExtractor.extractFirst("第一行\nhttps://example.com/x\n第三行"), "https://example.com/x", "多行文本")
    eq(UrlExtractor.extractFirst("https://my-site.example.com"), "https://my-site.example.com", "连字符主机")
    eq(UrlExtractor.extractFirst("https://user:pass@example.com/path"), "https://user:pass@example.com/path", "带userinfo")

    // 粘连文字截断（回归）
    eq(UrlExtractor.extractFirst("https://example.com/abc后面的说明文字"), "https://example.com/abc", "路径后粘中文")
    eq(UrlExtractor.extractFirst("https://example.com后面的说明文字"), "https://example.com", "域名后粘中文")
    eq(UrlExtractor.extractFirst("www.example.com/abc后面的文字"), "https://www.example.com/abc", "www域名后粘中文")
    eq(UrlExtractor.extractFirst("https://example.com/abc。后面还有一句话"), "https://example.com/abc", "句号后接文字")
    eq(UrlExtractor.extractFirst("https://example.com/abc，这是分享说明"), "https://example.com/abc", "全角逗号后接文字")
    eq(UrlExtractor.extractFirst("https://example.com/abc**加粗说明**"), "https://example.com/abc", "markdown星号粘连")
    eq(UrlExtractor.extractFirst("地址是https://example.com/abc后面的文字"), "https://example.com/abc", "前缀+粘连混合")
    eq(UrlExtractor.extractFirst("https://www.baidu.com/s?wd=%E4%BD%A0%E5%A5%BD"), "https://www.baidu.com/s?wd=%E4%BD%A0%E5%A5%BD", "百分号编码中文保留")
    eq(UrlExtractor.extractFirst("百度.中国"), "https://百度.中国", "中文域名整段保留")
    eq(UrlExtractor.extractFirst("https://例子.中国"), "https://例子.中国", "带协议中文域名保留")

    // 去重
    val all = UrlExtractor.extractAll("https://a.com https://b.com https://a.com")
    if (all == listOf("https://a.com", "https://b.com")) passed++ else {
        failed++; println("FAIL  去重\n      实际: $all")
    }

    // 误判拦截
    eq(UrlExtractor.extractFirst("192.168.1.1"), null, "纯IP")
    eq(UrlExtractor.extractFirst("https://192.168.1.1/admin"), null, "带协议IP")
    eq(UrlExtractor.extractFirst("MainActivity.kt"), null, "kt文件名")
    eq(UrlExtractor.extractFirst("build.gradle.kts"), null, "kts文件名")
    eq(UrlExtractor.extractFirst("com.cliplink.MainActivity"), null, "Java包名")
    eq(UrlExtractor.extractFirst("hello.notatld"), null, "未知后缀")
    eq(UrlExtractor.extractFirst("version.1"), null, "版本号")
    eq(UrlExtractor.extractFirst("12345"), null, "纯数字")
    eq(UrlExtractor.extractFirst("3.14159"), null, "小数")
    eq(UrlExtractor.extractFirst("mailto:someone@example.com"), null, "mailto")
    eq(UrlExtractor.extractFirst("tel:10086"), null, "tel")
    eq(UrlExtractor.extractFirst("javascript:alert(1)"), null, "javascript")
    eq(UrlExtractor.extractFirst("file:///C:/Windows"), null, "file协议")
    eq(UrlExtractor.extractFirst("ftp://example.com/file"), null, "ftp")
    eq(UrlExtractor.extractFirst(null), null, "null输入")
    eq(UrlExtractor.extractFirst(""), null, "空串")
    eq(UrlExtractor.extractFirst("   \n\t  "), null, "空白")
    eq(UrlExtractor.extractFirst("今天天气不错，我们去吃饭吧。"), null, "纯中文")
    eq(UrlExtractor.extractFirst("hello world"), null, "纯英文")
    eq(UrlExtractor.extractFirst("localhost"), null, "localhost")
    eq(UrlExtractor.extractFirst("http://localhost:8080"), null, "localhost带端口")
    eq(UrlExtractor.extractFirst("myserver.internal"), null, "internal")
    eq(UrlExtractor.extractFirst("a".repeat(50_000)), null, "超长文本")

    // 数量上限
    val many = (1..100).joinToString(" ") { "https://site$it.com" }
    val manyResult = UrlExtractor.extractAll(many)
    if (manyResult.size <= 20) passed++ else {
        failed++; println("FAIL  数量上限  实际: ${manyResult.size}")
    }

    eq(UrlExtractor.normalize(null), null, "normalize null")
    eq(UrlExtractor.normalize(""), null, "normalize 空")
    eq(UrlExtractor.normalize("   "), null, "normalize 空白")

    println()
    println("================================")
    println("通过: $passed    失败: $failed")
    println("================================")
    if (failed > 0) kotlin.system.exitProcess(1)
}
