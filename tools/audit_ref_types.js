#!/usr/bin/env node
/**
 * 一次性审计：从 code_reference.json 提取全部属性，按 type 归类，
 * 重点标出「type 暗示引用/单位/标记/产出语义 但 example 可能与 type 冲突」的属性，供人工核对。
 * 用法: node tools/audit_ref_types.js
 */
const fs = require('fs');
const path = require('path');
const DATA = JSON.parse(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/data/code_reference.json'), 'utf8'));

const props = []; // {name, name_en, type, example, desc_zh, section}
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) props.push({ ...p, section });
}

// 去重：name 相同取首个（与 gen_completion_types 一致）
const seen = new Set();
const uniq = props.filter(p => (seen.has(p.name) ? false : (seen.add(p.name), true)));

console.log('=== type 分布（全部 %d 个属性） ===', uniq.length);
const dist = new Map();
for (const p of uniq) dist.set(p.type || '(空)', (dist.get(p.type || '(空)') || 0) + 1);
for (const [t, n] of [...dist.entries()].sort((a, b) => a[0].localeCompare(b[0], 'en'))) {
  console.log(`  ${JSON.stringify(t)}: ${n}`);
}

// 引用语义关键词（这些 type 需要有真正的引用名/单位名语义，example 最能佐证）
const refKw = ['ref', 'marker', 'unit', 'projectile', 'effect', 'sound', 'animation', 'turret', 'action', 'decal', 'attachment', 'canbuild', 'leg', 'arm', 'produced', 'spawn', 'upgrad', 'convert', 'replace'];

// ===== 移植 gen_completion_types.js 的 classify 判定（获取分类归属） =====
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
  const t = String(rawType || '').toLowerCase().replace(/ /g, '').replace(/\(s\)/g, 's').replace(/ids/g, 'id');
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
const norm = t => String(t || '').replace(/ /g, '').toLowerCase();
function classify(p) {
  const name = p.name || ''; const nameEn = p.name_en || null;
  const en = (nameEn || name).toLowerCase(); const t = norm(p.type);
  if ((nameEn || '').toLowerCase() === '@copyfromsection' || name.toLowerCase() === '@copyfromsection' || name === '复制节') return '复制节';
  if (en === 'autotriggeronevent') return '事件触发';
  if (en.includes('memory') || name.includes('内存')) return '内存变量';
  if (UNIT_SPAWN.includes(en)) return '单位名';
  if (PROJECTILE_SPAWN.includes(en)) return '项目引用';
  if (projectRefKind(p.type || '')) return '项目引用';
  if (UNIT_REF_EXPR.includes(en) || MARKER_MIXED.includes(en) || MARKER_POS.includes(en)) return '单位标记';
  const enLC = (nameEn || name).toLowerCase();
  if (UNIT_NAME_LIST.has(enLC) || BUILT_FROM_RE.test(enLC) || enLC === 'name' || UNIT_NAME_TYPE_KW.some(k => t.includes(k))) return '单位名';
  return '文字/任意';
}

console.log('\n=== 含引用/产出语义关键词的 type 分类归属（全量核对） ===');
for (const p of uniq) {
  if (refKw.some(k => (p.type || '').toLowerCase().includes(k))) {
    const key = p.name_en || p.name;
    const ex = (p.example || '').replace(/\n/g, ' / ').slice(0, 80);
    console.log(`[${classify(p)}] ${key} | type=${p.type || '-'} | example=${ex}`);
  }
}