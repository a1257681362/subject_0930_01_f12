package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.EsignAdjudicationRequest;
import com.evops.coldchain.dto.EsignCompensationConfirmRequest;
import com.evops.coldchain.dto.EsignCompensationMatchRequest;
import com.evops.coldchain.dto.EsignCorrectionRequest;
import com.evops.coldchain.dto.EsignMediaAppendRequest;
import com.evops.coldchain.dto.EsignUploadRequest;
import com.evops.coldchain.entity.EsignAdjudication;
import com.evops.coldchain.entity.EsignCompensation;
import com.evops.coldchain.entity.EsignCorrection;
import com.evops.coldchain.entity.EsignMedia;
import com.evops.coldchain.entity.EsignReceipt;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.EsignAdjudicationMapper;
import com.evops.coldchain.mapper.EsignCompensationMapper;
import com.evops.coldchain.mapper.EsignCorrectionMapper;
import com.evops.coldchain.mapper.EsignMediaMapper;
import com.evops.coldchain.mapper.EsignReceiptMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电子签收服务（V12）。
 *
 * 业务规则：
 * - 司机离线时签收草稿保存在设备，恢复网络后用离线前生成的同一回执编号上传
 *   （签名/照片/定位/设备时间）；服务端按回执编号合并：同编号同内容重复上传只并入
 *   证据、累加重复次数；同编号不同内容一律拒绝（不得借合并覆盖或制造第二份事实）。
 * - 不同编号但签收内容一致：后者置 MERGED 指向正本，不形成第二份签收；
 *   不同编号且签收内容（签收人/机构/批准文号/包装规格/实收数量）不同：两份回执置
 *   CONFLICT、批次置 SIGN_FROZEN（保存冻结前状态），生成主管裁决单。
 * - 双钟点：deviceSignTime（设备钟，现场签收时刻）与 serverReceiveTime/acceptedTime
 *   （服务器钟）各自独立落库并列展示，任何修正都不改写设备时间。
 * - 主管（ADMIN）裁决：采信其一（满足闭环条件即闭环，否则解冻待补齐）或两份均无效；
 *   未采信回执置 SUPERSEDED 原样保留。
 * - 原始回执不可变：人工修正只能在修正表追加“字段/原值快照/修正值/原因”，
 *   关键签收字段禁止修正。
 * - 部分成功：成功部分电子签收与短少部分离线回执通过补偿记录一一对应、双向回填、
 *   数量平衡（签收数+短少数=发运数），补偿确认后电子签收方可闭环。
 *
 * 本服务只依赖各 Mapper 与横向服务，不依赖 TransportBatchService，保持单向依赖。
 */
@Service
public class EsignService {

    /** 允许人工修正的非关键字段白名单（关键签收事实只能新增回执/走裁决，不能改） */
    private static final List<String> CORRECTABLE_FIELDS =
            Collections.unmodifiableList(Arrays.asList("signOrg", "signRemark", "driverName"));
    private static final List<String> COMP_REASON_TYPES = Collections.unmodifiableList(Arrays.asList(
            CcConstants.COMP_REASON_TEMP, CcConstants.COMP_REASON_DAMAGE,
            CcConstants.COMP_REASON_REJECTION_PART, CcConstants.COMP_REASON_OTHER));

    @Resource private EsignReceiptMapper receiptMapper;
    @Resource private EsignMediaMapper mediaMapper;
    @Resource private EsignAdjudicationMapper adjudicationMapper;
    @Resource private EsignCorrectionMapper correctionMapper;
    @Resource private EsignCompensationMapper compensationMapper;
    @Resource private TransportBatchMapper batchMapper;
    @Resource private ShipmentOrderMapper orderMapper;
    @Resource private SequenceMapper sequenceMapper;
    @Resource private PermissionService permissionService;
    @Resource private HandoverService handoverService;
    @Resource private ExceptionLifecycleService exceptionLifecycleService;
    @Resource private BatchRescheduleService batchRescheduleService;
    @Resource private AlertEvaluationService alertEvaluationService;
    @Resource private OpLogService opLogService;
    @Resource private EsignConflictGateway conflictGateway;

    // ====================================================================
    // 回执上传（在线当场 / 离线草稿恢复）+ 按回执编号合并
    // ====================================================================

    @Transactional
    public Map<String, Object> upload(EsignUploadRequest req) {
        TransportBatch batch = requireUploadableBatch(req.getBatchId());
        String hash = payloadHash(req);

        // 1) 同回执编号：幂等合并（同内容）/ 拒绝（不同内容）
        EsignReceipt existing = receiptMapper.selectOne(new QueryWrapper<EsignReceipt>()
                .eq("receipt_no", req.getReceiptNo().trim()));
        if (existing != null) {
            return mergeExisting(existing, req, hash, batch);
        }

        // 2) 组装新回执
        String source = normalizeSource(req.getSource());
        String result = normalizeResult(req.getSignResult());
        validatePartial(result, req);
        LocalDateTime now = LocalDateTime.now();

        EsignReceipt r = new EsignReceipt();
        r.setReceiptNo(req.getReceiptNo().trim());
        r.setBatchId(batch.getId());
        r.setDeviceSerial(req.getDeviceSerial().trim());
        r.setDriverName(trimToNull(req.getDriverName()));
        r.setSignBy(req.getSignBy().trim());
        r.setSignOrg(trimToNull(req.getSignOrg()));
        r.setReceivedApprovalNo(req.getReceivedApprovalNo().trim());
        r.setReceivedPackageSpec(req.getReceivedPackageSpec().trim());
        r.setConfirmQty(req.getConfirmQty());
        r.setSignRemark(trimToNull(req.getSignRemark()));
        r.setSignResult(result);
        r.setShortQty(CcConstants.ESIGN_PARTIAL.equals(result) ? req.getShortQty() : 0);
        r.setShortReason(CcConstants.ESIGN_PARTIAL.equals(result) ? trimToNull(req.getShortReason()) : null);
        r.setShortReasonType(CcConstants.ESIGN_PARTIAL.equals(result)
                ? normalizeReasonType(req.getShortReasonType()) : null);
        r.setSource(source);
        r.setDeviceSignTime(req.getDeviceSignTime());
        r.setDeviceUploadTime(req.getDeviceUploadTime());
        r.setServerReceiveTime(now);
        r.setDuplicateCount(0);
        r.setPayloadHash(hash);

        // 3) 闭环后到达：忠实落库保留但不采信，不参与任何合并/闭环
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            r.setStatus(CcConstants.RECEIPT_AFTER_CLOSED);
            receiptMapper.insert(r);
            appendMediaInternal(r, req.getMedia());
            opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, r.getId(), batch.getId(), CcConstants.ACT_ESIGN_UPLOAD,
                    String.format("批次 %s 已闭环后到达电子回执 %s（%s，设备签收时间=%s，服务器接收=%s）：原样保留、不予采信、不覆盖既有闭环",
                            batch.getBatchNo(), r.getReceiptNo(), sourceDesc(source),
                            r.getDeviceSignTime(), now));
            return receiptView(r, batch, false, true, null, 0);
        }

        r.setStatus(CcConstants.RECEIPT_UPLOADED);
        receiptMapper.insert(r);
        int mediaAdded = appendMediaInternal(r, req.getMedia());

        if (CcConstants.ESIGN_SOURCE_OFFLINE.equals(source)) {
            // 离线草稿：现场签收事实发生在设备钟，恢复网络时补登记草稿事实
            opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, r.getId(), batch.getId(), CcConstants.ACT_ESIGN_DRAFT,
                    String.format("批次 %s 离线签收草稿补登记：回执 %s，签收人=%s，设备 %s 于设备时间 %s 在离线状态下完成签收",
                            batch.getBatchNo(), r.getReceiptNo(), r.getSignBy(), r.getDeviceSerial(), r.getDeviceSignTime()));
        }
        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, r.getId(), batch.getId(), CcConstants.ACT_ESIGN_UPLOAD,
                String.format("批次 %s 接收电子回执 %s（来源=%s，签收人=%s，实收=%d%s，设备签收时间=%s，服务器接收时间=%s，证据新增=%d 条）",
                        batch.getBatchNo(), r.getReceiptNo(), sourceDesc(source), r.getSignBy(),
                        r.getConfirmQty(), partialDesc(r), r.getDeviceSignTime(), now, mediaAdded));

        // 4) 冻结期间到达：现场事实保留，但不新增裁决、不闭环
        if (Boolean.TRUE.equals(batch.getSignFrozen())) {
            return receiptView(r, batch, true, false, null, mediaAdded);
        }

        // 5) 与同批在途回执比对：同内容并入 / 不同内容冻结
        Long adjudicationId = detectDuplicateOrConflict(batch, r);
        return receiptView(r, batch, Boolean.TRUE.equals(batch.getSignFrozen()), false, adjudicationId, mediaAdded);
    }

    /** 同回执编号重复上传：内容一致并入证据，内容不一致拒绝 */
    private Map<String, Object> mergeExisting(EsignReceipt existing, EsignUploadRequest req,
                                              String hash, TransportBatch batch) {
        if (!hash.equals(existing.getPayloadHash())) {
            throw new BusinessException(String.format(
                    "回执编号 %s 已存在一份内容不同的签收（签收时间/数量/签收人等不一致）："
                    + "同编号合并只能并入同一份签收的证据，不能借重复上传覆盖原始回执或产生第二份事实；"
                    + "如确为另一次签收，请使用新回执编号", existing.getReceiptNo()));
        }
        int added = appendMediaInternal(existing, req.getMedia());
        existing.setDuplicateCount(existing.getDuplicateCount() == null ? 1 : existing.getDuplicateCount() + 1);
        receiptMapper.updateById(existing);
        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, existing.getId(), batch.getId(), CcConstants.ACT_ESIGN_MERGE,
                String.format("回执 %s 同编号同内容第 %d 次重复上传：仅并入新增证据 %d 条，原始签收内容不变（状态=%s）",
                        existing.getReceiptNo(), existing.getDuplicateCount() + 1, added, existing.getStatus()));
        return receiptView(existing, batch, Boolean.TRUE.equals(batch.getSignFrozen()),
                CcConstants.RECEIPT_AFTER_CLOSED.equals(existing.getStatus()), null, added);
    }

    /**
     * 新回执与同批在途（UPLOADED）回执比对：
     * 内容一致 -> 新回执 MERGED 指向正本；内容不同 -> 冻结批次并生成裁决单。返回裁决单ID（无则 null）。
     */
    private Long detectDuplicateOrConflict(TransportBatch batch, EsignReceipt incoming) {
        // 只有“主张成功收货”的回执才互为竞争签收：短少方离线回执（confirmQty=0）与
        // 已建立补偿对应的 PARTIAL 回执是互补关系，不构成第二份签收。
        if (!isCompetingClaim(incoming)) {
            return null;
        }
        List<EsignReceipt> pending = receiptMapper.selectList(new QueryWrapper<EsignReceipt>()
                .eq("batch_id", batch.getId())
                .eq("status", CcConstants.RECEIPT_UPLOADED)
                .orderByAsc("id"));
        for (EsignReceipt other : pending) {
            if (other.getId().equals(incoming.getId()) || !isCompetingClaim(other)) {
                continue;
            }
            if (contentKey(other).equals(contentKey(incoming))) {
                incoming.setStatus(CcConstants.RECEIPT_MERGED);
                incoming.setPairedReceiptId(other.getId());
                receiptMapper.updateById(incoming);
                opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, incoming.getId(), batch.getId(), CcConstants.ACT_ESIGN_MERGE,
                        String.format("回执 %s 与在先回执 %s 签收内容完全一致（签收人/文号/包装/数量），作为重复采集并入正本，不形成第二份签收",
                                incoming.getReceiptNo(), other.getReceiptNo()));
                return null;
            }
            return freezeBatch(batch, other, incoming, "上传比对发现同一批次两份不同签收");
        }
        return null;
    }

    // ====================================================================
    // 媒体证据补传（只追加、去重；设备采集时间与服务器接收时间并列）
    // ====================================================================

    @Transactional
    public Map<String, Object> appendMedia(EsignMediaAppendRequest req) {
        EsignReceipt receipt = requireReceipt(req.getReceiptNo());
        int added = appendMediaInternal(receipt, req.getMedia());
        opLogService.log(CcConstants.ENT_ESIGN_MEDIA, receipt.getId(), receipt.getBatchId(), CcConstants.ACT_ESIGN_MEDIA,
                String.format("回执 %s 补传证据 %d 条（只追加、去重，不覆盖既有证据）", receipt.getReceiptNo(), added));
        TransportBatch batch = batchMapper.selectById(receipt.getBatchId());
        boolean frozen = batch != null && Boolean.TRUE.equals(batch.getSignFrozen());
        return receiptView(receipt, batch, frozen, false, null, added);
    }

    /** 插入媒体（只追加）；按 类型+摘要 去重；返回新增条数 */
    private int appendMediaInternal(EsignReceipt receipt, List<EsignUploadRequest.MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int added = 0;
        for (EsignUploadRequest.MediaItem item : items) {
            String type = normalizeMediaType(item.getMediaType());
            String fingerprint = mediaFingerprint(type, item);
            if (CcConstants.MEDIA_LOCATION.equals(type)
                    && (item.getLatitude() == null || item.getLongitude() == null)) {
                throw new BusinessException("定位证据必须包含经纬度");
            }
            Long dup = mediaMapper.selectCount(new QueryWrapper<EsignMedia>()
                    .eq("receipt_id", receipt.getId())
                    .eq("media_type", type)
                    .eq("sha256", fingerprint));
            if (dup != null && dup > 0) {
                continue;
            }
            EsignMedia m = new EsignMedia();
            m.setReceiptId(receipt.getId());
            m.setBatchId(receipt.getBatchId());
            m.setMediaType(type);
            m.setFileName(trimToNull(item.getFileName()));
            m.setFileUrl(trimToNull(item.getFileUrl()));
            m.setFileSize(item.getFileSize());
            m.setSha256(fingerprint);
            m.setLatitude(item.getLatitude());
            m.setLongitude(item.getLongitude());
            m.setAccuracyMeters(item.getAccuracyMeters());
            m.setAddrText(trimToNull(item.getAddrText()));
            m.setDeviceCaptureTime(item.getDeviceCaptureTime());
            m.setServerReceiveTime(now);
            mediaMapper.insert(m);
            added++;
        }
        return added;
    }

    // ====================================================================
    // 采信闭环（纯电子路径）
    // ====================================================================

    @Transactional
    public Map<String, Object> accept(String receiptNo) {
        EsignReceipt receipt = requireReceipt(receiptNo);
        TransportBatch batch = requireBatch(receipt.getBatchId());
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能电子签收闭环: " + batch.getBatchNo());
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("批次已签收闭环，不能重复闭环");
        }
        if (Boolean.TRUE.equals(batch.getSignFrozen())) {
            throw new BusinessException(String.format(
                    "批次 %s 因同一批次出现两份不同签收已冻结（裁决单#%d），须主管裁决后才能电子签收闭环",
                    batch.getBatchNo(), batch.getActiveAdjudicationId()));
        }
        if (!CcConstants.RECEIPT_UPLOADED.equals(receipt.getStatus())) {
            throw new BusinessException("仅待闭环（UPLOADED）回执可采信闭环，当前状态: " + receipt.getStatus()
                    + "（重复采集并入的 MERGED 回执请以正本闭环）");
        }

        // 闭环前再次比对：冻结期后/双废后可能已累积多张在途回执（只比对主张成功收货的竞争回执）
        List<EsignReceipt> pending = receiptMapper.selectList(new QueryWrapper<EsignReceipt>()
                .eq("batch_id", batch.getId())
                .eq("status", CcConstants.RECEIPT_UPLOADED)
                .orderByAsc("id"));
        for (EsignReceipt other : pending) {
            if (other.getId().equals(receipt.getId()) || !isCompetingClaim(other)) {
                continue;
            }
            if (contentKey(other).equals(contentKey(receipt))) {
                // 同内容：以本次采信的为正本，其余并入
                other.setStatus(CcConstants.RECEIPT_MERGED);
                other.setPairedReceiptId(receipt.getId());
                receiptMapper.updateById(other);
                opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, other.getId(), batch.getId(), CcConstants.ACT_ESIGN_MERGE,
                        String.format("闭环前确认回执 %s 与被采信回执 %s 内容一致，并入正本",
                                other.getReceiptNo(), receipt.getReceiptNo()));
            } else {
                long adjId = freezeBatch(batch, other, receipt, "电子签收闭环前比对发现两份不同签收");
                throw new BusinessException(String.format(
                        "回执 %s 与在途回执 %s 签收内容不一致，批次 %s 已冻结，生成主管裁决单#%d，闭环中止（两回执原始内容均已保留）",
                        receipt.getReceiptNo(), other.getReceiptNo(), batch.getBatchNo(), adjId));
            }
        }

        runAcceptGates(batch, receipt);
        doAccept(batch, receipt, LocalDateTime.now());
        return receiptView(receipt, batch, false, false, null, 0);
    }

    /** 闭环卡口：快照核对/数量平衡/补偿确认/三类证据/未关闭异常/交接/改期/权限开关 */
    private void runAcceptGates(TransportBatch batch, EsignReceipt r) {
        permissionService.requirePermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER);
        batchRescheduleService.requireNoPending(batch.getId());
        handoverService.requireReadyForDispatchIfStarted(batch.getId());

        if (!batch.getApprovalNoSnap().equals(r.getReceivedApprovalNo())
                || !batch.getPackageSpecSnap().equals(r.getReceivedPackageSpec())) {
            throw new BusinessException(String.format(
                    "电子签收核对不符：实物（批准文号=%s，包装规格=%s）与批次快照（%s，%s）不一致，不能闭环；"
                    + "如存在另一份不同签收，请由主管裁决",
                    r.getReceivedApprovalNo(), r.getReceivedPackageSpec(),
                    batch.getApprovalNoSnap(), batch.getPackageSpecSnap()));
        }

        if (CcConstants.ESIGN_FULL.equals(r.getSignResult())) {
            if (r.getShortQty() != null && r.getShortQty() > 0) {
                throw new BusinessException("签收结果为全部成功（FULL）时不能登记短少数量");
            }
            if (!batch.getShipQty().equals(r.getConfirmQty())) {
                throw new BusinessException(String.format(
                        "全部成功签收数量 %d 与发运数量 %d 不一致；部分成功请按 PARTIAL 登记并与离线回执做补偿对应",
                        r.getConfirmQty(), batch.getShipQty()));
            }
        } else {
            EsignCompensation comp = compensationMapper.selectOne(new QueryWrapper<EsignCompensation>()
                    .eq("esign_receipt_id", r.getId()));
            if (comp == null || !CcConstants.COMP_CONFIRMED.equals(comp.getStatus())) {
                throw new BusinessException("部分成功电子签收必须先与短少部分的离线回执建立补偿对应并确认，才能闭环");
            }
            EsignReceipt offline = receiptMapper.selectById(comp.getOfflineReceiptId());
            if (offline == null || !CcConstants.ESIGN_SOURCE_OFFLINE.equals(offline.getSource())) {
                throw new BusinessException("补偿记录对应的短少方必须是来源 OFFLINE 的离线回执");
            }
            if (!comp.getSignedQty().equals(r.getConfirmQty())
                    || comp.getSignedQty() + comp.getShortQty() != batch.getShipQty()) {
                throw new BusinessException(String.format(
                        "补偿数量不平衡：电子签收 %d + 离线短少 %d 必须等于发运数量 %d",
                        comp.getSignedQty(), comp.getShortQty(), batch.getShipQty()));
            }
        }

        if (!hasMediaType(r.getId(), CcConstants.MEDIA_SIGNATURE)
                || !hasMediaType(r.getId(), CcConstants.MEDIA_PHOTO)
                || !hasMediaType(r.getId(), CcConstants.MEDIA_LOCATION)) {
            throw new BusinessException("闭环前必须集齐三类证据：签名图像、现场照片、定位（可恢复网络后补传）");
        }

        long open = exceptionLifecycleService.unclosedCount(batch.getId());
        if (open > 0) {
            throw new BusinessException("存在 " + open + " 条未关闭异常，电子签收不能闭环；请完成处置并经复核关闭");
        }
    }

    /** 执行电子签收闭环（回执/批次/订单/计时段 + 双钟点留痕） */
    private void doAccept(TransportBatch batch, EsignReceipt r, LocalDateTime serverNow) {
        r.setStatus(CcConstants.RECEIPT_ACCEPTED);
        r.setAcceptedTime(serverNow);
        r.setAcceptedBy(UserContext.currentId());
        receiptMapper.updateById(r);

        batch.setStatus(CcConstants.BATCH_SIGNED);
        batch.setSignBy(r.getSignBy());
        batch.setSignRemark(r.getSignRemark());
        batch.setSignTime(serverNow);
        batch.setEsignReceiptId(r.getId());
        batch.setEsignDeviceSignTime(r.getDeviceSignTime());
        batch.setEsignServerSignTime(serverNow);
        batchMapper.updateById(batch);

        // 部分成功：承载短少/异常的离线回执随补偿一并采信（电子签收↔离线回执补偿闭合）
        if (r.getCompensationId() != null) {
            EsignCompensation comp = compensationMapper.selectById(r.getCompensationId());
            if (comp != null && CcConstants.COMP_CONFIRMED.equals(comp.getStatus())) {
                EsignReceipt offline = receiptMapper.selectById(comp.getOfflineReceiptId());
                if (offline != null && !CcConstants.RECEIPT_ACCEPTED.equals(offline.getStatus())) {
                    offline.setStatus(CcConstants.RECEIPT_ACCEPTED);
                    offline.setAcceptedTime(serverNow);
                    offline.setAcceptedBy(UserContext.currentId());
                    receiptMapper.updateById(offline);
                }
            }
        }

        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        if (order != null) {
            order.setStatus(CcConstants.ORDER_SIGNED);
            orderMapper.updateById(order);
        }
        alertEvaluationService.closeStreaks(batch.getId());

        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_SIGN,
                String.format("批次 %s 经电子回执 %s 签收闭环：签收人=%s，实收=%d%s；设备签收时间=%s 与服务器闭环时间=%s 并列留痕",
                        batch.getBatchNo(), r.getReceiptNo(), r.getSignBy(), r.getConfirmQty(),
                        partialDesc(r), r.getDeviceSignTime(), serverNow));
        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, r.getId(), batch.getId(), CcConstants.ACT_ESIGN_ACCEPT,
                String.format("电子回执 %s 采信闭环（来源=%s）：双钟点并列——设备时间=%s，服务器时间=%s；任何后续修正不得覆盖本原始回执",
                        r.getReceiptNo(), sourceDesc(r.getSource()), r.getDeviceSignTime(), serverNow));
    }

    // ====================================================================
    // 冲突冻结 + 主管裁决
    // ====================================================================

    /** 冻结批次：保存冻结前状态，两份回执置 CONFLICT，生成主管裁决单 */
    private long freezeBatch(TransportBatch batch, EsignReceipt first, EsignReceipt second, String trigger) {
        LocalDateTime now = LocalDateTime.now();
        EsignAdjudication adj = new EsignAdjudication();
        adj.setAdjudicationNo(generateAdjudicationNo());
        adj.setBatchId(batch.getId());
        adj.setFirstReceiptId(first.getId());
        adj.setSecondReceiptId(second.getId());
        adj.setConflictSummary(buildConflictSummary(first, second));
        adj.setStatus(CcConstants.ADJ_PENDING);
        adjudicationMapper.insert(adj);

        first.setStatus(CcConstants.RECEIPT_CONFLICT);
        first.setAdjudicationId(adj.getId());
        receiptMapper.updateById(first);
        second.setStatus(CcConstants.RECEIPT_CONFLICT);
        second.setAdjudicationId(adj.getId());
        receiptMapper.updateById(second);

        batch.setPreFreezeStatus(batch.getStatus());
        batch.setSignFrozen(true);
        batch.setSignFreezeTime(now);
        batch.setSignFreezeReason(trigger + "：" + adj.getConflictSummary());
        batch.setActiveAdjudicationId(adj.getId());
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_ESIGN_ADJUDICATION, adj.getId(), batch.getId(), CcConstants.ACT_ESIGN_FREEZE,
                String.format("%s：批次 %s 冻结（冻结前状态=%s）。在先回执 %s vs 在后回执 %s，差异：%s；裁决单 %s 待主管裁决，冻结期间不得签收/结算/撤回",
                        trigger, batch.getBatchNo(), batch.getPreFreezeStatus(),
                        first.getReceiptNo(), second.getReceiptNo(), adj.getConflictSummary(), adj.getAdjudicationNo()));
        return adj.getId();
    }

    @Transactional
    public EsignAdjudication adjudicate(EsignAdjudicationRequest req) {
        requireAdmin();
        String decision = req.getDecision().trim().toUpperCase();
        if (!CcConstants.ADJ_FIRST.equals(decision) && !CcConstants.ADJ_SECOND.equals(decision)
                && !CcConstants.ADJ_INVALID_BOTH.equals(decision)) {
            throw new BusinessException("裁决结论非法：仅支持 FIRST/SECOND/INVALID_BOTH");
        }
        EsignAdjudication adj = adjudicationMapper.selectById(req.getAdjudicationId());
        if (adj == null) {
            throw new BusinessException("裁决单不存在: " + req.getAdjudicationId());
        }
        if (CcConstants.ADJ_RESOLVED.equals(adj.getStatus())) {
            throw new BusinessException("裁决单 " + adj.getAdjudicationNo() + " 已裁决，裁决只追加一次，不能重复裁决");
        }
        TransportBatch batch = requireBatch(adj.getBatchId());
        EsignReceipt first = receiptMapper.selectById(adj.getFirstReceiptId());
        EsignReceipt second = receiptMapper.selectById(adj.getSecondReceiptId());
        LocalDateTime now = LocalDateTime.now();

        if (CcConstants.ADJ_INVALID_BOTH.equals(decision)) {
            markSuperseded(first);
            markSuperseded(second);
            unfreeze(batch);
            adj.setDecision(decision);
            adj.setWinningReceiptId(null);
        } else {
            EsignReceipt winner = CcConstants.ADJ_FIRST.equals(decision) ? first : second;
            EsignReceipt loser = CcConstants.ADJ_FIRST.equals(decision) ? second : first;
            markSuperseded(loser);
            adj.setDecision(decision);
            adj.setWinningReceiptId(winner.getId());

            boolean closed = false;
            String gateError = null;
            try {
                runAcceptGates(batch, winner);
            } catch (BusinessException gate) {
                gateError = gate.getMessage();
            }
            if (gateError == null) {
                // 采信且满足闭环条件：解冻并直接闭环
                clearFreezeFlags(batch);
                doAccept(batch, winner, now);
                winner.setAdjudicationId(adj.getId());
                receiptMapper.updateById(winner);
                // 归置冻结期间到达的其它在途回执：同内容并入正本，不同内容置未采信，
                // 避免批次闭环后仍悬挂 UPLOADED 回执
                sweepPendingOnAdjudicationClose(batch, winner, adj.getId());
                closed = true;
            } else {
                // 采信但暂不满足闭环条件：回执回到待闭环，批次解冻待补齐
                winner.setStatus(CcConstants.RECEIPT_UPLOADED);
                receiptMapper.updateById(winner);
                unfreeze(batch);
            }
            opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, winner.getId(), batch.getId(), CcConstants.ACT_ESIGN_ADJUDICATE,
                    String.format("主管采信回执 %s；另一份回执 %s 置 SUPERSEDED 原样保留。%s",
                            winner.getReceiptNo(), loser.getReceiptNo(),
                            closed ? "已满足闭环条件并完成电子签收闭环。"
                                    : "暂不满足闭环条件（" + gateError + "），批次解冻，补齐后可采信闭环。"));
        }

        adj.setStatus(CcConstants.ADJ_RESOLVED);
        adj.setDecisionNote(req.getDecisionNote());
        adj.setSupervisorId(UserContext.currentId());
        adj.setSupervisorName(UserContext.currentName());
        adj.setDecideTime(now);
        adjudicationMapper.updateById(adj);

        opLogService.log(CcConstants.ENT_ESIGN_ADJUDICATION, adj.getId(), batch.getId(), CcConstants.ACT_ESIGN_ADJUDICATE,
                String.format("主管 %s 对裁决单 %s 作出裁决：%s。%s",
                        adj.getSupervisorName(), adj.getAdjudicationNo(), decisionDesc(decision), req.getDecisionNote()));
        return adj;
    }

    // ====================================================================
    // 人工修正（只追加，不覆盖原始回执）
    // ====================================================================

    @Transactional
    public EsignCorrection correct(EsignCorrectionRequest req) {
        requirePerm(CcConstants.PERM_ESIGN_CORRECT);
        EsignReceipt receipt = requireReceipt(req.getReceiptNo());
        if (CcConstants.RECEIPT_SUPERSEDED.equals(receipt.getStatus())
                || CcConstants.RECEIPT_AFTER_CLOSED.equals(receipt.getStatus())) {
            throw new BusinessException("回执状态为 " + receipt.getStatus() + "，不能再修正");
        }
        String field = req.getFieldName().trim();
        if (!CORRECTABLE_FIELDS.contains(field)) {
            throw new BusinessException(String.format(
                    "字段 %s 是关键签收事实（签收人/数量/批准文号/包装规格/设备签收时间/定位/证据等），禁止人工修正；"
                    + "只能再产生新回执或通过主管裁决处理。允许修正的非关键字段：%s",
                    field, CORRECTABLE_FIELDS));
        }

        // 当前有效值：取该字段最近一次修正值，否则取原始回执值；原始回执行本身绝不 UPDATE
        String original = currentFieldValue(receipt.getId(), field, receipt);
        EsignCorrection c = new EsignCorrection();
        c.setReceiptId(receipt.getId());
        c.setBatchId(receipt.getBatchId());
        c.setFieldName(field);
        c.setOriginalValue(original);
        c.setCorrectedValue(req.getCorrectedValue().trim());
        c.setReason(req.getReason().trim());
        c.setCorrectedBy(UserContext.currentId());
        c.setCorrectedByName(UserContext.currentName());
        c.setCorrectTime(LocalDateTime.now());
        correctionMapper.insert(c);

        opLogService.log(CcConstants.ENT_ESIGN_CORRECTION, c.getId(), receipt.getBatchId(), CcConstants.ACT_ESIGN_CORRECT,
                String.format("回执 %s 字段 %s 人工修正：原值=[%s] -> 修正值=[%s]（修正只追加留痕，原始回执不被覆盖，查询并列展示）。原因：%s",
                        receipt.getReceiptNo(), field, original, c.getCorrectedValue(), c.getReason()));
        return c;
    }

    // ====================================================================
    // 部分成功补偿对应（电子签收 ↔ 离线回执，一一对应、双向回填、数量平衡）
    // ====================================================================

    @Transactional
    public EsignCompensation matchCompensation(EsignCompensationMatchRequest req) {
        requirePerm(CcConstants.PERM_ESIGN_COMPENSATE);
        EsignReceipt esign = requireReceipt(req.getEsignReceiptNo());
        EsignReceipt offline = requireReceipt(req.getOfflineReceiptNo());
        if (!esign.getBatchId().equals(offline.getBatchId())) {
            throw new BusinessException("电子签收回执与离线回执不属于同一运输批次，不能建立补偿对应");
        }
        TransportBatch batch = requireBatch(esign.getBatchId());
        if (esign.getCompensationId() != null || offline.getCompensationId() != null) {
            throw new BusinessException("电子签收回执或离线回执已存在补偿对应，一一对应关系不能重复建立");
        }
        if (!CcConstants.ESIGN_SOURCE_OFFLINE.equals(offline.getSource())) {
            throw new BusinessException("短少/异常方回执必须是来源 OFFLINE 的离线回执");
        }
        if (offline.getConfirmQty() != null && offline.getConfirmQty() > 0) {
            throw new BusinessException("承载短少/异常的离线回执成功签收数量应为 0，其短少数量与电子签收成功数量互补");
        }
        if (offline.getShortQty() == null || offline.getShortQty() <= 0) {
            throw new BusinessException("离线回执必须登记短少/异常数量（shortQty > 0）");
        }
        if (esign.getConfirmQty() == null || esign.getConfirmQty() <= 0) {
            throw new BusinessException("电子签收回执必须承载成功签收数量（confirmQty > 0）");
        }
        int signedQty = esign.getConfirmQty();
        int shortQty = offline.getShortQty();
        if (signedQty + shortQty != batch.getShipQty()) {
            throw new BusinessException(String.format(
                    "补偿数量不平衡：电子签收成功 %d + 离线短少 %d = %d，与发运数量 %d 不相等，不能建立对应",
                    signedQty, shortQty, signedQty + shortQty, batch.getShipQty()));
        }
        String reasonType = normalizeReasonType(req.getReasonType());
        for (EsignReceipt rc : Arrays.asList(esign, offline)) {
            if (CcConstants.RECEIPT_SUPERSEDED.equals(rc.getStatus())
                    || CcConstants.RECEIPT_AFTER_CLOSED.equals(rc.getStatus())
                    || CcConstants.RECEIPT_MERGED.equals(rc.getStatus())) {
                throw new BusinessException("回执 " + rc.getReceiptNo() + " 状态为 " + rc.getStatus() + "，不能建立补偿对应");
            }
        }

        EsignCompensation comp = new EsignCompensation();
        comp.setCompensationNo(generateCompensationNo());
        comp.setBatchId(batch.getId());
        comp.setEsignReceiptId(esign.getId());
        comp.setEsignReceiptNo(esign.getReceiptNo());
        comp.setOfflineReceiptId(offline.getId());
        comp.setOfflineReceiptNo(offline.getReceiptNo());
        comp.setSignedQty(signedQty);
        comp.setShortQty(shortQty);
        comp.setReasonType(reasonType);
        comp.setReasonDetail(req.getReasonDetail().trim());
        comp.setStatus(CcConstants.COMP_MATCHED);
        compensationMapper.insert(comp);

        esign.setCompensationId(comp.getId());
        esign.setPairedReceiptId(offline.getId());
        receiptMapper.updateById(esign);
        offline.setCompensationId(comp.getId());
        offline.setPairedReceiptId(esign.getId());
        receiptMapper.updateById(offline);

        opLogService.log(CcConstants.ENT_ESIGN_COMPENSATION, comp.getId(), batch.getId(), CcConstants.ACT_ESIGN_COMP_MATCH,
                String.format("部分成功补偿对应 %s：电子签收 %s（成功 %d）↔ 离线回执 %s（短少 %d，原因=%s）一一对应、双向回填；%d+%d=发运 %d 数量平衡，待确认后闭环",
                        comp.getCompensationNo(), esign.getReceiptNo(), signedQty, offline.getReceiptNo(), shortQty,
                        reasonType, signedQty, shortQty, batch.getShipQty()));
        return comp;
    }

    @Transactional
    public EsignCompensation confirmCompensation(EsignCompensationConfirmRequest req) {
        requirePerm(CcConstants.PERM_ESIGN_COMPENSATE);
        EsignCompensation comp = compensationMapper.selectById(req.getCompensationId());
        if (comp == null) {
            throw new BusinessException("补偿记录不存在: " + req.getCompensationId());
        }
        if (CcConstants.COMP_CONFIRMED.equals(comp.getStatus())) {
            throw new BusinessException("补偿对应已确认，不能重复确认");
        }
        // 确认前复核双向锚点与数量平衡仍成立（防止期间数据被挪动）
        EsignReceipt esign = receiptMapper.selectById(comp.getEsignReceiptId());
        EsignReceipt offline = receiptMapper.selectById(comp.getOfflineReceiptId());
        TransportBatch batch = requireBatch(comp.getBatchId());
        if (esign == null || offline == null
                || !comp.getId().equals(esign.getCompensationId())
                || !comp.getId().equals(offline.getCompensationId())
                || comp.getSignedQty() + comp.getShortQty() != batch.getShipQty()) {
            throw new BusinessException("补偿对应双向锚点或数量平衡已不成立，不能确认");
        }
        comp.setStatus(CcConstants.COMP_CONFIRMED);
        comp.setConfirmNote(req.getConfirmNote());
        comp.setConfirmedBy(UserContext.currentId());
        comp.setConfirmedByName(UserContext.currentName());
        comp.setConfirmTime(LocalDateTime.now());
        compensationMapper.updateById(comp);

        opLogService.log(CcConstants.ENT_ESIGN_COMPENSATION, comp.getId(), batch.getId(), CcConstants.ACT_ESIGN_COMP_CONFIRM,
                String.format("补偿对应 %s 确认：电子签收 %s 与离线回执 %s 数量平衡（%d+%d=%d），部分成功批次具备电子签收闭环条件",
                        comp.getCompensationNo(), esign.getReceiptNo(), offline.getReceiptNo(),
                        comp.getSignedQty(), comp.getShortQty(), batch.getShipQty()));
        return comp;
    }

    // ====================================================================
    // 纸质/原签收路径合流（由 TransportBatchService.sign 调用，避免循环依赖）
    // ====================================================================

    /**
     * 原签收（纸质/在线核对）闭环前的电子回执合流/冲突处理。
     * - 已冻结：直接拒绝。
     * - 无在途电子回执：返回 null（走原闭环）。
     * - 存在内容一致的电子回执：返回其 ID（纸质闭环后一并置 ACCEPTED，合流为同一份签收）。
     * - 存在内容不一致的电子回执：把本次签收登记为当场电子回执，REQUIRES_NEW 冻结后抛异常。
     */
    @Transactional
    public Long resolveManualSign(TransportBatch batch, String signBy, String approvalNo,
                                  String packageSpec, Integer confirmQty, String remark) {
        if (Boolean.TRUE.equals(batch.getSignFrozen())) {
            throw new BusinessException(String.format(
                    "批次 %s 因两份不同电子签收已冻结（裁决单#%d），须主管裁决后才能签收",
                    batch.getBatchNo(), batch.getActiveAdjudicationId()));
        }
        List<EsignReceipt> pending = receiptMapper.selectList(new QueryWrapper<EsignReceipt>()
                .eq("batch_id", batch.getId())
                .eq("status", CcConstants.RECEIPT_UPLOADED)
                .orderByAsc("id"));
        if (pending.isEmpty()) {
            return null;
        }
        for (EsignReceipt r : pending) {
            if (!isCompetingClaim(r)) {
                continue;
            }
            boolean same = safeEq(r.getSignBy(), signBy)
                    && safeEq(r.getReceivedApprovalNo(), approvalNo)
                    && safeEq(r.getReceivedPackageSpec(), packageSpec)
                    && r.getConfirmQty() != null && r.getConfirmQty().equals(confirmQty);
            if (same) {
                return r.getId();
            }
        }
        // 存在不一致的成功主张回执：登记本次当场签收为电子回执并冻结（独立事务保证冻结事实落库）
        java.util.Optional<EsignReceipt> conflictOpt = pending.stream().filter(this::isCompetingClaim).findFirst();
        if (!conflictOpt.isPresent()) {
            // 在途电子回执只是短少方离线记录，不构成第二份签收，纸质闭环照常
            return null;
        }
        EsignReceipt conflict = conflictOpt.get();
        String adjNo = conflictGateway.registerManualAndFreeze(batch.getId(), conflict.getId(),
                signBy, approvalNo, packageSpec, confirmQty, remark, batch.getResponsibleParty());
        throw new BusinessException(String.format(
                "本次签收与在途电子回执 %s 内容不一致，批次 %s 已冻结，生成主管裁决单 %s；两份签收原始内容均已保留",
                conflict.getReceiptNo(), batch.getBatchNo(), adjNo));
    }

    /** 纸质/当场闭环成功后把内容一致的电子回执合流为 ACCEPTED，并回填批次双钟点列 */
    @Transactional
    public void convergeAfterManualClose(Long receiptId, TransportBatch batch, LocalDateTime serverNow) {
        EsignReceipt r = receiptMapper.selectById(receiptId);
        if (r == null) {
            return;
        }
        r.setStatus(CcConstants.RECEIPT_ACCEPTED);
        r.setAcceptedTime(serverNow);
        r.setAcceptedBy(UserContext.currentId());
        receiptMapper.updateById(r);
        batch.setEsignReceiptId(r.getId());
        batch.setEsignDeviceSignTime(r.getDeviceSignTime());
        batch.setEsignServerSignTime(serverNow);
        batchMapper.updateById(batch);
        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, r.getId(), batch.getId(), CcConstants.ACT_ESIGN_ACCEPT,
                String.format("电子回执 %s 与当场签收内容一致，随签收闭环合流采信；设备签收时间=%s、服务器闭环时间=%s 并列",
                        r.getReceiptNo(), r.getDeviceSignTime(), serverNow));
    }

    /** 独立事务网关：登记当场纸质签收为电子回执 + 冻结，见 {@link EsignConflictGateway} */

    // ====================================================================
    // 查询
    // ====================================================================

    public EsignReceipt requireReceipt(String receiptNo) {
        EsignReceipt r = receiptMapper.selectOne(new QueryWrapper<EsignReceipt>()
                .eq("receipt_no", receiptNo.trim()));
        if (r == null) {
            throw new BusinessException("电子签收回执不存在: " + receiptNo);
        }
        return r;
    }

    public List<EsignReceipt> listByBatch(Long batchId) {
        return receiptMapper.selectList(new QueryWrapper<EsignReceipt>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    /** 某批次全部回执的详情视图（证据/修正/双钟点/补偿） */
    public List<Map<String, Object>> listReceiptViews(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (batch == null) {
            return out;
        }
        for (EsignReceipt r : listByBatch(batchId)) {
            out.add(receiptView(r, batch, Boolean.TRUE.equals(batch.getSignFrozen()),
                    CcConstants.RECEIPT_AFTER_CLOSED.equals(r.getStatus()),
                    r.getAdjudicationId(), 0));
        }
        return out;
    }

    public List<EsignMedia> listMedia(Long receiptId) {
        return mediaMapper.selectList(new QueryWrapper<EsignMedia>()
                .eq("receipt_id", receiptId).orderByAsc("media_type").orderByAsc("id"));
    }

    public List<EsignCorrection> listCorrections(Long receiptId) {
        return correctionMapper.selectList(new QueryWrapper<EsignCorrection>()
                .eq("receipt_id", receiptId).orderByAsc("id"));
    }

    public List<EsignAdjudication> listPendingAdjudications() {
        return adjudicationMapper.selectList(new QueryWrapper<EsignAdjudication>()
                .eq("status", CcConstants.ADJ_PENDING).orderByAsc("id"));
    }

    public Map<String, Object> receiptDetail(String receiptNo) {
        EsignReceipt r = requireReceipt(receiptNo);
        TransportBatch batch = batchMapper.selectById(r.getBatchId());
        return receiptView(r, batch, Boolean.TRUE.equals(batch.getSignFrozen()),
                CcConstants.RECEIPT_AFTER_CLOSED.equals(r.getStatus()), r.getAdjudicationId(), 0);
    }

    /** 批次电子签收聚合节点（供台账使用） */
    public Map<String, Object> batchNode(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        Map<String, Object> node = new LinkedHashMap<>();
        if (batch == null) {
            return node;
        }
        node.put("signFrozen", Boolean.TRUE.equals(batch.getSignFrozen()));
        node.put("preFreezeStatus", batch.getPreFreezeStatus());
        node.put("signFreezeTime", batch.getSignFreezeTime());
        node.put("signFreezeReason", batch.getSignFreezeReason());
        node.put("esignReceiptId", batch.getEsignReceiptId());
        // 双钟点并列展示（设备时间永不被服务器时间/人工修正覆盖）
        Map<String, Object> clocks = new LinkedHashMap<>();
        clocks.put("deviceSignTime", batch.getEsignDeviceSignTime());
        clocks.put("serverSignTime", batch.getEsignServerSignTime());
        node.put("clocks", clocks);

        List<Map<String, Object>> receipts = new ArrayList<>();
        for (EsignReceipt r : listByBatch(batchId)) {
            receipts.add(receiptView(r, batch,
                    Boolean.TRUE.equals(batch.getSignFrozen()),
                    CcConstants.RECEIPT_AFTER_CLOSED.equals(r.getStatus()),
                    r.getAdjudicationId(), 0));
        }
        node.put("receipts", receipts);
        node.put("adjudications", adjudicationMapper.selectList(new QueryWrapper<EsignAdjudication>()
                .eq("batch_id", batchId).orderByAsc("id")));
        node.put("compensations", compensationMapper.selectList(new QueryWrapper<EsignCompensation>()
                .eq("batch_id", batchId).orderByAsc("id")));
        return node;
    }

    // ====================================================================
    // 私有工具
    // ====================================================================

    private Map<String, Object> receiptView(EsignReceipt r, TransportBatch batch, boolean frozen,
                                            boolean afterClosed, Long adjudicationId, int mediaAdded) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("receipt", r);
        v.put("media", listMedia(r.getId()));
        v.put("corrections", listCorrections(r.getId()));
        // 双钟点并列
        Map<String, Object> clocks = new LinkedHashMap<>();
        clocks.put("deviceSignTime", r.getDeviceSignTime());
        clocks.put("deviceUploadTime", r.getDeviceUploadTime());
        clocks.put("serverReceiveTime", r.getServerReceiveTime());
        clocks.put("serverAcceptedTime", r.getAcceptedTime());
        if (r.getDeviceSignTime() != null && r.getServerReceiveTime() != null) {
            clocks.put("deviceServerOffsetSeconds",
                    Duration.between(r.getDeviceSignTime(), r.getServerReceiveTime()).getSeconds());
        }
        v.put("clocks", clocks);
        v.put("frozen", frozen);
        v.put("afterClosed", afterClosed);
        v.put("adjudicationId", adjudicationId != null ? adjudicationId : r.getAdjudicationId());
        v.put("mediaAdded", mediaAdded);
        v.put("mediaComplete", hasMediaType(r.getId(), CcConstants.MEDIA_SIGNATURE)
                && hasMediaType(r.getId(), CcConstants.MEDIA_PHOTO)
                && hasMediaType(r.getId(), CcConstants.MEDIA_LOCATION));
        if (r.getCompensationId() != null) {
            v.put("compensation", compensationMapper.selectById(r.getCompensationId()));
        }
        if (batch != null) {
            v.put("batchNo", batch.getBatchNo());
            v.put("shipQty", batch.getShipQty());
        }
        return v;
    }

    private boolean hasMediaType(Long receiptId, String type) {
        Long n = mediaMapper.selectCount(new QueryWrapper<EsignMedia>()
                .eq("receipt_id", receiptId).eq("media_type", type));
        return n != null && n > 0;
    }

    private void markSuperseded(EsignReceipt r) {
        r.setStatus(CcConstants.RECEIPT_SUPERSEDED);
        receiptMapper.updateById(r);
    }

    /** 解冻：恢复冻结前状态，清除活动裁决锚点（冻结时间/原因保留留痕） */
    private void unfreeze(TransportBatch batch) {
        batch.setSignFrozen(false);
        batch.setStatus(batch.getPreFreezeStatus() == null ? CcConstants.BATCH_SHIPPED : batch.getPreFreezeStatus());
        batch.setActiveAdjudicationId(null);
        batchMapper.updateById(batch);
    }

    /** 采信即闭环分支：只清冻结标记，状态已置 SIGNED，不能恢复 preFreezeStatus */
    private void clearFreezeFlags(TransportBatch batch) {
        batch.setSignFrozen(false);
        batch.setActiveAdjudicationId(null);
        batchMapper.updateById(batch);
    }

    /**
     * 主管裁决采信并闭环后，归置仍处于 UPLOADED 的其它在途回执：
     * 主张成功收货且内容与采信回执一致 -> MERGED 并入正本；
     * 其余（短少方离线回执/内容不一致）-> SUPERSEDED 原样保留。
     */
    private void sweepPendingOnAdjudicationClose(TransportBatch batch, EsignReceipt winner, Long adjId) {
        List<EsignReceipt> pending = receiptMapper.selectList(new QueryWrapper<EsignReceipt>()
                .eq("batch_id", batch.getId())
                .eq("status", CcConstants.RECEIPT_UPLOADED)
                .orderByAsc("id"));
        for (EsignReceipt r : pending) {
            if (r.getId().equals(winner.getId())) {
                continue;
            }
            if (isCompetingClaim(r) && contentKey(r).equals(contentKey(winner))) {
                r.setStatus(CcConstants.RECEIPT_MERGED);
                r.setPairedReceiptId(winner.getId());
                r.setAdjudicationId(adjId);
                receiptMapper.updateById(r);
            } else {
                r.setStatus(CcConstants.RECEIPT_SUPERSEDED);
                r.setAdjudicationId(adjId);
                receiptMapper.updateById(r);
            }
        }
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

    /**
     * 是否为“主张成功收货”的竞争签收：成功数量 > 0。
     * 短少/异常方离线回执（confirmQty=0，承载 shortQty）不主张成功收货，
     * 不与成功签收回执构成“两份不同签收”，它们之间是补偿互补关系。
     */
    private boolean isCompetingClaim(EsignReceipt r) {
        return r.getConfirmQty() != null && r.getConfirmQty() > 0;
    }

    /** 不同编号间判定“是否同一份签收”的业务内容键 */
    private String contentKey(EsignReceipt r) {        return String.join("|", nz(r.getSignBy()), nz(r.getSignOrg()), nz(r.getReceivedApprovalNo()),
                nz(r.getReceivedPackageSpec()), String.valueOf(r.getConfirmQty()));
    }

    /** 同编号幂等摘要：除媒体外的全量业务字段（含设备时间），同编号换任何字段都拒绝 */
    private String payloadHash(EsignUploadRequest req) {
        String canonical = String.join("#",
                nz(req.getReceiptNo()), String.valueOf(req.getBatchId()), nz(req.getDeviceSerial()),
                nz(req.getDriverName()), nz(req.getSignBy()), nz(req.getSignOrg()),
                nz(req.getReceivedApprovalNo()), nz(req.getReceivedPackageSpec()),
                String.valueOf(req.getConfirmQty()), nz(req.getSignRemark()),
                nz(req.getSignResult()), String.valueOf(req.getShortQty()),
                nz(req.getShortReason()), nz(req.getShortReasonType()), nz(req.getSource()),
                String.valueOf(req.getDeviceSignTime()), String.valueOf(req.getDeviceUploadTime()));
        return DeviceCertService.sha256Hex(canonical);
    }

    private String mediaFingerprint(String type, EsignUploadRequest.MediaItem item) {
        if (item.getSha256() != null && !item.getSha256().trim().isEmpty()) {
            return item.getSha256().trim();
        }
        String raw = String.join("#", type, nz(item.getFileName()), nz(item.getFileUrl()),
                String.valueOf(item.getLatitude()), String.valueOf(item.getLongitude()),
                String.valueOf(item.getDeviceCaptureTime()));
        return DeviceCertService.sha256Hex(raw);
    }

    private String currentFieldValue(Long receiptId, String field, EsignReceipt receipt) {
        List<EsignCorrection> history = correctionMapper.selectList(new QueryWrapper<EsignCorrection>()
                .eq("receipt_id", receiptId).eq("field_name", field).orderByDesc("id"));
        if (!history.isEmpty()) {
            return history.get(0).getCorrectedValue();
        }
        switch (field) {
            case "signOrg": return receipt.getSignOrg();
            case "signRemark": return receipt.getSignRemark();
            case "driverName": return receipt.getDriverName();
            default: return null;
        }
    }

    private TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    private TransportBatch requireUploadableBatch(Long batchId) {
        TransportBatch batch = requireBatch(batchId);
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能接收电子签收: " + batch.getBatchNo());
        }
        return batch;
    }

    private void requireAdmin() {
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject.getPrincipal() != null && subject.hasRole(CcConstants.ROLE_ADMIN)) {
                return;
            }
        } catch (Exception ignored) {
            // 无 Shiro 上下文，落入拒绝
        }
        throw new BusinessException("仅主管（ADMIN）可对签收冲突作出裁决");
    }

    private void requirePerm(String perm) {
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject.getPrincipal() != null && subject.isPermitted(perm)) {
                return;
            }
        } catch (Exception ignored) {
            // 无 Shiro 上下文，落入拒绝
        }
        throw new BusinessException("当前账号缺少权限点: " + perm);
    }

    private String generateAdjudicationNo() {
        return String.format("CP%08d", sequenceMapper.nextAdjudicationNo());
    }

    private String generateCompensationNo() {
        return String.format("BC%08d", sequenceMapper.nextCompensationNo());
    }

    private String normalizeSource(String s) {
        if (s == null) {
            return CcConstants.ESIGN_SOURCE_ONLINE;
        }
        String v = s.trim().toUpperCase();
        if (CcConstants.ESIGN_SOURCE_OFFLINE.equals(v)) {
            return v;
        }
        if (CcConstants.ESIGN_SOURCE_ONLINE.equals(v)) {
            return v;
        }
        throw new BusinessException("回执来源非法：仅支持 ONLINE/OFFLINE");
    }

    private String normalizeResult(String s) {
        if (s == null || s.trim().isEmpty()) {
            return CcConstants.ESIGN_FULL;
        }
        String v = s.trim().toUpperCase();
        if (CcConstants.ESIGN_FULL.equals(v) || CcConstants.ESIGN_PARTIAL.equals(v)) {
            return v;
        }
        throw new BusinessException("签收结果非法：仅支持 FULL/PARTIAL");
    }

    private String normalizeReasonType(String s) {
        if (s == null || s.trim().isEmpty()) {
            return CcConstants.COMP_REASON_OTHER;
        }
        String v = s.trim().toUpperCase();
        if (!COMP_REASON_TYPES.contains(v)) {
            throw new BusinessException("短少/补偿原因类型非法：" + s);
        }
        return v;
    }

    private String normalizeMediaType(String s) {
        if (s == null) {
            throw new BusinessException("媒体类型不能为空");
        }
        String v = s.trim().toUpperCase();
        if (CcConstants.MEDIA_SIGNATURE.equals(v) || CcConstants.MEDIA_PHOTO.equals(v)
                || CcConstants.MEDIA_LOCATION.equals(v)) {
            return v;
        }
        throw new BusinessException("媒体类型非法：仅支持 SIGNATURE/PHOTO/LOCATION");
    }

    private void validatePartial(String result, EsignUploadRequest req) {
        if (CcConstants.ESIGN_PARTIAL.equals(result)) {
            if (req.getShortQty() == null || req.getShortQty() <= 0) {
                throw new BusinessException("部分成功（PARTIAL）必须登记短少数量 shortQty > 0");
            }
            if (trimToNull(req.getShortReason()) == null) {
                throw new BusinessException("部分成功（PARTIAL）必须填写短少原因说明");
            }
            normalizeReasonType(req.getShortReasonType());
        } else if (req.getShortQty() != null && req.getShortQty() > 0) {
            throw new BusinessException("登记了短少数量时签收结果必须为 PARTIAL");
        }
    }

    private static boolean safeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String sourceDesc(String s) {
        return CcConstants.ESIGN_SOURCE_OFFLINE.equals(s) ? "离线草稿恢复上传" : "在线当场";
    }

    private static String partialDesc(EsignReceipt r) {
        return CcConstants.ESIGN_PARTIAL.equals(r.getSignResult())
                ? String.format("（部分成功，短少=%d，%s）", r.getShortQty(), nz(r.getShortReasonType()))
                : "（全部成功）";
    }

    private static String decisionDesc(String d) {
        switch (d) {
            case CcConstants.ADJ_FIRST: return "采信在先回执";
            case CcConstants.ADJ_SECOND: return "采信在后回执";
            case CcConstants.ADJ_INVALID_BOTH: return "两份签收均无效，可重新签收";
            default: return d;
        }
    }
}
