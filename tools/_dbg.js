const fs = require('fs');
for (const varName of ['MEMORY_PROPERTIES', 'BOOL_PROPERTIES']) {
  const KT = fs.readFileSync('app/src/main/java/com/rwmodstudio/core/translation/CompletionTypeRepository.kt', 'utf8');
  const idx = KT.indexOf(`val ${varName} = setOf(`);
  if (idx < 0) { console.log(varName, 'NOT FOUND'); continue; }
  let depth = 0, end = idx;
  const start = KT.indexOf('(', idx);
  for (let i = start; i < KT.length; i++) {
    if (KT[i] === '(') depth++;
    else if (KT[i] === ')') { depth--; if (depth === 0) { end = i; break; } }
  }
  const body = KT.slice(start, end + 1);
  const names = [...body.matchAll(/"([^"]+)"/g)].map(x => x[1].toLowerCase());
  console.log(varName, '->', JSON.stringify(names));
}