package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 电子签收回执人工修正记录（V12，只追加）。
 *
 * 任何修正都不能覆盖原始回执：本表逐次追加“字段 + 原值快照 + 修正值 + 原因”，
 * 查询时原始值与修正并列展示。关键签收字段（签收人/数量/批准文号/包装规格/
 * 设备签收时间/定位/证据）禁止修正，只能新增回执或走冲突主管裁决。
 */
@TableName("t_cc_esign_correction")
public class EsignCorrection extends CreateOnlyEntity {
    private Long receiptId;
    private Long batchId;
    private String fieldName;
    private String originalValue;
    private String correctedValue;
    private String reason;
    private Long correctedBy;
    private String correctedByName;
    private LocalDateTime correctTime;

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getOriginalValue() { return originalValue; }
    public void setOriginalValue(String originalValue) { this.originalValue = originalValue; }
    public String getCorrectedValue() { return correctedValue; }
    public void setCorrectedValue(String correctedValue) { this.correctedValue = correctedValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCorrectedBy() { return correctedBy; }
    public void setCorrectedBy(Long correctedBy) { this.correctedBy = correctedBy; }
    public String getCorrectedByName() { return correctedByName; }
    public void setCorrectedByName(String correctedByName) { this.correctedByName = correctedByName; }
    public LocalDateTime getCorrectTime() { return correctTime; }
    public void setCorrectTime(LocalDateTime correctTime) { this.correctTime = correctTime; }
}
