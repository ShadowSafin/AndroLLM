package io.androllm.engine.compat

/**
 * A minimal Jinja2 renderer covering exactly the constructs used by the
 * official chat templates in [ChatTemplates] (if/elif/else, for+loop.first,
 * set with dotted targets, string concatenation, a few tests and filters).
 *
 * It deliberately rejects anything outside that set instead of silently
 * mis-rendering — a template that needs more is a bug in this file.
 */
class ChatTemplateRenderer {

    /** Message in the shape the app already keeps: role + content (plain strings). */
    data class RenderMessage(val role: String, val content: String)

    /**
     * Renders [template] with [messages] plus a context of the usual variables
     * (`bos_token`, `eos_token`, `add_generation_prompt`, ...).
     *
     * [addGenerationPrompt] appends the family's assistant prefix so the model
     * starts generating in the assistant turn.
     */
    fun render(
        template: String,
        messages: List<RenderMessage>,
        bosToken: String? = null,
        eosToken: String? = null,
        addGenerationPrompt: Boolean = false,
        extraContext: Map<String, Any?> = emptyMap()
    ): String {
        val nodes = Parser(template).parse()
        val ctx = mutableMapOf<String, Any?>(
            "messages" to messages.map { m ->
                mapOf<String, Any?>("role" to m.role, "content" to m.content)
            },
            "add_generation_prompt" to addGenerationPrompt,
            "bos_token" to bosToken,
            "eos_token" to eosToken,
            "include_system" to true,
            "enable_thinking" to true,
            "is_tool_call" to false
        )
        ctx.putAll(extraContext)
        val sb = StringBuilder()
        nodes.forEach { it.render(ctx, sb, null) }
        return sb.toString()
    }

    private sealed class Node {
        abstract fun render(ctx: MutableMap<String, Any?>, sb: StringBuilder, loop: LoopVar?)
    }

    private class TextNode(val text: String) : Node() {
        override fun render(ctx: MutableMap<String, Any?>, sb: StringBuilder, loop: LoopVar?) {
            sb.append(text)
        }
    }

    private class ExprNode(val expr: Expr) : Node() {
        override fun render(ctx: MutableMap<String, Any?>, sb: StringBuilder, loop: LoopVar?) {
            val v = expr.eval(ctx, loop) ?: return
            sb.append(renderValue(v))
        }
    }

    private class IfBranch(val condition: Expr, val body: List<Node>)
    private class IfNode(val branches: List<IfBranch>, val elseBody: List<Node>?) : Node() {
        override fun render(ctx: MutableMap<String, Any?>, sb: StringBuilder, loop: LoopVar?) {
            for (branch in branches) {
                if (isTruthy(branch.condition.eval(ctx, loop))) {
                    branch.body.forEach { it.render(ctx, sb, loop) }
                    return
                }
            }
            elseBody?.forEach { it.render(ctx, sb, loop) }
        }
    }

    private class ForNode(val varName: String, val iterable: Expr, val body: List<Node>) : Node() {
        override fun render(ctx: MutableMap<String, Any?>, sb: StringBuilder, loop: LoopVar?) {
            val items = iterable.eval(ctx, loop)
            val list = when (items) {
                is List<*> -> items
                else -> throw ChatTemplateRenderException("for target is not a list: ${renderValue(items)}")
            }
            for ((i, item) in list.withIndex()) {
                ctx[varName] = item
                val lv = LoopVar(first = i == 0, last = i == list.size - 1, index0 = i)
                body.forEach { it.render(ctx, sb, lv) }
            }
            ctx.remove(varName)
        }
    }

    private class SetNode(val target: String, val value: Expr) : Node() {
        override fun render(ctx: MutableMap<String, Any?>, sb: StringBuilder, loop: LoopVar?) {
            val v = value.eval(ctx, loop)
            val parts = target.split('.')
            if (parts.size == 1) {
                ctx[target] = v
                return
            }
            var holder: Any? = ctx
            for (i in 0 until parts.size - 1) {
                holder = indexInto(holder, parts[i], parts.subList(0, i + 1).joinToString("."))
            }
            when (holder) {
                is Namespace -> holder[parts.last()] = v
                is MutableMap<*, *> -> (holder as MutableMap<String, Any?>)[parts.last()] = v
                else -> throw ChatTemplateRenderException("cannot assign to $target")
            }
        }
    }

    class LoopVar(val first: Boolean, val last: Boolean, val index0: Int)

    /** `namespace(name=value, ...)` — mutable dotted-attribute bag. */
    class Namespace(initial: Map<String, Any?>) {
        private val attrs = initial.toMutableMap()
        operator fun get(name: String): Any? = attrs[name]
        operator fun set(name: String, value: Any?) {
            attrs[name] = value
        }
    }

    // ---- expressions -------------------------------------------------------

    private sealed class Expr {
        abstract fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any?
    }

    private class Literal(val value: Any?) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?) = value
    }

    private class VarRef(val path: List<String>) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            var v: Any? = when (path[0]) {
                "loop" -> loop ?: throw ChatTemplateRenderException("loop used outside for")
                else -> ctx[path[0]]
            }
            for (i in 1 until path.size) {
                v = indexInto(v, path[i], path.subList(0, i + 1).joinToString("."))
            }
            return v
        }
    }

    private class IndexExpr(val base: Expr, val key: Expr) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            val b = base.eval(ctx, loop)
            val k = key.eval(ctx, loop)
            return indexInto(b, k, "indexed value")
        }
    }

    private class ConcatExpr(val parts: List<Expr>) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            val sb = StringBuilder()
            for (p in parts) {
                val v = p.eval(ctx, loop)
                if (v == null) throw ChatTemplateRenderException("cannot concatenate null")
                sb.append(renderValue(v))
            }
            return sb.toString()
        }
    }

    private class CompareExpr(val left: Expr, val op: String, val right: Expr) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            val l = left.eval(ctx, loop)
            val r = right.eval(ctx, loop)
            return when (op) {
                "==" -> l == r
                "!=" -> l != r
                else -> throw ChatTemplateRenderException("unsupported comparison $op")
            }
        }
    }

    private class IsExpr(val left: Expr, val test: String, val negated: Boolean) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            val result = when (test) {
                "none" -> left.eval(ctx, loop) == null
                "string" -> left.eval(ctx, loop) is String
                "true" -> isTruthy(left.eval(ctx, loop))
                "false" -> !isTruthy(left.eval(ctx, loop))
                "defined" -> {
                    val l = left
                    when (l) {
                        is VarRef -> l.path.firstOrNull()?.let { ctx.containsKey(it) } ?: false
                        else -> left.eval(ctx, loop) != null
                    }
                }
                else -> throw ChatTemplateRenderException("unsupported test 'is $test'")
            }
            return if (negated) !result else result
        }
    }

    private class BoolExpr(val left: Expr, val op: String, val right: Expr) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            val l = left.eval(ctx, loop)
            if (op == "and" && !isTruthy(l)) return false
            if (op == "or" && isTruthy(l)) return true
            return isTruthy(right.eval(ctx, loop))
        }
    }

    private class NotExpr(val inner: Expr) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?) = !isTruthy(inner.eval(ctx, loop))
    }

    private class FilterExpr(val inner: Expr, val name: String) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            val v = inner.eval(ctx, loop)
            return when (name) {
                "trim" -> (v as? String)?.trim() ?: throw ChatTemplateRenderException("| trim needs a string")
                "length" -> when (v) {
                    is String -> v.length
                    is List<*> -> v.size
                    else -> throw ChatTemplateRenderException("| length needs a string or list")
                }
                else -> throw ChatTemplateRenderException("unsupported filter | $name")
            }
        }
    }

    private class CallExpr(val name: String, val args: List<Pair<String?, Expr>>) : Expr() {
        override fun eval(ctx: MutableMap<String, Any?>, loop: LoopVar?): Any? {
            if (name == "namespace") {
                val initial = mutableMapOf<String, Any?>()
                for ((key, valueExpr) in args) {
                    val keyName = key ?: throw ChatTemplateRenderException("namespace() needs keyword arguments")
                    initial[keyName] = valueExpr.eval(ctx, loop)
                }
                return Namespace(initial)
            }
            if (name == "raise_exception") {
                val msg = args.joinToString(" ") { renderValue(it.second.eval(ctx, loop)) }
                throw ChatTemplateRenderException(msg)
            }
            throw ChatTemplateRenderException("unsupported call $name()")
        }
    }

    // ---- tokenizer + parser -------------------------------------------------

    private class Token(val kind: Kind, val text: String, val trimLeft: Boolean = false, val trimRight: Boolean = false) {
        enum class Kind { TEXT, EXPR, STMT }
    }

    private class Parser(private val template: String) {

        fun parse(): List<Node> {
            val tokens = tokenize()
            val (nodes, next) = parseStatements(tokens, 0)
            if (next != tokens.size) {
                throw err("unexpected '${TokenScanner(tokens[next].text).nextWord()}' at top level")
            }
            return nodes
        }

        /**
         * Parses consecutive statements starting at tokens[start], descending
         * into nested if/for blocks. Stops WITHOUT consuming the token at the
         * returned index when that token starts a block terminator
         * (endif/elif/else/endfor) — the caller owns the terminator. This is
         * what makes if-inside-for and for-inside-if work (every official
         * template nests these).
         */
        private fun parseStatements(tokens: List<Token>, start: Int): Pair<List<Node>, Int> {
            val nodes = mutableListOf<Node>()
            var i = start
            while (i < tokens.size) {
                val t = tokens[i]
                when (t.kind) {
                    Token.Kind.TEXT -> {
                        nodes += TextNode(t.text)
                        i++
                    }
                    Token.Kind.EXPR -> {
                        nodes += ExprNode(TokenScanner(t.text).parse())
                        i++
                    }
                    Token.Kind.STMT -> {
                        val scanner = TokenScanner(t.text)
                        when (scanner.nextWord()) {
                            "if" -> {
                                val (ifNodes, nextIdx) = parseIf(tokens, i, scanner)
                                nodes += ifNodes
                                i = nextIdx
                            }
                            "for" -> {
                                val (forNodes, nextIdx) = parseFor(tokens, i, scanner)
                                nodes += forNodes
                                i = nextIdx
                            }
                            "set" -> {
                                nodes += parseSet(scanner)
                                i++
                            }
                            "endif", "elif", "else", "endfor" -> return Pair(nodes, i)
                            else -> throw err("unsupported statement '${t.text.trim()}'")
                        }
                    }
                }
            }
            return Pair(nodes, i)
        }

        /** Parses `for X in EXPR ... endfor` starting at tokens[forIndex]. */
        private fun parseFor(
            tokens: List<Token>,
            forIndex: Int,
            scanner: TokenScanner
        ): Pair<List<Node>, Int> {
            val varName = scanner.nextWord() ?: throw err("for needs a variable")
            if (scanner.nextWord() != "in") throw err("for needs 'in'")
            val iterable = scanner.parse()
            var i = forIndex + 1
            val body = mutableListOf<Node>()
            while (true) {
                val (nodes, nextIdx) = parseStatements(tokens, i)
                body += nodes
                i = nextIdx
                if (i >= tokens.size) throw err("unterminated for (missing endfor)")
                when (TokenScanner(tokens[i].text).nextWord()) {
                    "endfor" -> return Pair(listOf(ForNode(varName, iterable, body)), i + 1)
                    "elif", "else", "endif" -> throw err("mismatched block terminator inside for")
                    else -> throw err("unexpected statement inside for")
                }
            }
        }

        /**
         * Parses `if COND ... (elif COND)* (else ...)? endif` starting at
         * tokens[ifIndex].
         */
        private fun parseIf(
            tokens: List<Token>,
            ifIndex: Int,
            ifScanner: TokenScanner
        ): Pair<List<Node>, Int> {
            var condition = ifScanner.parse()
            var i = ifIndex + 1
            val branches = mutableListOf<IfBranch>()
            val currentBody = mutableListOf<Node>()
            var elseBody: List<Node>? = null
            var seenElse = false
            while (true) {
                val (nodes, nextIdx) = parseStatements(tokens, i)
                currentBody += nodes
                i = nextIdx
                if (i >= tokens.size) throw err("unterminated if (missing endif)")
                when (TokenScanner(tokens[i].text).nextWord()) {
                    "elif" -> {
                        if (seenElse) throw err("elif after else")
                        // Copy: currentBody is cleared below and reused for the
                        // next branch — the stored body must be its own list.
                        branches += IfBranch(condition, currentBody.toList())
                        currentBody.clear()
                        val elifScanner = TokenScanner(tokens[i].text)
                        elifScanner.nextWord()
                        condition = elifScanner.parse()
                        i++
                    }
                    "else" -> {
                        if (seenElse) throw err("duplicate else")
                        branches += IfBranch(condition, currentBody.toList())
                        currentBody.clear()
                        seenElse = true
                        i++
                    }
                    "endif" -> {
                        if (seenElse) {
                            elseBody = currentBody.toList()
                        } else {
                            branches += IfBranch(condition, currentBody.toList())
                        }
                        return Pair(listOf(IfNode(branches, elseBody)), i + 1)
                    }
                    "endfor" -> throw err("mismatched endfor inside if")
                    else -> throw err("unexpected statement inside if")
                }
            }
        }

        /** Parses `set TARGET = EXPR`. */
        private fun parseSet(scanner: TokenScanner): Node {
            val target = scanner.nextWord() ?: throw err("set needs a variable")
            if (!scanner.consume('=')) throw err("set needs '=' after '$target'")
            return SetNode(target, scanner.parse())
        }

        private fun tokenize(): List<Token> {
            val out = mutableListOf<Token>()
            var pos = 0
            var textStart = 0
            while (pos < template.length) {
                val openExpr = template.indexOf("{{", pos)
                val openStmt = template.indexOf("{%", pos)
                val next = when {
                    openExpr < 0 && openStmt < 0 -> -1
                    openExpr < 0 -> openStmt
                    openStmt < 0 -> openExpr
                    else -> minOf(openExpr, openStmt)
                }
                if (next < 0) break
                if (next > textStart) {
                    out += Token(Token.Kind.TEXT, template.substring(textStart, next))
                }
                val isExpr = template.startsWith("{{", next)
                val close = template.indexOf(if (isExpr) "}}" else "%}", next + 2)
                if (close < 0) throw err("unterminated tag at offset $next")
                var innerStart = next + 2
                var trimLeft = false
                var trimRight = false
                if (innerStart < template.length && template[innerStart] == '-') {
                    trimLeft = true
                    innerStart++
                }
                var innerEnd = close
                if (innerEnd > innerStart && template[innerEnd - 1] == '-') {
                    trimRight = true
                    innerEnd--
                }
                val inner = template.substring(innerStart, innerEnd).trim()
                if (trimLeft && out.isNotEmpty()) {
                    val last = out.removeAt(out.size - 1)
                    if (last.kind == Token.Kind.TEXT) {
                        val trimmed = last.text.trimEnd()
                        if (trimmed.isNotEmpty()) out += Token(Token.Kind.TEXT, trimmed)
                    } else {
                        out += last
                    }
                }
                out += Token(if (isExpr) Token.Kind.EXPR else Token.Kind.STMT, inner, trimLeft, trimRight)
                pos = close + 2
                textStart = pos
            }
            if (textStart < template.length) out += Token(Token.Kind.TEXT, template.substring(textStart))
            val result = mutableListOf<Token>()
            var pendingWhitespace = false
            for (t in out) {
                if (t.kind == Token.Kind.TEXT && pendingWhitespace) {
                    val trimmed = t.text.trimStart()
                    if (trimmed.isNotEmpty()) result += Token(Token.Kind.TEXT, trimmed)
                    pendingWhitespace = false
                } else {
                    if (t.trimRight) pendingWhitespace = true
                    result += t
                }
            }
            return result
        }

        private fun err(msg: String) = ChatTemplateRenderException(msg)
    }

    /** Hand-rolled expression tokenizer/parser (small, strict). */
    private class TokenScanner(private val src: String) {
        private var pos = 0

        fun nextWord(): String? {
            skipWs()
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '.')) pos++
            return if (pos > start) src.substring(start, pos) else null
        }

        /** Skips whitespace and consumes [c] if it is next; false otherwise. */
        fun consume(c: Char): Boolean {
            skipWs()
            if (pos < src.length && src[pos] == c) {
                pos++
                return true
            }
            return false
        }

        private fun skipWs() {
            while (pos < src.length && (src[pos] == ' ' || src[pos] == '\t')) pos++
        }

        fun parse(): Expr = parseOr()

        private fun parseOr(): Expr {
            var left = parseAnd()
            while (true) {
                skipWs()
                if (src.startsWith("or", pos) && isWordBoundaryAfter("or")) {
                    pos += 2
                    left = BoolExpr(left, "or", parseAnd())
                } else break
            }
            return left
        }

        private fun parseAnd(): Expr {
            var left = parseNot()
            while (true) {
                skipWs()
                if (src.startsWith("and", pos) && isWordBoundaryAfter("and")) {
                    pos += 3
                    left = BoolExpr(left, "and", parseNot())
                } else break
            }
            return left
        }

        private fun parseNot(): Expr {
            skipWs()
            if (src.startsWith("not", pos) && isWordBoundaryAfter("not")) {
                pos += 3
                return NotExpr(parseNot())
            }
            return parseIs()
        }

        private fun parseIs(): Expr {
            var left = parseCompare()
            skipWs()
            if (src.startsWith("is", pos) && isWordBoundaryAfter("is")) {
                pos += 2
                skipWs()
                var negated = false
                if (src.startsWith("not", pos) && isWordBoundaryAfter("not")) {
                    negated = true
                    pos += 3
                    skipWs()
                }
                val wordStart = pos
                while (pos < src.length && src[pos].isLetter()) pos++
                val test = src.substring(wordStart, pos)
                left = IsExpr(left, test, negated)
            }
            return left
        }

        private fun parseCompare(): Expr {
            val left = parseConcat()
            skipWs()
            for (op in listOf("==", "!=")) {
                if (src.startsWith(op, pos)) {
                    pos += 2
                    return CompareExpr(left, op, parseConcat())
                }
            }
            return left
        }

        private fun parseConcat(): Expr {
            val parts = mutableListOf(parseFiltered())
            while (true) {
                skipWs()
                if (pos < src.length && src[pos] == '+') {
                    pos++
                    parts += parseFiltered()
                } else break
            }
            return if (parts.size == 1) parts[0] else ConcatExpr(parts)
        }

        private fun parseFiltered(): Expr {
            var e = parsePrimary()
            while (true) {
                skipWs()
                if (pos < src.length && src[pos] == '|') {
                    pos++
                    skipWs()
                    val nameStart = pos
                    while (pos < src.length && src[pos].isLetter()) pos++
                    val name = src.substring(nameStart, pos)
                    e = FilterExpr(e, name)
                } else break
            }
            return e
        }

        private fun parsePrimary(): Expr {
            skipWs()
            if (pos >= src.length) throw ChatTemplateRenderException("unexpected end of expression: '$src'")
            val c = src[pos]
            when {
                c == '\'' || c == '"' -> return Literal(parseString(c))
                c == '(' -> {
                    pos++
                    val inner = parseOr()
                    skipWs()
                    if (pos >= src.length || src[pos] != ')') throw ChatTemplateRenderException("missing ')' in '$src'")
                    pos++
                    return inner
                }
                c.isDigit() -> {
                    val start = pos
                    while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                    return Literal(src.substring(start, pos).toDouble())
                }
                c.isLetter() || c == '_' -> {
                    val nameStart = pos
                    while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                    val name = src.substring(nameStart, pos)
                    when (name) {
                        "true" -> return Literal(true)
                        "false" -> return Literal(false)
                        "null", "none" -> return Literal(null)
                    }
                    skipWs()
                    if (pos < src.length && src[pos] == '(') {
                        pos++
                        val args = mutableListOf<Pair<String?, Expr>>()
                        skipWs()
                        if (pos < src.length && src[pos] != ')') {
                            args += parseArg()
                            while (true) {
                                skipWs()
                                if (pos < src.length && src[pos] == ',') {
                                    pos++
                                    args += parseArg()
                                } else break
                            }
                        }
                        skipWs()
                        if (pos >= src.length || src[pos] != ')') throw ChatTemplateRenderException("missing ')' after $name in '$src'")
                        pos++
                        return CallExpr(name, args)
                    }
                    var e: Expr = VarRef(listOf(name))
                    while (pos < src.length && src[pos] == '.') {
                        pos++
                        val partStart = pos
                        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                        val part = src.substring(partStart, pos)
                        if (part.isEmpty()) throw ChatTemplateRenderException("bad attribute path in '$src'")
                        val basePath = (e as? VarRef)?.path
                            ?: throw ChatTemplateRenderException("attribute access only supported on identifiers")
                        e = VarRef(basePath + part)
                    }
                    while (pos < src.length && src[pos] == '[') {
                        pos++
                        val key = parseOr()
                        skipWs()
                        if (pos >= src.length || src[pos] != ']') throw ChatTemplateRenderException("missing ']' in '$src'")
                        pos++
                        e = IndexExpr(e, key)
                    }
                    return e
                }
                else -> throw ChatTemplateRenderException("unexpected character '$c' in '$src'")
            }
        }

        private fun parseArg(): Pair<String?, Expr> {
            skipWs()
            val save = pos
            val maybeKey = nextWord()
            if (maybeKey != null) {
                skipWs()
                if (pos < src.length && src[pos] == '=') {
                    pos++
                    return maybeKey to parseOr()
                }
            }
            pos = save
            return null to parseOr()
        }

        private fun parseString(quote: Char): String {
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos]
                if (c == quote) {
                    pos++
                    return sb.toString()
                }
                if (c == '\\') {
                    pos++
                    if (pos >= src.length) break
                    sb.append(
                        when (val esc = src[pos]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '\\' -> '\\'
                            '\'' -> '\''
                            '"' -> '"'
                            else -> esc
                        }
                    )
                    pos++
                } else {
                    sb.append(c)
                    pos++
                }
            }
            throw ChatTemplateRenderException("unterminated string in '$src'")
        }

        private fun isWordBoundaryAfter(word: String): Boolean {
            val idx = pos + word.length
            return idx >= src.length || (!src[idx].isLetterOrDigit() && src[idx] != '_')
        }
    }
}

/** Thrown when a template uses a construct the renderer does not support. */
class ChatTemplateRenderException(message: String, cause: Throwable? = null) : Exception(message, cause)

// File-private helpers shared by the nested node/expr classes. They are kept
// OUTSIDE [ChatTemplateRenderer] so the nested classes can resolve them.
private fun indexInto(container: Any?, key: Any?, what: String): Any? = when (container) {
    is Map<*, *> -> container[key]
    is ChatTemplateRenderer.Namespace -> container[key as String]
    is ChatTemplateRenderer.LoopVar -> when (key) {
        "first" -> container.first
        "last" -> container.last
        "index0" -> container.index0
        else -> throw ChatTemplateRenderException("unknown loop attribute '$key'")
    }
    is List<*> -> {
        val i = (key as? Number)?.toInt()
            ?: throw ChatTemplateRenderException("list index must be a number")
        if (i in container.indices) container[i]
        else throw ChatTemplateRenderException("index $i out of bounds for $what")
    }
    null -> throw ChatTemplateRenderException("$what is null")
    else -> throw ChatTemplateRenderException("cannot index into ${container.javaClass.simpleName}")
}

private fun renderValue(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Boolean -> if (v) "True" else "False"
    is List<*> -> v.joinToString(", ") { renderValue(it) }
    else -> v.toString()
}

private fun isTruthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is String -> v.isNotEmpty()
    is Number -> v.toDouble() != 0.0
    is List<*> -> v.isNotEmpty()
    else -> true
}