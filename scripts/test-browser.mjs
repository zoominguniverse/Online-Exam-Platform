import { randomBytes } from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, openSync, closeSync } from 'node:fs'
import { freePort } from './lib/ports.mjs'
import {verifyBrowserEvidence} from './check-browser-evidence.mjs'
import { fileURLToPath } from 'node:url'

if (process.versions.node !== '22.22.2') throw new Error('Use Node 22.22.2')
const cwd = fileURLToPath(new URL('..', import.meta.url))
const project = 'exam-browser-' + randomBytes(5).toString('hex')
const directory = `${cwd}tmp/${project}`
mkdirSync(directory, { recursive: true })
const apiPort = await freePort()
const webPort = await freePort()
const adminPort = await freePort()
const env = { ...process.env,
  EXAM_MIGRATION_PASSWORD: randomBytes(24).toString('hex'),
  EXAM_APP_PASSWORD: randomBytes(24).toString('hex'),
  EXAM_BOOTSTRAP_ADMIN_PASSWORD: randomBytes(24).toString('hex'),
  EXAM_API_PORT: String(apiPort), EXAM_API_ORIGIN: `http://127.0.0.1:${apiPort}`,
  EXAM_E2E_ORIGIN: `http://localhost:${webPort}`, EXAM_E2E_PROJECT: project,
  EXAM_E2E_ADMIN_ORIGIN: `http://localhost:${adminPort}`,
  EXAM_E2E_ARTIFACTS: `${directory}/evidence`,
}
const override = `${directory}/compose.json`
writeFileSync(override, JSON.stringify({ services: {
  api: { image: `${project}-api` }, migrate: { image: `${project}-api` },
  ...(process.env.EXAM_TEST_OFFLINE_IMAGES==='1'?{db:{pull_policy:'never'}}:{}),
} }))
const prefix = ['compose', '-p', project, '-f', 'deploy/compose.dev.yml', '-f', override]
function run(command, args) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' })
  if (result.error) console.error(result.error.message)
  return result.status ?? 1
}
async function ready(url, child) {
  for (let i = 0; i < 120; i++) {
    if (child && child.exitCode !== null) throw new Error('Vite stopped before readiness')
    // Keep a referenced timeout while probing: AbortSignal.timeout alone can let
    // Node exit with an unsettled top-level await during container startup.
    const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),1500)
    try { if ((await fetch(url, { signal: controller.signal })).ok) return } catch { /* starting */ }
    finally {clearTimeout(timeout)}
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`Readiness timeout: ${url}`)
}
let vite
let adminVite
let result = 1
const log = openSync(`${directory}/vite.log`, 'w')
try {
  if (run('docker', [...prefix, 'up', '--build', '-d', 'api']) !== 0) throw new Error('Compose startup failed')
  await ready(`http://127.0.0.1:${apiPort}/actuator/health/readiness`)
  vite = spawn(process.execPath, ['web/node_modules/vite/bin/vite.js', '--config',
    'web/student/vite.config.ts', '--port', String(webPort)], { cwd, env, stdio: ['ignore', log, log] })
  await ready(`${env.EXAM_E2E_ORIGIN}/student/login`, vite)
  adminVite=spawn(process.execPath,['web/node_modules/vite/bin/vite.js','--config','web/admin/vite.config.ts','--port',String(adminPort)],{cwd,env,stdio:['ignore',log,log]})
  await ready(`${env.EXAM_E2E_ADMIN_ORIGIN}/admin/login`,adminVite)
  console.log(`Isolated browser project: ${project}`)
  result = run(process.execPath, ['web/node_modules/playwright/cli.js', 'test', '--config', 'web/playwright.config.ts', ...process.argv.slice(2)])
  if(result===0&&process.argv.length===2){
    const verification=verifyBrowserEvidence(`${directory}/evidence`)
    writeFileSync(`${directory}/report.json`,JSON.stringify(verification,null,2)+'\n')
    console.log('Required browser evidence verified: '+verification.requiredScenarios+' scenarios')
  }
} catch (error) {
  result=1
  console.error(error.message)
} finally {
  const apiLog=spawnSync('docker',[...prefix,'logs','--no-color','api','migrate','db'],{cwd,env,encoding:'utf8',maxBuffer:32*1024*1024})
  writeFileSync(`${directory}/services.log`,(apiLog.stdout||'')+(apiLog.stderr||''))
  for(const child of [vite,adminVite]) if (child && child.exitCode === null) {
    const stopped = new Promise(resolve => child.once('exit', resolve))
    child.kill();await stopped
  }
  closeSync(log)
  const cleanup = run('docker', [...prefix, 'down', '--volumes'])
  if (result === 0) result = cleanup
  console.log(`Browser artifacts: ${directory}/evidence`)
}
process.exitCode = result
