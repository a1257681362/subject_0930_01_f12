package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 恢复网络后为既有回执补传媒体证据（签名/照片/定位）。
 * 按回执编号定位回执；媒体只追加，不覆盖已上传的任何一条。
 */
public class EsignMediaAppendRequest {

    @NotBlank(message = "回执编号不能为空")
    private String receiptNo;

    @Valid
    @NotEmpty(message = "至少补传一条媒体证据")
    private List<EsignUploadRequest.MediaItem> media;

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public List<EsignUploadRequest.MediaItem> getMedia() { return media; }
    public void setMedia(List<EsignUploadRequest.MediaItem> media) { this.media = media; }
}
