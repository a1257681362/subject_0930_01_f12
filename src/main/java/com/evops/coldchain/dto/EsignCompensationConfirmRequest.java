package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 部分成功补偿对应确认（配对核验无误后确认，批次电子签收闭环卡口据此放行） */
public class EsignCompensationConfirmRequest {

    @NotNull(message = "补偿记录ID不能为空")
    private Long compensationId;
    private String confirmNote;

    public Long getCompensationId() { return compensationId; }
    public void setCompensationId(Long compensationId) { this.compensationId = compensationId; }
    public String getConfirmNote() { return confirmNote; }
    public void setConfirmNote(String confirmNote) { this.confirmNote = confirmNote; }
}
