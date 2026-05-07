const fs = require('fs');
const { execSync } = require('child_process');

// Get the CONFIRMED WORKING 6c119b7 version
const base = execSync('git show 6c119b7:bookSource.json', { 
    cwd: 'f:\\tmp\\ai_item\\unidbg',
    encoding: 'utf8',
    maxBuffer: 1024 * 1024
});

// Only ONE replacement: placeholder URL -> actual localhost
const result = base.replace("'http://你的IP:端口'", "'http://127.0.0.1:9999'");

fs.writeFileSync('f:\\tmp\\ai_item\\unidbg\\bookSource.json', result, 'utf8');

// Verify
const v = fs.readFileSync('f:\\tmp\\ai_item\\unidbg\\bookSource.json', 'utf8');
const obj = JSON.parse(v);
console.log('Size:', Buffer.byteLength(v, 'utf8'), 'Valid JSON: true');
console.log('Has CR:', v.includes('\r'));
console.log('sixgodHost (jsLib):', obj[0].jsLib.match(/sixgodHost\s*=\s*"([^"]+)"/)[1]);
console.log('urls (loginUrl):', obj[0].loginUrl.match(/'urls':\s*\[([^\]]*)\]/)[1].trim());
console.log('names (loginUrl):', obj[0].loginUrl.match(/'names':\s*\[([^\]]*)\]/)[1].trim());
console.log('Has 你的IP:', v.includes('你的IP'));

// Verify vs base: should differ by EXACTLY one replacement
const diffs = [];
const baseStr = base;
const currStr = v;
let minLen = Math.min(baseStr.length, currStr.length);
let i = 0;
while (i < minLen) {
    if (baseStr[i] !== currStr[i]) {
        // Find the diff segment
        let start = i;
        while (i < minLen && baseStr[i] !== currStr[i]) i++;
        diffs.push({
            pos: start,
            base: baseStr.substring(start, start + 60),
            curr: currStr.substring(start, start + 60)
        });
    } else {
        i++;
    }
}
console.log('\nNumber of diff segments:', diffs.length);
diffs.forEach(d => {
    console.log('  At pos ' + d.pos + ':');
    console.log('    Base: ' + JSON.stringify(d.base));
    console.log('    Curr: ' + JSON.stringify(d.curr));
});