import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { resolve, join } from 'node:path';
const directory = resolve(process.argv[2]);
const expected = JSON.parse(await readFile(join(directory, 'reference.json'), 'utf8'));
const actual = JSON.parse(await readFile(join(directory, 'java.json'), 'utf8'));
const { createGlue } = await import(pathToFileURL(join(directory, 'reference-bridge.mts')));
const glue = await createGlue();
const decoded = glue.callString(stack => {
  const value = glue.writeString(actual.encrypted);
  const length = glue.lastLength();
  const machine = glue.writeString('probe-machine');
  glue.exports.model_cache_decrypt(stack, value, length, machine, glue.lastLength());
});
if (decoded !== expected.plain) { throw new Error('Java-to-Node cache decryption failed'); }
if (actual.randomBytes !== expected.randomBytes) { throw new Error('Random input consumption differs between runtimes'); }
console.log(JSON.stringify({crossDecryption:'passed',randomBytes:actual.randomBytes,syntheticOnly:true}));
