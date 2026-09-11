// 审计函数参数：param/*.json 中所有参数 key 在 value_desc.json 的 data.value.param.<key> 是否有说明
const fs = require('fs');
const path = require('path');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data');
const desc = JSON.parse(fs.readFileSync(path.join(assets, 'value', 'value_desc.json'), 'utf8'));

const files = fs.readdirSync(path.join(assets, 'param')).filter(f => f.endsWith('.json'));
let missing = [];
for (const f of files) {
  const data = JSON.parse(fs.readFileSync(path.join(assets, 'param', f), 'utf8'));
  for (const fn of data.functions || []) {
    for (const p of fn.params || []) {
      if (desc['data.value.param.' + p.key] === undefined) {
        missing.push({ file: f, fn: fn.key, key: p.key, zh: p.zh });
      }
    }
  }
}

console.log('=== 缺失说明的函数参数（data.value.param.*） ===');
if (missing.length === 0) console.log('(无)');
for (const m of missing) console.log(`${m.file}.${m.fn}.${m.key} (${m.zh})`);
