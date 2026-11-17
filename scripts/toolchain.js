/**
 * 后端工具链解析（JDK / Maven），供 run-backend.js 与 dev-start.js 共用。
 *
 * 为什么需要它：
 *  - 不同机器装的情况不一样：Windows 那台 Maven 在 PATH 里，Mac 这台可能一个都没有；
 *  - 直接 spawn('mvn') 在没有 Maven 的机器上只会得到 ENOENT，看不出到底缺什么；
 *  - macOS 上 brew 可能装不了（权限/网络），所以支持 ~/tools 下的便携工具链
 *    （由 scripts/install-toolchain.sh 安装）。
 *
 * 查找顺序：环境变量 → PATH → ~/tools 便携目录 → 系统常见安装位置。
 */
const fs = require('fs');
const path = require('path');
const os = require('os');

const isWindows = os.platform() === 'win32';

function exeName(name) {
  return isWindows ? `${name}.exe` : name;
}

function isFile(p) {
  try {
    return fs.statSync(p).isFile();
  } catch (e) {
    return false;
  }
}

function isDir(p) {
  try {
    return fs.statSync(p).isDirectory();
  } catch (e) {
    return false;
  }
}

function pathDirs(env) {
  return String(env.PATH || env.Path || '')
    .split(path.delimiter)
    .filter(Boolean);
}

/** 在 PATH 里按文件名找可执行文件（Windows 下 mvn 实际是 mvn.cmd） */
function findInPath(names, env) {
  for (const dir of pathDirs(env)) {
    for (const name of names) {
      const candidate = path.join(dir, name);
      if (isFile(candidate)) return candidate;
    }
  }
  return null;
}

function listDirSafe(dir) {
  try {
    return fs.readdirSync(dir).sort();
  } catch (e) {
    return [];
  }
}

function javaHomeCandidates(env) {
  const list = [];
  if (env.JAVA_HOME) list.push(env.JAVA_HOME);

  const tools = path.join(os.homedir(), 'tools');
  for (const name of listDirSafe(tools)) {
    if (!name.toLowerCase().startsWith('jdk')) continue;
    // macOS 的 JDK 包多一层 Contents/Home，Linux/Windows 直接是根目录
    list.push(path.join(tools, name, 'Contents', 'Home'));
    list.push(path.join(tools, name));
  }

  const jvmDir = '/Library/Java/JavaVirtualMachines';
  for (const name of listDirSafe(jvmDir)) {
    list.push(path.join(jvmDir, name, 'Contents', 'Home'));
  }
  return list.filter((home) => isFile(path.join(home, 'bin', exeName('java'))));
}

function mavenCandidates(env) {
  const names = isWindows ? ['mvn.cmd', 'mvn.bat', 'mvn.exe'] : ['mvn'];
  const list = [];

  const onPath = findInPath(names, env);
  if (onPath) list.push(onPath);

  if (env.MAVEN_HOME) {
    for (const name of names) list.push(path.join(env.MAVEN_HOME, 'bin', name));
  }

  const tools = path.join(os.homedir(), 'tools');
  for (const name of listDirSafe(tools)) {
    if (!name.toLowerCase().startsWith('apache-maven')) continue;
    for (const bin of names) list.push(path.join(tools, name, 'bin', bin));
  }
  return list.filter(isFile);
}

/** 解析出可用的 JDK 与 Maven（找不到的项为 null，由调用方决定怎么提示） */
function resolveToolchain(env = process.env) {
  return {
    javaHome: javaHomeCandidates(env)[0] || null,
    mvn: mavenCandidates(env)[0] || null,
  };
}

/**
 * 在给定环境变量基础上补齐 JAVA_HOME 与 PATH，返回 { env, javaHome, mvn }。
 * 让子进程直接 spawn(mvn) 也能找到 java，不用调用方逐处拼路径。
 */
function toolchainEnv(baseEnv) {
  const env = { ...(baseEnv || process.env) };
  const { javaHome, mvn } = resolveToolchain(env);

  if (javaHome) {
    env.JAVA_HOME = javaHome;
    env.PATH = [path.join(javaHome, 'bin'), env.PATH].filter(Boolean).join(path.delimiter);
  }
  if (mvn) {
    env.PATH = [path.dirname(mvn), env.PATH].filter(Boolean).join(path.delimiter);
  }
  return { env, javaHome, mvn };
}

/** 缺少工具链时的统一提示（两个启动脚本共用一套话术） */
function printMissing(missing) {
  if (missing.mvn) {
    console.error('❌ 未找到 Maven');
  }
  if (missing.javaHome) {
    console.error('❌ 未找到 JDK（需要 17+）');
  }
  console.error('   处理方式：');
  console.error('   · macOS：bash scripts/install-toolchain.sh（装到 ~/tools，无需 sudo / brew）');
  console.error('   · 其他系统：安装 JDK 17 与 Maven，并确保 java、mvn 在 PATH 中');
  console.error('   · 若已安装仍报此错，检查 JAVA_HOME 是否指向 JDK 根目录（不是 bin 目录）');
}

module.exports = { isWindows, resolveToolchain, toolchainEnv, printMissing };
