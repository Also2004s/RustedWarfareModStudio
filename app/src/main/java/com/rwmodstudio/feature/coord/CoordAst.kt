package com.rwmodstudio.feature.coord

sealed class AstNode

data class NumberLiteral(val value: Double) : AstNode()

data class Identifier(val name: String) : AstNode()

data class PropertyAccess(
    val expression: AstNode,
    val property: String
) : AstNode()

data class NamedArgument(
    val name: String,
    val expression: AstNode
)

data class Call(
    val name: String,
    val args: List<NamedArgument>
) : AstNode()

data class BinaryOp(
    val operator: String,
    val left: AstNode,
    val right: AstNode
) : AstNode()

data class UnaryOp(
    val operator: String,
    val operand: AstNode
) : AstNode()
