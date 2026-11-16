package com.zjlt.preaudit.module.rule.controller;

import com.zjlt.preaudit.common.api.PageResult;
import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.common.exception.BizException;
import com.zjlt.preaudit.common.util.ExcelUtil;
import com.zjlt.preaudit.module.rule.dto.RuleDtos;
import com.zjlt.preaudit.module.rule.service.RuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Tag(name = "规则库（定额/取费）")
@RestController
@RequestMapping("/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @Operation(summary = "定额子目分页")
    @GetMapping("/quota")
    public R<PageResult<RuleDtos.QuotaVO>> quotaPage(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String engType,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long size) {
        return R.ok(ruleService.quotaPage(keyword, engType, page, size));
    }

    @Operation(summary = "新增定额子目")
    @PostMapping("/quota")
    public R<RuleDtos.QuotaVO> createQuota(@Valid @RequestBody RuleDtos.QuotaReq req) {
        return R.ok(ruleService.saveQuota(req));
    }

    @Operation(summary = "修改定额子目")
    @PutMapping("/quota/{id}")
    public R<RuleDtos.QuotaVO> updateQuota(@PathVariable Long id, @Valid @RequestBody RuleDtos.QuotaReq req) {
        return R.ok(ruleService.updateQuota(id, req));
    }

    @Operation(summary = "删除定额子目")
    @DeleteMapping("/quota/{id}")
    public R<Void> deleteQuota(@PathVariable Long id) {
        ruleService.deleteQuota(id);
        return R.ok();
    }

    @Operation(summary = "导入定额子目 Excel")
    @PostMapping("/quota/import")
    public R<RuleDtos.ImportResult> importQuota(@RequestParam("file") MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return R.ok(ruleService.importQuotas(in, file.getOriginalFilename()));
        } catch (IOException e) {
            throw new BizException("文件读取失败：" + e.getMessage());
        }
    }

    @Operation(summary = "定额子目导入模板")
    @GetMapping("/quota/template")
    public void quotaTemplate(HttpServletResponse response) {
        ExcelUtil.writeToResponse(response, "定额子目导入模板.xlsx", "定额子目",
                ruleService.quotaTemplateHeaders(), ruleService.quotaTemplateRows());
    }

    @Operation(summary = "取费规则分页")
    @GetMapping("/fee")
    public R<PageResult<RuleDtos.FeeVO>> feePage(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String engType,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        return R.ok(ruleService.feePage(keyword, engType, page, size));
    }

    @Operation(summary = "新增取费规则")
    @PostMapping("/fee")
    public R<RuleDtos.FeeVO> createFee(@Valid @RequestBody RuleDtos.FeeReq req) {
        return R.ok(ruleService.saveFee(req));
    }

    @Operation(summary = "修改取费规则")
    @PutMapping("/fee/{id}")
    public R<RuleDtos.FeeVO> updateFee(@PathVariable Long id, @Valid @RequestBody RuleDtos.FeeReq req) {
        return R.ok(ruleService.updateFee(id, req));
    }

    @Operation(summary = "删除取费规则")
    @DeleteMapping("/fee/{id}")
    public R<Void> deleteFee(@PathVariable Long id) {
        ruleService.deleteFee(id);
        return R.ok();
    }

    @Operation(summary = "导入取费规则 Excel")
    @PostMapping("/fee/import")
    public R<RuleDtos.ImportResult> importFee(@RequestParam("file") MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return R.ok(ruleService.importFees(in, file.getOriginalFilename()));
        } catch (IOException e) {
            throw new BizException("文件读取失败：" + e.getMessage());
        }
    }

    @Operation(summary = "取费规则导入模板")
    @GetMapping("/fee/template")
    public void feeTemplate(HttpServletResponse response) {
        ExcelUtil.writeToResponse(response, "取费规则导入模板.xlsx", "取费规则",
                ruleService.feeTemplateHeaders(), ruleService.feeTemplateRows());
    }
}
