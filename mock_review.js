const fs = require('fs');
const cr = JSON.parse(fs.readFileSync('app/src/main/assets/data/code_reference.json','utf8'));
const map = new Map();
for (const sec of Object.values(cr.sections)) {
  for (const p of sec.data || []) {
    const en = (p.name_en || p.name || '').toLowerCase();
    if (en) map.set(en, p.name);
  }
}
// 翻译库补充（翻译引擎兜底）：translation.txt 的 键=值 全局映射
const dict = new Map();
try {
  for (const line of fs.readFileSync('app/src/main/assets/data/translation.txt','utf8').split(/\r?\n/)) {
    if (!line.includes('=')) continue;
    const i = line.indexOf('=');
    dict.set(line.slice(0,i).trim().toLowerCase(), line.slice(i+1).trim());
  }
} catch(e){}
for (const line of fs.readFileSync('app/src/main/assets/data/extra_translation_supplement.txt','utf8').split(/\r?\n/)) {
  if (!line.includes('=')) continue;
  const i = line.indexOf('=');
  dict.set(line.slice(0,i).trim().toLowerCase(), line.slice(i+1).trim());
}

function zh(en) {
  const k = en.toLowerCase();
  if (map.has(k)) {
    const cn = map.get(k);
    if (/[\u4e00-\u9fff]/.test(cn)) return cn; // code_reference 给了真中文
    if (dict.has(k)) return dict.get(k);       // 否则回退翻译字典
    return cn;
  }
  if (dict.has(k)) return dict.get(k);
  return null;
}

const txt = fs.readFileSync('mock_ini_pulled.ini','utf8');
let cur = '';
let misses = [];
let rows = []; // {sec, en, zh, miss}
for (const raw of txt.split(/\r?\n/)) {
  const line = raw.replace(/\r$/,'');
  if (line.startsWith('[') && line.endsWith(']')) { cur = line; continue; }
  const k = line.replace(/:$/,'').trim();
  if (!k || k.startsWith(';')) continue;
  const ch = zh(k);
  rows.push({sec: cur, en: k, zh: ch, miss: !ch});
  if (!ch) misses.push({sec: cur, en: k});
}

console.log('总键数:', rows.length, ' 未命中:', misses.length);
console.log('\n===== 未命中中文的英文键 =====');
for (const m of misses) console.log(`[${m.sec}] ${m.en}`);

// 未真正翻译的键：中文名 == 英文键本身（code_reference 里 name 即英文，无中文）
const hasCJK = s => /[\u4e00-\u9fff]/.test(s);
const echoed = rows.filter(r => r.zh && !hasCJK(r.zh));
console.log('\n===== 无中文(英文名回显)的键数:', echoed.length, '=====');
const bySec = {};
for (const r of echoed) (bySec[r.sec]=bySec[r.sec]||[]).push(r.en);
for (const s of Object.keys(bySec)) {
  console.log(`\n[${s}] (${bySec[s].length})`);
  for (const en of bySec[s]) console.log('  ' + en);
}

// 全量映射写文件
let out = '';
for (const r of rows) out += `[${r.sec}] ${r.en}  ->  ${r.zh}\n`;
fs.writeFileSync('mock_review_full.txt', out);