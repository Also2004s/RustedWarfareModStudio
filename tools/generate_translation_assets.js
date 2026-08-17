#!/usr/bin/env node
/**
 * 生成本地翻译库资源文件：
 * - 原生表：D:\ALSO2004\人机的玩笑\scripts\翻译库.txt -> app/src/main/assets/data/translation.txt
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.dirname(__dirname);
const ASSETS_DIR = path.join(ROOT, 'app', 'src', 'main', 'assets', 'data');
const NATIVE_SRC = 'D:/ALSO2004/人机的玩笑/scripts/翻译库.txt';

function copyNative() {
    if (!fs.existsSync(NATIVE_SRC)) {
        console.log(`[SKIP] 原生表源文件不存在: ${NATIVE_SRC}`);
        return;
    }
    fs.mkdirSync(ASSETS_DIR, { recursive: true });
    const content = fs.readFileSync(NATIVE_SRC, 'utf8');
    const out = path.join(ASSETS_DIR, 'translation.txt');
    fs.writeFileSync(out, content.replace(/\r\n/g, '\n'), 'utf8');
    console.log(`[OK] 原生表已生成: ${out} (${fs.statSync(out).size} bytes)`);
}

copyNative();
