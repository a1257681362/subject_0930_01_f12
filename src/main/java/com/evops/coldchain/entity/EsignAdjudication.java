package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 签收冲突主管裁决单（V12）。
 * 同一批次出现两份不同签收时生成；冲突双方与差异点在生成时冻结。
 * 裁决只追加一次：FIRST/SECOND 采信其一，另一份置 SUPERSEDED 原样保留；
 * INVALID_BOTH 两份均无效，批次解冻后可重新签收。
 */
@TableName("t_cc_esign_adjudication")
public class EsignAdjudication extends BaseEntity {
    private String adjudicationNo;
    private Long batchId;
    private Long firstReceiptId;
    private Long secondReceiptId;
    /** 差异点快照（字段 + 双方取值），冻结后不改 */
    private String conflictSummary;
    /** PENDING / RESOLVED */
    private String status;
    /** FIRST / SECOND / INVALID_BOTH */
    private String decision;
    private Long winningReceiptId;
    private String decisionNote;
    private Long supervisorId;
    private String supervisorName;
    private LocalDateTime decideTime;

    public String getAdjudicationNo() { return adjudicationNo; }
    public void setAdjudicationNo(String adjudicationNo) { this.adjudicationNo = adjudicationNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getFirstReceiptId() { return firstReceiptId; }
    public void setFirstReceiptId(Long firstReceiptId) { this.firstReceiptId = firstReceiptId; }
    public Long getSecondReceiptId() { return secondReceiptId; }
    public void setSecondReceiptId(Long secondReceiptId) { this.secondReceiptId = secondReceiptId; }
    public String getConflictSummary() { return conflictSummary; }
    public void setConflictSummary(String conflictSummary) { this.conflictSummary = conflictSummary; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Long getWinningReceiptId() { return winningReceiptId; }
    public void setWinningReceiptId(Long winningReceiptId) { this.winningReceiptId = winningReceiptId; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public Long getSupervisorId() { return supervisorId; }
    public void setSupervisorId(Long supervisorId) { this.supervisorId = supervisorId; }
    public String getSupervisorName() { return supervisorName; }
    public void setSupervisorName(String supervisorName) { this.supervisorName = supervisorName; }
    public LocalDateTime getDecideTime() { return decideTime; }
    public void setDecideTime(LocalDateTime decideTime) { this.decideTime = decideTime; }
}
