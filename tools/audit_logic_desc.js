// 审计 logicboolean.json 的 description 键在 value_desc.json 中是否缺失
const fs = require('fs');
const path = require('path');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data');
const desc = JSON.parse(fs.readFileSync(path.join(assets, 'value', 'value_desc.json'), 'utf8'));
const data = JSON.parse(fs.readFileSync(path.join(assets, 'value', 'logicboolean.json'), 'utf8'));

let missing = [];
for (const item of data.data || []) {
  const key = item.description;
  if (!key) { missing.push({ name: item.name, reason: 'no description field' }); continue; }
  if (desc[key] === undefined && desc[key.replace(/\.description$/, '')] === undefined) {
    missing.push({ name: item.name, key });
  }
}

console.log('=== 缺失说明的 logicboolean 条目 ===');
if (missing.length === 0) console.log('(无)');
for (const m of missing) console.log(`${m.name}  key=${m.key || ''}  ${m.reason || ''}`);
