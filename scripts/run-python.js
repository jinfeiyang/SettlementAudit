const { spawnSync } = require('child_process');
const os = require('os');
const { cleanEnv } = require('./clean-env');

const isWindows = os.platform() === 'win32';
const pythonCmd = isWindows ? 'python' : 'python3';
const [script, ...args] = process.argv.slice(2);

if (!script) {
  console.error('用法: node scripts/run-python.js <脚本路径> [参数...]');
  process.exit(1);
}

const result = spawnSync(pythonCmd, [script, ...args], {
  stdio: 'inherit',
  shell: false,
  env: cleanEnv(),
});

process.exit(result.status ?? 1);
