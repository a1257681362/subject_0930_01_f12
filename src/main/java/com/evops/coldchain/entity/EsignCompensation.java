package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 部分成功补偿对应（V12）。
 * 一条记录就是“一份成功部分的电子签收回执 ↔ 一份短少/异常部分的离线回执”的一一对应，
 * 双向回填到两张回执；数量必须平衡（signedQty + shortQty = 批次发运数）。
 * MATCHED -> CONFIRMED；确认后参与签收闭环卡口。电子签收与离线补偿可双向反查。
 */
@TableName("t_cc_esign_compensation")
public class EsignCompensation extends BaseEntity {
    private String compensationNo;
    private Long batchId;
    private Long esignReceiptId;
    private String esignReceiptNo;
    private Long offlineReceiptId;
    private String offlineReceiptNo;
    private Integer signedQty;
    private Integer shortQty;
    /** TEMP_EXCURSION / PACKAGE_DAMAGE / REJECTION_PART / OTHER */
    private String reasonType;
    private String reasonDetail;
    /** MATCHED / CONFIRMED */
    private String status;
    private String confirmNote;
    private Long confirmedBy;
    private String confirmedByName;
    private LocalDateTime confirmTime;

    public String getCompensationNo() { return compensationNo; }
    public void setCompensationNo(String compensationNo) { this.compensationNo = compensationNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getEsignReceiptId() { return esignReceiptId; }
    public void setEsignReceiptId(Long esignReceiptId) { this.esignReceiptId = esignReceiptId; }
    public String getEsignReceiptNo() { return esignReceiptNo; }
    public void setEsignReceiptNo(String esignReceiptNo) { this.esignReceiptNo = esignReceiptNo; }
    public Long getOfflineReceiptId() { return offlineReceiptId; }
    public void setOfflineReceiptId(Long offlineReceiptId) { this.offlineReceiptId = offlineReceiptId; }
    public String getOfflineReceiptNo() { return offlineReceiptNo; }
    public void setOfflineReceiptNo(String offlineReceiptNo) { this.offlineReceiptNo = offlineReceiptNo; }
    public Integer getSignedQty() { return signedQty; }
    public void setSignedQty(Integer signedQty) { this.signedQty = signedQty; }
    public Integer getShortQty() { return shortQty; }
    public void setShortQty(Integer shortQty) { this.shortQty = shortQty; }
    public String getReasonType() { return reasonType; }
    public void setReasonType(String reasonType) { this.reasonType = reasonType; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getConfirmNote() { return confirmNote; }
    public void setConfirmNote(String confirmNote) { this.confirmNote = confirmNote; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long confirmedBy) { this.confirmedBy = confirmedBy; }
    public String getConfirmedByName() { return confirmedByName; }
    public void setConfirmedByName(String confirmedByName) { this.confirmedByName = confirmedByName; }
    public LocalDateTime getConfirmTime() { return confirmTime; }
    public void setConfirmTime(LocalDateTime confirmTime) { this.confirmTime = confirmTime; }
}
