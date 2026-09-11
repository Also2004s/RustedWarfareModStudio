#!/usr/bin/env node
/**
 * 从逆向源码提取「内置图像」数据，生成 shared_images.json。
 * 数据源（游戏 v1.15 反编译）：AssetsID$drawable.java → app/src/main/assets/data/shared_images.json
 * 供 SharedImagesRepository 读取，作为图片补全 SHARED:/CORE: 的内置共享图候选。
 * 内置音效不做数据文件：可直接引用《内置音效》的权威名单（24 个）已在 ProjectRefValueCompletionProvider.builtInSounds 硬编码，
 * 与 code_reference.json 开火音效.desc 完全一致，无需单独资产文件。
 * 幂等：重复运行覆盖输出。
 */
const fs = require('fs');
const path = require('path');

const TMP_DRAWABLE = path.join(__dirname, '..', '.tmp_rw_source', 'src', 'main', 'java',
  'com', 'corrodinggames', 'rts', 'AssetsID$drawable.java');

const OUT_IMAGES = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'data', 'shared_images.json');

function extractNames(file) {
  const src = fs.readFileSync(file, 'utf8');
  const names = [];
  const re = /public static int\s+(\w+)\s*=/g;
  let m;
  while ((m = re.exec(src)) !== null) names.push(m[1]);
  return names;
}

// 保留完整从逆向源码提取的集合（含 UI 素材），排序稳定。
const images = extractNames(TMP_DRAWABLE);

fs.writeFileSync(OUT_IMAGES, JSON.stringify({
  version: 2,
  source: '逆向库 AssetsID$drawable.java（游戏 v1.15 内置全部 drawable 图像资源名）',
  images,
}, null, 2) + '\n');

console.log(`shared_images.json → ${images.length} 张内置图像`);