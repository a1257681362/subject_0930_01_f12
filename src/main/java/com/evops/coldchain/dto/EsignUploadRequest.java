package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 电子签收回执上传请求（V12）。
 *
 * 同一入口承载两种来源：
 * - ONLINE：现场有网，当场上传；
 * - OFFLINE：司机离线时把签收草稿（签名/照片/定位/设备时间）保存在设备，
 *   恢复网络后用离线前生成的同一 receiptNo 上传；服务端按 receiptNo 合并/幂等。
 *
 * 双钟点：deviceSignTime 必填（设备现场签收时刻，离线期间照记）；
 * deviceUploadTime 为设备发起上传时刻。服务端另记 serverReceiveTime，三者并列。
 */
public class EsignUploadRequest {

    /** 回执编号：设备离线前生成的稳定编号（建议 UUID），服务端按此合并，库内唯一 */
    @NotBlank(message = "回执编号不能为空")
    private String receiptNo;
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;

    @NotBlank(message = "设备标识不能为空")
    private String deviceSerial;
    private String driverName;

    @NotBlank(message = "签收人不能为空")
    private String signBy;
    private String signOrg;

    @NotBlank(message = "实物批准文号不能为空")
    private String receivedApprovalNo;
    @NotBlank(message = "实物包装规格不能为空")
    private String receivedPackageSpec;
    /** 实收（成功签收）数量，允许 0（整批短少/拒收时成功数为 0） */
    @NotNull(message = "实收数量不能为空")
    @PositiveOrZero(message = "实收数量不能为负")
    private Integer confirmQty;
    private String signRemark;

    /** FULL 全部成功 / PARTIAL 部分成功；不传默认 FULL */
    private String signResult;
    /** 短少/未成功数量（PARTIAL 时必须 > 0 且需离线回执补偿对应） */
    @PositiveOrZero(message = "短少数量不能为负")
    private Integer shortQty;
    private String shortReason;
    /** TEMP_EXCURSION/PACKAGE_DAMAGE/REJECTION_PART/OTHER（PARTIAL 必填） */
    private String shortReasonType;

    /** ONLINE / OFFLINE；不传默认 ONLINE */
    private String source;

    /** 设备记录的签收时刻（设备钟，只以此为准，服务端不改写） */
    @NotNull(message = "设备签收时间不能为空")
    private LocalDateTime deviceSignTime;
    /** 设备发起上传时刻（设备钟，可空） */
    private LocalDateTime deviceUploadTime;

    /** 随回执一并上传的媒体证据；闭环前签名/照片/定位三类必须集齐（可恢复网络后补传） */
    @Valid
    private List<MediaItem> media;

    /** 签名/照片/定位媒体项 */
    public static class MediaItem {
        /** SIGNATURE / PHOTO / LOCATION */
        @NotBlank(message = "媒体类型不能为空")
        private String mediaType;
        private String fileName;
        private String fileUrl;
        private Long fileSize;
        private String sha256;

        // —— LOCATION 定位类专属 ——
        private BigDecimal latitude;
        private BigDecimal longitude;
        private BigDecimal accuracyMeters;
        private String addrText;
        /** 设备采集时刻（设备钟） */
        @NotNull(message = "媒体设备采集时间不能为空")
        private LocalDateTime deviceCaptureTime;

        public String getMediaType() { return mediaType; }
        public void setMediaType(String mediaType) { this.mediaType = mediaType; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFileUrl() { return fileUrl; }
        public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
        public Long getFileSize() { return fileSize; }
        public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }
        public BigDecimal getLatitude() { return latitude; }
        public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
        public BigDecimal getLongitude() { return longitude; }
        public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
        public BigDecimal getAccuracyMeters() { return accuracyMeters; }
        public void setAccuracyMeters(BigDecimal accuracyMeters) { this.accuracyMeters = accuracyMeters; }
        public String getAddrText() { return addrText; }
        public void setAddrText(String addrText) { this.addrText = addrText; }
        public LocalDateTime getDeviceCaptureTime() { return deviceCaptureTime; }
        public void setDeviceCaptureTime(LocalDateTime deviceCaptureTime) { this.deviceCaptureTime = deviceCaptureTime; }
    }

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
    public List<MediaItem> getMedia() { return media; }
    public void setMedia(List<MediaItem> media) { this.media = media; }
}
