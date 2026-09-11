// 审计：assets/data/value/*.json 所有 description 键是否在 value_desc.json 中覆盖
const fs = require('fs');
const path = require('path');

const valueDir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'value');
const descFile = path.join(valueDir, 'value_desc.json');

const desc = JSON.parse(fs.readFileSync(descFile, 'utf8'));

const files = fs.readdirSync(valueDir).filter(f => f.endsWith('.json') && f !== 'value_desc.json');

const gaps = [];
for (const f of files) {
  const data = JSON.parse(fs.readFileSync(path.join(valueDir, f), 'utf8'));
  const entries = (data.data || []).filter(e => e && e.description);
  for (const e of entries) {
    const key = e.description;
    if (!(key in desc) && !(key.replace(/\.description$/, '') in desc)) {
      gaps.push({ file: f, name: e.name, key });
    }
  }
}

console.log('=== 未覆盖的 description 键（' + gaps.length + '） ===');
const byFile = {};
for (const g of gaps) {
  (byFile[g.file] = byFile[g.file] || []).push(g);
}
for (const [file, list] of Object.entries(byFile)) {
  console.log('\n[' + file + '] ' + list.length + ' 条');
  for (const g of list) {
    console.log('  ' + g.name + '  ->  ' + g.key);
  }
}

// ===== 2) 原版单位 =====
const vanillaFile = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'vanilla_units.json');
const vanilla = JSON.parse(fs.readFileSync(vanillaFile, 'utf8'));
const vanillaGaps = (vanilla.units || []).filter(u => !(('data.value.vanillaunit.' + u) in desc));
console.log('\n=== 未覆盖的原版单位（' + vanillaGaps.length + '） ===');
vanillaGaps.forEach(u => console.log('  ' + u));

// ===== 3) 内置音效 / 特效（与 ProjectRefValueCompletionProvider 词表一致） =====
const builtInEffects = ["small", "large", "smoke", "shockwave", "medium", "largeExplosion", "smallExplosion", "resourcePoolSmoke", "noneExplosion"];
const builtInSounds = ["attack", "move", "click", "missile_fire", "missile_hit", "unit_explode", "buiding_explode", "tank_firing", "cannon_firing", "gun_fire", "lighting_burst", "plasma_fire", "plasma_fire2", "firing3", "firing4", "large_gun_fire1", "large_gun_fire2", "bug_die", "bug_attack", "interface_error", "nuke_explode", "nuke_launch", "laser_deflect", "laser_deflect2"];
const effGaps = builtInEffects.filter(e => !(('data.value.effect.' + e) in desc));
const sndGaps = builtInSounds.filter(s => !(('data.value.sound.' + s) in desc));
console.log('\n=== 未覆盖的内置特效（' + effGaps.length + '） ===');
effGaps.forEach(e => console.log('  ' + e));
console.log('\n=== 未覆盖的内置音效（' + sndGaps.length + '） ===');
sndGaps.forEach(s => console.log('  ' + s));
