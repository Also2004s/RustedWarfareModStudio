// 临时分析：code_reference 中含 memory/内存 的属性 + 动态资源/kvp 属性
const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'code_reference.json');
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const sections = data.sections || {};

console.log('=== 含 memory/内存 属性 ===');
for (const [sec, secData] of Object.entries(sections)) {
  const arr = secData && secData.data ? secData.data : [];
  for (const p of arr) {
    if (!p || typeof p !== 'object') continue;
    const name = p.name || '';
    const nameEn = p.name_en || '';
    if (String(name).toLowerCase().includes('memory') || String(name).includes('内存') ||
        String(nameEn).toLowerCase().includes('memory')) {
      console.log(`${sec}\tname=${name}\tnameEn=${nameEn}\ttype=${p.type}\tdesc=${p.description}`);
    }
  }
}

console.log('\n=== 动态资源/键值对 属性 (setResource/addResource/kvp/memory 相关) ===');
for (const [sec, secData] of Object.entries(sections)) {
  const arr = secData && secData.data ? secData.data : [];
  for (const p of arr) {
    if (!p || typeof p !== 'object') continue;
    const name = p.name || '';
    const nameEn = p.name_en || '';
    const l = (String(name)+' '+String(nameEn)).toLowerCase();
    if (/setresource|addresource|resourcewithlogic|setlogicresource|keyvalue|kvp|memory/.test(l)) {
      console.log(`${sec}\tname=${name}\tnameEn=${nameEn}\ttype=${p.type}\tdesc=${p.description}`);
    }
  }
}
