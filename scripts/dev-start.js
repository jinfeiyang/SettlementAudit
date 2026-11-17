/**
 * 跨平台开发启动脚本（兼容 Mac / Linux / Windows）
 *
 * 背景：
 *  - Windows 版 CodeBuddy IDE 会通过 NODE_OPTIONS 注入 node-language-shim.cjs，
 *    它在检测到 CODEBUDDY_SESSION_ID 时会加载 Console Ninja 插件，
 *    导致 Vite 在深层目录下崩溃（Cannot read directory "../../../../.."）。
 *  - 因此在启动子进程前，清除这些 IDE 专属环境变量，让 Vite 像在普通终端里一样运行。
 *  - Mac/Linux 上没有这些变量，清除操作无副作用。
 */

const { spawn } = require('child_process');
const path = require('path');
const os = require('os');
const { cleanEnv } = require('./clean-env');
const { toolchainEnv, printMissing } = require('./toolchain');

const rootDir = path.join(__dirname, '..');
const backendDir = path.join(rootDir, 'backend');
const aiServiceDir = path.join(rootDir, 'ai-service');
const frontendDir = path.join(rootDir, 'frontend');

const isWindows = os.platform() === 'win32';
const pythonCmd = isWindows ? 'python' : 'python3';

function run(name, cmd, args, cwd, env, hint) {
  const child = spawn(cmd, args, {
    cwd,
    env: env || cleanEnv(),
    shell: isWindows,
    stdio: 'inherit',
  });
  child.on('exit', (code) => {
    if (code !== 0 && code !== null) {
      console.log(`[${name}] 退出 (code=${code})`);
      if (hint) console.log(hint);
    }
  });
  child.on('error', (err) => console.error(`[${name}] 启动失败: ${err.message}`));
  return child;
}

const children = [];
children.push(run('web', 'npm', ['run', 'dev'], frontendDir));
children.push(run('ai', pythonCmd, ['-m', 'uvicorn', 'app.main:app', '--reload', '--port', '8000'], aiServiceDir));

// local profile：H2 文件库，免装 MySQL/Redis，本地一条命令即可跑通主链路。
// JDK / Maven 由 toolchain 统一解析（环境变量 → PATH → ~/tools 便携工具链）。
const backendToolchain = toolchainEnv(cleanEnv());
if (backendToolchain.mvn && backendToolchain.javaHome) {
  children.push(
    run('backend', backendToolchain.mvn, ['spring-boot:run', '-Dspring-boot.run.profiles=local'],
      backendDir, backendToolchain.env,
      '   ⚠ 后端失败常见原因：8080 端口被占用、H2 文件被另一个实例锁住（先关掉旧的后端/IDE 里的 Application）'),
  );
} else {
  printMissing({ mvn: !backendToolchain.mvn, javaHome: !backendToolchain.javaHome });
  console.error('   ⚠ 前端与 AI 服务照常启动，但后端不会启动（登录等 /api 接口会 500）');
}

function killTree(child) {
  if (!child.pid) return;
  if (isWindows) {
    // Windows 下 child.kill() 只杀 cmd 外壳，mvn/java/python 会残留并占住端口
    spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
  } else {
    child.kill();
  }
}

function shutdown() {
  children.forEach(killTree);
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
