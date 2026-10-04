// NEW: the worker's own GraalJS grammar certifies statement boundaries and lexical bindings.
package splice.codemode

import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Lexer
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ParserException
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import com.oracle.js.parser.Token
import com.oracle.js.parser.TokenStream
import com.oracle.js.parser.TokenType
import com.oracle.js.parser.ir.FunctionNode
import com.oracle.js.parser.ir.VarNode

internal data class StatementBinding(val name: String, val kind: String)

internal sealed class StatementInput {
    data class Program(
        val source: String,
        val bindings: List<StatementBinding>,
        val compiled: CompiledStatement,
    ) : StatementInput()
    data class Failed(val error: String) : StatementInput()
    data object End : StatementInput()
}

private class StatementErrors : ErrorManager() {
    var diagnostic: String? = null

    override fun message(message: String) {
        diagnostic = message.substringBefore('\n')
    }
}

private data class ParsedStatements(val body: FunctionNode?, val error: String?)

// why: GraalJS 25.3 uses the ES2025 grammar; the boundary parser must accept the executing isolate's syntax.
private const val STATEMENT_ECMASCRIPT_VERSION = 2025
private const val NESTING_TOO_DEEP = "program nesting too deep to parse"
private val STATEMENT_GRAMMAR = ScriptEnvironment.builder()
    .ecmaScriptVersion(STATEMENT_ECMASCRIPT_VERSION).strict(true).build()
private val CONTINUATIONS = setOf(
    TokenType.ELSE, TokenType.CATCH, TokenType.FINALLY,
    TokenType.PERIOD, TokenType.OPTIONAL_CHAIN, TokenType.COMMARIGHT,
    TokenType.LPAREN, TokenType.LBRACKET, TokenType.TEMPLATE, TokenType.TEMPLATE_HEAD,
)
private val TRIVIA = setOf(TokenType.EOF, TokenType.EOL, TokenType.COMMENT, TokenType.DIRECTIVE_COMMENT)

/** Holds the final statement until a complete following token rules out grammar continuation. */
internal class CodeModeStatementParser(private val syntax: CodeModeStatementSyntax) {
    private val pending = StringBuilder()
    private val original = StringBuilder()
    private var complete = false
    private var failure: String? = null
    private var deferredLength = 0

    val isComplete: Boolean get() = complete
    val hasEnded: Boolean get() = complete || failure != null

    /** The stack overflow that reading the program's source raised, in its parse or its compile, if any. The source
     *  gets it as its SyntaxError, and the worker whose thread overflowed is retired once it has replied. */
    var overflow: StackOverflowError? = null
        private set

    fun terminalError(): String? {
        failure?.let { return it }
        return parse(original.toString()).error?.let { "SyntaxError: $it" }
    }

    fun defer(program: StatementInput.Program) {
        pending.insert(0, program.source)
        deferredLength = program.source.length
    }

    fun append(text: String, finished: Boolean, error: String?) {
        check(!complete && failure == null) { "Code-mode source already ended" }
        pending.append(text)
        original.append(text)
        complete = finished
        failure = error
    }

    fun next(): StatementInput? {
        val text = pending.toString()
        val length = if (complete) null else certifiedPrefix(text)
        return when {
            length != null -> {
                val source = text.substring(0, length)
                pending.delete(0, length)
                deferredLength = 0
                program(source, checkNotNull(parse(source).body))
            }
            failure != null -> {
                pending.clear()
                StatementInput.Failed(checkNotNull(failure))
            }
            complete -> finalInput(text)
            else -> null
        }
    }

    private fun finalInput(text: String): StatementInput {
        val parsed = parse(text)
        pending.clear()
        val body = parsed.body
        return when {
            parsed.error != null -> StatementInput.Failed("SyntaxError: ${parsed.error}")
            body == null -> StatementInput.Failed("SyntaxError: Invalid code-mode source")
            body.body.statements.isEmpty() -> StatementInput.End
            else -> program(text, body)
        }
    }

    private fun certifiedPrefix(text: String): Int? {
        val tokens = tokens(text)
        return (1 until tokens.size).asSequence()
            .mapNotNull { index -> candidate(text, tokens[index - 1], tokens[index]) }
            .firstOrNull { it > deferredLength }
    }

    private fun candidate(text: String, previous: Long, next: Long): Int? {
        val nextType = Token.descType(next)
        if (!closedToken(next, text.length)) return null
        if (nextType in CONTINUATIONS || nextType.isOperator(false)) return null
        val finish = Token.descPosition(previous) + Token.descLength(previous)
        val start = Token.descPosition(next)
        val type = Token.descType(previous)
        val lineBreak = text.substring(finish, start).any(Lexer::isEOL)
        return if (canEnd(type, lineBreak)) parsedBoundary(text, start, type, lineBreak) else null
    }

    private fun canEnd(type: TokenType, lineBreak: Boolean): Boolean = when (type) {
        TokenType.SEMICOLON, TokenType.RBRACE -> true
        else -> lineBreak
    }

    private fun parsedBoundary(text: String, start: Int, type: TokenType, lineBreak: Boolean): Int? {
        val parsed = parse(text.substring(0, start)).body ?: return null
        if (parsed.body.statements.isEmpty()) return null
        val needsCompound = type == TokenType.RBRACE && !lineBreak
        return if (needsCompound && !compoundEnd(parsed)) null else start
    }

    private fun compoundEnd(parsed: FunctionNode): Boolean = when (val last = parsed.body.statements.last()) {
        is com.oracle.js.parser.ir.ExpressionStatement -> false
        is VarNode -> last.isFunctionDeclaration || last.isClassDeclaration
        else -> true
    }

    private fun closedToken(token: Long, length: Int): Boolean =
        Token.descPosition(token) + Token.descLength(token) < length

    private fun parse(text: String): ParsedStatements {
        val errors = StatementErrors()
        val body = try {
            Parser(STATEMENT_GRAMMAR, Source.sourceFor("splice-statement", text), errors).parseFunctionBody(false, true)
        } catch (failure: StackOverflowError) {
            // GraalJS's parser recurses per nesting level; a deep program is the program's error, not the worker's.
            overflow = failure
            return ParsedStatements(null, NESTING_TOO_DEEP)
        }
        val diagnostic = errors.parserException?.message?.substringBefore('\n') ?: errors.diagnostic
        val valid = !errors.hasErrors() && syntax(text)
        return if (valid) {
            ParsedStatements(body, diagnostic)
        } else {
            ParsedStatements(null, diagnostic ?: "Invalid or incomplete statement")
        }
    }

    private fun program(text: String, body: FunctionNode): StatementInput {
        val functions = body.body.statements.filterIsInstance<VarNode>()
            .filter(VarNode::isFunctionDeclaration).map { it.name.name }.toSet()
        val bindings = body.body.symbols
            .filterNot { it.isInternal || it.isArguments }
            .filterNot { it.isThis || it.isNewTarget }
            .map { symbol ->
                val kind = when {
                    symbol.name in functions -> "function"
                    symbol.isConst -> "const"
                    symbol.isLet -> "let"
                    else -> "var"
                }
                StatementBinding(symbol.name, kind)
            }
        val compiled = try {
            CodeModeStatementCompiler(text, body).compile()
        } catch (failure: StackOverflowError) {
            // The compiler walks the parsed tree recursively, so a long member or call chain overflows it.
            overflow = failure
            return StatementInput.Failed("SyntaxError: $NESTING_TOO_DEEP")
        }
        return StatementInput.Program(text, bindings, compiled)
    }

    /**
     * The tokens of [text]. lexify() also returns before the stream is full, after every token that can open a
     * regular expression (only a parser can decide one), so the stream grows only when it is full, as GraalJS's
     * own Parser grows it. A pass that adds no token, or more tokens than [text] has characters, ends the listing:
     * a shorter listing certifies fewer boundaries, and the parse of the completed source decides the rest.
     */
    private fun tokens(text: String): List<Long> {
        val stream = TokenStream()
        val lexer = Lexer(
            Source.sourceFor("splice-statement", text),
            stream,
            false,
            STATEMENT_ECMASCRIPT_VERSION,
            false,
            false,
            true,
            true,
        )
        try {
            var listed = 0
            do {
                if (stream.isFull) stream.grow()
                val before = listed
                lexer.lexify()
                listed = stream.last() + 1
                val open = listed in before + 1..text.length + 1 &&
                    Token.descType(stream.get(stream.last())) != TokenType.EOF
            } while (open)
        } catch (_: ParserException) {
            // A partial string or template can follow already closed statements.
        }
        return (0..stream.last()).map(stream::get).filterNot { Token.descType(it) in TRIVIA }
    }
}
