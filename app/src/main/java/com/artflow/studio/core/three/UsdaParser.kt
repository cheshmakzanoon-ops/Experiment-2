package com.artflow.studio.core.three

/**
 * Reads text USD (.usda) into the specs the binary reader produces: prims by path ("/a/b") and
 * properties ("/a/b.name"), each with its fields ("typeName", "default", "interpolation",
 * "targetPaths", "connectionPaths", "upAxis" on "/"). Values stay as parsed: tokens and strings
 * as String, asset paths as [Asset], paths as [Path], lists of numbers flattened to [Numbers],
 * other lists as List. The selected variant of each variant set is read; class prims, inactive
 * prims and everything else the 3D import does not use are skipped.
 */
internal object UsdaParser : UsdReader.Values<Any> {
    private val MAGIC = "#usda".toByteArray()

    class Asset(
        val path: String,
    )

    class Path(
        val path: String,
    )

    class Numbers(
        val values: DoubleArray,
    )

    fun isUsda(bytes: ByteArray): Boolean = bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun parse(text: String): Map<String, Map<String, Any>> = Reader(Lexer(text)).layer()

    override fun token(rep: Any?): String? = rep as? String

    override fun tokens(rep: Any): List<String> = (rep as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(rep as? String)

    override fun assetPath(rep: Any): String? = (rep as? Asset)?.path

    override fun paths(rep: Any): List<String> =
        when (rep) {
            is Path -> listOf(rep.path)
            is List<*> -> rep.filterIsInstance<Path>().map { it.path }
            else -> emptyList()
        }

    override fun ints(rep: Any): IntArray? = (rep as? Numbers)?.values?.let { values -> IntArray(values.size) { values[it].toInt() } }

    override fun floats(rep: Any): FloatArray =
        when (rep) {
            is Numbers -> FloatArray(rep.values.size) { rep.values[it].toFloat() }
            is Double -> floatArrayOf(rep.toFloat())
            else -> throw IllegalArgumentException("The USD file has a value of an unexpected type")
        }

    private enum class Kind { PUNCT, STRING, ASSET, PATH, NUMBER, WORD, END }

    private class Token(
        val kind: Kind,
        val text: String,
    ) {
        fun isPunct(char: String) = kind == Kind.PUNCT && text == char
    }

    private class Lexer(
        private val text: String,
    ) {
        /** Where the next token starts; saved and restored to read a stretch again. */
        var at = 0

        fun next(): Token {
            skipBlank()
            if (at >= text.length) return Token(Kind.END, "")
            val c = text[at]
            return when {
                c == '"' || c == '\'' -> Token(Kind.STRING, string(c))
                c == '@' -> Token(Kind.ASSET, asset())
                c == '<' -> Token(Kind.PATH, until('>'))
                c in PUNCTUATION -> Token(Kind.PUNCT, text[at++].toString())
                c.isLetter() || c == '_' -> Token(Kind.WORD, scan { it.isLetterOrDigit() || it in WORD_EXTRA })
                c.isDigit() || c in NUMBER_START -> Token(Kind.NUMBER, scan { it.isLetterOrDigit() || it in NUMBER_EXTRA })
                else -> throw IllegalArgumentException("The USD text is damaged")
            }
        }

        private fun skipBlank() {
            var moved = true
            while (moved && at < text.length) {
                val from = at
                when {
                    text[at].isWhitespace() -> at++
                    text[at] == '#' || text.startsWith("//", at) -> at = endOf(text.indexOf('\n', at), 0)
                    text.startsWith("/*", at) -> at = endOf(text.indexOf("*/", at + 2), 2)
                }
                moved = at != from
            }
        }

        /** Just past a comment's end marker found at [found] ([length] long), or the end of the text. */
        private fun endOf(
            found: Int,
            length: Int,
        ): Int = if (found < 0) text.length else found + length

        private inline fun scan(accept: (Char) -> Boolean): String {
            val start = at++
            while (at < text.length && accept(text[at])) at++
            return text.substring(start, at)
        }

        private fun until(close: Char): String {
            val end = text.indexOf(close, at + 1)
            require(end > at) { "The USD text is damaged" }
            return text.substring(at + 1, end).also { at = end + 1 }
        }

        private fun asset(): String {
            val fence = if (text.startsWith("@@@", at)) "@@@" else "@"
            val end = text.indexOf(fence, at + fence.length)
            require(end >= 0) { "The USD text is damaged" }
            return text.substring(at + fence.length, end).also { at = end + fence.length }
        }

        private fun string(quote: Char): String {
            val triple = "$quote$quote$quote"
            if (text.startsWith(triple, at)) {
                val end = text.indexOf(triple, at + triple.length)
                require(end >= 0) { "The USD text is damaged" }
                return text.substring(at + triple.length, end).also { at = end + triple.length }
            }
            val out = StringBuilder()
            at++
            while (at < text.length && text[at] != quote) {
                if (text[at] == '\\' && at + 1 < text.length) at++
                out.append(text[at++])
            }
            require(at < text.length) { "The USD text is damaged" }
            at++
            return out.toString()
        }
    }

    private class Reader(
        private val lexer: Lexer,
    ) {
        private val specs = LinkedHashMap<String, MutableMap<String, Any>>()
        private var peeked: Token? = null

        fun layer(): Map<String, Map<String, Any>> {
            val root = specs.getOrPut("/") { HashMap() }
            if (peek().isPunct("(")) metadata(root)
            body("/", root, depth = 0)
            return specs
        }

        private fun peek(): Token = peeked ?: lexer.next().also { peeked = it }

        private fun next(): Token = peek().also { peeked = null }

        private fun expect(char: String) = require(next().isPunct(char)) { "The USD text is damaged near \"$char\"" }

        private fun word(): String = next().also { require(it.kind == Kind.WORD) { "The USD text is damaged" } }.text

        private fun accept(char: String): Boolean = peek().isPunct(char).also { if (it) next() }

        /** Prims and properties up to the closing brace (or the end, for the layer itself). */
        private fun body(
            path: String,
            fields: MutableMap<String, Any>,
            depth: Int,
        ) {
            while (true) {
                val token = next()
                when {
                    token.kind == Kind.END || token.isPunct("}") -> {
                        // The layer ends at the end of the text, a prim at its closing brace.
                        require((token.kind == Kind.END) == (path == "/")) { "The USD text is damaged" }
                        return
                    }
                    token.isPunct(";") -> Unit
                    token.kind != Kind.WORD -> throw IllegalArgumentException("The USD text is damaged")
                    token.text in SPECIFIERS -> prim(path, token.text, depth)
                    token.text == "variantSet" -> variantSet(path, fields, depth)
                    token.text == "reorder" -> statement()
                    else -> property(path, token.text)
                }
            }
        }

        private fun statement() {
            word()
            expect("=")
            value(0)
        }

        private fun prim(
            parent: String,
            specifier: String,
            depth: Int,
        ) {
            require(depth < MAX_DEPTH) { "The USD text is nested too deeply" }
            val type = if (peek().kind == Kind.WORD) word() else null
            val name = next().also { require(it.kind == Kind.STRING) { "The USD text is damaged" } }.text
            val path = if (parent == "/") "/$name" else "$parent/$name"
            val fields = specs.getOrPut(path) { HashMap() }
            type?.let { fields["typeName"] = it }
            if (peek().isPunct("(")) metadata(fields)
            expect("{")
            if (specifier == "class") {
                skipBlock()
                specs.remove(path)
                return
            }
            body(path, fields, depth + 1)
            if (fields["active"] == "false") specs.keys.removeAll { it == path || it.startsWith("$path/") || it.startsWith("$path.") }
        }

        /** `variantSet "name" = { "variant" { ... } ... }`: only the variant the prim selects is read. */
        private fun variantSet(
            path: String,
            fields: MutableMap<String, Any>,
            depth: Int,
        ) {
            val set = next().text
            expect("=")
            expect("{")
            val selected = (fields["variants"] as? Map<*, *>)?.get(set)
            while (!accept("}")) {
                val variant = next().also { require(it.kind == Kind.STRING) { "The USD text is damaged" } }.text
                if (peek().isPunct("(")) metadata(HashMap())
                expect("{")
                if (variant == selected) body(path, fields, depth + 1) else skipBlock()
            }
        }

        private fun property(
            prim: String,
            first: String,
        ) {
            var word = first
            while (word in QUALIFIERS) word = word()
            if (word == "rel") {
                val name = word()
                val fields = specs.getOrPut("$prim.$name") { HashMap() }
                if (accept("=")) targets(prim, value(0))?.let { fields["targetPaths"] = it }
                if (peek().isPunct("(")) metadata(fields)
                return
            }
            val type = if (accept("[")) word.also { expect("]") } + "[]" else word
            val name = word()
            val attribute = name.substringBeforeLast('.').takeIf { name.endsWith(".connect") || name.endsWith(".timeSamples") } ?: name
            val fields = specs.getOrPut("$prim.$attribute") { HashMap() }
            fields["typeName"] = type
            if (accept("=")) {
                val value = value(0)
                when {
                    name.endsWith(".connect") -> targets(prim, value)?.let { fields["connectionPaths"] = it }
                    name.endsWith(".timeSamples") -> firstSample(value)?.let { fields.putIfAbsent("default", quaternions(type, it)) }
                    value != NONE -> fields["default"] = quaternions(type, value)
                }
            }
            if (peek().isPunct("(")) metadata(fields)
        }

        /** USD writes quaternions real part first; the binary layout, and [Matrix4], put it last. */
        private fun quaternions(
            type: String,
            value: Any,
        ): Any {
            if (!type.startsWith("quat") || value !is Numbers) return value
            val v = value.values
            // Whole quaternions only: a partial one has no real part to move to the front.
            require(v.size % QUAT == 0) { "The USD text is damaged" }
            return Numbers(DoubleArray(v.size) { v[(it - it % QUAT) + (it % QUAT + 1) % QUAT] })
        }

        private fun firstSample(samples: Any): Any? =
            (samples as? Map<*, *>)
                ?.entries
                ?.minByOrNull { (it.key as? String)?.toDoubleOrNull() ?: Double.MAX_VALUE }
                ?.value
                ?.takeIf { it != NONE }

        private fun targets(
            prim: String,
            value: Any,
        ): List<Path>? {
            val paths = (value as? List<*>)?.filterIsInstance<Path>() ?: listOfNotNull(value as? Path)
            return paths.map { Path(resolve(prim, it.path)) }.takeIf { it.isNotEmpty() }
        }

        /** `( key = value ... )`: keeps each key's value in [fields]; doc strings and list edits are passed over. */
        private fun metadata(fields: MutableMap<String, Any>) {
            expect("(")
            while (!accept(")")) {
                val token = next()
                when {
                    token.kind == Kind.STRING || token.isPunct(";") || token.isPunct(",") -> Unit
                    token.isPunct("(") -> skipTo(")")
                    token.kind == Kind.WORD -> entry(if (token.text in LIST_EDITS) word() else token.text, fields)
                    else -> throw IllegalArgumentException("The USD text is damaged")
                }
            }
        }

        private fun entry(
            key: String,
            fields: MutableMap<String, Any>,
        ) {
            if (!accept("=")) return
            val value = value(0)
            if (value != NONE) fields[key] = value
        }

        private fun value(depth: Int): Any {
            require(depth < MAX_DEPTH) { "The USD text is nested too deeply" }
            val token = next()
            return when (token.kind) {
                Kind.NUMBER -> number(token.text)
                Kind.STRING -> token.text
                Kind.PATH -> Path(token.text)
                Kind.ASSET -> Asset(token.text).also { if (peek().kind == Kind.PATH) next() }
                Kind.WORD -> if (token.text == "None") NONE else NUMBER_WORDS[token.text] ?: token.text
                else ->
                    when (token.text) {
                        "(" -> list(")", depth)
                        "[" -> list("]", depth)
                        "{" -> dictionary(depth)
                        else -> throw IllegalArgumentException("The USD text is damaged")
                    }
            }
        }

        /** A list or tuple: [Numbers] when it holds only numbers (at any depth), else a List. */
        private fun list(
            close: String,
            depth: Int,
        ): Any {
            // Mesh data is long runs of numbers, read straight into one array; anything else is read again.
            val start = lexer.at
            val ahead = peeked
            val numbers = DoubleList()
            if (numeric(close, numbers, depth)) return Numbers(numbers.toArray())
            lexer.at = start
            peeked = ahead
            val items = ArrayList<Any>()
            while (!accept(close)) {
                items += value(depth + 1)
                if (!peek().isPunct(close)) expect(",")
            }
            return items
        }

        /** Reads numbers, and lists of them, up to [close] into [into]; false at anything else. */
        private fun numeric(
            close: String,
            into: DoubleList,
            depth: Int,
        ): Boolean {
            require(depth < MAX_DEPTH) { "The USD text is nested too deeply" }
            var token = next()
            while (!token.isPunct(close)) {
                val read =
                    when {
                        token.kind == Kind.NUMBER -> true.also { into.add(number(token.text)) }
                        token.kind == Kind.WORD && token.text in NUMBER_WORDS -> true.also { into.add(NUMBER_WORDS.getValue(token.text)) }
                        token.isPunct("(") -> numeric(")", into, depth + 1)
                        token.isPunct("[") -> numeric("]", into, depth + 1)
                        else -> false
                    }
                if (!read) return false
                token = next()
                if (token.isPunct(",")) {
                    token = next()
                } else if (!token.isPunct(close)) {
                    return false
                }
            }
            return true
        }

        /** `{ type name = value ... }`, or time samples `{ time: value, ... }`, by key. */
        private fun dictionary(depth: Int): Map<String, Any> {
            val entries = HashMap<String, Any>()
            while (!accept("}")) {
                val token = next()
                if (token.isPunct(",") || token.isPunct(";")) continue
                val key =
                    if (token.kind == Kind.NUMBER) {
                        token.text.also { expect(":") }
                    } else {
                        if (accept("[")) expect("]")
                        next().text.also { expect("=") }
                    }
                entries[key] = value(depth + 1)
            }
            return entries
        }

        /** Throws [NumberFormatException], an [IllegalArgumentException], for a malformed number. */
        private fun number(text: String): Double =
            when (text) {
                "-inf" -> Double.NEGATIVE_INFINITY
                "+inf" -> Double.POSITIVE_INFINITY
                else -> java.lang.Double.parseDouble(text)
            }

        /** Skips to the brace closing the one just read. */
        private fun skipBlock() = skipTo("}")

        private fun skipTo(close: String) {
            val closers = ArrayDeque(listOf(close))
            while (closers.isNotEmpty()) {
                val token = next()
                require(token.kind != Kind.END) { "The USD text ends too early" }
                when {
                    token.kind != Kind.PUNCT -> Unit
                    token.text == closers.last() -> closers.removeLast()
                    token.text in OPENERS -> closers.addLast(OPENERS.getValue(token.text))
                }
            }
        }

        private fun resolve(
            anchor: String,
            path: String,
        ): String {
            if (path.startsWith("/")) return path
            var base = anchor
            var rest = path.removePrefix("./")
            while (rest.startsWith("../")) {
                base = base.substringBeforeLast('/').ifEmpty { "/" }
                rest = rest.removePrefix("../")
            }
            return if (base == "/") "/$rest" else "$base/$rest"
        }
    }

    /** A growable array of doubles. */
    private class DoubleList {
        private var values = DoubleArray(INITIAL)
        private var size = 0

        fun add(value: Double) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray(): DoubleArray = values.copyOf(size)
    }

    private val NONE = Any()
    private const val INITIAL = 16
    private const val QUAT = 4
    private const val MAX_DEPTH = 64
    private const val PUNCTUATION = "()[]{}=,;:"
    private const val WORD_EXTRA = "_:."
    private const val NUMBER_START = "-+."
    private const val NUMBER_EXTRA = ".+-"
    private val SPECIFIERS = setOf("def", "over", "class")
    private val QUALIFIERS = setOf("custom", "uniform", "varying", "config", "prepend", "append", "delete", "add")
    private val LIST_EDITS = setOf("prepend", "append", "delete", "add", "reorder")
    private val OPENERS = mapOf("(" to ")", "[" to "]", "{" to "}")
    private val NUMBER_WORDS = mapOf("inf" to Double.POSITIVE_INFINITY, "nan" to Double.NaN)
}
