package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 电子签收回执（V12）。
 *
 * 不可变口径：签收人/机构、实物批准文号/包装规格、实收数量、设备签收时间、设备标识等
 * “签收内容列”一旦插入不再 UPDATE；状态/裁决/补偿/闭环锚点列允许沿状态机推进。
 * 双钟点：deviceSignTime（设备钟，现场签收时刻）与 serverReceiveTime/acceptedTime
 * （服务器钟）并列保存，互不覆盖。
 */
@TableName("t_cc_esign_receipt")
public class EsignReceipt extends BaseEntity {
    /** 回执编号：设备离线前生成的稳定编号，服务端按此合并/幂等，库内唯一 */
    private String receiptNo;
    private Long batchId;

    // —— 设备与签收主体快照（内容列，不可变）——
    private String deviceSerial;
    private String driverName;
    private String signBy;
    private String signOrg;

    // —— 实物核对结果（与批次快照核对；内容列，不可变）——
    private String receivedApprovalNo;
    private String receivedPackageSpec;
    private Integer confirmQty;
    private String signRemark;

    // —— 部分成功 ——
    /** FULL 全部成功 / PARTIAL 部分成功 */
    private String signResult;
    /** 短少/未成功数量（PARTIAL 时 > 0） */
    private Integer shortQty;
    private String shortReason;
    /** 短少原因类型：TEMP_EXCURSION/PACKAGE_DAMAGE/REJECTION_PART/OTHER（离线 PARTIAL 必填） */
    private String shortReasonType;

    /** ONLINE 在线当场 / OFFLINE 离线草稿恢复上传 */
    private String source;

    // —— 双钟点 ——
    /** 设备记录的签收时刻（设备钟，只写一次） */
    private LocalDateTime deviceSignTime;
    /** 设备发起上传时刻（设备钟） */
    private LocalDateTime deviceUploadTime;
    /** 服务端收到回执时刻（服务器钟） */
    private LocalDateTime serverReceiveTime;
    /** 采信闭环时刻（服务器钟） */
    private LocalDateTime acceptedTime;
    private Long acceptedBy;

    /** 同编号/同内容重复上传并入次数 */
    private Integer duplicateCount;
    /** 回执正文摘要：同编号不同内容拒绝的依据 */
    private String payloadHash;

    /** UPLOADED/ACCEPTED/MERGED/CONFLICT/SUPERSEDED/AFTER_CLOSED */
    private String status;

    // —— 裁决/补偿锚点（状态推进列）——
    private Long adjudicationId;
    private Long compensationId;
    /** 补偿配对的另一方回执（电子↔离线双向回填） */
    private Long pairedReceiptId;

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getDeviceSerial() { return deviceSerial; }
    public void setDeviceSerial(String deviceSerial) { this.deviceSerial = deviceSerial; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getSignBy() { return signBy; }
    public void setSignBy(String signBy) { this.signBy = signBy; }
    public String getSignOrg() { return signOrg; }
    public void setSignOrg(String signOrg) { this.signOrg = signOrg; }
    public String getReceivedApprovalNo() { return receivedApprovalNo; }
    public void setReceivedApprovalNo(String receivedApprovalNo) { this.receivedApprovalNo = receivedApprovalNo; }
    public String getReceivedPackageSpec() { return receivedPackageSpec; }
    public void setReceivedPackageSpec(String receivedPackageSpec) { this.receivedPackageSpec = receivedPackageSpec; }
    public Integer getConfirmQty() { return confirmQty; }
    public void setConfirmQty(Integer confirmQty) { this.confirmQty = confirmQty; }
    public String getSignRemark() { return signRemark; }
    public void setSignRemark(String signRemark) { this.signRemark = signRemark; }
    public String getSignResult() { return signResult; }
    public void setSignResult(String signResult) { this.signResult = signResult; }
    public Integer getShortQty() { return shortQty; }
    public void setShortQty(Integer shortQty) { this.shortQty = shortQty; }
    public String getShortReason() { return shortReason; }
    public void setShortReason(String shortReason) { this.shortReason = shortReason; }
    public String getShortReasonType() { return shortReasonType; }
    public void setShortReasonType(String shortReasonType) { this.shortReasonType = shortReasonType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getDeviceSignTime() { return deviceSignTime; }
    public void setDeviceSignTime(LocalDateTime deviceSignTime) { this.deviceSignTime = deviceSignTime; }
    public LocalDateTime getDeviceUploadTime() { return deviceUploadTime; }
    public void setDeviceUploadTime(LocalDateTime deviceUploadTime) { this.deviceUploadTime = deviceUploadTime; }
    public LocalDateTime getServerReceiveTime() { return serverReceiveTime; }
    public void setServerReceiveTime(LocalDateTime serverReceiveTime) { this.serverReceiveTime = serverReceiveTime; }
    public LocalDateTime getAcceptedTime() { return acceptedTime; }
    public void setAcceptedTime(LocalDateTime acceptedTime) { this.acceptedTime = acceptedTime; }
    public Long getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(Long acceptedBy) { this.acceptedBy = acceptedBy; }
    public Integer getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(Integer duplicateCount) { this.duplicateCount = duplicateCount; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAdjudicationId() { return adjudicationId; }
    public void setAdjudicationId(Long adjudicationId) { this.adjudicationId = adjudicationId; }
    public Long getCompensationId() { return compensationId; }
    public void setCompensationId(Long compensationId) { this.compensationId = compensationId; }
    public Long getPairedReceiptId() { return pairedReceiptId; }
    public void setPairedReceiptId(Long pairedReceiptId) { this.pairedReceiptId = pairedReceiptId; }
}
