package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.EsignAdjudication;
import com.evops.coldchain.entity.EsignReceipt;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.EsignAdjudicationMapper;
import com.evops.coldchain.mapper.EsignReceiptMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 电子签收冲突冻结的独立事务网关（V12）。
 *
 * 当场（纸质/在线核对）签收是在 TransportBatchService.sign 的外层事务中比对到
 * 不一致电子回执的；若冻结动作随外层事务一起回滚，“两份不同签收→冻结”的事实就会丢失。
 * 因此把“登记当场回执 + 冻结批次 + 生成裁决单”放在 REQUIRES_NEW 独立事务中提交，
 * 不随外层签收回滚。
 */
@Component
public class EsignConflictGateway {

    @Resource private EsignReceiptMapper receiptMapper;
    @Resource private EsignAdjudicationMapper adjudicationMapper;
    @Resource private TransportBatchMapper batchMapper;
    @Resource private SequenceMapper sequenceMapper;
    @Resource private OpLogService opLogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String registerManualAndFreeze(Long batchId, Long electronicReceiptId, String signBy,
                                          String approvalNo, String packageSpec, Integer confirmQty,
                                          String remark, String driverName) {
        TransportBatch batch = batchMapper.selectById(batchId);
        EsignReceipt electronic = receiptMapper.selectById(electronicReceiptId);
        LocalDateTime now = LocalDateTime.now();

        EsignReceipt manual = new EsignReceipt();
        long seq = sequenceMapper.nextAdjudicationNo();
        manual.setReceiptNo("MANUAL-" + batch.getBatchNo() + "-" + seq);
        manual.setBatchId(batchId);
        manual.setDeviceSerial("MANUAL-COUNTER");
        manual.setDriverName(driverName);
        manual.setSignBy(signBy.trim());
        manual.setReceivedApprovalNo(approvalNo.trim());
        manual.setReceivedPackageSpec(packageSpec.trim());
        manual.setConfirmQty(confirmQty);
        manual.setSignRemark(remark == null || remark.trim().isEmpty() ? null : remark.trim());
        manual.setSignResult(CcConstants.ESIGN_FULL);
        manual.setShortQty(0);
        manual.setSource(CcConstants.ESIGN_SOURCE_ONLINE);
        manual.setDeviceSignTime(now);
        manual.setServerReceiveTime(now);
        manual.setDuplicateCount(0);
        manual.setPayloadHash(DeviceCertService.sha256Hex(
                "MANUAL#" + batch.getBatchNo() + "#" + signBy + "#" + approvalNo + "#" + packageSpec
                        + "#" + confirmQty + "#" + seq));
        manual.setStatus(CcConstants.RECEIPT_UPLOADED);
        receiptMapper.insert(manual);

        EsignAdjudication adj = new EsignAdjudication();
        adj.setAdjudicationNo(String.format("CP%08d", sequenceMapper.nextAdjudicationNo()));
        adj.setBatchId(batchId);
        adj.setFirstReceiptId(electronic.getId());
        adj.setSecondReceiptId(manual.getId());
        adj.setConflictSummary(buildConflictSummary(electronic, manual));
        adj.setStatus(CcConstants.ADJ_PENDING);
        adjudicationMapper.insert(adj);

        electronic.setStatus(CcConstants.RECEIPT_CONFLICT);
        electronic.setAdjudicationId(adj.getId());
        receiptMapper.updateById(electronic);
        manual.setStatus(CcConstants.RECEIPT_CONFLICT);
        manual.setAdjudicationId(adj.getId());
        receiptMapper.updateById(manual);

        batch.setPreFreezeStatus(batch.getStatus());
        batch.setSignFrozen(true);
        batch.setSignFreezeTime(now);
        batch.setSignFreezeReason("当场（纸质/在线核对）签收与在途电子签收不一致：" + adj.getConflictSummary());
        batch.setActiveAdjudicationId(adj.getId());
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_ESIGN_ADJUDICATION, adj.getId(), batchId, CcConstants.ACT_ESIGN_FREEZE,
                String.format("当场签收与在途电子回执 %s 内容不一致：批次 %s 冻结（冻结前状态=%s），生成主管裁决单 %s；两份签收原始内容均已保留，冻结期间不得签收/结算/撤回。差异：%s",
                        electronic.getReceiptNo(), batch.getBatchNo(), batch.getPreFreezeStatus(),
                        adj.getAdjudicationNo(), adj.getConflictSummary()));
        return adj.getAdjudicationNo();
    }

    private String buildConflictSummary(EsignReceipt a, EsignReceipt b) {
        List<String> diffs = new ArrayList<>();
        if (!safeEq(a.getSignBy(), b.getSignBy())) {
            diffs.add(String.format("签收人[%s]≠[%s]", nz(a.getSignBy()), nz(b.getSignBy())));
        }
        if (!safeEq(a.getSignOrg(), b.getSignOrg())) {
            diffs.add(String.format("签收机构[%s]≠[%s]", nz(a.getSignOrg()), nz(b.getSignOrg())));
        }
        if (!safeEq(a.getReceivedApprovalNo(), b.getReceivedApprovalNo())) {
            diffs.add(String.format("批准文号[%s]≠[%s]", nz(a.getReceivedApprovalNo()), nz(b.getReceivedApprovalNo())));
        }
        if (!safeEq(a.getReceivedPackageSpec(), b.getReceivedPackageSpec())) {
            diffs.add(String.format("包装规格[%s]≠[%s]", nz(a.getReceivedPackageSpec()), nz(b.getReceivedPackageSpec())));
        }
        if (!java.util.Objects.equals(a.getConfirmQty(), b.getConfirmQty())) {
            diffs.add(String.format("实收数量[%d]≠[%d]", a.getConfirmQty(), b.getConfirmQty()));
        }
        return diffs.isEmpty() ? "签收内容存在差异" : String.join("；", diffs);
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
