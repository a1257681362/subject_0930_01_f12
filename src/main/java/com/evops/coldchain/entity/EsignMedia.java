package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 电子签收媒体证据（V12，只追加）：签名图像 / 现场照片 / 定位。
 * 每条并列记录设备采集时间（设备钟）与服务端接收时间（服务器钟）。
 */
@TableName("t_cc_esign_media")
public class EsignMedia extends CreateOnlyEntity {
    private Long receiptId;
    private Long batchId;
    /** SIGNATURE / PHOTO / LOCATION */
    private String mediaType;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String sha256;

    // —— 定位类专属 ——
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal accuracyMeters;
    private String addrText;

    /** 设备采集时刻（设备钟） */
    private LocalDateTime deviceCaptureTime;
    /** 服务端接收时刻（服务器钟） */
    private LocalDateTime serverReceiveTime;

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
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
    public LocalDateTime getServerReceiveTime() { return serverReceiveTime; }
    public void setServerReceiveTime(LocalDateTime serverReceiveTime) { this.serverReceiveTime = serverReceiveTime; }
}
