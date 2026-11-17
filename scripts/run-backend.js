const { spawn } = require('child_process');
const path = require('path');
const { cleanEnv } = require('./clean-env');
const { isWindows, toolchainEnv, printMissing } = require('./toolchain');
const { isPortBusy } = require('./port-check');

const PORT = 8080;

const backendDir = path.join(__dirname, '..', 'backend');

// 传 mysql 则连真实 MySQL（不激活 profile，走 application.yml）；
// 否则用 local profile：H2 文件库，免装 MySQL/Redis。
const useMysql = process.argv.includes('mysql');

console.log('🚀 启动后端服务 (端口 8080)...');
if (useMysql) {
  console.log('   MySQL 模式：读取 application.yml 的 DB_URL / DB_USERNAME / DB_PASSWORD');
} else {
  console.log('   local profile：H2 文件库，无需 MySQL/Redis');
  console.log('   如需连真实 MySQL，用 npm run dev:backend:mysql');
}

const { env, javaHome, mvn } = toolchainEnv(cleanEnv());

if (!mvn || !javaHome) {
  printMissing({ mvn: !mvn, javaHome: !javaHome });
  process.exit(1);
}

const args = ['spring-boot:run'];
if (!useMysql) {
  args.push('-Dspring-boot.run.profiles=local');
}

function printExitHints() {
  console.error('');
  console.error('❌ 后端启动异常，常见原因：');
  console.error('   · 端口 ' + PORT + ' 已被占用（已有一个后端在跑）：');
  console.error('       macOS/Linux: lsof -nP -iTCP:' + PORT + ' -sTCP:LISTEN');
  console.error('       Windows:     netstat -ano | findstr :' + PORT);
  console.error('   · H2 文件被另一个实例占用（backend/data/h2/preaudit.mv.db）：');
  console.error('       同样先停掉旧实例；关掉 IDE 里正在运行的 Application 也算');
  console.error('   · 数据库脚本执行失败：看上方 Failed to execute database script');
}

(async () => {
  if (await isPortBusy(PORT)) {
    console.error('❌ 端口 ' + PORT + ' 已被占用：可能已有一个后端在运行');
    console.error('   先停掉旧实例再启动：');
    console.error('       macOS/Linux: lsof -nP -iTCP:' + PORT + ' -sTCP:LISTEN');
    console.error('       Windows:     netstat -ano | findstr :' + PORT);
    process.exit(1);
  }

  const child = spawn(mvn, args, {
    cwd: backendDir,
    shell: isWindows,
    stdio: 'inherit',
    env,
  });

  child.on('error', (err) => {
    console.error('❌ 后端启动失败:', err.message);
    printMissing({ mvn: true, javaHome: false });
  });

  child.on('exit', (code) => {
    if (code) printExitHints();
    process.exit(code === null ? 0 : code);
  });
})();
