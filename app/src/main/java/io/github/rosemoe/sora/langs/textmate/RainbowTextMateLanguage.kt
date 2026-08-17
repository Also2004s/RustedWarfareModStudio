package io.github.rosemoe.sora.langs.textmate

import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry

/**
 * 包装 [TextMateLanguage]，在构造完成后将其分析器替换为 [RainbowTextMateAnalyzer]，
 * 从而在不破坏原有 INI 高亮/补全行为的前提下启用彩虹括号。
 */
class RainbowTextMateLanguage private constructor(
    grammar: org.eclipse.tm4e.core.grammar.IGrammar,
    configuration: org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration?,
    grammarRegistry: GrammarRegistry,
    themeRegistry: ThemeRegistry,
    collectIdentifiers: Boolean
) : TextMateLanguage(grammar, configuration, grammarRegistry, themeRegistry, collectIdentifiers) {

    init {
        // 父类构造时已经创建了默认的 TextMateAnalyzer；
        // 这里先销毁旧实例，再替换为彩虹括号版本，避免在 ThemeRegistry 中残留监听器。
        textMateAnalyzer?.let { old ->
            old.setReceiver(null)
            old.destroy()
        }
        val scopeName = grammar.scopeName
        val g = grammarRegistry.findGrammar(scopeName) ?: grammar
        val cfg = grammarRegistry.findLanguageConfiguration(g.scopeName)
        textMateAnalyzer = RainbowTextMateAnalyzer(this, g, cfg, themeRegistry)
    }

    companion object {
        @JvmStatic
        fun create(scopeName: String, collectIdentifiers: Boolean): RainbowTextMateLanguage {
            val grammarRegistry = GrammarRegistry.getInstance()
            val themeRegistry = ThemeRegistry.getInstance()
            val grammar = grammarRegistry.findGrammar(scopeName)
                ?: throw IllegalArgumentException("Language with scope name $scopeName not found")
            val configuration = grammarRegistry.findLanguageConfiguration(grammar.scopeName)
            return RainbowTextMateLanguage(
                grammar,
                configuration,
                grammarRegistry,
                themeRegistry,
                collectIdentifiers
            )
        }
    }
}
