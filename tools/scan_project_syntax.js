#!/usr/bin/env node
/** 扫描实际 MOD 项目的 ini/template 文件，统计各值语法真实出现在哪些属性名下（铁证） */
const fs = require('fs');
const path = require('path');

const PROJECT_DIR = 'D:/ALSO2004/人机的玩笑/人机的玩笑';
const files = [];
function walk(dir) {
  if (!fs.existsSync(dir)) return;
  for (const f of fs.readdirSync(dir)) {
    const fp = path.join(dir, f);
    const st = fs.statSync(fp);
    if (st.isDirectory()) walk(fp);
    else if (/\.(ini|template)$/i.test(f)) files.push(fp);
  }
}
walk(PROJECT_DIR);
console.log(`文件数: ${files.length}`);

const cross = new Map(), interp = new Map(), mem = new Map(), res = new Map(), func = new Map();
for (const fp of files) {
  const lines = fs.readFileSync(fp, 'utf8').split('\n');
  for (const line of lines) {
    const m = line.match(/^\s*([^:=\[\]@#]+?)\s*:\s*(.*)$/);
    if (!m) continue;
    const key = m[1].trim();
    const val = m[2];
    if (val.includes('${') && !val.includes('%{')) cross.set(key, (cross.get(key) || 0) + 1);
    if (val.includes('%{')) interp.set(key, (interp.get(key) || 0) + 1);
    if (/内存\./.test(val)) mem.set(key, (mem.get(key) || 0) + 1);
    if (/资源\./.test(val)) res.set(key, (res.get(key) || 0) + 1);
    // 带参函数调用（值里含中文/英文函数名后跟 ( 且带 = 参数）
    const fn = val.match(/[\u4e00-\u9fff_a-zA-Z][\u4e00-\u9fff_a-zA-Z0-9_]*\(\s*[\u4e00-\u9fff_a-zA-Z][\u4e00-\u9fff_a-zA-Z0-9_]*\s*=/);
    if (fn) func.set(key, (func.get(key) || 0) + 1);
  }
}
const show = (name, map) => {
  console.log(`\n=== ${name}（${map.size} 个属性） ===`);
  [...map.entries()].sort((a, b) => b[1] - a[1]).forEach(([k, n]) => console.log(`  ${k} × ${n}`));
};
show('跨节引用 ${..}', cross);
show('文本插值 %{..}', interp);
show('内存引用 内存.', mem);
show('资源引用链 资源.', res);
show('命名参数函数 xxx(参数=..)', func);
