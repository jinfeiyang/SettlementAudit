/**
 * 构造干净的环境变量，剔除 Windows 版 CodeBuddy IDE 注入的 Node 钩子。
 * 这些钩子会加载 Console Ninja 插件，导致 Vite 在深层目录下崩溃
 * （Cannot read directory "../../../../.."）。Mac/Linux 无这些变量，无副作用。
 */
function cleanEnv(extra = {}) {
  const env = { ...process.env, ...extra };
  delete env.NODE_OPTIONS;
  delete env.CODEBUDDY_SESSION_ID;
  delete env.CLAUDE_SESSION_ID;
  return env;
}

module.exports = { cleanEnv };
