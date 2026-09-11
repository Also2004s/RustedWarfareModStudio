// 审计 value 数据文件中哪些枚举值的 description 键在 value_desc.json 中缺失
const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'value');
const descRaw = fs.readFileSync(path.join(dir, 'value_desc.json'), 'utf8');
const desc = JSON.parse(descRaw);

// EnumValueCompletionProvider nameToFileMap + typeToFileMap 涉及的枚举文件
const enumFiles = [
  'movementType', 'drawType', 'layer', 'displayType', 'drawLayer', 'teamColoringMode',
  'attackMovement', 'onActions', 'onlyTeam', 'searchTeam', 'addWaypoint_type',
  'addWaypoint_target_nearestUnit_team', 'fireTurretXAtGround_onlyOverPassableTileOf',
  'takeResources_includeUnitsWithinRange_team', 'transportUnitsRequireMovementType',
  'convertTo_keepCurrentFields', 'whenBuilding_temporarilyConvertTo_keepFields',
  'onNewMapSpawn', 'displayDigitGrouping', 'setUnitStats', 'autoTriggerCheckRate',
  'explodeTypeOnDeath', 'stripIndex', 'priority'
];

let missing = [];
for (const f of enumFiles) {
  const file = path.join(dir, f + '.json');
  if (!fs.existsSync(file)) { console.log('MISSING FILE: ' + f); continue; }
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  for (const item of data.data || []) {
    const key = item.description;
    if (!key) { missing.push({ file: f, name: item.name, reason: 'no description field' }); continue; }
    if (desc[key] === undefined) {
      missing.push({ file: f, name: item.name, key });
    }
  }
}

console.log('=== 缺失说明的枚举值 ===');
if (missing.length === 0) console.log('(无)');
for (const m of missing) {
  console.log(`${m.file}.${m.name}  key=${m.key || ''}  ${m.reason || ''}`);
}
