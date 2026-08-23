#!/usr/bin/env node
/**
 * 补全类型分类硬编码名单生成器。
 *
 * 从 assets/data/code_reference.json 的 17 个节收集全部属性（与分类页同口径：
 * 按 name 去重、首个出现优先），按各补全 Provider 的判定逻辑（移植自
 * feature/completion/value/ 各 Provider，见下方各名单注释）一次性归类，
 * 产出 Kotlin 硬编码名单写入 tools/completion_types_generated.kt。
 *
 * 运行时（CompletionTypeRepository.classify）只做英文名名单查找，不再按 type 推断——
 * type 不可靠，名单即审核结果。数据或判定变更后重新运行本脚本再贴回仓库。
 *
 * 用法: node tools/gen_completion_types.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const DATA = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/assets/data/code_reference.json'), 'utf8'));

// ===== 分类常量（与 CompletionTypeRepository.kt 保持一致） =====
const CAT = {
  BOOL: '布尔值', LOGIC: '逻辑表达式', ENUM: '枚举值', IMAGE: '图片路径',
  EVENT: '事件触发', MEMORY: '内存变量', TAG: '标签', RESOURCE: '资源',
  UNIT_NAME: '单位名', UNIT_MARKER: '单位标记', PROJECT_REF: '项目引用',
  COPY_SECTION: '复制节', TEXT: '文字/任意',
};

// ===== 各 Provider 判定名单（移植，勿凭记忆改，以 Provider 源码为准） =====

// ResourceNameKeys.DEFAULT_KEYS（core/translation/ResourceNameKeys.kt）
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

// UnitSpawnCompletionProvider
const UNIT_SPAWN = ['spawnunits', 'spawnunit', 'produceunits', 'addunitsintotransport', 'attachments_addnewunits'];
const PROJECTILE_SPAWN = ['spawnprojectiles', 'spawnprojectile', 'spawnprojectilesoncreate', 'spawnprojectilesonendoflife', 'spawnprojectilesonexplode'];

// UnitRefValueCompletionProvider
const UNIT_REF_EXPR = [
  'setcustomtarget1', 'setcustomtarget2', 'sendmessageto', 'transporttargetnow',
  'addwaypoint_target_fromreference', 'takeresources_includereference',
  'teleportto', 'fireturretxatground_withtarget', 'alsotriggerorqueueactionwithtarget',
];
const MARKER_POS = ['baseposition', 'drawlineto'];
const MARKER_MIXED = ['unitshowninui', 'textaddunitname', 'descriptionaddfromunit'];

// UnitNameValueCompletionProvider（isUnitNameListProperty：unitsSpawnedOnDeath / overrideAndReplace / upgradedFrom）
const UNIT_NAME_LIST = new Set(['unitsspawnedondeath', 'overrideandreplace', 'upgradedfrom']);
const BUILT_FROM_RE = /^builtFrom_\d+_name$/i;
const UNIT_NAME_TYPE_KW = ['unitref', 'unitreftype', 'unittype', 'unittypes'];

// 合成指令条目（不在 code_reference，由翻译库收录；运行时由对应 Provider 服务）
// @copyFromSection（复制节）/@copyFrom_skipThisSection（复制但跳过节）判定源在 CompletionShared，
// 不再由本脚本生成。

// ResourceValueCompletionProvider.TYPE_KEYWORDS（归一化）
const RESOURCE_TYPE_KW = ['price', 'resource', 'customprice', 'customresource', 'resourceref', 'resources', 'dynamicresources'];

// LogicBooleanValueCompletionProvider 类型判定（归一化）
const LOGIC_TYPES = new Set(['logicboolean', 'logicnumber', 'logic', 'keyvaluepairs', 'fieldsvalues', 'dynamicresources']);

// EnumValueCompletionProvider nameToFileMap / typeToFileMap
const ENUM_NAME_MAP = [
  'movementtype', 'drawtype', 'layer', 'displaytype', 'drawlayer', 'teamcoloringmode',
  'attackmovement', 'onactions', 'onlyteam', 'searchteam', 'addwaypoint_type',
  'addwaypoint_target_nearestunit_team', 'addwaypoint_target_randomunit_team',
  'fireturretxatground_onlyoverpassabletileof', 'turretstargetgroundonlyoverpassabletileof',
  'turretsTargetGroundOnlyOverPassableTileOf'.toLowerCase(),
  'takeresources_includeunitswithinrange_team', 'transportunitsrequiremovementtype',
  'convertto_keepcurrentfields', 'whenbuilding_temporarilyconvertto_keepfields',
  'onnewmapspawn', 'displaydigitgrouping', 'setunitstats', 'autotriggercheckrate',
];
const ENUM_TYPE_MAP = {
  movementtype: 1, movementtypes: 1, drawtype: 1, drawlayer: 1, layer: 1,
  displaytype: 1, teamcoloringmode: 1, attackmovement: 1,
};

// ProjectRefValueCompletionProvider.projectRefKind
function projectRefKind(rawType) {
  const t = rawType.toLowerCase().replace(/ /g, '').replace(/\(s\)/g, 's').replace(/ids/g, 'id');
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

// CopyFromSectionValueCompletionProvider
function isCopyFromSection(nameEn, name) {
  return (nameEn || '').toLowerCase() === '@copyfromsection' ||
    name.toLowerCase() === '@copyfromsection' || name === '复制节';
}

// 共享判定源（core/translation/CompletionShared.kt）已接管的名单：这些分类不再由本脚本生成，
// 而由 CompletionShared 统一判定（Provider 与分类页共用），避免两套名单漂移。
// 生成结果仅保留「非共享、type 驱动」的剩余名单（UNIT_NAME / PROJECT_REF 各缩减后的 EXTRA）。
const SHARED_UNIT_NAME = new Set([...UNIT_SPAWN, ...UNIT_NAME_LIST]);
const SHARED_PROJECT_REF = new Set(PROJECTILE_SPAWN);
const SHARED_UNIT_MARKER = new Set([...UNIT_REF_EXPR, ...MARKER_POS, ...MARKER_MIXED]);

const norm = t => (t || '').replace(/ /g, '').toLowerCase();

/** 归类（顺序即优先级；名单互斥由生成时首中即定保证） */
function classify(p) {
  const name = p.name || '';
  const nameEn = p.name_en || null;
  const en = (nameEn || name).toLowerCase();
  const t = norm(p.type);
  const enKey = (nameEn || name); // 硬编码名单键：英文优先，无英文用原 name

  if (isCopyFromSection(nameEn, name)) return CAT.COPY_SECTION;
  if (en === 'autotriggeronevent') return CAT.EVENT;
  // 内存：属性名（英/中）含 memory/内存（设置/更新/声明单位内存），先于逻辑（kvp 会被逻辑吞掉）
  if (en.includes('memory') || name.includes('内存')) return CAT.MEMORY;
  // 生成类属性（名字精确匹配，type 是 projectile ref 也不能被项目引用抢走——运行时按名字命中）
  // 单位生成(spawnUnits 等)→单位名；抛射体生成(spawnProjectiles*)→项目引用（抛射体节引用，与 projectile/altProjectile 同类）
  if (UNIT_SPAWN.includes(en)) return CAT.UNIT_NAME;
  if (PROJECTILE_SPAWN.includes(en)) return CAT.PROJECT_REF;
  if (projectRefKind(p.type || '')) return CAT.PROJECT_REF;
  if (UNIT_REF_EXPR.includes(en) || MARKER_MIXED.includes(en) || MARKER_POS.includes(en)) return CAT.UNIT_MARKER;
  if (resourceNameKeyMatch(en) || RESOURCE_TYPE_KW.some(k => t.includes(k))) return CAT.RESOURCE;
  if (ENUM_NAME_MAP.includes(en)) return CAT.ENUM;
  if (LOGIC_TYPES.has(t)) return CAT.LOGIC;
  if (UNIT_NAME_LIST.has(en) || BUILT_FROM_RE.test(en) || en === 'name' ||
    UNIT_NAME_TYPE_KW.some(k => t.includes(k))) return CAT.UNIT_NAME;
  if (t === 'bool' || t === 'boolean' || t.startsWith('bool/') || t.startsWith('boolean/') ||
    t.replace(/-\?$/, '') === 'bool' || t.replace(/-\?$/, '') === 'boolean') return CAT.BOOL;
  if (ENUM_TYPE_MAP[t]) return CAT.ENUM;
  if (en.includes('image') || t.includes('image') || t.includes('path')) return CAT.IMAGE;
  {
    const tl = (p.type || '').toLowerCase().trim();
    const namesJoined = [name, nameEn].filter(Boolean).join(' ').toLowerCase();
    if (['tags', 'tag list', 'tag ref', 'message tag'].includes(tl)) return CAT.TAG;
    if ((tl === 'string(s)' || tl === 'strings(s)') && namesJoined.includes('tag')) return CAT.TAG;
  }
  return CAT.TEXT;
}

// ===== 收集属性（与分类页同口径：17 节、按 name 去重、首中优先；过滤文档模板条目） =====
const props = new Map(); // name -> {name, name_en, type, section}
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    if (!props.has(p.name)) props.set(p.name, { ...p, section });
  }
}
// 文档模板条目（name 含 ": " 的 defineUnitMemory 示例）非真实属性，剔除
for (const k of [...props.keys()]) {
  if (k.includes(': ')) props.delete(k);
}

// ===== 归类 =====
const byCat = new Map(Object.values(CAT).map(c => [c, []]));
const keyOf = p => (p.name_en || p.name);
for (const p of props.values()) {
  byCat.get(classify(p)).push(p);
}
// 合成指令条目（@copyfromsection/@copyfrom_skipthissection）判定源在 CompletionShared，
// 由 classify 运行时经 isCopyFromSectionProperty/isCopyFromSkipSectionProperty 处理，不再由脚本生成。

// ===== 审计输出 =====
const order = Object.values(CAT);
console.log('=== 分类统计（共 %d 个属性） ===', props.size);
for (const c of order) {
  console.log(`${c}: ${byCat.get(c).length}`);
}
const cjkRe = /[\u4e00-\u9fff]/;
for (const c of order) {
  const list = byCat.get(c);
  console.log(`\n--- ${c}（${list.length}） ---`);
  for (const p of list) {
    const flag = cjkRe.test(keyOf(p)) ? ' [中文键]' : '';
    console.log(`  ${keyOf(p)} | ${p.name} | type=${p.type || '-'} | 节=${p.section}${flag}`);
  }
}

// ===== 生成 Kotlin 名单（键 = name_en 优先，无英文用 name；小写） =====
const kotlinSetName = {
  [CAT.BOOL]: 'BOOL_PROPERTIES', [CAT.LOGIC]: 'LOGIC_PROPERTIES', [CAT.ENUM]: 'ENUM_PROPERTIES',
  [CAT.IMAGE]: 'IMAGE_PROPERTIES', [CAT.EVENT]: 'EVENT_PROPERTIES', [CAT.MEMORY]: 'MEMORY_PROPERTIES',
  [CAT.TAG]: 'TAG_PROPERTIES', [CAT.RESOURCE]: 'RESOURCE_PROPERTIES', [CAT.UNIT_NAME]: 'UNIT_NAME_PROPERTIES',
  [CAT.UNIT_MARKER]: 'UNIT_MARKER_PROPERTIES', [CAT.PROJECT_REF]: 'PROJECT_REF_PROPERTIES',
  [CAT.COPY_SECTION]: 'COPY_SECTION_PROPERTIES',
};
let out = [];
out.push('// ===== 以下名单由 tools/gen_completion_types.js 从 code_reference.json 生成（勿手改名单本身，');
out.push('//      数据或判定变更后重跑脚本再整体替换）；运行时只做名单查找，不按 type 推断 =====');
for (const c of order) {
  if (c === CAT.TEXT) continue; // 文字/任意为兜底，不列名单
  // 单位标记 / 复制节 已整体下沉 CompletionShared，不再由本脚本生成
  if (c === CAT.UNIT_MARKER || c === CAT.COPY_SECTION) continue;
  // 过滤共享名单已覆盖的条目，仅留 type 驱动的剩余名单（EXTRA）
  let listed = byCat.get(c);
  if (c === CAT.UNIT_NAME) {
    listed = listed.filter(p =>
      !SHARED_UNIT_NAME.has(keyOf(p).toLowerCase()) && !BUILT_FROM_RE.test(keyOf(p)));
  } else if (c === CAT.PROJECT_REF) {
    listed = listed.filter(p => !SHARED_PROJECT_REF.has(keyOf(p).toLowerCase()));
  }
  const names = [...new Set(listed.map(p => keyOf(p).toLowerCase()))].sort();
  if (names.length === 0) continue; // 空分类跳过
  out.push('');
  out.push(`/** ${c} 分类属性（${names.length} 个，键=英文小写；共享部分见 CompletionShared） */`);
  out.push(`val ${kotlinSetName[c]} = setOf(`);
  const perLine = 4;
  for (let i = 0; i < names.length; i += perLine) {
    const chunk = names.slice(i, i + perLine).map(n => `"${n}"`).join(', ');
    out.push('    ' + chunk + (i + perLine < names.length ? ',' : ''));
  }
  out.push(')');
}
fs.writeFileSync(path.join(__dirname, 'completion_types_generated.kt'), out.join('\n') + '\n', 'utf8');
console.log('\n已生成: tools/completion_types_generated.kt');
