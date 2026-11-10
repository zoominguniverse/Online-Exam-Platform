import { randomBytes } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const cwd = fileURLToPath(new URL('..', import.meta.url))
const project = 'exam-java-test-' + randomBytes(5).toString('hex')
const env = {
  ...process.env,
  EXAM_MIGRATION_PASSWORD: randomBytes(24).toString('hex'),
  EXAM_APP_PASSWORD: randomBytes(24).toString('hex'),
  EXAM_TEST_EXPECTED_VERSION: process.argv.includes('--negative-control') ? 'deliberately-invalid' : 'v1',
}
const prefix = ['compose', '-p', project, '-f', 'deploy/compose.dev.yml', '-f', 'deploy/compose.test.yml']

function run(executable, args) {
  const result = spawnSync(executable, args, { cwd, env, stdio: 'inherit' })
  if (result.error) console.error(result.error.message)
  return result.status ?? 1
}

let result = run(process.execPath, ['--test', 'tests/exam.test.cjs', 'tests/wechat.test.cjs'])
if (result === 0) {
  try {
    result = run('docker', [...prefix, 'run', '--rm', '--build', 'interface-tests'])
  } finally {
    const cleanup = run('docker', [...prefix, 'down', '--volumes'])
    if (result === 0) result = cleanup
  }
}
process.exitCode = result
