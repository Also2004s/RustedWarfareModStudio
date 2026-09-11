// 严格检查：所有 value 数据文件 + logicboolean + 参数，description 键解析结果
// 若结果缺失或解析结果不含中文字符，则视为「没有中文说明」
const fs = require('fs');
const path = require('path');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data');
const desc = JSON.parse(fs.readFileSync(path.join(assets, 'value', 'value_desc.json'), 'utf8'));

function resolve(key) {
  if (key === undefined || key === null || key === '') return null;
  if (desc[key] !== undefined) return desc[key];
  const stripped = key.replace(/\.description$/, '');
  if (desc[stripped] !== undefined) return desc[stripped];
  return null;
}

function hasCJK(s) { return /[\u4e00-\u9fff]/.test(s); }

let issues = [];

// 1. 所有 value 数据文件（含 logicboolean）
for (const f of fs.readdirSync(path.join(assets, 'value'))) {
  if (!f.endsWith('.json') || f === 'value_desc.json') continue;
  const data = JSON.parse(fs.readFileSync(path.join(assets, 'value', f), 'utf8'));
  for (const item of data.data || []) {
    const key = item.description;
    if (!key) { issues.push({ file: f, name: item.name, reason: 'no description field' }); continue; }
    const r = resolve(key);
    if (r === null) issues.push({ file: f, name: item.name, key, reason: 'missing' });
    else if (!hasCJK(r)) issues.push({ file: f, name: item.name, key, reason: 'non-chinese: ' + r });
  }
}

// 2. 参数表 param/*.json 的参数名说明
for (const f of fs.readdirSync(path.join(assets, 'param'))) {
  if (!f.endsWith('.json')) continue;
  const data = JSON.parse(fs.readFileSync(path.join(assets, 'param', f), 'utf8'));
  for (const fn of data.functions || []) {
    for (const p of fn.params || []) {
      const r = resolve('data.value.param.' + p.key);
      if (r === null) issues.push({ file: 'param/' + f, name: p.key, key: 'data.value.param.' + p.key, reason: 'missing' });
      else if (!hasCJK(r)) issues.push({ file: 'param/' + f, name: p.key, key: 'data.value.param.' + p.key, reason: 'non-chinese: ' + r });
    }
  }
}

console.log('=== 说明缺失或非中文的条目 ===');
if (issues.length === 0) console.log('(无)');
for (const m of issues) console.log(`${m.file}.${m.name}  ${m.reason}  key=${m.key || ''}`);
