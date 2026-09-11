// 临时分析脚本：提取 code_reference.json 中所有「引用型」属性（turret/projectile/effect/action/animation/decal/attachment/canbuild/sound/file）
const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'code_reference.json');
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const sections = data.sections || {};

// 归一化 type（与 projectRefKind 一致）
function normType(t) {
  return String(t).toLowerCase().replace(/ /g, '').replace(/\(s\)/g, 's').replace(/ids/g, 'id');
}

const rows = [];
for (const [sec, secData] of Object.entries(sections)) {
  const arr = secData && secData.data ? secData.data : [];
  for (const p of arr) {
    if (!p || typeof p !== 'object') continue;
    const name = p.name || '';
    const type = p.type || '';
    const nameEn = p.name_en || '';
    const desc = p.description || '';
    const nt = normType(type);
    const refish = /turret|projectile|effect|action|animation|decal|attachment|canbuild|sound|file|\.ini/.test(nt) || /\.ini|\.template/.test(desc);
    if (refish) rows.push({ sec, name, nameEn, type, nt, desc });
  }
}

const kinds = {
  turret: /turret/,
  projectile: /projectile/,
  effect: /effect/,
  action: /action/,
  animation: /animation/,
  decal: /decal/,
  attachment: /attachment/,
  canbuild: /canbuild/,
  sound: /sound/,
  file: /file|\.ini|\.template/,
};

for (const [kind, re] of Object.entries(kinds)) {
  console.log(`\n########## ${kind.toUpperCase()} ##########`);
  const matched = rows.filter(r => re.test(r.nt));
  const seen = new Set();
  for (const r of matched) {
    const key = `${r.sec}|${r.name}|${r.nameEn}`;
    if (seen.has(key)) continue;
    seen.add(key);
    console.log(`${r.sec}\t${r.name}\t${r.nameEn}\ttype=${r.type}\tnt=${r.nt}`);
  }
}
