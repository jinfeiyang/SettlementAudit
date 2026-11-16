const { spawn } = require('child_process');
const path = require('path');
const os = require('os');
const { cleanEnv } = require('./clean-env');

const backendDir = path.join(__dirname, '..', 'backend');
const isWindows = os.platform() === 'win32';
const mavenCmd = isWindows ? 'mvn.cmd' : 'mvn';

console.log('🚀 启动后端服务 (端口 8080)...');
console.log('   local profile：H2 文件库，无需 MySQL/Redis');
console.log('   如需连真实 MySQL，用 npm run dev:backend:mysql');

const mvn = spawn(mavenCmd, ['spring-boot:run', '-Dspring-boot.run.profiles=local'], {
  cwd: backendDir,
  shell: isWindows,
  stdio: 'inherit',
  env: cleanEnv(),
});

mvn.on('error', (err) => {
  console.error('❌ 后端启动失败:', err.message);
  console.error('   请确认已安装 Maven 并配置 JAVA_HOME');
});
