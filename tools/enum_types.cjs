const fs = require('fs');
const d = JSON.parse(fs.readFileSync('app/src/main/assets/data/code_reference.json', 'utf8'));
const props = [];
for (const sec of Object.keys(d.sections)) {
  const arr = d.sections[sec].data || [];
  for (const p of arr) {
    if (p && p.name) props.push({
      sec,
      name: String(p.name).toLowerCase(),
      en: String(p.name_en || p.name).toLowerCase(),
      type: String(p.type || '').toLowerCase()
    });
  }
}
const byEn = new Map();
for (const p of props) {
  const k = p.en || p.name;
  if (!byEn.has(k)) byEn.set(k, []);
  byEn.get(k).push(p);
}
const norm = t => t.replace(/ /g, '');
function dump(title, test) {
  console.log('=== ' + title + ' ===');
  const seen = new Set();
  for (const [k, ps] of byEn) {
    if (seen.has(k)) continue;
    const ts = [...new Set(ps.map(p => p.type))];
    if (ts.some(t => test(norm(t)))) {
      console.log('  ' + k + ' | ' + ts.join('/') + ' | ' + ps.map(p => p.sec).join(','));
    }
    seen.add(k);
  }
  console.log('');
}
dump('IMAGE/DRAWABLE-type', t => t.includes('image') || t.includes('drawable'));
dump('SOUND-type', t => t.includes('sound') || t.includes('sfx') || t.includes('audio'));
dump('RESOURCE/PRICE-type', t => /(resource|price)/.test(t));
dump('TAG-type', t => t.includes('tag'));
dump('UNIT-type', t => t.includes('unitref') || t.includes('unittype') || t.includes('unitnames'));
