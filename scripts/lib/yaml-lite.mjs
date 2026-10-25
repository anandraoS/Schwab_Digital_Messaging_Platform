// yaml-lite: a small, dependency-free parser for the YAML SUBSET used by this repo's CI/CD, compose, K8s and CF files.
// Supports: block maps/lists, nested lists-of-maps, quoted + plain scalars, block scalars (| >), single/multi-line
// flow [..] {..}, anchors (&a), aliases (*a), merge keys (<<), comments, multi-document (---).
// Does NOT support: tags (!!), complex keys (?), multi-line plain scalars, tabs for indentation.
// It is a syntax sanity check, not a full YAML 1.2 implementation. Errors carry a line number.

export class YamlError extends Error {
  constructor(msg, line) { super(`line ${line}: ${msg}`); this.line = line; }
}

function indentOf(s) { let n = 0; while (n < s.length && s[n] === ' ') n++; return n; }

function stripComment(s) {
  let q = null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (q) { if (c === q) { if (q === "'" && s[i + 1] === "'") { i++; continue; } q = null; } else if (c === '\\' && q === '"') i++; continue; }
    if ((c === '"' || c === "'") && (i === 0 || /[\s\[{,:-]/.test(s[i - 1]))) { q = c; continue; }
    if (c === '#' && (i === 0 || /\s/.test(s[i - 1]))) return s.slice(0, i).trimEnd();
  }
  return s.trimEnd();
}

function findKeySplit(t) {
  // returns index of the ':' that terminates a mapping key, or -1
  let q = null;
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (q) { if (c === q) q = null; continue; }
    if (i === 0 && (c === '"' || c === "'")) { q = c; continue; }
    if (c === ':' && (i + 1 === t.length || t[i + 1] === ' ')) return i;
    if ((c === '[' || c === '{') && i === 0) return -1;
  }
  return -1;
}

function plainScalar(t) {
  if (t === '' || t === '~' || t === 'null' || t === 'Null' || t === 'NULL') return null;
  if (t === 'true' || t === 'True' || t === 'TRUE') return true;
  if (t === 'false' || t === 'False' || t === 'FALSE') return false;
  if (/^[-+]?\d+$/.test(t)) return Number(t);
  if (/^[-+]?\d*\.\d+$/.test(t)) return Number(t);
  return t;
}

function unquote(t, line) {
  if (t.startsWith('"')) {
    if (!t.endsWith('"') || t.length < 2) throw new YamlError('unterminated double-quoted string', line);
    return t.slice(1, -1).replace(/\\(["\\nt\/])/g, (_, c) => ({ n: '\n', t: '\t' }[c] ?? c));
  }
  if (t.startsWith("'")) {
    if (!t.endsWith("'") || t.length < 2) throw new YamlError('unterminated single-quoted string', line);
    return t.slice(1, -1).replace(/''/g, "'");
  }
  return null;
}

function parseFlow(str, line) {
  let p = 0;
  const ws = () => { while (p < str.length && /\s/.test(str[p])) p++; };
  function value() {
    ws();
    if (str[p] === '[') {
      p++; const arr = [];
      for (;;) { ws(); if (str[p] === ']') { p++; return arr; } arr.push(value()); ws(); if (str[p] === ',') p++; else if (str[p] !== ']') throw new YamlError('expected , or ] in flow sequence', line); }
    }
    if (str[p] === '{') {
      p++; const obj = {};
      for (;;) {
        ws(); if (str[p] === '}') { p++; return obj; }
        const k = token(true); ws();
        if (str[p] === ':') { p++; obj[k] = value(); } else obj[k] = null;
        ws(); if (str[p] === ',') p++; else if (str[p] !== '}') throw new YamlError('expected , or } in flow mapping', line);
      }
    }
    return plainOrQuoted(token(false));
  }
  function token(isKey) {
    ws();
    if (str[p] === '"' || str[p] === "'") {
      const q = str[p]; let j = p + 1;
      while (j < str.length && !(str[j] === q && str[j - 1] !== '\\')) j++;
      if (j >= str.length) throw new YamlError('unterminated quoted string in flow', line);
      const raw = str.slice(p, j + 1); p = j + 1; return { quoted: raw };
    }
    let j = p;
    while (j < str.length && !(',]}'.includes(str[j])) && !(isKey && str[j] === ':' && (str[j + 1] === ' ' || j + 1 >= str.length))) {
      if (!isKey && str[j] === ':' && str[j + 1] === ' ') break;
      j++;
    }
    const raw = str.slice(p, j).trim(); p = j; return raw;
  }
  function plainOrQuoted(tok) {
    if (typeof tok === 'object') return unquote(tok.quoted, line);
    return plainScalar(tok);
  }
  // keys may come back as {quoted}; normalise
  const origToken = token;
  // eslint-disable-next-line no-func-assign
  token = (isKey) => { const r = origToken(isKey); return isKey && typeof r === 'object' ? unquote(r.quoted, line) : r; };
  const v = value(); ws();
  if (p < str.length) throw new YamlError('unexpected trailing characters in flow collection', line);
  return v;
}

function parseDocument(raw, base) {
  const anchors = {};
  let i = 0;
  const L = (k = i) => base + k + 1;
  const isBlank = (s) => s.trim() === '' || s.trim().startsWith('#');
  const skipBlank = () => { while (i < raw.length && isBlank(raw[i])) i++; return i < raw.length; };
  for (const [k, s] of raw.entries()) { if (/^\s*\t/.test(s) || /^ *\t/.test(s)) throw new YamlError('tab used for indentation', base + k + 1); }

  function blockScalar(header, parentIndent) {
    const folded = header[0] === '>';
    const lines = []; let blockIndent = null;
    while (i < raw.length) {
      const s = raw[i];
      if (s.trim() === '') { lines.push(''); i++; continue; }
      const ind = indentOf(s);
      if (ind <= parentIndent) break;
      if (blockIndent === null) blockIndent = ind;
      lines.push(s.slice(Math.min(blockIndent, ind))); i++;
    }
    while (lines.length && lines[lines.length - 1] === '') lines.pop();
    return folded ? lines.join(' ') : lines.join('\n');
  }

  function scalarOrFlow(text, parentIndent, line) {
    let t = text.trim(); let anchor = null;
    const am = t.match(/^&(\S+)\s*(.*)$/);
    if (am) { anchor = am[1]; t = am[2]; }
    let val;
    if (t.startsWith('*')) {
      const name = t.slice(1).trim();
      if (!(name in anchors)) throw new YamlError(`unknown alias *${name}`, line);
      return anchors[name];
    }
    if (/^[|>][+-]?\d*$/.test(t)) val = blockScalar(t, parentIndent);
    else if (t.startsWith('[') || t.startsWith('{')) {
      let acc = t; const open = (s) => (s.match(/[\[{]/g) || []).length - (s.match(/[\]}]/g) || []).length;
      while (open(acc) > 0 && i < raw.length) { acc += ' ' + stripComment(raw[i]).trim(); i++; }
      val = parseFlow(acc, line);
    } else if (t.startsWith('"') || t.startsWith("'")) val = unquote(t, line);
    else val = plainScalar(t);
    if (anchor) anchors[anchor] = val;
    return val;
  }

  function parseNode(minIndent) {
    if (!skipBlank()) return null;
    const s = raw[i]; const ind = indentOf(s);
    if (ind < minIndent) return null;
    const t = stripComment(s).trim();
    if (t === '-' || t.startsWith('- ')) return parseList(ind);
    if (findKeySplit(t) >= 0) return parseMap(ind);
    i++; return scalarOrFlow(t, ind - 1, L(i - 1));
  }

  function parseMap(ind) {
    const obj = {};
    while (skipBlank()) {
      const s = raw[i]; const cur = indentOf(s);
      if (cur < ind) break;
      if (cur > ind) throw new YamlError('unexpected indentation inside mapping', L());
      const t = stripComment(s).trim();
      if (t === '-' || t.startsWith('- ')) break;
      const sp = findKeySplit(t);
      if (sp < 0) throw new YamlError(`expected "key: value" but got "${t.slice(0, 40)}"`, L());
      const kline = L();
      let key = t.slice(0, sp).trim();
      const uq = unquote(key, kline); if (uq !== null) key = uq;
      const rest = t.slice(sp + 1).trim();
      i++;
      let val;
      if (rest === '' || /^&\S+$/.test(rest)) {
        const aName = rest.startsWith('&') ? rest.slice(1) : null;
        if (skipBlank() && indentOf(raw[i]) > ind) val = parseNode(ind + 1);
        else if (skipBlank() && indentOf(raw[i]) === ind && /^-( |$)/.test(stripComment(raw[i]).trim())) val = parseList(ind);
        else val = null;
        if (aName) anchors[aName] = val;
      } else val = scalarOrFlow(rest, ind, kline);
      if (key === '<<' && val && typeof val === 'object') { for (const [k2, v2] of Object.entries(val)) if (!(k2 in obj)) obj[k2] = v2; continue; }
      if (Object.prototype.hasOwnProperty.call(obj, key)) throw new YamlError(`duplicate key "${key}"`, kline);
      obj[key] = val;
    }
    return obj;
  }

  function parseList(ind) {
    const arr = [];
    while (skipBlank()) {
      const s = raw[i]; const cur = indentOf(s);
      if (cur < ind) break;
      if (cur > ind) throw new YamlError('unexpected indentation inside sequence', L());
      const t = stripComment(s).trim();
      if (!(t === '-' || t.startsWith('- '))) break;
      const line = L();
      const rest = t === '-' ? '' : t.slice(2).trim();
      if (rest === '') { i++; arr.push(skipBlank() && indentOf(raw[i]) > ind ? parseNode(ind + 1) : null); continue; }
      const offset = s.indexOf('-', ind) + 1 + (s.slice(s.indexOf('-', ind) + 1).match(/^ */)[0].length);
      const looksMap = findKeySplit(rest) >= 0 && !/^[\[{]/.test(rest);
      if (looksMap) {
        raw[i] = ' '.repeat(offset) + s.slice(offset);
        arr.push(parseMap(offset));
      } else if (/^-( |$)/.test(rest)) {
        raw[i] = ' '.repeat(offset) + s.slice(offset);
        arr.push(parseList(offset));
      } else { i++; arr.push(scalarOrFlow(rest, ind, line)); }
    }
    return arr;
  }

  const result = parseNode(0);
  if (skipBlank()) throw new YamlError(`unparsed content remains: "${raw[i].trim().slice(0, 40)}"`, L());
  return result;
}

/** Parse a (possibly multi-document) YAML string. Returns an array of documents. */
export function parseYamlDocs(text) {
  const rawLines = text.replace(/\r\n/g, '\n').split('\n');
  const docs = []; let cur = []; let start = 0;
  const flush = () => { if (cur.some((l) => l.trim() !== '' && !l.trim().startsWith('#'))) docs.push(parseDocument(cur, start)); cur = []; };
  rawLines.forEach((l, idx) => {
    if (/^---(\s|$)/.test(l)) { flush(); start = idx + 1; }
    else if (/^\.\.\.\s*$/.test(l)) { flush(); start = idx + 1; }
    else { if (cur.length === 0) start = idx; cur.push(l); }
  });
  flush();
  return docs;
}

export function parseYaml(text) {
  const docs = parseYamlDocs(text);
  return docs.length === 0 ? null : docs[0];
}
