/**
 * 解析模块：调用 ai-service 完成 CAD 矢量解析与清单 Excel 解析，产物落解析产物库。
 * 边界约束：解析为异步任务（MQ 骨架暂用 @Async 占位），大文件分段处理。
 */
package com.zjlt.preaudit.module.parse;
