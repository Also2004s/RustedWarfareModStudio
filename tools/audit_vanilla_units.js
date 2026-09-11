// 审计原版单位名：vanilla_units.json 中的单位在 value_desc.json 的 data.value.vanillaunit.* 是否有中文说明
const fs = require('fs');
const path = require('path');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data');
const unitsRaw = fs.readFileSync(path.join(assets, 'vanilla_units.json'), 'utf8');
const units = JSON.parse(unitsRaw).units;
const desc = JSON.parse(fs.readFileSync(path.join(assets, 'value', 'value_desc.json'), 'utf8'));

const missing = [];
for (const u of units) {
  if (!u.trim()) continue;
  if (desc['data.value.vanillaunit.' + u] === undefined) missing.push(u);
}

console.log('原版单位总数:', units.length);
console.log('已覆盖:', units.length - missing.length);
console.log('=== 缺失中文说明的原版单位 ===');
if (missing.length === 0) console.log('(无)');
else for (const m of missing) console.log(m);
