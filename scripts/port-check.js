/**
 * 端口占用探测：启动后端前先看 8080 有没有人占，避免只看到一句 exit code 1。
 */
const net = require('net');

/** 端口是否已被占用（连得上就认为被占用） */
function isPortBusy(port, host = '127.0.0.1', timeout = 600) {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host });
    let settled = false;
    const finish = (busy) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(busy);
    };
    socket.setTimeout(timeout);
    socket.once('connect', () => finish(true));
    socket.once('timeout', () => finish(false));
    socket.once('error', () => finish(false));
  });
}

module.exports = { isPortBusy };
