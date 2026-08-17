package com.rwmodstudio.feature.coord

fun parseCoordExpression(text: String): AstNode? {
    return try {
        val parser = Parser(text)
        val node = parser.parseExpression()
        parser.expectEof()
        node
    } catch (_: ParseError) {
        null
    }
}

private class ParseError : RuntimeException()

private sealed class Token {
    data class Number(val value: Double) : Token()
    data class Ident(val name: String) : Token()
    data class Op(val op: String) : Token()
    object LParen : Token()
    object RParen : Token()
    object Dot : Token()
    object Comma : Token()
    object Eq : Token()
    object Eof : Token()
}

private class Parser(text: String) {
    private val tokens = lex(text)
    private var index = 0

    fun parseExpression(): AstNode {
        return parseComparison()
    }

    fun expectEof() {
        if (peek() != Token.Eof) throw ParseError()
    }

    private fun parseComparison(): AstNode {
        var node = parseAdditive()
        while (true) {
            node = when (val op = peek()) {
                is Token.Op -> when (op.op) {
                    ">", "<", ">=", "<=", "==", "!=" -> {
                        advance()
                        BinaryOp(op.op, node, parseAdditive())
                    }
                    else -> break
                }
                else -> break
            }
        }
        return node
    }

    private fun parseAdditive(): AstNode {
        var node = parseMultiplicative()
        while (true) {
            node = when (val op = peek()) {
                is Token.Op -> when (op.op) {
                    "+", "-" -> {
                        advance()
                        BinaryOp(op.op, node, parseMultiplicative())
                    }
                    else -> break
                }
                else -> break
            }
        }
        return node
    }

    private fun parseMultiplicative(): AstNode {
        var node = parseUnary()
        while (true) {
            node = when (val op = peek()) {
                is Token.Op -> when (op.op) {
                    "*", "/", "%" -> {
                        advance()
                        BinaryOp(op.op, node, parseUnary())
                    }
                    else -> break
                }
                else -> break
            }
        }
        return node
    }

    private fun parseUnary(): AstNode {
        return when (val op = peek()) {
            is Token.Op -> when (op.op) {
                "+", "-" -> {
                    advance()
                    UnaryOp(op.op, parseUnary())
                }
                else -> parsePostfix()
            }
            else -> parsePostfix()
        }
    }

    private fun parsePostfix(): AstNode {
        var node = parsePrimary()
        while (peek() == Token.Dot) {
            advance()
            val name = expectIdent()
            node = PropertyAccess(node, name)
        }
        return node
    }

    private fun parsePrimary(): AstNode {
        return when (val token = peek()) {
            is Token.Number -> {
                advance()
                NumberLiteral(token.value)
            }
            is Token.Ident -> {
                advance()
                if (peek() == Token.LParen) {
                    parseCall(token.name)
                } else {
                    Identifier(token.name)
                }
            }
            Token.LParen -> {
                advance()
                val node = parseExpression()
                if (peek() != Token.RParen) throw ParseError()
                advance()
                node
            }
            else -> throw ParseError()
        }
    }

    private fun parseCall(name: String): AstNode {
        advance() // consume (
        val args = mutableListOf<NamedArgument>()
        if (peek() != Token.RParen) {
            args.add(parseArgument())
            while (peek() == Token.Comma) {
                advance()
                args.add(parseArgument())
            }
        }
        if (peek() != Token.RParen) throw ParseError()
        advance()
        return Call(name, args)
    }

    private fun parseArgument(): NamedArgument {
        if (peek() is Token.Ident && peek(1) == Token.Eq) {
            val name = (peek() as Token.Ident).name
            advance()
            advance()
            return NamedArgument(name, parseExpression())
        }
        return NamedArgument("", parseExpression())
    }

    private fun expectIdent(): String {
        val token = peek()
        if (token !is Token.Ident) throw ParseError()
        advance()
        return token.name
    }

    private fun peek(offset: Int = 0): Token {
        val i = index + offset
        return if (i < tokens.size) tokens[i] else Token.Eof
    }

    private fun advance() {
        if (index < tokens.size) index++
    }

    companion object {
        private fun lex(text: String): List<Token> {
            val list = mutableListOf<Token>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                        val start = i
                        var hasDot = false
                        if (c == '.') {
                            hasDot = true
                            i++
                        }
                        while (i < text.length && (text[i].isDigit() || (!hasDot && text[i] == '.'))) {
                            if (text[i] == '.') {
                                if (hasDot) break
                                hasDot = true
                            }
                            i++
                        }
                        val value = text.substring(start, i).toDoubleOrNull() ?: throw ParseError()
                        list.add(Token.Number(value))
                    }
                    c.isIdentifierStart() -> {
                        val start = i
                        while (i < text.length && text[i].isIdentifierPart()) i++
                        list.add(Token.Ident(text.substring(start, i)))
                    }
                    c == '+' || c == '-' || c == '*' || c == '/' || c == '%' ||
                        c == '>' || c == '<' || c == '!' || c == '=' -> {
                        val op = when (c) {
                            '>' -> if (i + 1 < text.length && text[i + 1] == '=') ">=" else ">"
                            '<' -> if (i + 1 < text.length && text[i + 1] == '=') "<=" else "<"
                            '!' -> if (i + 1 < text.length && text[i + 1] == '=') "!=" else throw ParseError()
                            '=' -> if (i + 1 < text.length && text[i + 1] == '=') "==" else "="
                            else -> c.toString()
                        }
                        if (op.length == 2) i += 2 else i++
                        if (op == "=") {
                            list.add(Token.Eq)
                        } else {
                            list.add(Token.Op(op))
                        }
                    }
                    c == '(' -> { list.add(Token.LParen); i++ }
                    c == ')' -> { list.add(Token.RParen); i++ }
                    c == '.' -> { list.add(Token.Dot); i++ }
                    c == ',' -> { list.add(Token.Comma); i++ }
                    else -> throw ParseError()
                }
            }
            list.add(Token.Eof)
            return list
        }

        private fun Char.isIdentifierStart(): Boolean = this == '_' || this.isLetter()
        private fun Char.isIdentifierPart(): Boolean = this == '_' || this.isLetterOrDigit()
    }
}
