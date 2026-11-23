package com.acme.marketing.control.interfaces;

import com.acme.marketing.control.application.BundleScheduleWorker;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅由携带可信租户机器身份的调度执行器唤醒，接口不接受活动、租户或回调地址。 */
@RestController
public class BundleScheduleTickController {
    private final BundleScheduleWorker worker;
    public BundleScheduleTickController(BundleScheduleWorker worker) { this.worker = worker; }

    /** 返回此次领取及持久写回结果；发布是否成功由可恢复操作记录提供。 */
    @PostMapping("/internal/v1/release-bundle-schedules:tick")
    public BundleScheduleWorker.Tick tick() { return worker.tick(); }
}
