// 临时分析：标签类 + 资源类属性精确名单
const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'code_reference.json');
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const sections = data.sections || {};

const rows = [];
for (const [sec, secData] of Object.entries(sections)) {
  const arr = secData && secData.data ? secData.data : [];
  for (const p of arr) {
    if (!p || typeof p !== 'object') continue;
    const name = p.name || '';
    const nameEn = p.name_en || '';
    const type = p.type || '';
    const lname = String(name).toLowerCase();
    const lnameEn = String(nameEn).toLowerCase();
    const ltype = String(type).toLowerCase();
    const isTag = ['tags','tag list','tag ref','message tag'].includes(ltype) ||
      (ltype.startsWith('string') && (lname.includes('tag') || lnameEn.includes('tag') || lname.includes('标签')));
    const isResource = /price|resource/.test(ltype) || lname.includes('资源') || lname.includes('价格') ||
      lnameEn.includes('resource') || lnameEn.includes('price');
    if (isTag) rows.push({ sec, name, nameEn, type, desc: p.description, cat: 'TAG' });
    else if (isResource) rows.push({ sec, name, nameEn, type, desc: p.description, cat: 'RES' });
  }
}

console.log('=== TAG ===');
for (const r of rows.filter(r => r.cat === 'TAG')) {
  console.log(`${r.sec}\tname=${r.name}\tnameEn=${r.nameEn}\ttype=${r.type}`);
}
console.log('\n=== RESOURCE (type 含 price/resource 或名含资源/价格) ===');
for (const r of rows.filter(r => r.cat === 'RES')) {
  console.log(`${r.sec}\tname=${r.name}\tnameEn=${r.nameEn}\ttype=${r.type}`);
}
