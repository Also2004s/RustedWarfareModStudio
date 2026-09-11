#!/usr/bin/env node
/**
 * 运行时名单全量核对：以 CompletionTypeRepository.kt / CompletionShared.kt 的**真实硬编码名单**
 * 为唯一判定源（而非过期的 gen_completion_types.js），对 code_reference 全部属性逐条归类，
 * 输出两类需人工复核项：
 *   A) type 含引用/产出语义关键词 却落入「文字/任意」兜底（疑似漏分类）
 *   B) desc_zh/example 明显暗示应属某类、但与运行时归类不符
 * 用法: node tools/audit_runtime_list.js
 */
const fs = require('fs');
const path = require('path');

const DATA = JSON.parse(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/data/code_reference.json'), 'utf8'));
const KT = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/rwmodstudio/core/translation/CompletionTypeRepository.kt'), 'utf8');
const SH = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/rwmodstudio/core/translation/CompletionShared.kt'), 'utf8');

// ---- 解析 CompletionShared 名单 ----
function parseAndroidSet(file, varName) {
  const m = file.match(new RegExp(`val\\s+${varName}\\s*=\\s*setOf\\((\\s*"[^"]*"(?:\\s*,\\s*"[^"]*")*\\s*)\\)`, 's'));
  if (!m) return new Set();
  const names = [...m[1].matchAll(/"([^"]+)"/g)].map(x => x[1].toLowerCase().trim());
  return new Set(names);
}
const unitSpawnProps = parseAndroidSet(SH, 'unitSpawnProperties');
const projectileSpawnProps = parseAndroidSet(SH, 'projectileSpawnProperties');
const unitNameListProps = parseAndroidSet(SH, 'unitNameListProperties');
const unitRefExprProps = parseAndroidSet(SH, 'unitRefExpressionProperties');
const unitMarkerPosProps = parseAndroidSet(SH, 'unitMarkerPositionProperties');
const unitMarkerMixedProps = parseAndroidSet(SH, 'unitMarkerMixedProperties');

// ---- 解析 CompletionTypeRepository 名单 ----
function parseKtSet(file, varName) {
  const m = file.match(new RegExp(`val\\s+${varName}\\s*=\\s*setOf\\(([\\s\\S]*?)\\)\\s*$`, 'm'));
  if (!m) { /* 跨行带尾注释，退而求其次按块匹配 */ }
  // 用更宽松：取该 val 之后到下一个 ")" 结束
  const idx = file.indexOf(`val ${varName} = setOf(`);
  if (idx < 0) return new Set();
  let depth = 0, end = idx;
  const start = file.indexOf('(', idx);
  for (let i = start; i < file.length; i++) {
    if (file[i] === '(') depth++;
    else if (file[i] === ')') { depth--; if (depth === 0) { end = i; break; } }
  }
  const body = file.slice(start, end + 1);
  return new Set([...body.matchAll(/"([^"]+)"/g)].map(x => x[1].toLowerCase().trim()));
}
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

// 复制节/复制但跳过@ 合成
const COPY = new Set(['@copyfromsection', '复制节']);
const COPY_SKIP = new Set(['@copyfrom_skipthissection', '复制但跳过节']);

function runtimeClassify(p) {
  const name = p.name || ''; const nameEn = p.name_en || '';
  const en = (nameEn || name).toLowerCase();
  if (COPY.has(en) || name === '复制节') return '复制节';
  if (COPY_SKIP.has(en) || name === '复制但跳过节') return '布尔值';
  if (EVENT.has(en)) return '事件触发';
  if (MEMORY.has(en)) return '内存变量';
  if (unitSpawnProps.has(en) && !projectileSpawnProps.has(en)) return '单位名';
  if (unitNameListProps.has(en) || /^builtfrom_\d+_name$/.test(en) || UNIT_NAME.has(en)) return '单位名';
  if (unitRefExprProps.has(en) || unitMarkerPosProps.has(en) || unitMarkerMixedProps.has(en)) return '单位标记';
  if (RESOURCE.has(en)) return '资源';
  if (ENUM.has(en)) return '枚举值';
  if (LOGIC.has(en)) return '逻辑表达式';
  if (projectileSpawnProps.has(en) || PROJECT_REF.has(en)) return '项目引用';
  if (BOOL.has(en)) return '布尔值';
  if (IMAGE.has(en)) return '图片路径';
  if (TAG.has(en)) return '标签';
  return '文字/任意';
}

const props = []; // 去重，name 取首个
const seen = new Set();
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    const key = p.name;
    if (seen.has(key)) continue;
    seen.add(key); props.push({ ...p, section });
  }
}

const refKw = ['ref', 'marker', 'unit', 'projectile', 'effect', 'sound', 'animation', 'turret', 'action', 'decal', 'attachment', 'canbuild', 'leg', 'arm', 'produced', 'spawn', 'upgrad', 'convert', 'replace', 'tag', 'resource', 'memory'];
const title = p => (p.desc_zh || p.description || '') + ' | ' + (p.example || '');

console.log('=== A. type 含引用/产出关键词，但运行时归类为「文字/任意」（疑似漏分类） ===');
let nA = 0;
for (const p of props) {
  const t = String(p.type || '').toLowerCase();
  if (refKw.some(k => t.includes(k))) {
    const cat = runtimeClassify(p);
    if (cat === '文字/任意') {
      nA++;
      console.log(`  [${cat}] ${p.name_en || p.name} | type=${p.type || '-'}`);
      console.log(`      ${title(p).replace(/\n/g, ' / ').slice(0, 140)}`);
    }
  }
}
console.log(`  -> ${nA} 项`);

console.log('\n=== B. desc 暗示明确分类、但与运行时归类不符 ===');
const kw = [
  { cat: '单位名', re: /填.*单位名|单位名|单位类型名/ },
  { cat: '枚举值', re: /options:|枚举|可选.*，可选|关键字/ },
  { cat: '标签', re: /标签/ },
];
let nB = 0;
for (const p of props) {
  const cat = runtimeClassify(p);
  for (const k of kw) {
    if (k.cat !== cat && k.re.test((p.desc_zh || '') + ' ' + (p.example || ''))) {
      nB++;
      console.log(`  [${cat} -> 疑似${k.cat}] ${p.name_en || p.name} | type=${p.type || '-'}`);
      console.log(`      ${title(p).replace(/\n/g, ' / ').slice(0, 140)}`);
      break;
    }
  }
}
console.log(`  -> ${nB} 项`);