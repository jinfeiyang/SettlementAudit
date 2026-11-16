#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成当日日报到 scripts/daily_report.txt（纯文本，无 # / ## 等 markdown 符号，适配钉钉表单直接粘贴）。

数据来源（手动整理，无需 git）：
  1. 项目功能优化：从 scripts/daily_notes.txt 的「[代码]」段落读取，展示改动的实际工作内容，而非文件名清单。
  2. 其他事项：从 scripts/daily_notes.txt 的「[其他]」段落读取（如非代码事项）。

daily_notes.txt 格式（按类别分段，空段可省；# 或 // 开头的行视为注释忽略）：
  [代码]
  - 图纸预览：新增 DWG→DXF 转换缓存与前端交互渲染，消除重复转换
  [其他]
  - 与一线确认图签符号命名
  - 参加元景认证考试

说明：
  - 本脚本只做「排版」：读 daily_notes.txt → 写 daily_report.txt。不读 git、不总结、不提交，
    所以 notes 里是哪天的内容，生成出来就是哪天的（要写今天的内容见 npm run daily 的提示词）。
  - 日期默认当天；可用环境变量 DAY=2026-08-14 覆盖（补写历史日报）。

运行：
  npm run daily:build
  DAY=2026-08-13 python3 scripts/build_daily_report.py   # 补写指定日期
"""

import os
import sys
import datetime
import textwrap

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "daily_report.txt")
NOTES = os.path.join(HERE, "daily_notes.txt")

DAY = os.environ.get("DAY") or datetime.date.today().isoformat()


def read_notes():
    """读取 daily_notes.txt，按 [代码] / [其他] 类别分组返回。

    返回 { '代码': [...], '其他': [...] }，仅含非空条目（去掉 '-' 前缀）。
    """
    groups = {"代码": [], "其他": []}
    cur = None
    if not os.path.exists(NOTES):
        return groups
    with open(NOTES, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#") or s.startswith("//"):
                continue
            if s.startswith("[") and s.endswith("]"):
                tag = s[1:-1].strip()
                cur = tag if tag in groups else None
                continue
            if cur is None:
                continue
            item = s[1:].strip() if s.startswith("-") else s
            if item:
                groups[cur].append(item)
    return groups


# 逐行写入，超长自动折行（续行缩进两空格），避免破坏钉钉表单粘贴对齐。
# 注意 break_long_words=True：中文句子没有空格，整段会成为一个「超长单词」，
# 若不允许断开长单词，textwrap 只能把 "1." 单独留在首行、正文整体挪到下一行（折行错乱）。
def append_lines(lines, text):
    if len(text) > 120:
        wrapped = textwrap.wrap(text, width=118, break_long_words=True, break_on_hyphens=False)
        if not wrapped:
            wrapped = [text]
        lines.append(wrapped[0])
        for extra in wrapped[1:]:
            lines.append("  " + extra)
    else:
        lines.append(text)


def main():
    day_label = DAY
    # —— 过期内容拦截 ——
    # 本脚本只排版（notes → report），不读 git、不写总结：notes 里是哪天的内容，成品就是哪天的。
    # 因此默认拒绝生成「notes 非当天」的日报，避免把昨天写的内容当成今天的发出去。
    # 补写历史日报（DAY=YYYY-MM-DD）或确实要重新生成时，用 --force / FORCE=1 跳过本检查。
    force = bool(os.environ.get("FORCE")) or ("--force" in sys.argv)
    if not force and day_label == datetime.date.today().isoformat() and os.path.exists(NOTES):
        notes_day = datetime.date.fromtimestamp(os.path.getmtime(NOTES)).isoformat()
        if notes_day != day_label:
            print(f"✗ 已中止：daily_notes.txt 最后修改于 {notes_day}，不是 {day_label}，生成出来会是 {notes_day} 的内容。")
            print("  本脚本只做排版：读 daily_notes.txt → 写 daily_report.txt，不读 git、不总结。")
            print("  请先更新 notes，再重新生成：")
            print("    · 让 AI 生成（推荐）：粘贴 npm run daily 打印的提示词，AI 会读今天的 git 改动写好 notes；")
            print("    · 自己写：编辑 scripts/daily_notes.txt，[代码] 段每条以 \"- \" 开头。")
            print("  确实要用现有 notes 强行重新生成：npm run daily:build -- --force")
            return 1
    notes = read_notes()
    code_items = notes.get("代码", [])
    other_items = notes.get("其他", [])

    # 注：生成后【不再】清空 daily_notes.txt，保证可重复生成、避免误跑变空。

    lines = []

    # 一、项目功能优化（展示实际工作内容，而非文件名）
    lines.append("一、项目功能优化")
    if code_items:
        for i, s in enumerate(code_items, 1):
            append_lines(lines, f"{i}. {s}")
    else:
        lines.append("（无）")

    # 二、其他事项
    if other_items:
        lines.append("二、其他")
        for i, s in enumerate(other_items, 1):
            append_lines(lines, f"{i}. {s}")

    content = "\n".join(lines)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"已生成（自动）：{OUT}")
    print(f"日期：{day_label} | 项目功能优化：{len(code_items)} 项 | 其他：{len(other_items)} 条")
    print("请打开该文件核对格式与内容，确认无误后再手动粘贴到钉钉表单。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
