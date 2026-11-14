// Scrubgnome Away from Home (web) - UI glue. Twig's voice lives here.
// Author: kultrshok. MIT License.
import { analyze, scrub, human, UnsupportedFormatError, ScrubIncompleteError } from './scrubber.js';
import { checkTones, Level } from './tone.js';

const $ = id => document.getElementById(id);
const INTRO = "Twig here, pocket edition! Hand me a video and I'll rip out every sneaky little tag that snitches on where, when and with what you filmed it. It all happens right here on your phone. This page isn't even ALLOWED to talk to the internet. Great gears, the privacy!";

let file = null, cleanFile = null, busy = false, previewUrl = null;

const twig = s => { $('twig').textContent = s; };
const show = (id, on = true) => $(id).classList.toggle('hidden', !on);
const geekOn = () => $('geek').checked;
const applyGeek = () => {
  for (const id of ['fgeek', 'tgeek']) show(id, geekOn() && $(id).textContent.length > 0);
};

function setBusy(b, msg) {
  busy = b;
  for (const id of ['pick', 'scrub', 'tone']) $(id).disabled = b;
  show('prog', b);
  $('prog').value = 0;
  $('status').classList.remove('err');
  $('status').textContent = msg || '';
}
const progress = f => { $('prog').value = Math.round(f * 1000); };

function fail(msg) {
  setBusy(false);
  $('status').classList.add('err');
  $('status').textContent = msg;
  twig("Well, #@%&. That didn't go to plan. Read the red bit.");
}

function reset() {
  file = null; cleanFile = null;
  if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
  for (const id of ['filecard', 'scrub', 'tone', 'tonecard', 'savedcard', 'preview', 'download']) show(id, false);
  $('savedcard').classList.remove('dirty');
  $('fgeek').textContent = ''; $('tgeek').textContent = '';
  $('status').textContent = '';
  $('file').value = '';
  twig(INTRO);
}

function outputType(f) {
  const n = (f.name || '').toLowerCase();
  if (f.type === 'video/quicktime' || n.endsWith('.mov')) return ['.mov', 'video/quicktime'];
  if ((f.type || '').startsWith('video/3gpp') || n.endsWith('.3gp')) return ['.3gp', 'video/3gpp'];
  return ['.mp4', 'video/mp4'];
}

function randomHex(bytes) {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
}

async function load(f) {
  if (busy || !f) return;
  reset();
  file = f;
  setBusy(true, 'Sniffing for snitches…');
  try {
    const { report } = await analyze(f, progress);
    setBusy(false);
    show('filecard'); show('scrub'); show('tone');
    $('fname').textContent = `${f.name || 'video'} · ${human(f.size)}`;
    const box = $('findings');
    box.textContent = '';
    if (!report.categories.size) {
      box.textContent = 'Huh. Already squeaky clean. Suspicious… but clean. You can still save a fresh copy with a random name.';
      twig("Nothing to rip out! Either you're a pro or someone beat me to it. I'm mildly offended.");
    } else {
      box.append('Twig sniffed out these snitches:');
      const ul = document.createElement('ul');
      for (const c of report.categories) { const li = document.createElement('li'); li.textContent = c; ul.append(li); }
      box.append(ul);
      if (report.recordedUtc) { const p = document.createElement('div'); p.style.marginTop = '8px'; p.textContent = `Filmed: ${report.recordedUtc} (says the file)`; box.append(p); }
      if (/\d{8}/.test(f.name || '')) { const p = document.createElement('div'); p.style.marginTop = '8px'; p.textContent = 'Also, the file NAME has a date in it. Your clean copy gets a random name.'; box.append(p); }
      twig("Great gears! This thing is blabbing like a tourist. Hit Scrub and I'll zero every last tattletale byte.");
    }
    $('fgeek').textContent = report.details.map(d => `› ${d}`).join('\n');
    applyGeek();
  } catch (e) {
    file = null;
    fail(e instanceof UnsupportedFormatError
      ? `#@%&! ${e.message} I only operate on MP4, MOV and 3GP, if you please.`
      : `Gah! Couldn't read that one: ${e.message}`);
  }
}

// Shows the result card. dirty = the verify pass still found something.
function showResult(clean, name, mime, msg, dirty) {
  cleanFile = new File([clean], name, { type: mime, lastModified: 0 });
  setBusy(false);
  show('scrub', false);
  show('savedcard');
  $('savedcard').classList.toggle('dirty', dirty);
  $('saved').textContent = msg.replace('{size}', human(cleanFile.size));
  $('share').innerHTML = dirty ? '📤&nbsp; Save copy for rechecking' : '📤&nbsp; Save / share clean copy';
  $('download').innerHTML = dirty ? '⬇️&nbsp; Download copy for rechecking' : '⬇️&nbsp; Download clean copy';
  previewUrl = URL.createObjectURL(cleanFile);
  $('preview').src = previewUrl;
  show('preview');
  const canShare = navigator.canShare && navigator.canShare({ files: [cleanFile] });
  show('share', !!canShare);
  const dl = $('download');
  dl.href = previewUrl; dl.download = name;
  show('download', !canShare);
}

async function doScrub() {
  if (busy || !file) return;
  setBusy(true, 'Scrubbing! Zap… wipe… zero…');
  const [ext, mime] = outputType(file);
  try {
    const { clean, report } = await scrub(file, progress);
    const name = `scrubbed_${randomHex(4)}${ext}`;
    const bits = [`Removed ${report.boxesFreed} tag blocks`];
    if (report.tracksRemoved) bits.push(`${report.tracksRemoved} hidden data track(s)`);
    if (report.trailerRemoved) bits.push('a vendor trailer');
    showResult(clean, name, mime, `Double-checked: zero metadata left in ${name} ({size}). ${bits.join(', ')}. Video and sound untouched, not one pixel re-encoded.`, false);
    twig('Ha HA! It works! It actually works! Save it or post it. The trackers can taunt my shiny clean file all they like.');
  } catch (e) {
    if (!(e instanceof ScrubIncompleteError)) { fail(`#@%&! ${e.message}. Don't post this one.`); return; }
    // Keep the half-scrubbed copy so it can be fed back in and rechecked. The name says
    // NOT_CLEAN so it never gets mistaken for a postable file in the camera roll.
    const name = `NOT_CLEAN_recheck_${randomHex(4)}${ext}`;
    const left = [...e.leftover.categories].join(', ');
    showResult(e.clean, name, mime, `Partly scrubbed: ${name} ({size}). Still in there: ${left}. Do NOT post this one. Save it, then tap "Scrub another" and load it back in to recheck what's left.`, true);
    $('fgeek').textContent += `\n\nStill found after scrubbing:\n${e.leftover.details.map(d => `› ${d}`).join('\n')}`;
    applyGeek();
    $('status').classList.add('err');
    $('status').textContent = `#@%&! ${e.message}. Don't post this one.`;
    twig("Blast! Something's clinging on like a barnacle. I've saved what I could. Keep it, feed it back to me later, and we'll see what's left.");
  }
}

async function doShare() {
  if (!cleanFile) return;
  try {
    await navigator.share({ files: [cleanFile] });
  } catch (e) {
    if (e.name !== 'AbortError') { show('download'); $('status').textContent = 'Sharing hiccuped. Use the download button instead.'; }
  }
}

async function doTone() {
  if (busy || !file) return;
  setBusy(true, 'Ear to the speaker… listening for squeaks only bats and ad-trackers can hear…');
  try {
    const r = await checkTones(file, progress);
    setBusy(false);
    show('tonecard');
    const head = $('thead');
    if (r && r.none) {
      head.style.color = 'var(--muted)';
      head.textContent = 'No audio track';
      $('tbody').textContent = 'Silent movie! Nothing in here can whistle at anybody.';
      $('tgeek').textContent = '';
      twig('No sound, no snitching. Next!');
      applyGeek();
      return;
    }
    if (!r) {
      head.style.color = 'var(--muted)';
      head.textContent = "Can't check this audio";
      $('tbody').textContent = "Either there's no sound track, or it's a format your browser won't decode for me. Desktop Scrubgnome can check it.";
      $('tgeek').textContent = '';
      applyGeek();
      return;
    }
    const looks = {
      [Level.HIGH]: ['#@%&! ', 'var(--danger)', "Aha! A beacon! Why, it squeaks like a rusty hamster. I can't cut it out in the pocket edition yet. Run this clip through desktop Scrubgnome's audio cleaning before posting."],
      [Level.MEDIUM]: ['Hmm… ', 'var(--warn)', 'Something is chirping up there. Could be a beacon, could be a cheap phone charger. If it matters, clean the audio on the desktop version.'],
      [Level.LOW]: ['Meh. ', 'var(--info)', 'Just hiss up top. Electronics, wind, the usual gremlins. Nothing that looks like a beacon.'],
      [Level.NO_ROOM]: ['Ha! ', 'var(--accent)', 'Your phone chopped off everything above the beacon band when it recorded. Nowhere for a tracker to hide!'],
      [Level.NONE]: ['All quiet. ', 'var(--accent)', 'Not a peep up there. The ad-goblins have nothing to say about this one.'],
    }[r.level];
    head.style.color = looks[1];
    head.textContent = looks[0] + r.headline;
    $('tbody').textContent = r.explanation;
    $('tgeek').textContent = r.details.map(d => `› ${d}`).join('\n');
    twig(looks[2]);
    applyGeek();
  } catch (e) {
    fail(`Gah! My ear trumpet jammed: ${e.message}`);
  }
}

$('pick').addEventListener('click', () => !busy && $('file').click());
$('file').addEventListener('change', e => load(e.target.files[0]));
$('scrub').addEventListener('click', doScrub);
$('tone').addEventListener('click', doTone);
$('share').addEventListener('click', doShare);
$('again').addEventListener('click', reset);
$('geek').addEventListener('change', applyGeek);

// desktop drag-and-drop
const main = $('main');
main.addEventListener('dragover', e => { e.preventDefault(); main.classList.add('drop'); });
main.addEventListener('dragleave', () => main.classList.remove('drop'));
main.addEventListener('drop', e => { e.preventDefault(); main.classList.remove('drop'); load(e.dataTransfer.files[0]); });

reset();

// offline support (works after the first visit, even in airplane mode)
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
