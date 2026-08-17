// 铁锈代码用法参考 v3：调用方分类 + 值来源(表达式)完整
const fs = require('fs');
const path = require('path');
const BASE = path.join(__dirname, 'app/src/main/assets/data');

const cr = JSON.parse(fs.readFileSync(path.join(BASE, 'code_reference.json'), 'utf8'));
const snip = JSON.parse(fs.readFileSync(path.join(BASE, 'snippets.json'), 'utf8'));

// snippets 权威类型索引
const snipType = {};
for (const [key, val] of Object.entries(snip)) {
  const m = (val.description || '').match(/\[类型:\s*([^\]]+)\]/);
  if (m) snipType[key.startsWith('prop_') ? key.slice(5) : key] = m[1].trim();
}

// code_reference 节的描述翻译（sections_description）
const secDesc = cr.sections_description || {};

// 属性聚合
const propAgg = {};
for (const [sec, cat] of Object.entries(cr.sections)) {
  for (const p of cat.data) {
    (propAgg[p.name] = propAgg[p.name] || []).push({
      sec, type: p.type || '', name_en: p.name_en || '',
      desc: p.desc_zh || p.description || '', example: p.example || ''
    });
  }
}

const norm = s => (s || '').toLowerCase().replace(/\s+/g, '');

// 手工精准归类：无权威类型标注的特殊项，按实际用法语义归类
const MANUAL = {
  '重力': '数值', '初始非制导垂直速度': '数值', '指定攻击地面数量': '数值', '设置身体旋转': '数值',
  '移动延迟': '时间', '重新瞄准在飞行时搜索延迟': '时间',
  '指定攻击地面类型': '枚举', '阵营色模式': '枚举',
  '发送战争快报至所有': '布尔', '排错信息': '文字',
  '在创建时刷抛射体': '抛射体引用', '图像集': '图像',
  'arm#_[time]': '动画引用', 'leg#_[time]': '动画引用', 'body_[time]': '动画引用', 'effect_[time]': '动画引用',
  '定义单位内存': '内存', '@memory': '内存',
  '队伍色相': '数值',
  '锁定': '逻辑判断表',
};

function classifyProp(name, entries) {
  if (MANUAL[name]) return MANUAL[name];
  const e0 = entries[0];
  const en = (e0.name_en || '').toLowerCase();
  const snipT = snipType[name];
  const ct = norm(e0.type);

  if (en === 'autotriggeronevent') return '事件触发';

  // 权威类型（snippets）优先：仅当它是具体/有区分度的类型时才采用，
  // 避免 String/float 等宽泛基础类型抢占 codeType 的准确归类。
  if (snipT) {
    const s = norm(snipT);
    const raw = String(snipT).trim().toLowerCase();
    const c = classifyByAuthoritative(s, raw, ct);
    if (c) return c;
  }

  // codeType 兜底
  const c2 = classifyByCodeType(ct);
  if (c2) return c2;
  return '其他';
}

// 依据 snippets 权威类型（具体类型优先）。raw 为保留空格的原始小写，用于区分 unit ref(标记)/unitref(类型)
function classifyByAuthoritative(s, raw, ct) {
  // 单位类型（填单位类型名）：unitref / unitref/unittype / unittypes（无空格）
  if (s === 'unitref/unittype' || s === 'unittypes' || raw === 'unitref' || raw === 'unit type') return '单位类型引用';
  // 单位标记（填表达式）：unit ref（带空格）/ marker / unit ref/marker / marker ref / unit
  if (raw === 'unit ref' || raw === 'unit ref/marker' || raw === 'marker' || raw === 'marker ref' || raw === 'unit') return '调用单位标记';
  if (s === 'messagetag') return '消息标签';
  if (s === 'tags' || s === 'taglist' || s === 'tagref' || s === 'tag') return '单位标签';
  if (s === 'logicboolean' || s === 'logic' || s === 'logicnumber') return '逻辑判断表';
  if (s === 'actionref' || s === 'actionrefs') return '行动引用';
  if (s === 'time') return '时间';
  if (s === 'effect' || s === 'effectref') return '效果引用';
  if (s === 'sound' || s === 'soundref') return '声音引用';
  if (s === 'projectile' || s === 'projectileref') return '抛射体引用';
  if (s === 'turret' || s === 'turrets' || s === 'turretref') return '炮塔引用';
  if (s === 'animation' || s === 'leg/arm') return '动画引用';
  if (s === 'attachment' || s === 'attachmentref') return '附属引用';
  if (s === 'decal' || s === 'decalref') return '贴花引用';
  if (s === 'image' || s === 'fileimage') return '图像';
  if (s === 'color' || s === 'colour') return '颜色';
  if (s === 'resource') return '资源';
  if (s === 'price' || s === 'prices') return '价格';
  if (s === 'relation') return '关系';
  if (s === 'memory' || s === 'memoryref') return '内存';
  if (s === 'point' || s === 'coordinate') return '坐标';
  return null; // 宽泛类型（bool/int/float/string/number 等）交给 codeType
}

// 依据 code_reference 类型（基础类型兜底）
function classifyByCodeType(ct) {
  if (/^bool/.test(ct) || /^boolean$/.test(ct)) return '布尔';
  if (/^int$|^ints$|^number$|^float$|^logicnumber$/.test(ct)) return '数值';
  if (/price/.test(ct)) return '价格';
  if (/^string/.test(ct) || /^localestring/.test(ct)) return '文字';
  if (/effect/.test(ct)) return '效果引用';
  if (/sound/.test(ct)) return '声音引用';
  if (/projectile/.test(ct)) return '抛射体引用';
  if (/turret/.test(ct)) return '炮塔引用';
  if (/animation/.test(ct) || /^leg\/arm$/.test(ct)) return '动画引用';
  if (/attachment/.test(ct)) return '附属引用';
  if (/decal/.test(ct)) return '贴花引用';
  if (/image|file/.test(ct)) return '图像';
  if (/color|colour/.test(ct)) return '颜色';
  if (/resource|addenergy/.test(ct)) return '资源';
  if (/^time/.test(ct)) return '时间';
  if (/relation/.test(ct)) return '关系';
  if (/memory/.test(ct)) return '内存';
  if (/^enum$|movementtypes|displaytype|normal\|displacement/.test(ct)) return '枚举';
  if (/^point/.test(ct)) return '坐标';
  if (/^list$/.test(ct)) return '列表';
  if (/key.?value/.test(ct)) return '键值对';
  if (/^fields/.test(ct)) return '字段集';
  if (/^action/.test(ct)) return '行动引用';
  if (/^event$/.test(ct)) return '事件引用';
  return null;
}

// 分类顺序
const order = [
  '调用单位标记', '单位类型引用', '单位标签', '消息标签', '事件触发', '逻辑判断表',
  '数值', '价格', '布尔', '文字', '效果引用', '声音引用', '抛射体引用', '炮塔引用',
  '动画引用', '附属引用', '贴花引用', '图像', '颜色', '资源', '时间', '关系', '内存',
  '枚举', '坐标', '列表', '键值对', '字段集', '行动引用', '事件引用', '其他'
];
const groups = {};
order.forEach(k => groups[k] = []);
const unknown = [];
for (const [name, entries] of Object.entries(propAgg)) {
  const c = classifyProp(name, entries);
  if (groups[c]) groups[c].push({ name, entries });
  else unknown.push(name + ' -> ' + c);
}
Object.keys(groups).forEach(k => groups[k].sort((a, b) => a.name.localeCompare(b.name, 'zh')));

function esc(s) { return (s || '').replace(/\|/g, '\\|'); }
function fmtItem(item) {
  const secs = [...new Set(item.entries.map(e => e.sec))];
  const e0 = item.entries[0];
  const L = [];
  L.push(`### ${item.name}`);
  const meta = [];
  if (e0.name_en) meta.push(`**英文** \`${esc(e0.name_en)}\``);
  // 同一属性在不同节类型不同时，按节列出各自类型；否则用 entries[0] 的类型
  const typeBySec = new Map();
  for (const e of item.entries) typeBySec.set(e.sec, e.type);
  const distinctTypes = new Set(item.entries.map(e => e.type).filter(Boolean));
  if (distinctTypes.size > 1) {
    meta.push(`**可按节区分类型**`);
  } else if (e0.type) {
    meta.push(`**类型** \`${esc(e0.type)}\``);
  }
  const st = snipType[item.name];
  if (st) meta.push(`**权威类型** \`${esc(st)}\``);
  meta.push(`**可用节** ${secs.map(s => '`' + s + '`').join(' ')}`);
  if (meta.length) L.push(meta.join(' · '));
  // 按节列出类型（当各节类型不同时）
  if (distinctTypes.size > 1) {
    L.push('');
    for (const e of item.entries) {
      if (e.type) L.push(`- **${e.sec}**：\`${esc(e.type)}\``);
    }
  }
  const descs = [...new Set(item.entries.map(e => e.desc).filter(Boolean))];
  if (descs.length) { L.push(''); L.push(descs.join('\n\n')); }
  const ex = item.entries.find(e => e.example)?.example;
  if (ex) { L.push(''); L.push('```ini'); L.push(ex); L.push('```'); }
  L.push('');
  return L;
}

// ===== 值来源：logicboolean 表达式 =====
const lb = cr.values['logicboolean'];
const lbData = lb ? lb.data : [];
// 表达式类型分组
function exprGroup(p) {
  const t = String(p.type || '').toLowerCase();
  if (t.includes('unit') || t.includes('marker') || t === 'event') return '单位标记表达式';
  if (t.includes('bool')) return '布尔表达式';
  if (t.includes('float') || t.includes('int') || t.includes('num')) return '数值表达式';
  if (t.includes('string')) return '文字表达式';
  if (t.includes('any') || t.includes('same') || t.includes('self only') || t.includes('all')) return '通用表达式';
  if (t.includes('logic')) return '逻辑判断表';
  return '其他表达式';
}
const exprOrder = ['单位标记表达式', '数值表达式', '布尔表达式', '文字表达式', '逻辑判断表', '通用表达式', '其他表达式'];
const exprGroups = {};
exprOrder.forEach(k => exprGroups[k] = []);
for (const p of lbData) exprGroups[exprGroup(p)].push(p);
Object.keys(exprGroups).forEach(k => exprGroups[k].sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh')));

// ===== 去重：以翻译库为准 =====
// 翻译库（translation.txt）是运行时驱动显示的权威数据源。同一表达式分组内，
// 按英文名判定重复：去掉 () 内容（括号内不管是什么）和 self. 前缀后相同的条目视为重复。
// 保留英文名命中翻译库的那条（优先保留本身即为干净形式的条目）；若翻译库 key 本身带 ()
// （极少数特殊条目），则保留带括号的特殊条目。
const trans = {};
// 翻译库 = 原生 translation.txt + 附加翻译表 extra_translation_supplement.txt（两者都是翻译库的一部分）
for (const file of ['translation.txt', 'extra_translation_supplement.txt']) {
  for (const line of fs.readFileSync(path.join(BASE, file), 'utf8').split(/\r?\n/)) {
    const m = line.match(/^([^=]+)=(.*)$/);
    if (m && m[1].trim()) trans[m[1].trim()] = m[2].trim();
  }
}
// 判重 key：去掉括号及内容 + 去掉 self. 前缀
const keyOf = en => String(en || '').replace(/\(.*\)/g, '').replace(/^self\./, '').trim();
for (const k of Object.keys(exprGroups)) {
  const g = exprGroups[k];
  const byKey = {};
  for (const p of g) {
    const n = keyOf(p.name_en || p.name);
    (byKey[n] = byKey[n] || []).push(p);
  }
  const out = [];
  for (const arr of Object.values(byKey)) {
    if (arr.length === 1) { out.push(arr[0]); continue; }
    // 以翻译库为准：优先保留英文名命中翻译库且本身即为干净形式（无 self./括号）的条目
    const nk = a => keyOf(a.name_en || a.name);
    const winner = arr.find(p => nk(p) in trans && String(p.name_en || p.name) === nk(p))
      || arr.find(p => nk(p) in trans)
      || arr[0];
    // 合并其余条目的缺失字段（返回值类型/描述/示例）
    for (const other of arr) {
      if (other === winner) continue;
      if (!winner.type && other.type) winner.type = other.type;
      if (!winner.desc_zh && other.desc_zh) winner.desc_zh = other.desc_zh;
      if (!winner.example && other.example) winner.example = other.example;
    }
    out.push(winner);
  }
  exprGroups[k] = out;
}

// 表达式描述翻译（从 code_reference 聚合值里找中文）
const valueZh = {}; // description key -> 中文
for (const [catName, cat] of Object.entries(cr.values)) {
  const hasType = cat.data.some(x => x.type && String(x.type).trim() !== '');
  for (const p of cat.data) {
    const key = '值@' + catName + '@' + (p.name || '');
    if (p.desc_zh) valueZh[p.name] = p.desc_zh;
  }
}
function exprDesc(p) {
  if (p.desc_zh) return p.desc_zh;
  // 尝试翻译引擎
  return '';
}

function fmtExpr(p) {
  const L = [];
  const en = p.name_en || p.name;
  let h = `### ${esc(p.name)}`;
  if (en && en !== p.name) h += `（\`${esc(en)}\`）`;
  L.push(h);
  if (p.type) L.push(`**返回值类型** \`${esc(p.type)}\``);
  const d = exprDesc(p);
  if (d) { L.push(''); L.push(d); }
  if (p.example) { L.push(''); L.push('```ini'); L.push(p.example); L.push('```'); }
  L.push('');
  return L;
}

const L = [];
L.push('# 铁锈战争 MOD 代码用法参考（按实际用法 v3）');
L.push('');
L.push('> 分两个维度：**调用方**（属性，每个代码接收什么值）与 **值来源**（可被调用的表达式/函数）。');
L.push('> 分类以**自动补全(snippets)的权威类型标注**为基准，并标注每个代码的**可用节**。');
L.push('> 核心区分：**单位标记**(填表达式) vs **单位类型**(填单位名) vs **单位标签**(填字符串标签) vs **消息标签**(带标签发送消息)。');
L.push('');

// 速查表
L.push('## 值类型速查');
L.push('');
const legend = [
  ['调用单位标记', '属性的值填「单位标记表达式」，如 `setCustomTarget1: self.父单位`'],
  ['单位类型引用', '属性的值填「单位类型名」，如 `UI中显示的单位: heavyTank`'],
  ['单位标签', '填字符串标签，如 `tags: 海, 对空`、`临时标签添加: buff`'],
  ['消息标签', '仅 `带标签发送消息`，配合 `自动触发事件: 新消息(需标签=...)`'],
  ['事件触发', '`自动触发事件` + 全部触发条件（含 withActionTag / withTag）'],
  ['逻辑判断表', '填 if/then/else 条件逻辑'],
  ['数值/价格/布尔/文字', '基本值类型'],
  ['效果/声音/抛射体/炮塔/动画/贴花引用', '填资源 ID'],
];
L.push('| 值类型 | 说明 |');
L.push('| --- | --- |');
for (const [k, v] of legend) L.push(`| ${k} | ${v} |`);
L.push('');

// 标签辨析
L.push('## 标签辨析：单位标签 / 消息标签 / 行动标签');
L.push('');
L.push('**三者不是同一个东西，但共享同一套字符串标签系统**（同命名空间，值可互用）：');
L.push('');
L.push('| 类型 | 权威类型 | 用于 | 例子 |');
L.push('| --- | --- | --- | --- |');
L.push('| **单位标签** | `tags` | 单位身上的标签，用于统计/过滤/检索 | `tags: 海, 对空`、`临时标签添加: buff`、`接近单位(需标签="对地威胁")` |');
L.push('| **消息标签** | `Message Tag` | 仅 `带标签发送消息`，配合 `自动触发事件: 新消息(需标签=...)` 回调 | `带标签发送消息: hitZone` → `自动触发事件: 新消息(需标签="hitZone")` |');
L.push('| **行动标签** | —（事件参数） | 仅自动触发事件 `queueItemAdded(withActionTag=...)`，用于队列计数 | `自动触发事件: 队列添加项目(withActionTag="actionFire")` |');
L.push('');
L.push('**关键点**：');
L.push('- 单位标签作用在「单位」上（`tags:` 属性定义、`自身有标签` 检查、`接近单位`/`队伍中此单位数量` 过滤）。');
L.push('- 消息标签作用在「消息」上（发送方 `带标签发送消息` 打标签，接收方 `新消息(需标签)` 匹配回调）。');
L.push('- 二者值都是字符串，可互换（同一个标签既可以是单位标签也可以是消息标签），但**用途语义不同**。');
L.push('');

// ===== 第一部分：值来源 =====
L.push('# 一、值来源（可调用的表达式 / 函数）');
L.push('');
L.push('> 这些是**可被调用**的值来源，可直接填入支持对应类型的属性值位置，支持 `.` 链式续写。');
L.push('');
for (const type of exprOrder) {
  const g = exprGroups[type];
  if (!g.length) continue;
  L.push(`## ${type}（${g.length} 个）`);
  L.push('');
  for (const p of g) L.push(...fmtExpr(p));
  L.push('---'); L.push('');
}

// ===== 第二部分：调用方 =====
L.push('# 二、调用方（属性，每个代码接收什么值）');
L.push('');
L.push('> 每个属性标注「接收值类型」+「可用节」。');
L.push('');
for (const type of order) {
  if (type === '事件触发' || !groups[type].length) continue;
  L.push(`## ${type}（${groups[type].length} 个）`);
  L.push('');
  L.push(`> ${secDesc[type] || ''}`);
  L.push('');
  for (const item of groups[type]) L.push(...fmtItem(item));
  L.push('---'); L.push('');
}

// 事件专节
L.push('## 事件触发');
L.push('');
for (const item of groups['事件触发']) {
  const e0 = item.entries[0];
  L.push(`### ${item.name}`);
  L.push(`**英文** \`${esc(e0.name_en)}\` · **类型** \`${esc(e0.type)}\` · **可用节** 行动`);
  if (e0.desc) { L.push(''); L.push(e0.desc); }
  L.push('');
}
const evCat = cr.values['自动触发事件'];
if (evCat) {
  L.push('### 可用事件（autoTriggerOnEvent 参数）');
  L.push('');
  L.push('| 中文 | 英文 | 标签参数 |');
  L.push('| --- | --- | --- |');
  for (const p of evCat.data) {
    const zh = p.name || ''; const n = p.name_en || zh;
    let tag = '—';
    if (n.includes('withActionTag')) tag = '行动标签（withActionTag）';
    else if (n.includes('withTag')) tag = '标签（withTag）';
    L.push(`| ${esc(zh)} | \`${esc(n)}\` | ${tag} |`);
  }
  L.push('');
}
L.push('---'); L.push('');

// ===== 附录：特定值 =====
L.push('# 三、特定值（枚举选项）');
L.push('');
for (const [catName, cat] of Object.entries(cr.values)) {
  if (catName === 'logicboolean' || catName === '自动触发事件') continue;
  L.push(`## ${catName}`);
  L.push('');
  for (const p of cat.data) {
    L.push(`- **${esc(p.name)}**${(p.name_en && p.name_en !== p.name) ? '（`' + esc(p.name_en) + '`）' : ''}${p.desc_zh ? '：' + p.desc_zh : ''}`);
  }
  L.push('');
}

const outPath = path.join(__dirname, '代码用法参考.md');
fs.writeFileSync(outPath, L.join('\n'), 'utf8');
console.log('=== 值来源(表达式) ===');
for (const k of exprOrder) if (exprGroups[k].length) console.log(k + ':', exprGroups[k].length);
console.log('=== 调用方(属性) ===');
for (const k of order) if (groups[k].length) console.log(k + ':', groups[k].length);
console.log('未归类:', unknown.length); unknown.slice(0, 20).forEach(u => console.log('  ?', u));
console.log('已生成:', outPath);