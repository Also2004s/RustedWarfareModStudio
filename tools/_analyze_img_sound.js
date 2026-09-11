// 临时分析脚本：提取 code_reference.json 中图片/音效相关属性，核对真实用法
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
    const type = p.type || '';
    const nameEn = p.name_en || '';
    const desc = p.description || '';
    const lname = String(name).toLowerCase();
    const ltype = String(type).toLowerCase();
    const lnameEn = String(nameEn).toLowerCase();
    const isImage = lname.includes('image') || lnameEn.includes('image') ||
      ltype.includes('image') || ltype.includes('drawable');
    const isSound = ltype.includes('sound') || ltype.includes('audio') ||
      /playsound|shoot_sound|soundon|soundat|hit_sound|hitSound/.test(lname) ||
      /playsound|shoot_sound|soundon|soundat/.test(lnameEn);
    if (isImage || isSound) {
      rows.push({ sec, name, nameEn, type, desc });
    }
  }
}

console.log('=== IMAGE RELATED ===');
for (const r of rows.filter(r => /image|drawable/.test((r.name + ' ' + r.nameEn + ' ' + r.type).toLowerCase()))) {
  console.log(`${r.sec}\tname=${r.name}\tnameEn=${r.nameEn}\ttype=${r.type}\tdesc=${r.desc}`);
}
console.log('\n=== SOUND RELATED ===');
for (const r of rows.filter(r => /sound|audio/.test((r.name + ' ' + r.nameEn + ' ' + r.type).toLowerCase()))) {
  console.log(`${r.sec}\tname=${r.name}\tnameEn=${r.nameEn}\ttype=${r.type}\tdesc=${r.desc}`);
}
