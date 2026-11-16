package com.diagramio.api.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** role được kiểm tra một lần ở ShareRole.from (sai thì 400). */
public record CreateShareRequest(
        @Schema(description = "Quyền hạn chia sẻ: view (chỉ xem) hoặc edit (chỉnh sửa)", example = "view",
                allowableValues = {"view", "edit"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String role,

        @Schema(description = "Thời điểm hết hạn, phải ở tương lai. Bỏ qua hoặc null dùng TTL cấu hình (mặc định 7 ngày).",
                types = {"string", "null"}, format = "date-time")
        Instant expiresAt
) {
}
