package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 签收冲突主管裁决请求。
 * decision：FIRST 采信在先回执 / SECOND 采信在后回执 / INVALID_BOTH 两份均无效。
 */
public class EsignAdjudicationRequest {

    @NotNull(message = "裁决单ID不能为空")
    private Long adjudicationId;
    @NotBlank(message = "裁决结论不能为空")
    private String decision;
    @NotBlank(message = "裁决意见不能为空")
    private String decisionNote;

    public Long getAdjudicationId() { return adjudicationId; }
    public void setAdjudicationId(Long adjudicationId) { this.adjudicationId = adjudicationId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
}
