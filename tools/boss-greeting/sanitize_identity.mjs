/**
 * 身份信息脱敏工具
 *
 *   node sanitize_identity.mjs            只报告，不改文件
 *   node sanitize_identity.mjs --apply    真改
 *   node sanitize_identity.mjs --verify   复查是否还有残留（有残留则 exit 1）
 *
 * 处理范围：本目录及子目录下的 .mjs / .py / .json / .jsonl / .md / .txt
 * 排除：backup_* 目录、.boss-profile 目录、node_modules
 *
 * 注意：脱敏会改动队列里的话术正文。之后再跑 --send，
 * send_boss 会因为检测到占位符而拒绝发送（除非显式加 --allow-placeholder）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MODE = process.argv.includes('--apply') ? 'apply'
  : process.argv.includes('--verify') ? 'verify'
    : 'report';

// 顺序很重要：长串必须先替换，否则带校区后缀的全称会被它的前缀（不带后缀那条）先吃掉。
// 下面的字面量全部用 \u 转义存放 —— 这个文件本身也不该出现明文身份信息。
const MAP = [
  ['\u54C8\u5C14\u6EE8\u5DE5\u4E1A\u5927\u5B66\uFF08\u5A01\u6D77\uFF09', 'XX\u5927\u5B66\uFF08XX\uFF09'],
  ['\u54C8\u5C14\u6EE8\u5DE5\u4E1A\u5927\u5B66(\u5A01\u6D77)', 'XX\u5927\u5B66(XX)'],
  ['\u54C8\u5DE5\u5927\u5A01\u6D77', 'XX\u5927\u5B66'],
  ['\u54C8\u5C14\u6EE8\u5DE5\u4E1A\u5927\u5B66', 'XX\u5927\u5B66'],
  ['\u54C8\u5DE5\u5927', 'XX\u5927\u5B66'],
  ['\u5468\u6631\u6749', '\u5F20\u4E09'],
  ['\u8BA1\u7B97\u673A\u79D1\u5B66\u4E0E\u6280\u672F', 'XX\u4E13\u4E1A'],
];

const EXTS = new Set(['.mjs', '.py', '.json', '.jsonl', '.md', '.txt']);
const SKIP_DIR = /^(backup_.*|\.boss-profile|node_modules|\.git)$/;
// 本文件自己存着替换表（表里必须写原始串），不能把自己也脱敏掉
const SELF = path.basename(fileURLToPath(import.meta.url));

function walk(dir, out = []) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIR.test(ent.name)) continue;
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) walk(p, out);
    else if (EXTS.has(path.extname(ent.name).toLowerCase()) && ent.name !== SELF) out.push(p);
  }
  return out;
}

const files = walk(HERE);

if (MODE === 'verify') {
  const dirty = [];
  for (const f of files) {
    const txt = fs.readFileSync(f, 'utf8');
    for (const [from] of MAP) {
      const n = txt.split(from).length - 1;
      if (n > 0) dirty.push({ file: path.relative(HERE, f), from, n });
    }
  }
  if (!dirty.length) {
    console.log(`✓ 复查通过：${files.length} 个文件里没有身份信息残留`);
    process.exit(0);
  }
  console.log(`✗ 仍有 ${dirty.length} 处残留：`);
  for (const d of dirty) console.log(`  ${d.file}  「${d.from}」×${d.n}`);
  process.exit(1);
}

let totalHits = 0;
let totalFiles = 0;
const rows = [];

for (const f of files) {
  let txt = fs.readFileSync(f, 'utf8');
  let changed = 0;
  const detail = [];
  for (const [from, to] of MAP) {
    const n = txt.split(from).length - 1;
    if (!n) continue;
    txt = txt.split(from).join(to);
    changed += n;
    detail.push(`${from}→${to} ×${n}`);
  }
  if (changed) {
    totalHits += changed;
    totalFiles++;
    rows.push({ file: path.relative(HERE, f), changed, detail });
    if (MODE === 'apply') fs.writeFileSync(f, txt, 'utf8');
  }
}

rows.sort((a, b) => b.changed - a.changed);
console.log(MODE === 'apply' ? '=== 已脱敏 ===' : '=== 待脱敏（未落盘）===');
for (const r of rows) {
  console.log(`${String(r.changed).padStart(4)} 处  ${r.file}`);
  console.log(`        ${r.detail.join('，')}`);
}
console.log(`\n合计 ${totalFiles} 个文件 / ${totalHits} 处`);

if (MODE === 'report') {
  console.log('\n以上只是报告。确认后执行：node sanitize_identity.mjs --apply');
}
