const fs = require('fs');
const path = require('path');

const ASSETS_DIR = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data');
const TRANSLATION_FILE = path.join(ASSETS_DIR, 'translation.txt');
const SUPPLEMENT_FILE = path.join(ASSETS_DIR, 'extra_translation_supplement.txt');
const EXTRA_FILE = path.join(ASSETS_DIR, 'extra_completions.json');
const REPORT_FILE = path.join(__dirname, 'missing_extra_en_in_dict.json');

function collectEnKeysFromTranslation(content) {
    const keys = new Set();
    for (let line of content.split(/\r?\n/)) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#')) continue;

        const sectionMatch = trimmed.match(/^\[(.+?)\]\s*=\s*\[(.+?)\]$/);
        if (sectionMatch) {
            keys.add(sectionMatch[1].trim());
            continue;
        }

        if (trimmed.includes('=')) {
            const idx = trimmed.indexOf('=');
            const eng = trimmed.substring(0, idx).trim();
            if (eng) keys.add(eng);
        }
    }
    return keys;
}

function main() {
    const translationContent = fs.readFileSync(TRANSLATION_FILE, 'utf8');
    const dictEnSet = collectEnKeysFromTranslation(translationContent);

    // 补充表已改为 en = zh 文本格式（与 translation.txt 相同），复用同一解析器
    const supplementContent = fs.readFileSync(SUPPLEMENT_FILE, 'utf8');
    for (const en of collectEnKeysFromTranslation(supplementContent)) {
        dictEnSet.add(en);
    }

    const extraItems = JSON.parse(fs.readFileSync(EXTRA_FILE, 'utf8'));
    const missing = [];
    for (const item of extraItems) {
        const nameEn = (item.nameEn || '').trim();
        const name = (item.name || '').trim();
        if (!nameEn) continue;
        if (!dictEnSet.has(nameEn)) {
            missing.push({
                nameEn,
                name,
                category: item.category || []
            });
        }
    }

    console.log(`翻译库英文键总数: ${dictEnSet.size}`);
    console.log(`附件表条目数: ${extraItems.length}`);
    console.log(`未找到翻译的 nameEn 数量: ${missing.length}`);
    if (missing.length > 0) {
        console.log('\n缺失列表:');
        for (const m of missing) {
            console.log(`  ${m.nameEn}  (${m.name})`);
        }
    }

    fs.writeFileSync(REPORT_FILE, JSON.stringify(missing, null, 2), 'utf8');
    console.log(`\n报告已保存: ${REPORT_FILE}`);
}

main();
