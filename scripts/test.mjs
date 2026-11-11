import { randomBytes } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { readdirSync,writeFileSync,mkdirSync } from 'node:fs'
import { resolve,relative,isAbsolute } from 'node:path'

if (process.versions.node !== '22.22.2') throw new Error('Use Node 22.22.2')

const cwd = fileURLToPath(new URL('..', import.meta.url))
const project = 'exam-java-test-' + randomBytes(5).toString('hex')
const env = {
  ...process.env,
  EXAM_MIGRATION_PASSWORD: randomBytes(24).toString('hex'),
  EXAM_APP_PASSWORD: randomBytes(24).toString('hex'),
  EXAM_TEST_EXPECTED_VERSION: process.argv.includes('--negative-control') ? 'deliberately-invalid' : 'v1',
}
const prefix = ['compose', '-p', project, '-f', 'deploy/compose.dev.yml', '-f', 'deploy/compose.test.yml']
if(process.env.EXAM_TEST_OFFLINE_IMAGES==='1'){
  mkdirSync(resolve(cwd,'tmp',project),{recursive:true})
  const offline=resolve(cwd,'tmp',project,'offline-images.json')
  writeFileSync(offline,JSON.stringify({services:{db:{pull_policy:'never'}}}));prefix.push('-f',offline)
}
if(process.env.EXAM_TEST_MAVEN_MIRROR) {
  const mirror=process.env.EXAM_TEST_MAVEN_MIRROR
  if(!/^http:\/\/host\.docker\.internal:[0-9]+\/$/.test(mirror))throw new Error('Expected a local host Maven transport mirror')
  mkdirSync(resolve(cwd,'tmp',project),{recursive:true})
  const settings=resolve(cwd,'tmp',project,'maven-settings.xml');const override=resolve(cwd,'tmp',project,'mirror.json')
  writeFileSync(settings,`<settings><mirrors><mirror><id>central</id><mirrorOf>*</mirrorOf><url>${mirror}</url></mirror></mirrors></settings>`)
  writeFileSync(override,JSON.stringify({services:{'interface-tests':{volumes:[{type:'bind',source:settings,target:'/tmp/exam-maven-settings.xml',read_only:true}]}}}))
  prefix.push('-f',override)
}
// Mutation verification builds copies under this workspace; the live source is never modified.
if(process.env.EXAM_TEST_BACKEND_CONTEXT) {
  const context=resolve(process.env.EXAM_TEST_BACKEND_CONTEXT)
  const within=relative(resolve(cwd,'tmp'),context)
  if(!within||within.startsWith('..')||isAbsolute(within))throw new Error('Test backend context must be under workspace tmp/')
  mkdirSync(resolve(cwd,'tmp',project),{recursive:true})
  const override=resolve(cwd,'tmp',project,'context.json')
  writeFileSync(override,JSON.stringify({services:{'interface-tests':{build:{context,target:'build'}}}}))
  prefix.push('-f',override)
}

function run(executable, args) {
  const result = spawnSync(executable, args, { cwd, env, stdio: 'inherit' })
  if (result.error) console.error(result.error.message)
  return result.status ?? 1
}

const selection = process.argv.find(argument => argument.startsWith('--java-test='))?.slice('--java-test='.length)
const command = ['mvn', '-B', '-ntp', '-Dmaven.repo.local=/opt/maven-repository',
  ...(process.env.EXAM_TEST_MAVEN_MIRROR?['--settings','/tmp/exam-maven-settings.xml']:[]),
  ...(selection?[`-Dtest=${selection}`]:[]),'test']
let result = run(process.execPath, ['--test', ...readdirSync(new URL('../tests/', import.meta.url))
  .filter(name => name.endsWith('.test.cjs')).map(name => 'tests/' + name)])
if (result === 0) {
  try {
    result = run('docker', [...prefix, 'run', '--rm', '--build', 'interface-tests', ...command])
  } finally {
    const cleanup = run('docker', [...prefix, 'down', '--volumes'])
    if (result === 0) result = cleanup
  }
}
process.exitCode = result
