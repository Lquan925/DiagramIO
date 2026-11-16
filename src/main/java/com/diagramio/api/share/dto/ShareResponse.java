package com.diagramio.api.share.dto;

import com.diagramio.domain.share.DiagramShare;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record ShareResponse(
        @Schema(description = "Mã định danh liên kết chia sẻ")
        UUID id,

        @Schema(description = "Mã định danh sơ đồ")
        UUID diagramId,

        @Schema(description = "Mã token chia sẻ bảo mật, gửi qua header X-Share-Token khi gọi /api/v1/diagrams/{id}...")
        String shareToken,

        @Schema(description = "Đường dẫn chia sẻ chứa token, mở là xem được sơ đồ",
                example = "http://localhost:8080/api/v1/shares/3f9a1c...")
        String shareUrl,

        @Schema(description = "Quyền hạn chia sẻ (view hoặc edit)")
        String role,

        @Schema(description = "Thời gian tạo liên kết")
        Instant createdAt,

        @Schema(description = "Liên kết hết hạn khi now >= expiresAt. Null chỉ dành cho liên kết cũ không hết hạn.",
                types = {"string", "null"}, format = "date-time")
        Instant expiresAt
) {
    public static ShareResponse fromDomain(DiagramShare share, String shareUrl) {
        return new ShareResponse(
                share.getId(),
                share.getDiagramId(),
                share.getShareToken(),
                shareUrl,
                share.getRole().value(),
                share.getCreatedAt(),
                share.getExpiresAt()
        );
    }
}
