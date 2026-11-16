#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成周报，同时输出两份：
  - scripts/weekly_report.md ：Markdown 格式（# 大标题 / ## 主题），在钉钉文档、网页、Typora 等
    支持 Markdown 的环境里渲染出真实字号层级。
  - scripts/weekly_report.txt ：纯文本（无 markdown 符号），适配钉钉审批/表单纯文本框直接粘贴。

内容来源：
  1. 本周工作总结（自动）：调用钉钉 CLI（dws）拉取多维表「团队日报、周报」中提交人「陈翱」的日报，
     默认统计「上一个周一 ~ 今天」，按功能域归纳去重。
     临时改区间：WEEK_START=2026-11-02 python3 scripts/build_weekly_report.py
     强制重新拉取（忽略缓存）：NO_CACHE=1 python3 scripts/build_weekly_report.py
  2. 下周计划（人工维护）：NEXT_PLAN。

依赖：dws CLI（需已登录；未登录先执行 dws auth login）。
运行：npm run weekly   （= python3 scripts/build_weekly_report.py）
输出：scripts/weekly_report.md 与 scripts/weekly_report.txt
"""

import difflib
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
CACHE_TTL = 1800  # 缓存 30 分钟，避免反复全量拉取 1200+ 条记录

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

# ---- 功能域归纳 ----
# 每条要点单独判定所属功能域（不按日报里的「一、项目功能优化」等宽泛标题整体归类）。
# 打分规则：命中的关键词按长度加权（越长越具体、权重越高），取得分最高的功能域；
# 同分按下方顺序取靠前者。这样「模板列全量提交」不会因为一个「全量」就被判成同步类。
TOPIC_GROUPS = [
    ("造价预审工具", ["造价", "预审", "定额", "取费", "图纸解析", "结算审核", "清单解析"]),
    ("文档与沉淀", ["文档", "说明书", "操作手册", "操作文档", "沉淀"]),
    ("审批与状态", ["审批", "表单", "驳回", "撤销", "会签", "820015", "状态", "权限", "校验",
                    "userId", "unionId", "60121", "手机号"]),
    ("导出与列表", ["导出", "列表", "排序", "列宽", "行高", "字号", "单元格", "居中"]),
    ("需求单与附件", ["需求单", "附件", "上传", "预览", "下载", "fileId", "导入"]),
    ("发货单与拼车", ["发货单", "拼车", "装车", "签收", "载重", "容积", "停靠", "拼车池"]),
    ("运费与同步", ["运费", "分摊", "预估费用", "对账", "增量", "全量", "触发器", "回填"]),
    ("部署与环境", ["部署", "云函数", "容器云", "镜像", "环境变量", "PVC"]),
    ("其他事务", ["培训", "考试", "竞赛", "大赛", "安全检查", "验收", "通报", "PPT", "抽审",
                  "配置", "智能体", "apikey"]),
]
OTHER_TOPIC = "其他"

# 日报里的「块标题」行：一、项目功能优化
TITLE_RE = re.compile(r"^[一二三四五六七八九十]+[、.．]\s*(.*)$")
# 要点行：1. xxx / 1、xxx / - xxx；点后紧跟数字不当编号（避免「13.5%」被切开）
ITEM_RE = re.compile(r"^(\d+[.、．](?!\d)\s*|[-*]\s+)(.*)$")
# 日报里误粘的提示语，清洗时截掉
NOISE_RE = re.compile(r"[，,。;；]?\s*(帮我生成|请生成|生成日报|日报内容|按以上内容).*$")

NEXT_PLAN = """下周计划
1. （按实际情况填写：下周主要开发目标）
2. （按实际情况填写）
"""

# 人工补充条目：归入「其他」域（用于日报没写但要计入周报的事项）
EXTRA_OTHER = []

# 兜底内容：dws 不可用 / 未登录 / 区间内无日报时使用，仍按同一套渲染输出
MANUAL_FALLBACK = [("本周工作", ["（自动拉取钉钉日报失败或区间内无日报，请手工补充本周工作。）"])]


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
        proc = subprocess.run([exe] + args, capture_output=True, text=True, timeout=600)
        return proc.stdout, proc.returncode
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return "", 1


def fetch_records():
    """拉取工作日报表记录（带本地缓存）；失败返回 None。"""
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
        "--field-ids", ",".join([FIELD_DATE, FIELD_CONTENT, FIELD_SUBMITTER]),
        "--format", "json",
    ])
    if code != 0 or not out.strip():
        return None
    # dws 输出可能带 [pagination] 等前缀提示行，从第一个 { 开始截取
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
    """统计区间：默认「上一个周一 ~ 今天」（周报覆盖上一周工作，周一/周日提交都适用）。
    起点可用 WEEK_START=YYYY-MM-DD 覆盖，终点固定为今天。"""
    today = date.today()
    monday = today - timedelta(days=today.weekday())
    start = monday - timedelta(days=7)
    env = (os.environ.get("WEEK_START") or "").strip()
    if env:
        try:
            start = datetime.strptime(env, "%Y-%m-%d").date()
        except ValueError:
            print("⚠ WEEK_START 需为 YYYY-MM-DD，已忽略：%s" % env)
    return start, today


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


def clean_point(text):
    """清理要点：去掉误粘的提示语与多余空白。"""
    t = NOISE_RE.sub("", text).strip()
    return t.rstrip("，,。;； ")


def parse_daily(content):
    """单日日报 → 要点文本列表（标题块仅作上下文，空块不产出条目）。"""
    items = []
    last = None
    for raw_line in content.split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        if TITLE_RE.match(line):
            last = None
            continue
        mi = ITEM_RE.match(line)
        if mi:
            items.append(mi.group(2))
            last = len(items) - 1
        elif last is not None:
            items[last] = (items[last] + " " + line).strip()  # 续行合并
    return [p for p in (clean_point(x) for x in items) if p]


def kw_score(kw):
    """关键词权重：词越长越具体，区分度越高。"""
    n = len(kw)
    return 1 if n <= 2 else (2 if n == 3 else 3)


def topic_of(point):
    """单条要点 → 功能域名。
    优先只看要点首段（冒号/逗号前的概述，最能代表这件事属于哪个功能域），
    首段没有命中任何关键词时再用整句兜底打分；同分取配置顺序靠前者。"""
    head = re.split(r"[：:，,。；;]", point, maxsplit=1)[0]
    for text in (head, point):
        best, best_score = OTHER_TOPIC, 0
        for topic, kws in TOPIC_GROUPS:
            score = sum(kw_score(kw) for kw in kws if kw in text)
            if score > best_score:
                best, best_score = topic, score
        if best_score:
            return best
    return OTHER_TOPIC


def is_duplicate(a, b):
    """跨天重复描述判定（同一件事写了两天）。"""
    if a == b:
        return True
    return difflib.SequenceMatcher(None, a, b).ratio() >= 0.6


def build_structure(rows):
    """全部要点按功能域聚合，跨天近似重复的要点只保留更完整的一条。"""
    if not rows:
        return None
    order, merged = [], {}
    for row in rows:
        for point in parse_daily(row["content"]):
            topic = topic_of(point)
            bucket = merged.setdefault(topic, [])
            dup_idx = next((i for i, old in enumerate(bucket) if is_duplicate(old, point)), None)
            if dup_idx is None:
                bucket.append(point)
            elif len(point) > len(bucket[dup_idx]):
                bucket[dup_idx] = point  # 保留信息更全的写法
            if topic not in order:
                order.append(topic)
    if EXTRA_OTHER:
        merged.setdefault(OTHER_TOPIC, []).extend(EXTRA_OTHER)
        if OTHER_TOPIC not in order:
            order.append(OTHER_TOPIC)
    # 「其他事务」与「其他」固定排最后（业务主线在前）
    for tail_topic in ("其他事务", OTHER_TOPIC):
        if tail_topic in order and len(order) > 1:
            order.remove(tail_topic)
            order.append(tail_topic)
    return [(t, merged[t]) for t in order if merged.get(t)]


def render_markdown(struct):
    """Markdown：# 本周工作总结 / ## 一、功能域 / 1. 要点。"""
    lines = ["# 本周工作总结", ""]
    for i, (topic, points) in enumerate(struct):
        lines.append("## %s、%s" % (CN_NUM[i], topic))
        for j, p in enumerate(points, 1):
            lines.append("%d. %s" % (j, p))
        lines.append("")
    return "\n".join(lines).rstrip()


def render_text(struct):
    """纯文本：无 markdown 符号，用缩进与编号区分层级，可直接粘进表单。"""
    lines = ["本周工作总结", ""]
    for i, (topic, points) in enumerate(struct):
        lines.append("%s、%s" % (CN_NUM[i], topic))
        for j, p in enumerate(points, 1):
            lines.append("  %d. %s" % (j, p))
        lines.append("")
    return "\n".join(lines).rstrip()


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
    if not struct:
        struct = [(topic, list(points)) for topic, points in MANUAL_FALLBACK]

    md_final = render_markdown(struct) + "\n\n## 下周计划\n" + "\n".join(NEXT_PLAN.splitlines()[1:])
    txt_final = render_text(struct) + "\n\n" + NEXT_PLAN
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
