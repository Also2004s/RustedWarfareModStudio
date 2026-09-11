#!/usr/bin/env node
/**
 * 实际项目值域扫描（绝对正确性核对基准）：
 * 1) 加载 code_reference 全部属性（name/name_en/type/desc_zh/example）
 * 2) 递归扫描实际项目 D:\ALSO2004\人机的玩笑\人机的玩笑 全部 .ini/.template + .yaml/.lua
 * 3) 对每个属性，按该行【键】匹配（英文 name_en 或 中文 name），收集其【值】出现频次
 * 4) 输出：当前运行时归类为「文字/任意」的属性，其真实取值集合 —— 用来识别
 *    「实际有明确值域却无补全 Provider」的属性（如 界面显示内部调用displayLocaleKey），
 *    并核对每个引用类属性在真实项目里填的是单位名/单位标记/节名/标签/资源。
 * 用法: node tools/scan_project_values.js
 */
const fs = require('fs');
const path = require('path');

const DATA = JSON.parse(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/data/code_reference.json'), 'utf8'));
const KT = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/rwmodstudio/core/translation/CompletionTypeRepository.kt'), 'utf8');
const SH = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/rwmodstudio/core/translation/CompletionShared.kt'), 'utf8');
const PROJ = 'D:\\ALSO2004\\人机的玩笑\\人机的玩笑';

function parseKtSet(file, varName) {
  const idx = file.indexOf(`val ${varName} = setOf(`);
  if (idx < 0) return new Set();
  let depth = 0; const start = file.indexOf('(', idx);
  for (let i = start; i < file.length; i++) {
    if (file[i] === '(') depth++; else if (file[i] === ')') { depth--; if (depth === 0) return new Set([...file.slice(start, i + 1).matchAll(/"([^"]+)"/g)].map(x => x[1].toLowerCase())); }
  }
  return new Set();
}
function parseAndSet(file, varName) {
  const m = file.match(new RegExp(`val\\s+${varName}\\s*=\\s*setOf\\((\\s*"[^"]*"(?:\\s*,\\s*"[^"]*")*\\s*)\\)`, 's'));
  return m ? new Set([...m[1].matchAll(/"([^"]+)"/g)].map(x => x[1].toLowerCase())) : new Set();
}
const BOOL = parseKtSet(KT, 'BOOL_PROPERTIES'), LOGIC = parseKtSet(KT, 'LOGIC_PROPERTIES'),
  ENUM = parseKtSet(KT, 'ENUM_PROPERTIES'), IMAGE = parseKtSet(KT, 'IMAGE_PROPERTIES'),
  EVENT = parseKtSet(KT, 'EVENT_PROPERTIES'), MEMORY = parseKtSet(KT, 'MEMORY_PROPERTIES'),
  TAG = parseKtSet(KT, 'TAG_PROPERTIES'), RESOURCE = parseKtSet(KT, 'RESOURCE_PROPERTIES'),
  UNIT_NAME = parseKtSet(KT, 'UNIT_NAME_PROPERTIES'), PROJECT_REF = parseKtSet(KT, 'PROJECT_REF_PROPERTIES');
const unitSpawn = parseAndSet(SH, 'unitSpawnProperties'), projectileSpawn = parseAndSet(SH, 'projectileSpawnProperties');
const unitNameList = parseAndSet(SH, 'unitNameListProperties'), unitRefExpr = parseAndSet(SH, 'unitRefExpressionProperties'),
  unitMarkerPos = parseAndSet(SH, 'unitMarkerPositionProperties'), unitMarkerMixed = parseAndSet(SH, 'unitMarkerMixedProperties');

function runtimeClassify(p) {
  const en = ((p.name_en || '') || p.name || '').toLowerCase();
  if (EVENT.has(en)) return '事件触发';
  if (MEMORY.has(en)) return '内存变量';
  if (unitSpawn.has(en) && !projectileSpawn.has(en)) return '单位名';
  if (unitNameList.has(en) || /^builtfrom_\d+_name$/.test(en) || UNIT_NAME.has(en)) return '单位名';
  if (unitRefExpr.has(en) || unitMarkerPos.has(en) || unitMarkerMixed.has(en)) return '单位标记';
  if (RESOURCE.has(en)) return '资源';
  if (ENUM.has(en)) return '枚举值';
  if (LOGIC.has(en)) return '逻辑表达式';
  if (projectileSpawn.has(en) || PROJECT_REF.has(en)) return '项目引用';
  if (BOOL.has(en)) return '布尔值';
  if (IMAGE.has(en)) return '图片路径';
  if (TAG.has(en)) return '标签';
  return '文字/任意';
}

// ==== 收集 code_reference 属性，形成 key->prop 映射（英文小写 & 中文原名都可能作行键） ====
const propByIdent = new Map(); // 键(小写) -> prop
const props = [];
const seen = new Set();
for (const [, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    if (seen.has(p.name)) continue;
    seen.add(p.name);
    props.push(p);
    const en = (p.name_en || '').toLowerCase();
    const zh = String(p.name).trim();
    if (en) propByIdent.set(en, p);
    if (zh) propByIdent.set(zh, p);
  }
}

// ==== 递归扫描项目文件 ====
const files = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full);
    else if (/\.(ini|template)$/i.test(e.name)) files.push(full);
  }
})(PROJ);

// 每属性 -> 值频次图；同时记录 命中哪些文件(前若干)
const valueUsage = new Map(); // ident(lower) -> Map<value, count>
const identUsage = new Map(); // ident(lower) -> 次数
let lineCount = 0;

for (const f of files) {
  let content;
  try { content = fs.readFileSync(f, 'utf8'); } catch { continue; }
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || line.startsWith('[')) continue;
    const ci = line.indexOf(':');
    if (ci <= 0) continue;
    let key = line.slice(0, ci).trim();
    // 多语言后缀 显示名称_zh 等，剥掉 _xxx
    const bareKey = key; 
    const value = line.slice(ci + 1).trim();
    // 去掉键的 _LANG 后缀（如 界面显示名称_en / name_de）
    key = key.replace(/_[a-z]{2}$/i, (m) => (/^_/, '') && m);
    const lower = key.toLowerCase();
    let prop = propByIdent.get(lower) || propByIdent.get(key);
    // 兜底：用剥后缀的英文小写
    if (!prop) prop = propByIdent.get(key.toLowerCase());
    if (!prop) continue;
    lineCount++;
    identUsage.set((prop.name_en||prop.name), (identUsage.get(prop.name_en||prop.name)||0)+1);
    const vid = (prop.name_en||prop.name);
    if (!valueUsage.has(vid)) valueUsage.set(vid, new Map());
    const m = valueUsage.get(vid);
    m.set(value, (m.get(value)||0)+1);
  }
}

// ==== 输出 ====
console.log(`扫描文件 ${files.length} 个，命中属性行 ${lineCount} 行`);
console.log('\n=== 当前「文字/任意」属性，但实际项目有取值（值域 → 判断是否需补全 Provider） ===');
const textProps = props.filter(p => runtimeClassify(p) === '文字/任意');
for (const p of textProps) {
  const vid = p.name_en || p.name;
  const u = valueUsage.get(vid);
  if (!u) continue;
  const vals = [...u.entries()].sort((a,b)=>b[1]-a[1]).slice(0, 8);
  const joined = vals.map(([v,c]) => `${v}(${c})`).join('  ');
  // 移除超长值，聚焦前缀
  const head = p.desc_zh || '';
  console.log(`\n◆ ${vid} | type=${p.type||'-'} | 出现${identUsage.get(vid)}次`);
  console.log(`   值: ${joined.slice(0, 160)}`);
  console.log(`   说明: ${head.slice(0, 80)}`);
}