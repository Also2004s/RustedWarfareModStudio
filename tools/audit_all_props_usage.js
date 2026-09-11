#!/usr/bin/env node
/**
 * 全量遗漏审计：对所有属性收集真实项目取值，按「去重值数/离散度」衡量，
 * 找出「有明确离散值域但当前无补全/或归类不准」的属性。
 * 重点定位：
 *   A) 落「文字/任意」但真实值离散度低（≤N 个不同值，且非纯数字）→ 疑似缺枚举/布尔/资源/节名补全
 *   B) 所有属性真实值形态与分类严重不符（如 归单位名但填表达式，归项目引用但填数字索引）
 * 用法: node tools/audit_all_props_usage.js
 */
const fs = require('fs');
const path = require('path');
const DATA = JSON.parse(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/data/code_reference.json'), 'utf8'));
const SH = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/rwmodstudio/core/translation/CompletionShared.kt'), 'utf8');
const KT = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/rwmodstudio/core/translation/CompletionTypeRepository.kt'), 'utf8');
const PROJ = 'D:\\ALSO2004\\人机的玩笑\\人机的玩笑';

function parseAndroidSet(file, varName) {
  const m = file.match(new RegExp(`val\\s+${varName}\\s*=\\s*setOf\\(([\\s\\S]*?)\\)`));
  if (!m) return new Set();
  return new Set([...m[1].matchAll(/"([^"]+)"/g)].map(x => x[1].toLowerCase().trim()));
}
function parseKtSet(file, varName) {
  const idx = file.indexOf(`val ${varName} = setOf(`);
  if (idx < 0) return new Set();
  let depth = 0; const start = file.indexOf('(', idx);
  for (let i = start; i < file.length; i++) {
    if (file[i] === '(') depth++;
    else if (file[i] === ')') { depth--; if (depth === 0) return new Set([...file.slice(start, i + 1).matchAll(/"([^"]+)"/g)].map(x => x[1].toLowerCase().trim())); }
  }
  return new Set();
}
const unitSpawn = parseAndroidSet(SH, 'unitSpawnProperties');
const projectileSpawn = parseAndroidSet(SH, 'projectileSpawnProperties');
const unitNameList = parseAndroidSet(SH, 'unitNameListProperties');
const unitRefExpr = parseAndroidSet(SH, 'unitRefExpressionProperties');
const unitMarkerPos = parseAndroidSet(SH, 'unitMarkerPositionProperties');
const unitMarkerMixed = parseAndroidSet(SH, 'unitMarkerMixedProperties');
const BOOL = parseKtSet(KT, 'BOOL_PROPERTIES');
const LOGIC = parseKtSet(KT, 'LOGIC_PROPERTIES');
const ENUM = parseKtSet(KT, 'ENUM_PROPERTIES');
const IMAGE = parseKtSet(KT, 'IMAGE_PROPERTIES');
const EVENT = parseKtSet(KT, 'EVENT_PROPERTIES');
const MEMORY = parseKtSet(KT, 'MEMORY_PROPERTIES');
const TAG = parseKtSet(KT, 'TAG_PROPERTIES');
const RESOURCE = parseKtSet(KT, 'RESOURCE_PROPERTIES');
const UNIT_NAME = parseKtSet(KT, 'UNIT_NAME_PROPERTIES');
const PROJECT_REF = parseKtSet(KT, 'PROJECT_REF_PROPERTIES');
const COPY = new Set(['@copyfromsection', '复制节']);
const COPY_SKIP = new Set(['@copyfrom_skipthissection', '复制但跳过节']);

function runtimeClassify(p) {
  const name = p.name || ''; const nameEn = p.name_en || '';
  const en = (nameEn || name).toLowerCase();
  if (COPY.has(en) || name === '复制节') return '复制节';
  if (COPY_SKIP.has(en) || name === '复制但跳过节') return '布尔值';
  if (EVENT.has(en)) return '事件触发';
  if (MEMORY.has(en)) return '内存变量';
  if (unitSpawn.has(en) && !projectileSpawn.has(en)) return '单位名[生成]';
  if (unitNameList.has(en) || /^builtfrom_\d+_name$/.test(en) || UNIT_NAME.has(en)) return '单位名[列表]';
  if (unitRefExpr.has(en) || unitMarkerPos.has(en)) return '单位标记[表达式]';
  if (unitMarkerMixed.has(en)) return '单位标记[混合]';
  if (RESOURCE.has(en)) return '资源';
  if (ENUM.has(en)) return '枚举值';
  if (LOGIC.has(en)) return '逻辑表达式';
  if (projectileSpawn.has(en) || PROJECT_REF.has(en)) return '项目引用[节名]';
  if (BOOL.has(en)) return '布尔值';
  if (IMAGE.has(en)) return '图片路径';
  if (TAG.has(en)) return '标签';
  return '文字/任意';
}

const propByIdent = new Map();
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    if (p.name_en) propByIdent.set(p.name_en.toLowerCase(), p);
    propByIdent.set(String(p.name).trim(), p);
    propByIdent.set(String(p.name).trim().toLowerCase(), p);
  }
}

// 收集全部真实取值：enLower -> Map<value,count>
const usage = new Map(); // en -> Map<value,count>  和 文件数
const usageFiles = new Map();
const files = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full);
    else if (/\.(ini|template)$/i.test(e.name)) files.push(full);
  }
})(PROJ);

for (const f of files) {
  let content; try { content = fs.readFileSync(f, 'utf8'); } catch { continue; }
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || /^\s*\[/.test(line)) continue;
    const ci = line.indexOf(':');
    if (ci <= 0) continue;
    const key = line.slice(0, ci).trim().toLowerCase();
    const value = line.slice(ci + 1).trim();
    if (!value) continue;
    const prop = propByIdent.get(key);
    if (!prop || !prop.name_en) continue;
    const en = prop.name_en.toLowerCase();
    if (!usage.has(en)) { usage.set(en, new Map()); usageFiles.set(en, new Set()); }
    usage.get(en).set(value, (usage.get(en).get(value) || 0) + 1);
    usageFiles.get(en).add(f);
  }
}

function isNumeric(v) {
  return /^-?\d*\.?\d+(%|s|px|px\/s)?$/.test(v) || /^auto$/i.test(v);
}
function isPureWord(v) { // 单标识符（无逗号/括号/空格运算符）
  if (/[,()]/.test(v)) return false;
  if (/[+*=<>&|]/.test(v)) return false;
  if (/\s/.test(v)) return false;
  return true;
}
// 该属性真实值全部为数字形态
function allNumeric(vals) { return [...vals].every(v => isNumeric(v)); }

// 收集「文字/任意」且有真实取值，且非全数字，且去重值数少 → 疑似缺枚举
const candidates = [];
for (const [en, vals] of usage) {
  if (runtimeClassify(propByIdent.get(en)) !== '文字/任意') continue;
  if ([...vals.values()].reduce((a,b)=>a+b,0) < 2) continue; // 至少2次
  if (allNumeric(vals)) continue; // 纯数字 → 正常数值
  const distinct = vals.size;
  candidates.push({ en, name: propByIdent.get(en).name, type: propByIdent.get(en).type || '-', distinct, total: [...vals.values()].reduce((a,b)=>a+b,0), vals });
}
candidates.sort((a,b)=>a.distinct-b.distinct);

console.log(`扫描文件 ${files.length}；「文字/任意」且有真实取值且非纯数字的属性 ${candidates.length} 个（按去重值数升序）\n`);
for (const c of candidates) {
  const top = [...c.vals.entries()].sort((a,b)=>b[1]-a[1]).slice(0, 6).map(([v,n])=>`${JSON.stringify(v.slice(0,40))}×${n}`).join('  ');
  console.log(`◈ ${c.name} (${c.en}) type=${c.type} · 去重${c.distinct}值/${c.total}行`);
  console.log(`    ${top}`);
}

// 形态核查：分类 vs 真实形态冲突
console.log('\n\n===== B. 分类形态冲突核查 ====');
const famExpr = ['单位标记[表达式]', '单位标记[混合]', '逻辑表达式'];
function containsExpr(v) { return /\.|\(\)|创建标记|接近单位|范围内|内存\.|最后伤害源|父单位|self\.|通道|获取|设置|比较|资源\.|主核|拥有|存|区块|返回|阵营|单位组|所有|任意|第一个|计数/.test(v); }
let nB = 0;
for (const [en, vals] of usage) {
  const cat = runtimeClassify(propByIdent.get(en));
  const samples = [...vals.entries()].sort((a,b)=>b[1]-a[1]).slice(0, 4);
  // 1) 归「单位名[生成]/[列表]」但含顺序/索引式值的很少见，跳过
  // 2) 归「项目引用[节名]」但真实几乎全数字（索引式，非节名）
  if (cat === '项目引用[节名]') {
    const nonNumeric = [...vals.entries()].filter(([v]) => !isPureWord(v) || /^\d+$/.test(v)).length;
    const numericOnly = [...vals.keys()].every(v => /^\d+$/.test(v));
    if (numericOnly && vals.size > 1) {
      nB++;
      console.log(`  [项目引用 → 疑似数字索引] ${en} (${propByIdent.get(en).name}) · 样例: ${samples.map(([v,n])=>`${v}×${n}`).join(' ')}`);
    }
  }
  // 3) 归「单位名」但真实含表达式形态（如 创建标记/self 不含在 spawn 量里）→ 需人工看
}
console.log(`  -> 冲突项 ${nB}`);