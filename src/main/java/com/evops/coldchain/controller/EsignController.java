package com.evops.coldchain.controller;

import com.evops.coldchain.dto.EsignAdjudicationRequest;
import com.evops.coldchain.dto.EsignCompensationConfirmRequest;
import com.evops.coldchain.dto.EsignCompensationMatchRequest;
import com.evops.coldchain.dto.EsignCorrectionRequest;
import com.evops.coldchain.dto.EsignMediaAppendRequest;
import com.evops.coldchain.dto.EsignUploadRequest;
import com.evops.coldchain.entity.EsignAdjudication;
import com.evops.coldchain.entity.EsignCompensation;
import com.evops.coldchain.entity.EsignCorrection;
import com.evops.coldchain.service.EsignService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 电子签收（V12）：
 * 离线草稿恢复上传（按回执编号合并） -> 签名/照片/定位证据 -> 部分成功补偿对应 ->
 * 采信闭环；同批两份不同签收自动冻结批次，由主管（ADMIN）裁决；人工修正只追加不覆盖。
 * 设备时间与服务器时间在回执详情/批次台账中并列展示。
 */
@RestController
@RequestMapping("/api/cold-chain/esign")
public class EsignController {

    private final EsignService esignService;

    public EsignController(EsignService esignService) {
        this.esignService = esignService;
    }

    /** 回执上传：在线当场 / 离线草稿恢复（按回执编号幂等合并） */
    @PostMapping("/receipts")
    public ApiResponse<Map<String, Object>> upload(@Valid @RequestBody EsignUploadRequest req) {
        return ApiResponse.ok(esignService.upload(req));
    }

    /** 恢复网络后补传签名/照片/定位（只追加） */
    @PostMapping("/receipts/media")
    public ApiResponse<Map<String, Object>> appendMedia(@Valid @RequestBody EsignMediaAppendRequest req) {
        return ApiResponse.ok(esignService.appendMedia(req));
    }

    /** 采信电子回执闭环（含闭环前同内容并入/不同内容冻结） */
    @PostMapping("/receipts/{receiptNo}/accept")
    public ApiResponse<Map<String, Object>> accept(@PathVariable("receiptNo") String receiptNo) {
        return ApiResponse.ok(esignService.accept(receiptNo));
    }

    /** 回执详情（证据、修正、双钟点、补偿对应） */
    @GetMapping("/receipts/{receiptNo}")
    public ApiResponse<Map<String, Object>> receipt(@PathVariable("receiptNo") String receiptNo) {
        return ApiResponse.ok(esignService.receiptDetail(receiptNo));
    }

    /** 某批次的全部电子回执 */
    @GetMapping("/batches/{batchId}/receipts")
    public ApiResponse<List<Map<String, Object>>> byBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(esignService.listReceiptViews(batchId));
    }

    /** 人工修正非关键字段（只追加，原始回执不覆盖） */
    @PostMapping("/corrections")
    public ApiResponse<EsignCorrection> correct(@Valid @RequestBody EsignCorrectionRequest req) {
        return ApiResponse.ok(esignService.correct(req));
    }

    /** 部分成功：电子签收 ↔ 离线回执 一一对应 */
    @PostMapping("/compensations/match")
    public ApiResponse<EsignCompensation> matchCompensation(@Valid @RequestBody EsignCompensationMatchRequest req) {
        return ApiResponse.ok(esignService.matchCompensation(req));
    }

    /** 补偿对应确认（确认后部分成功批次可电子签收闭环） */
    @PostMapping("/compensations/confirm")
    public ApiResponse<EsignCompensation> confirmCompensation(@Valid @RequestBody EsignCompensationConfirmRequest req) {
        return ApiResponse.ok(esignService.confirmCompensation(req));
    }

    /** 主管裁决（ADMIN） */
    @PostMapping("/adjudications/decide")
    public ApiResponse<EsignAdjudication> adjudicate(@Valid @RequestBody EsignAdjudicationRequest req) {
        return ApiResponse.ok(esignService.adjudicate(req));
    }

    /** 待主管裁决列表 */
    @GetMapping("/adjudications/pending")
    public ApiResponse<List<EsignAdjudication>> pending() {
        return ApiResponse.ok(esignService.listPendingAdjudications());
    }
}
