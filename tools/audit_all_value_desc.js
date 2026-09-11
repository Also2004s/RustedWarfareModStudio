// 审计所有 data/value/*.json 的 description 键在 value_desc.json 中是否缺失
// 与 ValueDescriptionRepository.lookup 的解析逻辑一致：
//   1. 精确匹配 key
//   2. 未命中则去掉 ".description" 后缀再匹配
// 两者都不命中才算缺失
const fs = require('fs');
const path = require('path');

const dir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'value');
const descRaw = fs.readFileSync(path.join(dir, 'value_desc.json'), 'utf8');
const desc = JSON.parse(descRaw);

const files = fs.readdirSync(dir).filter(f => f.endsWith('.json') && f !== 'value_desc.json' && f !== 'logicboolean.json');

function resolve(key) {
  if (key === undefined || key === null || key === '') return null;
  if (desc[key] !== undefined) return desc[key];
  const stripped = key.replace(/\.description$/, '');
  if (desc[stripped] !== undefined) return desc[stripped];
  return null;
}

let missing = [];
for (const f of files) {
  const data = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
  for (const item of data.data || []) {
    const key = item.description;
    if (!key) { missing.push({ file: f, name: item.name, reason: 'no description field' }); continue; }
    if (resolve(key) === null) {
      missing.push({ file: f, name: item.name, key });
    }
  }
}

console.log('=== 缺失说明的 value 条目（所有数据文件） ===');
if (missing.length === 0) console.log('(无)');
for (const m of missing) {
  console.log(`${m.file}.${m.name}  key=${m.key || ''}  ${m.reason || ''}`);
}
