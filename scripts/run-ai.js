const { spawn } = require('child_process');
const path = require('path');
const os = require('os');
const { cleanEnv } = require('./clean-env');

const aiServiceDir = path.join(__dirname, '..', 'ai-service');
const isWindows = os.platform() === 'win32';
const pythonCmd = isWindows ? 'python' : 'python3';

console.log('🚀 启动AI服务 (端口 8000)...');
const uvicorn = spawn(pythonCmd, ['-m', 'uvicorn', 'app.main:app', '--reload', '--port', '8000'], {
  cwd: aiServiceDir,
  shell: false,
  stdio: 'inherit',
  env: cleanEnv(),
});

uvicorn.on('error', (err) => {
  console.error('❌ AI服务启动失败:', err.message);
  console.error('   请先运行: pip install -r ai-service/requirements.txt');
});
