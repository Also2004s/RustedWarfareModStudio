#!/usr/bin/env node
/**
 * 参数类成员最终验证：复现 CompletionTypeRepository.isParameterMember 判定，
 * 断言 (1) 全部证据属性命中参数类；(2) 参数类不含非表达式类成员（杜绝混入）；
 * (3) 成员数合理非零；(4) 属性类调用源一致性。
 *
 * 参数类 = 唯一值类：该属性的【值】里能写 函数名(xxx) 带括号调用（如 int(a,b)、distance(,)）。
 */
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const DATA = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/assets/data/code_reference.json'), 'utf8'));

// ===== 复现属性类归类（与 gen_completion_types.js / CompletionTypeRepository 一致） =====
const props = new Map();
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    if (!props.has(p.name)) props.set(p.name, { ...p, section });
  }
}
for (const k of [...props.keys()]) if (k.includes(': ')) props.delete(k);

const RESOURCE_NAME_KEYS = ['price', 'borrowResourcesWhileAlive', 'borrowResourcesWhileBuilt', 'streamingCost', 'generation_resources', 'resourceUsage', 'addResources', 'addResourcesScaledByAIHandicaps', 'addResourcesWithLogic', 'setResourcesWithLogic', 'mutatorX_addResourcesAreaHit', 'mutatorX_addResourcesDirectHit'];
const resourceNameKeyMatch = en => RESOURCE_NAME_KEYS.some(k => /mutatorX_/i.test(k)
  ? new RegExp('^mutator[0-9]+_' + k.slice(9).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i').test(en)
  : k.toLowerCase() === en.toLowerCase());
const UNIT_SPAWN = ['spawnunits', 'spawnunit', 'produceunits', 'addunitsintotransport', 'attachments_addnewunits'];
const PROJECTILE_SPAWN = ['spawnprojectiles', 'spawnprojectile', 'spawnprojectilesoncreate', 'spawnprojectilesonendoflife', 'spawnprojectilesonexplode'];
const UNIT_REF_EXPR = ['setcustomtarget1', 'setcustomtarget2', 'sendmessageto', 'transporttargetnow', 'addwaypoint_target_fromreference', 'takeresources_includereference', 'teleportto', 'fireturretxatground_withtarget', 'alsotriggerorqueueactionwithtarget'];
const MARKER_POS = ['baseposition', 'drawlineto'];
const MARKER_MIXED = ['unitshowninui', 'textaddunitname', 'descriptionaddfromunit'];
const UNIT_NAME_LIST = new Set(['unitsspawnedondeath', 'overrideandreplace', 'upgradedfrom']);
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
  if (t.includes('effectref') || ['effect', 'effects', 'effectsref'].includes(t)) return 'effect';
  if (t.includes('actionref') || ['actions', 'actionid'].includes(t)) return 'action';
  if (t.includes('animationref') || t === 'animationid') return 'animation';
  if (t.includes('decalref') || t === 'decals') return 'decal';
  if (t.includes('attachmentref') || t === 'attachmentid') return 'attachment';
  if (t === 'canbuild' || t.includes('canbuildref')) return 'canbuild';
  if (t.includes('soundref') || t === 'sounds') return 'sound';
  return null;
}
const norm = t => (t || '').replace(/ /g, '').toLowerCase();
const BOOL_SET = new Set(), LOGIC_SET = new Set(), ENUM_SET = new Set(), IMAGE_SET = new Set(),
  EVENT_SET = new Set(), MEMORY_SET = new Set(), TAG_SET = new Set(), RESOURCE_SET = new Set(),
  UNIT_NAME_SET = new Set(), UNIT_MARKER_SET = new Set(), PROJECT_REF_SET = new Set(), TEXT_SET = new Set();
for (const p of props.values()) {
  const name = p.name || '';
  const en = (p.name_en || name).toLowerCase();
  const t = norm(p.type);
  const tl = (p.type || '').toLowerCase().trim();
  const namesJoined = [name, p.name_en].filter(Boolean).join(' ').toLowerCase();
  let cat;
  if (en === '@copyfromsection') cat = '复制节';
  else if (en === 'autotriggeronevent') cat = '事件触发';
  else if (en.includes('memory') || name.includes('内存')) cat = '内存变量';
  else if (UNIT_SPAWN.includes(en) || PROJECTILE_SPAWN.includes(en)) cat = '单位名';
  else if (projectRefKind(p.type || '')) cat = '项目引用';
  else if (UNIT_REF_EXPR.includes(en) || MARKER_MIXED.includes(en) || MARKER_POS.includes(en)) cat = '单位标记';
  else if (resourceNameKeyMatch(en) || RESOURCE_TYPE_KW.some(k => t.includes(k))) cat = '资源';
  else if (ENUM_NAME_MAP.includes(en)) cat = '枚举值';
  else if (LOGIC_TYPES.has(t)) cat = '逻辑表达式';
  else if (UNIT_NAME_LIST.has(en) || BUILT_FROM_RE.test(p.name_en || p.name) || en === 'name' || UNIT_NAME_TYPE_KW.some(k => t.includes(k))) cat = '单位名';
  else if (t === 'bool' || t === 'boolean' || t.startsWith('bool/') || t.startsWith('boolean/') || ['bool', 'boolean'].includes(t.replace(/-\?$/, ''))) cat = '布尔值';
  else if (ENUM_TYPE_MAP[t]) cat = '枚举值';
  else if (en.includes('image') || t.includes('image') || t.includes('path')) cat = '图片路径';
  else if (['tags', 'tag list', 'tag ref', 'message tag'].includes(tl) || ((tl === 'string(s)' || tl === 'strings(s)') && namesJoined.includes('tag'))) cat = '标签';
  else cat = '文字/任意';
  ({ '布尔值': BOOL_SET, '逻辑表达式': LOGIC_SET, '枚举值': ENUM_SET, '图片路径': IMAGE_SET, '事件触发': EVENT_SET, '内存变量': MEMORY_SET, '标签': TAG_SET, '资源': RESOURCE_SET, '单位名': UNIT_NAME_SET, '单位标记': UNIT_MARKER_SET, '项目引用': PROJECT_REF_SET, '文字/任意': TEXT_SET }[cat]).add(en);
}
// 合成指令条目（不在 code_reference，运行时由对应 Provider 服务，见 SYNTHETIC_PROPERTIES）
BOOL_SET.add('@copyfrom_skipthissection'); // 复制但跳过节 → Bool 服务补 真/假

// ===== 复现 isParameterMember（唯一值类：参数类） =====
const DYNAMIC_RES = new Set(['addresourceswithlogic', 'setresourceswithlogic']);
const PARAMETER_OWNER = [LOGIC_SET, UNIT_MARKER_SET, MEMORY_SET, EVENT_SET, UNIT_NAME_SET];
const OWNER_CATS = new Set(['逻辑表达式', '单位标记', '内存变量', '事件触发', '单位名']);
function isParameterMember(propCategory, en) {
  en = (en || '').toLowerCase();
  if (DYNAMIC_RES.has(en)) return true;
  return PARAMETER_OWNER.some(s => s.has(en));
}
const propCatOf = en => {
  for (const [cat, set] of [['布尔值', BOOL_SET], ['逻辑表达式', LOGIC_SET], ['枚举值', ENUM_SET], ['图片路径', IMAGE_SET], ['事件触发', EVENT_SET], ['内存变量', MEMORY_SET], ['标签', TAG_SET], ['资源', RESOURCE_SET], ['单位名', UNIT_NAME_SET], ['单位标记', UNIT_MARKER_SET], ['项目引用', PROJECT_REF_SET]])
    if (set.has(en)) return cat;
  return '文字/任意';
};

// ===== 断言 1：证据属性命中参数类 =====
const paramEvidence = [
  // 逻辑表达式
  'requireconditional', 'isvisible', 'frame', 'islocked', 'autotrigger',
  // 单位标记
  'addwaypoint_target_fromreference', 'teleportto', 'baseposition', 'sendmessageto',
  // 内存变量
  'defineunitmemory', 'setunitmemory', 'updateunitmemory',
  // 事件
  'autotriggeronevent',
  // 单位名（产生单位/刷抛射体括号内可带参）
  'produceunits', 'spawnunits', 'unitsspawnedondeath', 'name',
  // 动态资源（RHS 是逻辑表达式，可写带参函数）
  'setresourceswithlogic', 'addresourceswithlogic'
];
let fail = 0;
console.log('=== 断言1：证据属性命中「参数类」 ===');
for (const en of paramEvidence) {
  const ok = isParameterMember(propCatOf(en), en);
  if (!ok) { fail++; console.log(`  ✗ 参数类 缺失 ${en}`); }
}
console.log(fail === 0 ? '  全部命中 ✓' : `  ${fail} 个缺失 ✗`);

// ===== 断言 2：参数类不含非表达式类成员（值不能写带参函数的属性不得计入） =====
console.log('\n=== 断言2：参数类不含混入成员 ===');
let fail2 = 0;
for (const p of props.values()) {
  const en = (p.name_en || p.name).toLowerCase();
  const pc = propCatOf(en);
  if (isParameterMember(pc, en)) {
    if (!(DYNAMIC_RES.has(en) || OWNER_CATS.has(pc))) {
      fail2++; console.log(`  ✗ 混入 ${pc} ${en}`);
    }
  }
}
console.log(fail2 === 0 ? '  无混入 ✓' : `  ${fail2} 个混入 ✗`);

// ===== 断言 3：参数类成员数统计 =====
console.log('\n=== 断言3：参数类成员数（应 > 0） ===');
const nParams = [...props.values()].filter(p => isParameterMember(propCatOf((p.name_en || p.name).toLowerCase()), (p.name_en || p.name).toLowerCase())).length;
console.log(`  参数类: ${nParams}`);
const fail3 = nParams === 0 ? 1 : 0;
console.log(fail3 === 0 ? '  非零 ✓' : '  为零 ✗');

// ===== 断言 4：属性类调用源一致性（关键调用源证据，人工审核固化） =====
const sourceEvidence = {
  '布尔值': ['@copyfrom_skipthissection', 'hidden', 'displayremainingstockpile', 'frameindexrandom'],
  '逻辑表达式': ['requireconditional', 'isvisible', 'frame', 'autotrigger'],
  '单位名': ['unitsSpawnedOnDeath', 'spawnUnits', 'overrideAndReplace', 'name'],
  '单位标记': ['setCustomTarget1', 'teleportTo', 'basePosition', 'sendMessageTo'],
  '资源': ['price', 'borrowResourcesWhileAlive', 'addResourcesWithLogic', 'setResourcesWithLogic'],
  '标签': ['tags', 'sendMessageWithTags', 'temporarilyAddTags'],
  '内存变量': ['defineUnitMemory', 'setUnitMemory', 'updateUnitMemory'],
  '项目引用': ['projectile', 'effectOnDeath', 'playAnimation', 'explodeEffect'],
  '事件触发': ['autoTriggerOnEvent'],
  '图片路径': ['image', 'iconImage'],
  '枚举值': ['movementType', 'drawLayer'],
  '复制节': []
};
const propCatOfEns = en => {
  en = en.toLowerCase();
  for (const [cat, set] of [['布尔值', BOOL_SET], ['逻辑表达式', LOGIC_SET], ['枚举值', ENUM_SET], ['图片路径', IMAGE_SET], ['事件触发', EVENT_SET], ['内存变量', MEMORY_SET], ['标签', TAG_SET], ['资源', RESOURCE_SET], ['单位名', UNIT_NAME_SET], ['单位标记', UNIT_MARKER_SET], ['项目引用', PROJECT_REF_SET]])
    if (set.has(en)) return cat;
  return '文字/任意';
};
console.log('\n=== 断言4：属性类调用源一致性 ===');
let fail4 = 0;
for (const [wantCat, ens] of Object.entries(sourceEvidence)) {
  for (const en of ens) {
    const got = propCatOfEns(en);
    if (got !== wantCat) { fail4++; console.log(`  ✗ ${en} 期望[${wantCat}] 实得[${got}]`); }
  }
}
console.log(fail4 === 0 ? '  全部一致 ✓' : `  ${fail4} 个不一致 ✗`);
process.exit(fail + fail2 + fail3 + fail4 > 0 ? 1 : 0);