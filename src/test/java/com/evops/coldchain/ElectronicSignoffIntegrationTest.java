package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电子签收（V12）验收测试。
 *
 * 覆盖：
 * 1. 离线草稿：恢复网络后按回执编号上传签名/照片/定位与设备时间，服务端合并闭环，
 *    设备时间与服务器时间并列展示；同编号同内容重复上传幂等并入，不同内容拒绝。
 * 2. 同批两份不同签收：两份回执 CONFLICT、批次 SIGN_FROZEN，主管（ADMIN）裁决；
 *    冻结期间签收/撤回被拦截；采信其一即闭环、另一份 SUPERSEDED 原样保留。
 * 3. 人工修正只追加不覆盖原始回执；关键签收字段禁止修正。
 * 4. 冷链部分成功：成功部分电子签收（PARTIAL）与短少部分离线回执补偿记录一一对应、
 *    双向回填、数量平衡，确认后方可闭环，闭环后双方均采信。
 * 5. 闭环后到达的回执原样保留不采信；无权限账号 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ElectronicSignoffIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
    private static final String BASE = "/api/cold-chain";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;
    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;
    @Autowired private TransportBatchMapper batchMapper;

    // ====================================================================
    // 1) 离线草稿恢复上传 + 三类证据 + 双钟点 + 闭环 + 幂等
    // ====================================================================
    @Test
    void offlineDraft_uploadMergeAccept_withDualClocks_andIdempotent() throws Exception {
        long batchId = shipNewBatch(10);
        TransportBatch frozen = batchMapper.selectById(batchId);
        LocalDateTime deviceSign = LocalDateTime.now().minusHours(2);
        LocalDateTime deviceUpload = LocalDateTime.now().minusMinutes(1);

        // 离线草稿：恢复网络后用离线前生成的回执编号上传，三类证据一次到齐
        Map<String, Object> body = receiptBody("OFF-DRAFT-1", batchId, "院药库张主任",
                frozen, 10, "FULL", null, "OFFLINE", deviceSign);
        body.put("deviceUploadTime", deviceUpload.toString());
        JsonNode up = post("/esign/receipts", body, "carrier");
        assertTrue(up.path("success").asBoolean(), up.toString());
        assertEquals("UPLOADED", up.path("data").path("receipt").path("status").asText());
        assertEquals("OFFLINE", up.path("data").path("receipt").path("source").asText());
        assertTrue(up.path("data").path("mediaComplete").asBoolean());
        // 双钟点：设备时间与服务器接收时间并列
        assertEquals(deviceSign, LocalDateTime.parse(up.path("data").path("clocks").path("deviceSignTime").asText()));
        assertFalse(up.path("data").path("clocks").path("serverReceiveTime").asText().isEmpty());

        // 同编号同内容重复上传（只补一条新照片）：幂等并入，不产生第二份回执
        Map<String, Object> again = receiptBody("OFF-DRAFT-1", batchId, "院药库张主任",
                frozen, 10, "FULL", null, "OFFLINE", deviceSign);
        again.put("deviceUploadTime", deviceUpload.toString());
        List<Map<String, Object>> extraMedia = new ArrayList<>();
        extraMedia.add(media("PHOTO", "photo2.jpg", null, null, LocalDateTime.now().minusHours(2)));
        again.put("media", extraMedia);
        JsonNode merge = post("/esign/receipts", again, "carrier");
        assertTrue(merge.path("success").asBoolean(), merge.toString());
        assertEquals(1, merge.path("data").path("receipt").path("duplicateCount").asInt());
        assertEquals(1, merge.path("data").path("mediaAdded").asInt());

        // 同编号不同内容（改了数量）必须拒绝：不能借合并产生第二份事实
        Map<String, Object> tampered = receiptBody("OFF-DRAFT-1", batchId, "院药库张主任",
                frozen, 9, "FULL", null, "OFFLINE", deviceSign);
        assertStop(post("/esign/receipts", tampered, "carrier"), "内容不同");

        // 采信闭环
        JsonNode accept = post("/esign/receipts/OFF-DRAFT-1/accept", map(), "carrier");
        assertTrue(accept.path("success").asBoolean(), accept.toString());
        assertEquals("ACCEPTED", accept.path("data").path("receipt").path("status").asText());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());
        // 批次双钟点并列
        TransportBatch signed = batchMapper.selectById(batchId);
        assertNotNull(signed.getEsignDeviceSignTime());
        assertNotNull(signed.getEsignServerSignTime());
        assertEquals(deviceSign, signed.getEsignDeviceSignTime());

        // 台账包含电子签收节点且双钟点并列
        JsonNode ledger = get("/batches/" + batchId + "/ledger", "carrier");
        assertEquals(deviceSign, LocalDateTime.parse(ledger.path("esign").path("clocks").path("deviceSignTime").asText()));
        assertFalse(ledger.path("esign").path("clocks").path("serverSignTime").asText().isEmpty());
    }

    // ====================================================================
    // 2) 两份不同签收 -> 冻结 -> 主管裁决；冻结期间签收/撤回拦截
    // ====================================================================
    @Test
    void twoDifferentSignings_freezeBatch_supervisorAdjudicates() throws Exception {
        long batchId = shipNewBatch(10);
        TransportBatch frozen = batchMapper.selectById(batchId);
        LocalDateTime t = LocalDateTime.now().minusMinutes(30);

        // 第一份签收
        JsonNode r1 = post("/esign/receipts",
                receiptBody("RCP-A", batchId, "院药库张主任", frozen, 10, "FULL", null, "ONLINE", t), "carrier");
        assertTrue(r1.path("success").asBoolean(), r1.toString());

        // 第二份不同签收（签收人不同）-> 两份 CONFLICT、批次冻结、生成裁决单
        JsonNode r2 = post("/esign/receipts",
                receiptBody("RCP-B", batchId, "院药库李代办", frozen, 10, "FULL", null, "ONLINE", t), "receiver");
        assertTrue(r2.path("success").asBoolean(), r2.toString());
        assertTrue(r2.path("data").path("frozen").asBoolean(), r2.toString());
        assertNotNull(r2.path("data").path("adjudicationId").asLong());
        long adjId = r2.path("data").path("adjudicationId").asLong();
        assertEquals("CONFLICT", r2.path("data").path("receipt").path("status").asText());
        TransportBatch frozenBatch = batchMapper.selectById(batchId);
        assertTrue(frozenBatch.getSignFrozen());
        assertNotNull(frozenBatch.getPreFreezeStatus());

        // 冻结期间：电子签收闭环、纸质签收、撤回一律拦截
        assertStop(post("/esign/receipts/RCP-A/accept", map(), "carrier"), "已冻结");
        assertStop(post("/batches/sign", manualSignBody(batchId, frozen, "院药库张主任", 10), "receiver"), "已冻结");
        assertStop(post("/batches/revoke", map("batchId", batchId, "reason", "冻结期间撤回"), "dispatcher"), "冻结");

        // 非主管（carrier）不能裁决 -> 403
        HttpStatus code = postStatus("/esign/adjudications/decide",
                map("adjudicationId", adjId, "decision", "FIRST", "decisionNote", "采信第一份"), "carrier");
        assertEquals(HttpStatus.FORBIDDEN, code);

        // 主管裁决：采信在先回执
        JsonNode adj = post("/esign/adjudications/decide",
                map("adjudicationId", adjId, "decision", "FIRST", "decisionNote", "核验现场记录，采信张主任签收"), "admin");
        assertTrue(adj.path("success").asBoolean(), adj.toString());
        assertEquals("RESOLVED", adj.path("data").path("status").asText());

        // 采信即闭环；第二份 SUPERSEDED 原样保留；冻结解除
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());
        assertFalse(batchMapper.selectById(batchId).getSignFrozen());
        JsonNode aDetail = get("/esign/receipts/RCP-A", "admin");
        assertEquals("ACCEPTED", aDetail.path("receipt").path("status").asText());
        JsonNode bDetail = get("/esign/receipts/RCP-B", "admin");
        assertEquals("SUPERSEDED", bDetail.path("receipt").path("status").asText());
    }

    // 主管判定两份均无效：解冻恢复原状态，可重新签收闭环
    @Test
    void adjudicateInvalidBoth_unfreezesAndAllowsNewSign() throws Exception {
        long batchId = shipNewBatch(8);
        TransportBatch frozen = batchMapper.selectById(batchId);
        LocalDateTime t = LocalDateTime.now().minusMinutes(20);

        post("/esign/receipts", receiptBody("DUP-1", batchId, "张主任", frozen, 8, "FULL", null, "ONLINE", t), "carrier");
        Map<String, Object> dup2 = receiptBody("DUP-2", batchId, "李代办", frozen, 7,
                "PARTIAL", "有争议", "ONLINE", t);
        dup2.put("shortQty", 1);
        dup2.put("shortReasonType", "OTHER");
        JsonNode r2 = post("/esign/receipts", dup2, "receiver");
        long adjId = r2.path("data").path("adjudicationId").asLong();

        JsonNode adj = post("/esign/adjudications/decide",
                map("adjudicationId", adjId, "decision", "INVALID_BOTH", "decisionNote", "两份均无法核实，作废重签"), "admin");
        assertTrue(adj.path("success").asBoolean(), adj.toString());

        TransportBatch batch = batchMapper.selectById(batchId);
        assertFalse(batch.getSignFrozen());
        assertEquals(CcConstants.BATCH_SHIPPED, batch.getStatus());
        assertEquals("SUPERSEDED", get("/esign/receipts/DUP-1", "admin").path("receipt").path("status").asText());
        assertEquals("SUPERSEDED", get("/esign/receipts/DUP-2", "admin").path("receipt").path("status").asText());

        // 重新用新回执签收闭环
        JsonNode r3 = post("/esign/receipts",
                receiptBody("DUP-3", batchId, "王主任", frozen, 8, "FULL", null, "ONLINE", LocalDateTime.now()), "receiver");
        assertTrue(r3.path("success").asBoolean(), r3.toString());
        assertTrue(post("/esign/receipts/DUP-3/accept", map(), "receiver").path("success").asBoolean());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());
    }

    // ====================================================================
    // 3) 人工修正只追加不覆盖原始回执；关键字段禁修
    // ====================================================================
    @Test
    void correction_appendsWithoutOverwriting_andKeyFieldsForbidden() throws Exception {
        long batchId = shipNewBatch(6);
        TransportBatch frozen = batchMapper.selectById(batchId);
        post("/esign/receipts", receiptBody("CORR-1", batchId, "赵签收", frozen, 6, "FULL", null,
                "ONLINE", LocalDateTime.now()), "carrier");

        // 修正非关键字段 signOrg：只追加，原始回执不变
        JsonNode c1 = post("/esign/corrections",
                map("receiptNo", "CORR-1", "fieldName", "signOrg",
                        "correctedValue", "杭州市一院药剂科", "reason", "补正科室名称"), "dispatcher");
        assertTrue(c1.path("success").asBoolean(), c1.toString());
        JsonNode detail = get("/esign/receipts/CORR-1", "dispatcher");
        // 原始回执的签收机构仍为空（未被覆盖）
        assertTrue(detail.path("receipt").path("signOrg").isNull());
        assertEquals(1, detail.path("corrections").size());
        assertEquals("杭州市一院药剂科", detail.path("corrections").get(0).path("correctedValue").asText());

        // 关键签收字段禁止修正
        assertStop(post("/esign/corrections",
                map("receiptNo", "CORR-1", "fieldName", "confirmQty",
                        "correctedValue", "5", "reason", "想改数量"), "dispatcher"), "关键签收事实");
        assertStop(post("/esign/corrections",
                map("receiptNo", "CORR-1", "fieldName", "deviceSignTime",
                        "correctedValue", LocalDateTime.now().toString(), "reason", "想改设备时间"), "dispatcher"),
                "关键签收事实");
    }

    // ====================================================================
    // 4) 部分成功：电子签收 ↔ 离线回执补偿一一对应、双向回填、数量平衡
    // ====================================================================
    @Test
    void partialSuccess_compensationLinksEsignAndOffline_balancedAndConfirmed() throws Exception {
        int shipQty = 10;
        long batchId = shipNewBatch(shipQty);
        TransportBatch frozen = batchMapper.selectById(batchId);
        LocalDateTime t = LocalDateTime.now().minusMinutes(15);

        // 成功部分：电子签收 8 件（PARTIAL）
        Map<String, Object> esignBody = receiptBody("PART-OK", batchId, "院药库", frozen,
                8, "PARTIAL", "2 件温度异常拒收", "ONLINE", t);
        esignBody.put("shortQty", 2);
        esignBody.put("shortReasonType", "TEMP_EXCURSION");
        JsonNode ok = post("/esign/receipts", esignBody, "carrier");
        assertTrue(ok.path("success").asBoolean(), ok.toString());

        // 未确认补偿对应前，电子签收不能闭环
        assertStop(post("/esign/receipts/PART-OK/accept", map(), "carrier"), "补偿对应");

        // 短少部分：离线回执，成功 0、短少 2
        Map<String, Object> offBody = receiptBody("PART-SHORT", batchId, "院药库", frozen,
                0, "PARTIAL", "2 件到货温度超标", "OFFLINE", t.minusMinutes(10));
        offBody.put("shortQty", 2);
        offBody.put("shortReasonType", "TEMP_EXCURSION");
        JsonNode off = post("/esign/receipts", offBody, "carrier");
        assertTrue(off.path("success").asBoolean(), off.toString());
        // 短少方不主张成功收货，不触发冲突冻结
        assertFalse(batchMapper.selectById(batchId).getSignFrozen());

        // 建立补偿对应：一一对应、双向回填、8+2=10 平衡
        JsonNode match = post("/esign/compensations/match",
                map("esignReceiptNo", "PART-OK", "offlineReceiptNo", "PART-SHORT",
                        "reasonType", "TEMP_EXCURSION", "reasonDetail", "冷链到货 2 件温度超限，成功 8 件电子签收"), "dispatcher");
        assertTrue(match.path("success").asBoolean(), match.toString());
        long compId = match.path("data").path("id").asLong();
        assertEquals("MATCHED", match.path("data").path("status").asText());
        // 双向回填
        assertEquals(compId, get("/esign/receipts/PART-OK", "dispatcher").path("receipt").path("compensationId").asLong());
        assertEquals(compId, get("/esign/receipts/PART-SHORT", "dispatcher").path("receipt").path("compensationId").asLong());

        // 数量不平衡的第二组对应不可能建立（回执已一一对应），重复建立被拒绝
        assertStop(post("/esign/compensations/match",
                map("esignReceiptNo", "PART-OK", "offlineReceiptNo", "PART-SHORT",
                        "reasonType", "OTHER", "reasonDetail", "重复建立"), "dispatcher"), "已存在补偿对应");

        // 确认补偿对应
        JsonNode confirm = post("/esign/compensations/confirm",
                map("compensationId", compId, "confirmNote", "核实 8+2=10，补偿对应成立"), "dispatcher");
        assertTrue(confirm.path("success").asBoolean(), confirm.toString());

        // 电子签收闭环：成功部分采信；离线回执随之采信；批次 SIGNED
        JsonNode accept = post("/esign/receipts/PART-OK/accept", map(), "carrier");
        assertTrue(accept.path("success").asBoolean(), accept.toString());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());
        assertEquals("ACCEPTED", get("/esign/receipts/PART-SHORT", "dispatcher").path("receipt").path("status").asText());
        // 台账中补偿记录可查且双向编号互见
        JsonNode comp = get("/batches/" + batchId + "/ledger", "dispatcher").path("esign").path("compensations");
        assertEquals(1, comp.size());
        assertEquals("PART-OK", comp.get(0).path("esignReceiptNo").asText());
        assertEquals("PART-SHORT", comp.get(0).path("offlineReceiptNo").asText());
    }

    // 补偿数量不平衡（成功 7 + 短少 2 ≠ 10）拒绝建立
    @Test
    void compensation_unbalanced_rejected() throws Exception {
        long batchId = shipNewBatch(10);
        TransportBatch frozen = batchMapper.selectById(batchId);
        LocalDateTime t = LocalDateTime.now();

        Map<String, Object> esignBody = receiptBody("BAL-OK", batchId, "院药库", frozen, 7,
                "PARTIAL", "数量待核", "ONLINE", t);
        esignBody.put("shortQty", 3);
        esignBody.put("shortReasonType", "OTHER");
        assertTrue(post("/esign/receipts", esignBody, "carrier").path("success").asBoolean());

        Map<String, Object> offBody = receiptBody("BAL-SHORT", batchId, "院药库", frozen, 0,
                "PARTIAL", "短少登记", "OFFLINE", t);
        offBody.put("shortQty", 2);
        offBody.put("shortReasonType", "OTHER");
        assertTrue(post("/esign/receipts", offBody, "carrier").path("success").asBoolean());

        assertStop(post("/esign/compensations/match",
                map("esignReceiptNo", "BAL-OK", "offlineReceiptNo", "BAL-SHORT",
                        "reasonType", "OTHER", "reasonDetail", "数量不平也试图配对"), "dispatcher"),
                "补偿数量不平衡");
    }

    // ====================================================================
    // 5) 闭环后到达回执原样保留不采信；证据不全不能闭环；无权限 403
    // ====================================================================
    @Test
    void afterClosedReceiptKeptNotAccepted_mediaRequired_forbiddenForLackingPerm() throws Exception {
        long batchId = shipNewBatch(4);
        TransportBatch frozen = batchMapper.selectById(batchId);
        LocalDateTime t = LocalDateTime.now().minusMinutes(5);

        // 证据不全（只给签名，无照片/定位）不能闭环
        Map<String, Object> incomplete = receiptBody("INC-1", batchId, "药库", frozen, 4, "FULL", null, "ONLINE", t);
        incomplete.put("media", list(media("SIGNATURE", "sig.png", null, null, t)));
        JsonNode up = post("/esign/receipts", incomplete, "carrier");
        assertTrue(up.path("success").asBoolean(), up.toString());
        assertFalse(up.path("data").path("mediaComplete").asBoolean());
        assertStop(post("/esign/receipts/INC-1/accept", map(), "carrier"), "三类证据");

        // 补传照片、定位
        assertTrue(post("/esign/receipts/media",
                map("receiptNo", "INC-1", "media", list(
                        media("PHOTO", "p.jpg", null, null, t),
                        location(t))), "carrier").path("success").asBoolean());
        assertTrue(post("/esign/receipts/INC-1/accept", map(), "carrier").path("success").asBoolean());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());

        // 闭环后再到达的回执：原样保留 AFTER_CLOSED，不采信、不覆盖
        JsonNode late = post("/esign/receipts",
                receiptBody("LATE-1", batchId, "药库", frozen, 4, "FULL", null, "ONLINE", LocalDateTime.now()), "carrier");
        assertTrue(late.path("success").asBoolean(), late.toString());
        assertEquals("AFTER_CLOSED", late.path("data").path("receipt").path("status").asText());
        assertTrue(late.path("data").path("afterClosed").asBoolean());

        // device 账号无电子签收查看权限 -> 403
        ResponseEntity<String> denied = http.withBasicAuth("device", "device123")
                .getForEntity("http://localhost:" + port + BASE + "/esign/batches/" + batchId + "/receipts", String.class);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
    }

    // 在途电子回执与纸质/当场签收不一致：当场签收被登记为电子回执并冻结，主管裁决采信电子回执闭环
    @Test
    void electronicVsManualSign_conflictFreezes_supervisorPicksElectronic() throws Exception {
        long batchId = shipNewBatch(5);
        TransportBatch frozen = batchMapper.selectById(batchId);
        LocalDateTime t = LocalDateTime.now().minusMinutes(10);

        // 司机已上传一份电子签收（证据齐全）
        assertTrue(post("/esign/receipts",
                receiptBody("EM-1", batchId, "院药库张主任", frozen, 5, "FULL", null, "ONLINE", t), "carrier")
                .path("success").asBoolean());

        // 收货台用不同签收人走纸质/在线核对签收：批次冻结（独立事务落库），本次签收回滚
        assertStop(post("/batches/sign", manualSignBody(batchId, frozen, "院药库马代办", 5), "receiver"), "冻结");
        TransportBatch fb = batchMapper.selectById(batchId);
        assertTrue(fb.getSignFrozen());
        assertNotNull(fb.getActiveAdjudicationId());
        // 批次未闭环（纸质签收回滚），两份回执均 CONFLICT 保留
        assertFalse(CcConstants.BATCH_SIGNED.equals(fb.getStatus()));

        // 主管采信在先电子回执 -> 满足闭环条件，直接闭环
        JsonNode adj = post("/esign/adjudications/decide",
                map("adjudicationId", fb.getActiveAdjudicationId(), "decision", "FIRST",
                        "decisionNote", "现场签名与定位可核实，采信司机电子签收"), "admin");
        assertTrue(adj.path("success").asBoolean(), adj.toString());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());
        assertEquals("ACCEPTED", get("/esign/receipts/EM-1", "admin").path("receipt").path("status").asText());
    }

    // ---------------- 全链路建样 ----------------

    private long shipNewBatch(int shipQty) {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V12验收药品-" + nano);
        dc.setApprovalNo("国药准字V12-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V12验收医院");
        oc.setShipQty(shipQty);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测V12·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName("V12验收物流");
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V12-" + nano);
        pc.setModel("TP-300");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V12-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V12校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v12bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    // ---------------- 请求体构造 ----------------

    private Map<String, Object> receiptBody(String receiptNo, long batchId, String signBy,
                                            TransportBatch frozen, int confirmQty, String result,
                                            String remark, String source, LocalDateTime deviceSign) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receiptNo", receiptNo);
        m.put("batchId", batchId);
        m.put("deviceSerial", "DEV-" + receiptNo);
        m.put("driverName", "王司机");
        m.put("signBy", signBy);
        m.put("receivedApprovalNo", frozen.getApprovalNoSnap());
        m.put("receivedPackageSpec", frozen.getPackageSpecSnap());
        m.put("confirmQty", confirmQty);
        m.put("signRemark", remark);
        m.put("shortReason", remark);
        m.put("signResult", result);
        m.put("source", source);
        m.put("deviceSignTime", deviceSign.toString());
        List<Map<String, Object>> media = new ArrayList<>();
        media.add(media("SIGNATURE", "sig.png", null, null, deviceSign));
        media.add(media("PHOTO", "photo.jpg", null, null, deviceSign));
        media.add(location(deviceSign));
        m.put("media", media);
        return m;
    }

    private Map<String, Object> media(String type, String name, String fileUrl, String sha, LocalDateTime capture) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaType", type);
        m.put("fileName", name);
        m.put("fileUrl", fileUrl);
        m.put("sha256", sha == null ? ("HASH-" + type + "-" + name + "-" + capture.toString()) : sha);
        m.put("deviceCaptureTime", capture.toString());
        if ("PHOTO".equals(type)) {
            m.put("fileSize", 128);
        }
        return m;
    }

    private Map<String, Object> location(LocalDateTime capture) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaType", "LOCATION");
        m.put("latitude", 30.274100);
        m.put("longitude", 120.155100);
        m.put("accuracyMeters", 12.5);
        m.put("addrText", "杭州市第一人民医院药库");
        m.put("sha256", "HASH-LOCATION-" + capture.toString());
        m.put("deviceCaptureTime", capture.toString());
        return m;
    }

    private Map<String, Object> manualSignBody(long batchId, TransportBatch frozen, String signBy, int qty) {
        return map("batchId", batchId, "signBy", signBy,
                "receivedApprovalNo", frozen.getApprovalNoSnap(),
                "receivedPackageSpec", frozen.getPackageSpecSnap(),
                "confirmQty", qty);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> list(Map<String, Object>... items) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> i : items) {
            l.add(i);
        }
        return l;
    }

    // ---------------- HTTP 辅助 ----------------

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拦截: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private JsonNode post(String path, Object body, String user) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = http.withBasicAuth(user, user + "123")
                    .postForObject("http://localhost:" + port + BASE + path,
                            new HttpEntity<>(body, headers), String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpStatus postStatus(String path, Object body, String user) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = http.withBasicAuth(user, user + "123")
                    .postForEntity("http://localhost:" + port + BASE + path,
                            new HttpEntity<>(body, headers), String.class);
            return resp.getStatusCode();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return (HttpStatus) e.getStatusCode();
        }
    }

    private JsonNode get(String path, String user) {
        try {
            String resp = http.withBasicAuth(user, user + "123")
                    .getForObject("http://localhost:" + port + BASE + path, String.class);
            JsonNode node = M.readTree(resp);
            assertTrue(node.path("success").asBoolean(), "GET 应成功: " + resp);
            return node.path("data");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
