package com.acme.marketing.control.interfaces;

import com.acme.marketing.control.application.BundleSchedules;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 操作员端按固定组合排期；所有访问继承活动范围和发布权限。 */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/release-bundles/schedules")
public class BundleScheduleController {
    private final BundleSchedules schedules;
    public BundleScheduleController(BundleSchedules schedules) { this.schedules = schedules; }

    /** 创建或根据同一键恢复排期。 */
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public BundleSchedules.View create(@PathVariable String campaignId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody BundleSchedules.Request request) { return schedules.create(campaignId, key, request); }

    /** 仅查询指定活动与组合版本。 */
    @GetMapping
    public List<BundleSchedules.View> recent(@PathVariable String campaignId, @RequestParam long version) {
        return schedules.recent(campaignId, version);
    }

    /** 向数据库提交带代次的暂停，防止迟到点击覆盖较新的决定。 */
    @PostMapping("/{scheduleId}:pause")
    public BundleSchedules.View pause(@PathVariable String campaignId, @PathVariable String scheduleId,
            @RequestParam long expectedRevision) { return schedules.change(campaignId, scheduleId, expectedRevision, false); }

    /** 恢复沿用第一次设置的版本、时间窗口和位置。 */
    @PostMapping("/{scheduleId}:resume")
    public BundleSchedules.View resume(@PathVariable String campaignId, @PathVariable String scheduleId,
            @RequestParam long expectedRevision) { return schedules.change(campaignId, scheduleId, expectedRevision, true); }
}
