#!/usr/bin/env node
/**
 * 路径补全缺口考察：扫描实际项目所有「音效/图片/文件名/CUSTOM/project ref 路径」类属性的真实取值，
 * 确认哪些属性有明确值域但当前补全不覆盖。
 * 用法: node tools/audit_path_props.js
 */
const fs = require('fs');
const path = require('path');
const DATA = JSON.parse(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/data/code_reference.json'), 'utf8'));
const PROJ = 'D:\\ALSO2004\\人机的玩笑\\人机的玩笑';

const propByIdent = new Map();
for (const [section, sd] of Object.entries(DATA.sections)) {
  for (const p of (sd.data || [])) {
    if (p.name_en) propByIdent.set(p.name_en.toLowerCase(), p);
    propByIdent.set(String(p.name).trim().toLowerCase(), p);
  }
}

const files = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full);
    else if (/\.(ini|template)$/i.test(e.name)) files.push(full);
  }
})(PROJ);

// 关注属性：路径/音效/图片/CUSTOM
const usage = new Map(); // en -> Map<value,count>
const fileSet = new Map(); // en -> Set<file>
for (const f of files) {
  let content; try { content = fs.readFileSync(f, 'utf8'); } catch { continue; }
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || /^\s*\[/.test(line)) continue;
    const ci = line.indexOf(':');
    if (ci <= 0) continue;
    const key = line.slice(0, ci).trim().toLowerCase();
    const value = line.slice(ci + 1).trim();
    if (!value) continue;
    const prop = propByIdent.get(key);
    if (!prop || !prop.name_en) continue;
    const en = prop.name_en.toLowerCase();
    const isPathLike =
      /sound|audio|\.ogg|\.wav|\.mp3/.test(key) ||
      /\.png|\.jpg|\.jpeg|\.gif|\.bmp|\.webp/.test(value) ||
      /\.ogg|\.wav|\.mp3/.test(value) ||
      /^ROOT:/i.test(value) ||
      /^CUSTOM:/i.test(value) ||
      value.startsWith('sfx') || value.startsWith('sounds') ||
      /audio\//.test(value) || /sound/.test(value);
    if (!isPathLike) continue;
    if (!usage.has(en)) { usage.set(en, new Map()); fileSet.set(en, new Set()); }
    usage.get(en).set(value, (usage.get(en).get(value) || 0) + 1);
    fileSet.get(en).add(f);
  }
}

console.log(`扫描 ${files.length} 文件，路径/音效/图片类命中属性 ${usage.size}\n`);
for (const [en, vals] of [...usage.entries()].sort((a,b)=>b[1].size-a[1].size)) {
  const p = propByIdent.get(en);
  const top = [...vals.entries()].sort((a,b)=>b[1]-a[1]).slice(0, 8).map(([v,n])=>`${JSON.stringify(v.slice(0,36))}×${n}`).join('  ');
  console.log(`◈ ${p.name} (${en}) type=${p.type} · 去重${vals.size}值/${[...vals.values()].reduce((a,b)=>a+b,0)}行`);
  console.log(`    ${top}`);
}

// 收集项目内全部图片/音频文件名（供对比哪些用上了）用相对路径
console.log('\n\n===== 项目内音频文件（全部） =====');
const afiles = [];
(function walk2(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk2(full);
    else if (/\.(ogg|wav|mp3)$/i.test(e.name)) afiles.push(full.replace(/\\/g,'/'));
  }
})(PROJ);
afiles.forEach(a => console.log('  ' + (a.replace(/^[^:]*:/,''))));

// 触发 CUSTOM 的属性（值含 CUSTOM: 的在哪个属性）
console.log('\n\n===== CUSTOM: 用法（属性→值） =====');
for (const [en, vals] of usage) {
  const customs = [...vals.keys()].filter(v => /^custom:/i.test(v));
  if (customs.length) {
    console.log(`  ${propByIdent.get(en).name} (${en}) → ${customs.slice(0,5).join(' | ')}`);
  }
}