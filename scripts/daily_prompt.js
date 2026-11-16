#!/usr/bin/env node
// 生成一段「发给 AI 助手」的提示词，打印到终端，复制后粘贴到对话输入框即可。
// 用途：让 AI 读取当日 git 改动 → 写入 scripts/daily_notes.txt → 生成 scripts/daily_report.txt → 提交。
// 日期取本机时区（toISOString 是 UTC，早上 8 点前会比本地日期少一天）
const now = new Date()
const pad = (n) => String(n).padStart(2, '0')
const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`

const prompt = `请帮我生成今天的开发日报（项目：通信工程造价结算 AI 预审分析工具）。

步骤：
1. 查看仓库今天（${today}）的 git 改动：运行 git log --since="${today} 00:00:00" --pretty=format:"%h %s" 以及 git status --short，结合具体文件改动总结「今天实际做了什么」。
2. 把总结写入 scripts/daily_notes.txt 的 [代码] 段（每条用 "- " 开头，描述性语言，说明改动内容与影响，不要只列文件名）；如当天有非代码事项，写入 [其他] 段。
3. 运行 npm run daily:build 生成 scripts/daily_report.txt，确认文件已生成、内容无误。
4. 提交改动：git add scripts/daily_notes.txt scripts/daily_report.txt && git commit -m "chore: 补充 ${today} 开发日报（简要列出当日主要内容）"；推送由我确认后手动执行，不要自动 push。

日报格式要求（纯文本，无 markdown 符号，适配钉钉表单直接粘贴）：
一、项目功能优化
1. xxx（说明改动与影响）
2. xxx
二、其他
1. xxx

按「项目功能优化 / 其他」两类组织，条目清晰、聚焦实际工作与影响。生成后告诉我日报已写入 scripts/daily_report.txt。`

console.log(prompt)
