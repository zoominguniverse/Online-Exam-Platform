import { spawn } from 'node:child_process';
import { readdir, mkdtemp, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:net';
import { randomUUID, randomBytes } from 'node:crypto';
import EmbeddedPostgres from 'embedded-postgres';
const files = [];
async function collect(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const name = `${dir}/${entry.name}`;
    if (entry.isDirectory()) await collect(name);
    else if (name.endsWith('.test.ts')) files.push(name);
  }
}
await collect('tests');
const directory = await mkdtemp(path.join(tmpdir(), 'exam-postgres-'));
const socket = createServer();
const runId=randomUUID(),reportDir=path.resolve('artifacts','test',runId);
await mkdir(reportDir,{recursive:true});
let postgres;
let started = false;
try {
  await new Promise((resolve, reject) => { socket.once('error', reject); socket.listen(0, '127.0.0.1', resolve); });
  const port = socket.address().port;
  await new Promise(resolve => socket.close(resolve));
  const password = randomBytes(24).toString('hex');
  postgres = new EmbeddedPostgres({ databaseDir: directory, user: 'exam_test', password, port, persistent: false, authMethod: 'scram-sha-256', autoRehydrateSymlinks: true, postgresFlags: ['-h', '127.0.0.1', '-k', directory], onLog() {}, onError() {} });
  await postgres.initialise();
  await postgres.start(); started = true;
  await postgres.createDatabase('exam_test');
  const child = spawn(process.execPath, ['--test','--test-reporter=spec','--test-reporter-destination=stdout','--test-reporter=junit',`--test-reporter-destination=${path.join(reportDir,'junit.xml')}`, ...files.sort()], { stdio: 'inherit', env: { ...process.env, EXAM_TEST_RUN_ID: runId, EXAM_TEST_DATABASE_URL: `postgresql://exam_test:${password}@127.0.0.1:${port}/exam_test` } });
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => child.kill(signal));
  process.exitCode = await new Promise((resolve,reject) => { child.on('error',reject);child.on('exit', (code, signal) => resolve(signal ? 1 : code ?? 1)); });
} finally {
  let cleaned=false;
  try {
    if (socket.listening) await new Promise(resolve=>socket.close(resolve));
    if (started) await postgres.stop();
    await rm(directory, { recursive: true, force: true });cleaned=true;
  } finally {
    await writeFile(path.join(reportDir,'run.json'),JSON.stringify({runId,node:process.version,platform:process.platform,architecture:process.arch,exitCode:process.exitCode??1,cleaned,files},null,2));
    console.log(`Test report: ${reportDir}`);
  }
}
