#!/usr/bin/env node
/**
 * 混合域属性真实使用核对（绝对正确性基准）：
 * 对真实项目 D:\ALSO2004\人机的玩笑\人机的玩笑 中，所有「引用语义」属性
 * （单位名 / 单位标记表达式 / 单位标记位置 / 单位标记混合 / 单位生成 / 项目引用 等）
 * 逐属性收集其真实取值，并区分形态：
 *   - 纯标识符（单位名/节名/资源名等，不含 . ( ) 空格 逻辑符号）→ 判断是否会被单位名补全覆盖
 *   - 表达式（含 self. 创建标记( 接近单位( . 内存. $ { } 等）→ 判断是否为真正的「混合域」
 * 目的是验证 unitMarkerMixedProperties 等名单是否被真实用法印证（多填纯单位名 vs 多填表达式），
 * 以及是否有属性实际混用单位名+表达式，但当前名单只归了一半。
 * 用法: node tools/audit_mixed_ref_usage.js [可选的属性名关键词过滤]
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
const UNIT_NAME = parseKtSet(KT, 'UNIT_NAME_PROPERTIES');
const PROJECT_REF = parseKtSet(KT, 'PROJECT_REF_PROPERTIES');

// 属性名集合：这一组的「引用语义」关键词
const allRefProps = new Set([
  ...unitSpawn, ...projectileSpawn, ...unitNameList, ...unitRefExpr, ...unitMarkerPos, ...unitMarkerMixed, ...UNIT_NAME, ...PROJECT_REF
]);

// code_reference 属性索引：name/name_en -> prop
const propByIdent = new Map();
const props = [];
const seen = new Set();
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    if (seen.has(p.name)) continue;
    seen.add(p.name);
    props.push(p);
    if (p.name_en) propByIdent.set(p.name_en.toLowerCase(), p);
    propByIdent.set(String(p.name).trim(), p);
  }
}
// name_en 集合（避免 name_en 值如 "unitsSpawnedOnDeath" 与中文 name 混淆，都归一化处理）
const enToZh = new Map();
for (const p of props) if (p.name_en) enToZh.set(p.name_en.toLowerCase(), p.name);

function whichFamily(en) {
  if (unitMarkerMixed.has(en)) return '单位标记[混合]';
  if (unitRefExpr.has(en) || unitMarkerPos.has(en)) return '单位标记[表达式]';
  if (unitSpawn.has(en)) return projectileSpawn.has(en) ? '项目引用[抛射体生成]' : '单位名[生成]';
  if (unitNameList.has(en) || UNIT_NAME.has(en)) return '单位名[列表]';
  if (PROJECT_REF.has(en) || projectileSpawn.has(en)) return '项目引用[节名]';
  return '?';
}

// 词法判定形态：表达式/逻辑特征
const EXPR_RE = /self\.|\.|\(|\)|创建标记|接近单位|范围内|内|之间方向|内存\.|最后伤害源|父单位|@|:/;
const dropsSuf = s => s.replace(/_[a-z]{2}$/i, '$$$&');
const files = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full);
    else if (/\.(ini|template)$/i.test(e.name)) files.push(full);
  }
})(PROJ);

// per-ident: Map<family, Map<en, {name, file, rawLine, value}>>
const usage = new Map(); // en(lower) -> Map<value,count>  但需区分形态，另存样本
const samples = new Map(); // en(lower) -> {family, name, nameZh, values:Map<value,count>}
const filterKw = process.argv[2] ? process.argv[2].toLowerCase() : null;

for (const f of files) {
  let content; try { content = fs.readFileSync(f, 'utf8'); } catch { continue; }
  let inCore = false, curSection = '';
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    const sec = line.match(/^\s*\[(.*?)\]\s*$/);
    if (sec) { curSection = sec[1].trim().toLowerCase(); inCore = curSection === 'core'; continue; }
    if (line.startsWith('#')) continue;
    const ci = line.indexOf(':');
    if (ci <= 0) continue;
    const key = line.slice(0, ci).trim();
    const value = line.slice(ci + 1).trim();
    if (!value) continue;
    const bare = key.toLowerCase();
    let prop = propByIdent.get(bare) || propByIdent.get(key);
    if (!prop) prop = propByIdent.get(key.toLowerCase());
    if (!prop) continue;
    const en = (prop.name_en || '').toLowerCase();
    if (!allRefProps.has(en)) continue;
    if (filterKw && !en.includes(filterKw) && !String(prop.name).toLowerCase().includes(filterKw)) continue;
    const fam = whichFamily(en);
    if (!samples.has(en)) samples.set(en, { family: fam, name: prop.name, nameEn: prop.name_en || prop.name, file: new Set(), values: new Map() });
    const s = samples.get(en);
    s.file.add(path.relative(PROJ, f).replace(/\\/g, '/'));
    s.values.set(value, (s.values.get(value) || 0) + 1);
  }
}

// 输出：按家族分组，每属性列样本 + 形态统计
function isPureIdent(v) {
  // 纯标识符：无表达式特征、无空格逗号逻辑运算符
  if (/[(),]/.test(v)) return false;
  if (/[+*\-/<>=&|]/.test(v)) return false;
  if (!/^[A-Za-z0-9_\-]+$/.test(v)) return false; // 纯英文/数字/下划线→标识符；含中文→可能是单位名或节名
  return true;
}
// 含中文但无表达式特征的，也算「可能是单位名/标签之类可补全项」
function isCJKIdent(v) {
  if (/[(),]/.test(v)) return false;
  if (/[+*\-/<>=&|]/.test(v)) return false;
  return /[\u4e00-\u9fff]/.test(v);
}

const grouped = new Map();
for (const [en, s] of samples) {
  if (filterKw && !en.includes(filterKw) && !s.name.toLowerCase().includes(filterKw)) continue;
  if (!grouped.has(s.family)) grouped.set(s.family, []);
  grouped.get(s.family).push(s);
}

console.log(`扫描文件 ${files.length} 个；命中引用语义属性 ${samples.size} 个\n`);
for (const [family, list] of grouped) {
  console.log(`\n========== ${family} (${list.length}) ==========`);
  const sorted = [...list].sort((a, b) => b.values.size - a.values.size);
  for (const s of sorted) {
    let pure = 0, cjk = 0, expr = 0;
    for (const [v, c] of s.values) {
      if (isPureIdent(v)) pure += c;
      else if (isCJKIdent(v)) cjk += c;
      else expr += c;
    }
    const total = pure + cjk + expr;
    const filesShown = [...s.file].slice(0, 3).join(', ');
    console.log(`\n◈ ${s.nameEn} (${s.name}) · 家族:${s.family} · 命中${total}行 / 去重${s.values.size}值`);
    console.log(`    形态: 纯标识 ${pure} | 中文标识 ${cjk} | 表达式/其他 ${expr}`);
    console.log(`    样例值: ${[...s.values.entries()].sort((a,b)=>b[1]-a[1]).slice(0, 6).map(([v,c])=>`${JSON.stringify(v)}(${c})`).join('  ')}`);
    if (s.values.size <= 12) console.log(`    文件: ${filesShown}`);
  }
}