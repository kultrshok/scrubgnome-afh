// Node self-test for the web scrubber + tone check (same generated files as selftest.sh).
import { analyze, scrub } from '../web/js/scrubber.js';
import { UltrasonicAnalyzer, extractAac } from '../web/js/tone.js';
import { openAsBlob, readFileSync, writeFileSync } from 'node:fs';
const [mov, mp4, raw, aacOut] = process.argv.slice(2);
for (const f of [mov, mp4]) {
  const blob = await openAsBlob(f);
  const before = (await analyze(blob)).report;
  console.log(f.split('/').pop(), 'before:', [...before.categories].join(', '));
  if (!before.categories.size) throw new Error('test file should carry metadata');
  const { clean } = await scrub(blob);            // throws if anything survives
  writeFileSync(f + '.web', Buffer.from(await clean.arrayBuffer()));
  console.log(f.split('/').pop(), 'after : CLEAN');
}
const b = readFileSync(raw), s = new Float32Array(b.length / 2);
for (let i = 0; i < s.length; i++) s[i] = b.readInt16LE(i * 2) / 32768;
const a = new UltrasonicAnalyzer(48000); a.feed(s);
const r = a.finish();
console.log('tone check:', r.level, '-', r.headline);
if (r.level !== 'HIGH') throw new Error('19 kHz beacon not detected');
const x = await extractAac(await openAsBlob(mov));
if (!x || x.adts.length < 1000) throw new Error('AAC extraction failed');
writeFileSync(aacOut, x.adts);
console.log('aac extraction:', x.adts.length, 'bytes at', x.sampleRate, 'Hz');
