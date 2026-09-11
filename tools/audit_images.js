// 审计共享图像：shared_images.json 中所有图像名在 value_desc.json 的 data.value.image.* 是否有说明
const fs = require('fs');
const path = require('path');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data');
const desc = JSON.parse(fs.readFileSync(path.join(assets, 'value', 'value_desc.json'), 'utf8'));
const si = JSON.parse(fs.readFileSync(path.join(assets, 'shared_images.json'), 'utf8'));

// shared_images.json 结构可能是 { images: [...] } 或直接数组
const names = Array.isArray(si) ? si : (si.images || si.names || []);
let missing = [];
for (const n of names) {
  if (desc['data.value.image.' + n] === undefined) missing.push(n);
}

console.log('共享图像总数:', names.length);
console.log('已覆盖:', names.length - missing.length);
console.log('=== 缺失中文说明的共享图像 ===');
if (missing.length === 0) console.log('(无)');
else for (const m of missing) console.log(m);
