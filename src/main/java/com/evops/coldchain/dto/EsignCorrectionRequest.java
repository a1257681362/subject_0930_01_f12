package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 电子签收回执人工修正请求。
 * 修正只追加修正记录，绝不覆盖原始回执；关键签收字段禁止修正。
 */
public class EsignCorrectionRequest {

    @NotBlank(message = "回执编号不能为空")
    private String receiptNo;
    /** 被修正字段（仅白名单非关键字段，如 signOrg/signRemark/addrText） */
    @NotBlank(message = "修正字段不能为空")
    private String fieldName;
    @NotBlank(message = "修正值不能为空")
    private String correctedValue;
    @NotBlank(message = "修正原因不能为空")
    private String reason;

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getCorrectedValue() { return correctedValue; }
    public void setCorrectedValue(String correctedValue) { this.correctedValue = correctedValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
