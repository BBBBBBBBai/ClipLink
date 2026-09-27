package com.cliplink

import java.util.Locale

/**
 * 从任意文本中提取 URL。
 *
 * 纯 Kotlin 实现，**不依赖 `android.util.Patterns`**：后者是 Android 框架类，
 * 在 JVM 单元测试里只会返回桩值，会导致"测试通过但运行时行为不同"。
 * 自带正则让本地测试与真机行为一致。
 *
 * 核心职责：
 * 1. 找出文本里第一个可信的网址（一个文本可能含多个）
 * 2. 补全缺失的协议（`github.com` -> `https://github.com`）
 * 3. 过滤误判（纯数字、纯 IP、文件名、代码片段里的伪域名）
 */
object UrlExtractor {

    /** 候选串的最大长度。超长的基本可判定不是正常网址，同时避免正则开销。 */
    private const val MAX_CANDIDATE_LENGTH = 2048

    /** 返回结果上限，避免一段日志里成百上千个链接时做无谓解析。 */
    private const val MAX_RESULTS = 20

    /**
     * URL 主体允许的字符：可打印 ASCII，剔除引号、尖括号、反引号这类包裹符。
     *
     * 只放行 ASCII 是截断粘连文字的关键：复制出来的网址里中文部分必然是
     * 百分号编码（`wd=%E4%BD%A0%E5%A5%BD`）；剪贴板里紧贴在链接后面的
     * 中文、全角符号、emoji 一律是说明文字，必须在匹配阶段就截断，
     * 否则会被整段吞进链接。
     */
    private val BODY_CHARS = """[[\x21-\x7E]&&[^"'`<>]]"""

    /**
     * 网址匹配主正则。
     *
     * 分两种形态：
     * - 带协议：`http(s)://` 或 `www.` 开头，主体字符集由 [BODY_CHARS] 收紧
     * - 裸域名：由下面的 TLD 白名单二次收紧
     */
    private val URL_REGEX = Regex(
        """(?:https?://|www\.)$BODY_CHARS+""" +
            """|""" +
            """[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+\.[a-zA-Z]{2,}(?::\d{1,5})?(?:[/?#]$BODY_CHARS*)?"""
    )

    /**
     * 已知的顶级域名后缀白名单。
     *
     * 裸域名判断必须依赖后缀白名单，否则 `MainActivity.kt`、`com.google.gson`
     * 这类标识符会被当成域名。带协议的不受此限制。
     */
    private val KNOWN_TLDS = setOf(
        // 通用
        "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "name",
        "pro", "aero", "coop", "museum", "jobs", "mobi", "travel", "tel", "asia",
        "app", "dev", "io", "ai", "me", "tv", "cc", "xyz", "top", "site", "online",
        "store", "shop", "tech", "space", "website", "live", "life", "world", "news",
        "blog", "wiki", "cloud", "link", "click", "download", "run",
        "fun", "art", "design", "studio", "digital", "media", "agency", "email",
        "group", "network", "solutions", "services", "systems", "tools", "works",
        "codes", "software", "host", "page", "pub", "red", "blue", "green", "one",
        "plus", "fan", "club", "band", "guru", "expert", "review", "rocks",
        "cafe", "city", "earth", "today", "chat", "video", "audio", "photo",
        "pics", "gallery", "social", "team", "company", "center", "academy", "school",
        "institute", "foundation", "care", "clinic", "health", "fitness", "finance",
        "capital", "money", "bank", "market", "deals", "sale", "discount",
        "coupons", "gift", "toys", "games", "game", "play", "sport", "football",
        "basketball", "racing", "holiday", "hotel", "restaurant", "food",
        "coffee", "beer", "wine", "farm", "garden", "house", "estate", "properties",
        "rentals", "flights", "tours", "cruises", "vacations", "tips", "ninja",
        "zone", "cool", "best", "vip", "icu", "ltd", "llc", "inc", "wang", "ren",
        "work", "fm", "am", "gd", "la", "ly", "to", "gg", "je", "im", "is", "nu",
        "se", "sh", "so", "st", "tw", "vc", "ws", "be", "at", "ch", "cz", "de",
        "dk", "es", "fi", "fr", "gr", "hu", "ie", "il", "in", "ir", "jp", "kr",
        "lt", "lu", "lv", "nl", "no", "nz", "pl", "pt", "ro", "ru", "sk", "tr",
        "ua", "uk", "us", "za", "it", "my",
        // 中国
        "cn", "中国", "公司", "网络", "网址",
        // 常见二级结构（`.com.cn`、`.co.uk` 的首段）
        "co", "or", "ne", "go", "ac"
    )

    /**
     * 从文本中提取第一个 URL。识别不到时返回 null。
     */
    fun extractFirst(text: String?): String? = extractAll(text).firstOrNull()

    /**
     * 提取文本中所有可信的 URL，按出现顺序返回并去重。
     */
    fun extractAll(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 整段文本本身就是网址时直接返回，跳过逐词扫描（最常见的情况）。
        normalize(trimmed)?.let { return listOf(it) }

        val results = LinkedHashSet<String>()
        for (match in URL_REGEX.findAll(trimmed)) {
            if (results.size >= MAX_RESULTS) break
            normalize(match.value)?.let { results.add(it) }
        }
        return results.toList()
    }

    /**
     * 把候选串规范化为可打开的 URL；不是合法网址时返回 null。
     *
     * 这里是所有误判的拦截点。
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        var candidate = raw.trim().trim(*TRIM_CHARS)
        if (candidate.isEmpty() || candidate.length > MAX_CANDIDATE_LENGTH) return null

        candidate = candidate.trimEnd(*TRAILING_CHARS)
        if (candidate.isEmpty()) return null

        val lower = candidate.lowercase(Locale.ROOT)

        // 明确的非 http(s) 协议一律拒绝。裸域名不含冒号，不受影响。
        if (SCHEME_LIKE.containsMatchIn(candidate) && !lower.startsWith("http")) return null

        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
        val withScheme = if (hasScheme) candidate else "https://$candidate"

        if (!isPlausibleHost(withScheme, requireKnownTld = !hasScheme)) return null

        // host 之后的路径/查询/片段必须是可打印 ASCII（复制出来的网址中文必为
        // 百分号编码）。粘连在链接后面的说明文字会在这里被拦下，
        // 调用方（extractAll）回退到正则逐字符截取。
        val afterScheme = withScheme.substringAfter("://")
        val hostPort = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val rest = afterScheme.removePrefix(hostPort)
        if (rest.any { it.code < 0x21 || it.code > 0x7E }) return null

        return withScheme
    }

    /**
     * 校验 host 部分是否可信。
     *
     * @param requireKnownTld 裸域名输入时为 true，此时必须命中 TLD 白名单
     */
    private fun isPlausibleHost(url: String, requireKnownTld: Boolean): Boolean {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return false

        val hostPort = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')

        // 空格/控制字符出现在 authority 部分（含 userinfo）＝粘连的正文。
        if (hostPort.any { it.code < 0x21 }) return false

        // 去掉 userinfo（user:pass@host）
        val hostOnly = hostPort.substringAfterLast('@')
        val host = hostOnly.substringBefore(':')

        if (host.isEmpty() || host.contains(' ')) return false

        // 纯 IP 不是用户想打开的"网址"，且内网地址大量出现在配置文本里。
        if (IPV4.matches(host)) return false
        if (host.startsWith("[") || host.endsWith("]")) return false

        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false

        // 主机名各段不能以下划线或连字符开头/结尾（域名规范）。
        if (labels.any { it.startsWith('-') || it.endsWith('-') }) return false

        // 每段要么是纯 ASCII 字母数字连字符，要么是纯非 ASCII（国际化域名，
        // 如 `例子.中国`）。`com后面文字` 这种半 ASCII 半中文的段就是粘连。
        val isAsciiLabel = { label: String ->
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
        if (labels.any { !isAsciiLabel(it) && it.any { ch -> ch.code <= 127 } }) return false

        if (requireKnownTld) {
            val tld = labels.last().lowercase(Locale.ROOT)
            if (tld !in KNOWN_TLDS) return false
        }

        return true
    }

    /** 网页/文本里常见的包裹字符。 */
    private val TRIM_CHARS = charArrayOf(
        '"', '\'', '`', '<', '>', '(', ')', '[', ']', '{', '}',
        '“', '”', '‘', '’', '《', '》', '〈', '〉', '「', '」', '『', '』',
        '（', '）', '【', '】', '，', '。', '、', '：', '；', '！', '？',
        ' ', '\t', '\n', '\r', '*', '-', '=', '+', '|', '\\', '^', '~', '_'
    )

    /** 句子结尾标点，出现在 URL 尾部时应剥离。 */
    private val TRAILING_CHARS = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'', '*', '`',
        '。', '，', '、', '：', '；', '！', '？', '）', '】', '》', '」', '』', '…'
    )

    /** 形如 `scheme:` 的前缀，用于排除非 http(s) 协议。 */
    private val SCHEME_LIKE = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

    private val IPV4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
}
