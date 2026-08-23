#!/usr/bin/env node
/**
 * 补全类型分类名单验证器。
 *
 * 对 gen_completion_types.js 产出的分类做反向交叉验证：
 * 每个分类有「语义特征」（type 应为何、名字应含何、应在哪个名单），
 * 逐成员核对并报告冲突项（归类与特征矛盾 = 可疑漏判/误判，需人工确认）。
 *
 * 用法: node tools/verify_completion_types.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DATA = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/assets/data/code_reference.json'), 'utf8'));

// 与 gen_completion_types.js 相同的收集口径
const props = new Map();
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    if (!props.has(p.name)) props.set(p.name, { ...p, section });
  }
}
for (const k of [...props.keys()]) {
  if (k.includes(': ')) props.delete(k);
}

// ===== 重新按生成逻辑归类（与 gen 脚本一致） =====
const RESOURCE_NAME_KEYS = [
  'price', 'borrowResourcesWhileAlive', 'borrowResourcesWhileBuilt', 'streamingCost',
  'generation_resources', 'resourceUsage', 'addResources', 'addResourcesScaledByAIHandicaps',
  'addResourcesWithLogic', 'setResourcesWithLogic',
  'mutatorX_addResourcesAreaHit', 'mutatorX_addResourcesDirectHit',
];
function resourceNameKeyMatch(en) {
  return RESOURCE_NAME_KEYS.some(k => {
    if (/^mutatorX_/i.test(k)) {
      return new RegExp('^mutator[0-9]+_' + k.replace(/^mutatorX_/i, '').replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i').test(en);
    }
    return k.toLowerCase() === en.toLowerCase();
  });
}
const UNIT_SPAWN = ['spawnunits', 'spawnunit', 'produceunits', 'addunitsintotransport', 'attachments_addnewunits'];
const PROJECTILE_SPAWN = ['spawnprojectiles', 'spawnprojectile', 'spawnprojectilesoncreate', 'spawnprojectilesonendoflife', 'spawnprojectilesonexplode'];
const UNIT_REF_EXPR = ['setcustomtarget1', 'setcustomtarget2', 'sendmessageto', 'transporttargetnow', 'addwaypoint_target_fromreference', 'takeresources_includereference', 'teleportto', 'fireturretxatground_withtarget', 'alsotriggerorqueueactionwithtarget'];
const MARKER_POS = ['baseposition', 'drawlineto'];
const MARKER_MIXED = ['unitshowninui', 'textaddunitname', 'descriptionaddfromunit'];
const UNIT_NAME_LIST = new Set(['unitsspawnondeath', 'overrideandreplace', 'upgradedfrom']);
const BUILT_FROM_RE = /^builtFrom_\d+_name$/i;
const UNIT_NAME_TYPE_KW = ['unitref', 'unitreftype', 'unittype', 'unittypes'];
const RESOURCE_TYPE_KW = ['price', 'resource', 'customprice', 'customresource', 'resourceref', 'resources', 'dynamicresources'];
const LOGIC_TYPES = new Set(['logicboolean', 'logicnumber', 'logic', 'keyvaluepairs', 'fieldsvalues', 'dynamicresources']);
const ENUM_NAME_MAP = ['movementtype', 'drawtype', 'layer', 'displaytype', 'drawlayer', 'teamcoloringmode', 'attackmovement', 'onactions', 'onlyteam', 'searchteam', 'addwaypoint_type', 'addwaypoint_target_nearestunit_team', 'addwaypoint_target_randomunit_team', 'fireturretxatground_onlyoverpassabletileof', 'turretstargetgroundonlyoverpassabletileof', 'takeresources_includeunitswithinrange_team', 'transportunitsrequiremovementtype', 'convertto_keepcurrentfields', 'whenbuilding_temporarilyconvertto_keepfields', 'onnewmapspawn', 'displaydigitgrouping', 'setunitstats', 'autotriggercheckrate'];
const ENUM_TYPE_MAP = { movementtype: 1, movementtypes: 1, drawtype: 1, drawlayer: 1, layer: 1, displaytype: 1, teamcoloringmode: 1, attackmovement: 1 };
function projectRefKind(rawType) {
  const t = (rawType || '').toLowerCase().replace(/ /g, '').replace(/\(s\)/g, 's').replace(/ids/g, 'id');
  if (t === 'turret' || t.includes('turretref') || t === 'turrets') return 'turret';
  if (t.includes('projectileref') || t === 'projectiles') return 'projectile';
  if (t.includes('effectref') || t === 'effect' || t === 'effects' || t === 'effectsref') return 'effect';
  if (t.includes('actionref') || t === 'actions' || t === 'actionid') return 'action';
  if (t.includes('animationref') || t === 'animationid') return 'animation';
  if (t.includes('decalref') || t === 'decals') return 'decal';
  if (t.includes('attachmentref') || t === 'attachmentid') return 'attachment';
  if (t === 'canbuild' || t.includes('canbuildref')) return 'canbuild';
  if (t.includes('soundref') || t === 'sounds') return 'sound';
  return null;
}
function isCopyFromSection(nameEn, name) {
  return (nameEn || '').toLowerCase() === '@copyfromsection' || name.toLowerCase() === '@copyfromsection' || name === '复制节';
}
const norm = t => (t || '').replace(/ /g, '').toLowerCase();
function classify(p) {
  const name = p.name || '';
  const nameEn = p.name_en || null;
  const en = (nameEn || name).toLowerCase();
  const t = norm(p.type);
  if (isCopyFromSection(nameEn, name)) return '复制节';
  if (en === 'autotriggeronevent') return '事件触发';
  if (en.includes('memory') || name.includes('内存')) return '内存变量';
  if (UNIT_SPAWN.includes(en) || PROJECTILE_SPAWN.includes(en)) return '单位名';
  if (projectRefKind(p.type || '')) return '项目引用';
  if (UNIT_REF_EXPR.includes(en) || MARKER_MIXED.includes(en) || MARKER_POS.includes(en)) return '单位标记';
  if (resourceNameKeyMatch(en) || RESOURCE_TYPE_KW.some(k => t.includes(k))) return '资源';
  if (ENUM_NAME_MAP.includes(en)) return '枚举值';
  if (LOGIC_TYPES.has(t)) return '逻辑表达式';
  if (UNIT_NAME_LIST.has(en) || BUILT_FROM_RE.test(en) || en === 'name' || UNIT_NAME_TYPE_KW.some(k => t.includes(k))) return '单位名';
  if (t === 'bool' || t === 'boolean' || t.startsWith('bool/') || t.startsWith('boolean/') || t.replace(/-\?$/, '') === 'bool' || t.replace(/-\?$/, '') === 'boolean') return '布尔值';
  if (ENUM_TYPE_MAP[t]) return '枚举值';
  if (en.includes('image') || t.includes('image') || t.includes('path')) return '图片路径';
  {
    const tl = (p.type || '').toLowerCase().trim();
    const namesJoined = [name, nameEn].filter(Boolean).join(' ').toLowerCase();
    if (['tags', 'tag list', 'tag ref', 'message tag'].includes(tl)) return '标签';
    if ((tl === 'string(s)' || tl === 'strings(s)') && namesJoined.includes('tag')) return '标签';
  }
  return '文字/任意';
}

const byCat = new Map();
for (const p of props.values()) {
  const c = classify(p);
  if (!byCat.has(c)) byCat.set(c, []);
  byCat.get(c).push(p);
}

// ===== 反向验证规则：每类「语义特征」 =====
const rules = {
  '布尔值': p => /^(bool|boolean)(\/|-\?|$)/i.test(norm(p.type) ? p.type.replace(/ /g, '') : ''),
  '逻辑表达式': p => ['logicboolean', 'logicnumber', 'logic', 'keyvaluepairs', 'fieldsvalues', 'dynamicresources'].includes(norm(p.type)),
  '枚举值': p => ENUM_NAME_MAP.includes((p.name_en || p.name).toLowerCase()) || !!ENUM_TYPE_MAP[norm(p.type)],
  '图片路径': p => (p.name_en || p.name).toLowerCase().includes('image') || norm(p.type).includes('image') || norm(p.type).includes('path'),
  '事件触发': p => (p.name_en || p.name).toLowerCase() === 'autotriggeronevent',
  '内存变量': p => (p.name_en || p.name).toLowerCase().includes('memory') || p.name.includes('内存'),
  '标签': p => ['tags', 'taglist', 'tagref', 'messagetag'].includes(norm(p.type)) || ((p.name_en || p.name).toLowerCase().includes('tag') && ['string(s)', 'strings(s)'].includes((p.type || '').toLowerCase().trim())),
  '资源': p => resourceNameKeyMatch((p.name_en || p.name).toLowerCase()) || RESOURCE_TYPE_KW.some(k => norm(p.type).includes(k)),
  '单位名': p => UNIT_SPAWN.includes((p.name_en || p.name).toLowerCase()) || PROJECTILE_SPAWN.includes((p.name_en || p.name).toLowerCase()) || UNIT_NAME_LIST.has((p.name_en || p.name).toLowerCase()) || BUILT_FROM_RE.test(p.name_en || p.name) || (p.name_en || p.name).toLowerCase() === 'name' || UNIT_NAME_TYPE_KW.some(k => norm(p.type).includes(k)),
  '单位标记': p => UNIT_REF_EXPR.includes((p.name_en || p.name).toLowerCase()) || MARKER_MIXED.includes((p.name_en || p.name).toLowerCase()) || MARKER_POS.includes((p.name_en || p.name).toLowerCase()),
  '项目引用': p => !!projectRefKind(p.type || ''),
  '复制节': p => false, // 合成条目，code_reference 无，跳过
};

// 文字/任意做正向验证：其中不应有本应进其他类的属性
const others = ['bool', 'boolean', 'logicboolean', 'logicnumber', 'logic', 'keyvaluepairs', 'fieldsvalues', 'dynamicresources'];

console.log('===== 反向交叉验证：分类内成员与该类语义特征冲突项 =====\n');
let totalIssues = 0;
for (const [cat, list] of byCat) {
  const rule = rules[cat];
  if (!rule) continue;
  const bad = list.filter(p => !rule(p));
  if (bad.length) {
    console.log(`[${cat}] ${bad.length}/${list.length} 项与语义特征冲突：`);
    for (const p of bad) {
      console.log(`  ✗ ${p.name_en || p.name} | name=${p.name} | type=${p.type || '-'} | 节=${p.section}`);
    }
    totalIssues += bad.length;
  }
}

// 文字/任意 正向检查：type 是 bool/logic/enum-tag/resource-ref 类却落兜底的可疑项
console.log('\n===== [文字/任意] 中 type 语义可疑的漏归档项 =====\n');
const textList = byCat.get('文字/任意') || [];
const suspicious = textList.filter(p => {
  const t = norm(p.type);
  const en = (p.name_en || p.name).toLowerCase();
  if (others.some(o => t === o || t.startsWith(o + '/'))) return true;       // bool/logic 系
  if (['tags', 'taglist', 'tagref', 'messagetag'].includes(t)) return true;  // tag 系
  if (RESOURCE_TYPE_KW.some(k => t === k)) return true;                      // 资源系（精确等于，非 contains）
  if (t.includes('unitref') || t.includes('unittype') || t.includes('unittypes')) return true; // 单位系
  if (projectRefKind(p.type || '')) return true;                             // 项目引用系
  if (['movementtype', 'drawtype', 'drawlayer', 'layer', 'displaytype', 'teamcoloringmode', 'attackmovement'].includes(t)) return true; // 枚举系
  if (en.includes('tag') && ['string(s)', 'strings(s)'].includes((p.type || '').toLowerCase().trim())) return true; // string(s)+tag 名（TagProvider 会命中）
  return false;
});
for (const p of suspicious) {
  console.log(`  ? ${p.name_en || p.name} | name=${p.name} | type=${p.type || '-'} | 节=${p.section}`);
}
console.log(`\n可疑项：${suspicious.length} 个，类内冲突项：${totalIssues} 个`);
