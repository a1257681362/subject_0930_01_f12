package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 部分成功补偿对应登记：把“成功部分电子签收回执”与“短少/异常部分离线回执”
 * 按回执编号一一配对。数量平衡与来源合法性由服务端校验。
 */
public class EsignCompensationMatchRequest {

    /** 成功部分电子签收回执编号 */
    @NotBlank(message = "电子签收回执编号不能为空")
    private String esignReceiptNo;
    /** 短少/异常部分离线回执编号（source=OFFLINE） */
    @NotBlank(message = "离线回执编号不能为空")
    private String offlineReceiptNo;
    /** 补偿原因类型：TEMP_EXCURSION/PACKAGE_DAMAGE/REJECTION_PART/OTHER */
    @NotBlank(message = "补偿原因类型不能为空")
    private String reasonType;
    @NotBlank(message = "补偿原因说明不能为空")
    private String reasonDetail;

    public String getEsignReceiptNo() { return esignReceiptNo; }
    public void setEsignReceiptNo(String esignReceiptNo) { this.esignReceiptNo = esignReceiptNo; }
    public String getOfflineReceiptNo() { return offlineReceiptNo; }
    public void setOfflineReceiptNo(String offlineReceiptNo) { this.offlineReceiptNo = offlineReceiptNo; }
    public String getReasonType() { return reasonType; }
    public void setReasonType(String reasonType) { this.reasonType = reasonType; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
}
