package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ScheduleView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date", example = "2026-10-22", description = "校园本地日期") String date,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "按开始时间升序，相邻已合并；未配置为空数组") List<ScheduleInterval> intervals,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0", description = "整个人员的当前版本，与姓名、启停、绑定及其他日期共用") String barberVersion) {}
