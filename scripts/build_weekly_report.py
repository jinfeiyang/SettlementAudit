#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成周报，同时输出两份：
  - scripts/weekly_report.md ：Markdown 格式（# 大标题 / ## 主题 / ### 子主题），在钉钉文档、网页、Typora 等
    支持 Markdown 的环境里渲染出真实的字号与加粗层级。
  - scripts/weekly_report.txt ：纯文本（无 markdown 符号），适配钉钉审批/表单纯文本框直接粘贴，用缩进区分层级。

内容来源（沿用 telecom-dashboard-vue 项目的周报生成逻辑）：
  1. 本周工作总结（自动）：调用钉钉 CLI（dws）拉取多维表「团队日报、周报」中提交人「陈翱」的日报，
     默认统计「本周一 ~ 今天」（覆盖本周 5 个工作日），按主题归纳并在主题内分子主题。
     临时改区间：WEEK_START=2026-11-16 python3 scripts/build_weekly_report.py
     强制重新拉取（忽略缓存）：NO_CACHE=1 python3 scripts/build_weekly_report.py
  2. 下周计划（人工维护）：NEXT_PLAN。

依赖：dws CLI（需已登录；未登录先执行 dws auth login）。
运行：npm run weekly
输出：scripts/weekly_report.md 与 scripts/weekly_report.txt
"""

import json
import os
import re
import shutil
import subprocess
import time
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_MD = os.path.join(HERE, "weekly_report.md")
OUT_TXT = os.path.join(HERE, "weekly_report.txt")
CACHE = os.path.join(HERE, ".aitable_records.json")
CACHE_TTL = 1800  # 缓存 30 分钟，避免反复全量拉取

# ---- 钉钉多维表配置（团队日报、周报）----
BASE_ID = "jb9Y4gmKWrZMLD3MT4leO1xkWGXn6lpz"
TABLE_ID = "dv19yqvsgs3oebp3pcjys"
FIELD_DATE = "NQCmHjY"       # 汇报日期
FIELD_CONTENT = "9GnLKMU"    # 当天工作内容
FIELD_SUBMITTER = "RqyEYpD"  # 提交人（成员数组）
FIELD_STATS = "DgK6Ilm"      # 工作量统计（暂不写入周报）

# 提交人过滤：陈翱
SUBMITTER_NAME = "陈翱"
SUBMITTER_USER_ID = "27570710041225385"

CN_NUM = ["一", "二", "三", "四", "五", "六", "七", "八", "九", "十"]

# ---- 主题归纳配置（造价预审工具，先具体后宽泛）----
# 每条要点单独判主题：先按要点文本关键词，回退到所在块标题，再回退「其他」。
TOPIC_GROUPS = [
    ("施工光缆与重复计费", ["施工光缆", "表七", "段落", "重复计费", "重复段", "芯数"]),
    ("清单导入与解析", ["清单", "表三甲", "表八", "xls", "Excel", "导入", "公式", "可解析率", "标准化", "甲供"]),
    ("图纸解析与量算", ["图纸", "DWG", "DXF", "图框", "图签", "图页", "材料表", "量算", "ODA", "标注", "光缆长度"]),
    ("定额取费与核查", ["定额", "取费", "核查", "疑点", "命中率", "偏差", "核减", "复核", "计价"]),
    ("报告与导出", ["报告", "导出", "成文", "归档", "台账", "PDF"]),
    ("项目与资料管理", ["项目", "资料", "上传", "订单", "编号", "施工费", "合同", "目录"]),
    ("界面与交互", ["页签", "列表", "表格", "高亮", "跳转", "筛选", "分页", "弹窗", "字号", "排版", "样式", "列宽"]),
    ("文档与方案", ["文档", "说明书", "手册", "需求说明", "方案", "部署", "容器云", "架构", "工具链"]),
    ("其他事务", ["培训", "考试", "竞赛", "大赛", "会议", "检查", "验收", "通报", "PPT", "抽审",
                  "审计", "自研线索", "反诈"]),
]
OTHER_TOPIC = "其他"

# 日报里的「块标题」行：一、项目功能优化
TITLE_RE = re.compile(r"^[一二三四五六七八九十]+[、.．]\s*(.*)$")
# 要点行：1. xxx / 1、xxx / - xxx；点后紧跟数字不当编号（避免「13.5%」被切开）
ITEM_RE = re.compile(r"^(\d+[.、．](?!\d)\s*|[-*]\s+)(.*)$")

# ---- 子主题聚类（主题内再分小类）----
SUBTOPIC_GROUPS = [
    ("导入与解析", ["导入", "解析", "识别", "提取", "读取", "多表合并", "精度"]),
    ("图纸与光缆", ["图纸", "光缆", "图签", "图框", "图页", "材料表", "芯数", "段落"]),
    ("核查与规则", ["核查", "规则", "定额", "取费", "偏差", "疑点", "复核", "命中率", "重复"]),
    ("展示与交互", ["展示", "列表", "表格", "页签", "跳转", "高亮", "筛选", "分页", "弹窗", "排版", "字号"]),
    ("导出与报告", ["导出", "报告", "成文", "台账", "归档"]),
    ("项目与资料", ["项目", "资料", "上传", "订单", "编号", "施工费", "合同"]),
    ("文档与环境", ["文档", "说明书", "手册", "方案", "部署", "工具链", "环境"]),
]
SUBTOPIC_OTHER = "其他"

NEXT_PLAN = """下周计划
1. 补齐一期验收三项指标的自动验证脚本：清单可解析率不低于 95%、定额前三命中率不低于 85%、主链路闭环耗时，形成可重复执行的回归基线。
2. 完善重复计费识别：补充反转与近似段落的标注样本，核对误报，并把确认的重复段接入疑点清单。
3. 打通 AI 归因开关与依据引用，补齐用户手册与试用反馈记录，支撑一期试用版交付。
4. 部署配置收口：补 Ingress、替换镜像与数据库地址占位符、统一 PVC 容量口径。
"""

MANUAL_FALLBACK = """本周工作总结
（自动拉取钉钉日报失败或本周无日报，以下为占位内容，请手工补充本周工作。）
一、本周工作
1. 待补充。
"""


def resolve_dws():
    """定位 dws 可执行文件（IDE/GUI 启动的进程 PATH 常缺 ~/.local/bin）。"""
    p = shutil.which("dws")
    if p:
        return p
    for cand in ("~/.local/bin/dws", "/usr/local/bin/dws", "/opt/homebrew/bin/dws"):
        path = os.path.expanduser(cand)
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path
    return None


def run_dws(args):
    """执行 dws 命令，返回 (stdout, returncode)。"""
    exe = resolve_dws()
    if not exe:
        return "", 1
    try:
        proc = subprocess.run([exe] + args, capture_output=True, text=True, timeout=300)
        return proc.stdout, proc.returncode
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return "", 1


def fetch_records():
    """拉取工作日报表全部记录（仅取所需字段）。"""
    use_cache = os.environ.get("NO_CACHE") != "1"
    if use_cache and os.path.isfile(CACHE) and time.time() - os.path.getmtime(CACHE) < CACHE_TTL:
        try:
            with open(CACHE, encoding="utf-8") as f:
                return json.load(f)
        except (json.JSONDecodeError, OSError):
            pass
    out, code = run_dws([
        "aitable", "record", "list",
        "--base-id", BASE_ID,
        "--table-id", TABLE_ID,
        "--all",
        "--field-ids", ",".join([FIELD_DATE, FIELD_CONTENT, FIELD_SUBMITTER, FIELD_STATS]),
        "--format", "json",
    ])
    if code != 0 or not out.strip():
        return None
    text = out[out.find("{"):] if "{" in out else out
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if isinstance(data.get("data"), dict):
        data = data["data"]
    records = data.get("records", [])
    try:
        with open(CACHE, "w", encoding="utf-8") as f:
            json.dump(records, f, ensure_ascii=False)
    except OSError:
        pass
    return records


def week_range():
    """统计区间：默认「本周一 ~ 今天」（本周 5 个工作日）。
    起点可用 WEEK_START=YYYY-MM-DD 覆盖，终点固定为今天。"""
    today = date.today()
    monday = today - timedelta(days=today.weekday())
    env = (os.environ.get("WEEK_START") or "").strip()
    if env:
        try:
            monday = datetime.strptime(env, "%Y-%m-%d").date()
        except ValueError:
            print("⚠ WEEK_START 需为 YYYY-MM-DD，已忽略：%s" % env)
    return monday, today


def filter_week_records(records):
    """过滤：提交人为陈翱 + 日期在统计区间内，按日期升序。"""
    start, today = week_range()
    rows = []
    for r in records:
        cells = r.get("cells", {})
        submitters = cells.get(FIELD_SUBMITTER) or []
        uids = [s.get("userId") for s in submitters if isinstance(s, dict)]
        if SUBMITTER_USER_ID not in uids:
            continue
        raw = (cells.get(FIELD_DATE) or "")[:10]
        try:
            d = datetime.strptime(raw, "%Y-%m-%d").date()
        except ValueError:
            continue
        if start <= d <= today:
            rows.append({"date": d, "content": (cells.get(FIELD_CONTENT) or "").strip()})
    rows.sort(key=lambda x: x["date"])
    return rows


def parse_daily(content):
    """单日日报解析为扁平要点列表 [(块标题, 要点文本), ...]。"""
    items = []
    cur_title = None
    last = None
    for raw_line in content.split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        m = TITLE_RE.match(line)
        if m:
            cur_title = m.group(1)
            last = None
            continue
        mi = ITEM_RE.match(line)
        if mi:
            items.append((cur_title, mi.group(2)))
            last = len(items) - 1
        else:
            # 续行：追加到上一条要点（含「1、xxx，2、xxx」写在同一行的情况）
            if last is not None:
                t, old = items[last]
                items[last] = (t, (old + " " + line).strip())
    return items


def point_topic(block_title, point):
    """单条要点 → 主题名：先按要点文本关键词，回退块标题，再回退其他。"""
    for topic, kws in TOPIC_GROUPS:
        for kw in kws:
            if kw in point:
                return topic
    for topic, kws in TOPIC_GROUPS:
        for kw in kws:
            if block_title and kw in block_title:
                return topic
    return OTHER_TOPIC


def subtopic_of(point):
    for name, kws in SUBTOPIC_GROUPS:
        for kw in kws:
            if kw in point:
                return name
    return SUBTOPIC_OTHER


def build_structure(rows):
    """本周日报按主题归纳，主题内再按子主题聚类。
    返回 [(topic, [(subtopic, [point, ...]), ...]), ...]。"""
    if not rows:
        return None
    order = []
    merged = {}
    for row in rows:
        for block_title, point in parse_daily(row["content"]):
            topic = point_topic(block_title, point)
            if topic not in merged:
                merged[topic] = []
                order.append(topic)
            merged[topic].append(point)
    if OTHER_TOPIC in order and len(order) > 1:
        order.remove(OTHER_TOPIC)
        order.append(OTHER_TOPIC)
    if "其他事务" in order and len(order) > 1:
        order.remove("其他事务")
        order.append("其他事务")

    result = []
    for topic in order:
        pts = merged[topic]
        if not pts:
            continue
        sub_order = []
        sub = {}
        for p in pts:
            st = subtopic_of(p)
            sub.setdefault(st, []).append(p)
            if st not in sub_order:
                sub_order.append(st)
        if SUBTOPIC_OTHER in sub_order and len(sub_order) > 1:
            sub_order.remove(SUBTOPIC_OTHER)
            sub_order.append(SUBTOPIC_OTHER)
        result.append((topic, [(st, sub[st]) for st in sub_order]))
    return result


def render_markdown(struct):
    """Markdown：# 本周工作总结 / ## 一、主题 / ### 1. 子主题 / - 要点。"""
    L = ["# 本周工作总结", ""]
    for i, (topic, subs) in enumerate(struct):
        L.append("## %s、%s" % (CN_NUM[i], topic))
        for j, (st, items) in enumerate(subs, 1):
            L.append("### %d. %s" % (j, st))
            for it in items:
                L.append("- %s" % it)
        L.append("")
    return "\n".join(L).rstrip()


def render_text(struct):
    """纯文本：缩进区分层级，无 markdown 符号，适配表单直接粘贴。"""
    L = ["本周工作总结", ""]
    for i, (topic, subs) in enumerate(struct):
        L.append("%s、%s" % (CN_NUM[i], topic))
        for j, (st, items) in enumerate(subs, 1):
            L.append("  %d. %s" % (j, st))
            for it in items:
                L.append("     - %s" % it)
        L.append("")
    return "\n".join(L).rstrip()


def main():
    start, today = week_range()

    records = fetch_records()
    if records is None:
        print("⚠ 未找到 dws 或执行失败，回退使用手工维护内容。")
        rows = []
    else:
        rows = filter_week_records(records)
        if not rows:
            print("⚠ %s ~ %s 未找到 %s 的日报，回退使用手工维护内容。"
                  % (start.strftime("%m月%d日"), today.strftime("%m月%d日"), SUBMITTER_NAME))
        else:
            print("✓ 从钉钉日报拉取 %s 日报 %d 条（%s ~ %s）"
                  % (SUBMITTER_NAME, len(rows), start.strftime("%m-%d"), today.strftime("%m-%d")))

    struct = build_structure(rows)
    if struct:
        md_body = render_markdown(struct)
        txt_body = render_text(struct)
    else:
        md_body = txt_body = MANUAL_FALLBACK

    next_md = "## 下周计划\n" + "\n".join(NEXT_PLAN.splitlines()[1:])
    md_final = md_body + "\n\n" + next_md
    txt_final = txt_body + "\n\n" + NEXT_PLAN
    with open(OUT_MD, "w", encoding="utf-8") as f:
        f.write(md_final)
    with open(OUT_TXT, "w", encoding="utf-8") as f:
        f.write(txt_final)
    print("已生成：")
    print("  Markdown: %s" % OUT_MD)
    print("  纯文本  : %s" % OUT_TXT)
    print("请核对内容与归类；md 用于钉钉文档等可渲染环境，txt 用于纯文本框。")


if __name__ == "__main__":
    main()
