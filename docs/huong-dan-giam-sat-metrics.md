# Hướng dẫn giám sát hệ thống với Prometheus Metrics & Spring Boot Actuator

Tài liệu này hướng dẫn cách truy cập, bảo mật và truy vấn các chỉ số giám sát hiệu năng (metrics) của hệ thống DiagramIO phục vụ kiểm thử tải, giám sát vận hành và phát hiện sự cố.

---

## 1. Tổng quan kiến trúc giám sát

DiagramIO sử dụng **Spring Boot Actuator** tích hợp cùng **Micrometer** và **Prometheus registry**:
- **Health check:** `/actuator/health` (và `/api/v1/health`)
- **Prometheus Scrape Endpoint:** `/actuator/prometheus` (định dạng text tiêu chuẩn OpenMetrics/Prometheus)
- **Tập trung đo lường 3 nhóm chỉ số cốt lõi:**
  1. **Thời gian phản hồi (Response Time / Latency)** theo phân vị P50, P90, P95, P99 và trung bình.
  2. **Tỷ lệ lỗi (Error Rate)** phân tách theo mã trạng thái HTTP (2xx, 4xx, 5xx, đặc biệt là lỗi 429 Rate Limit).
  3. **Tình trạng Connection Pool (HikariCP)** gồm số kết nối bận (active), rảnh (idle), hàng đợi chờ (pending) và timeout.

---

## 2. Bảo mật và phân quyền endpoint giám sát

Để tránh lộ thông tin nội bộ hệ thống ra bên ngoài, endpoint `/actuator/**` được kiểm soát nghiêm ngặt bởi `MonitoringSecurityFilter`:

1. **Endpoint công khai:**
   - `/actuator/health` được mở công khai (không cần xác thực) phục vụ cho Docker, Kubernetes readiness/liveness probes.
2. **Endpoint bị hạn chế (`/actuator/prometheus`, `/actuator/metrics`, ...):**
   - Chỉ cho phép truy cập khi thỏa mãn một trong các điều kiện sau:
     - **Địa chỉ IP nội bộ:** Đến từ `localhost` / `127.0.0.1` / `::1` (khi bật `monitoring.allow-local: true` ở môi trường dev).
     - **Mã token bí mật (Header):** Gửi kèm header `X-Monitoring-Token: <MONITORING_SECRET_TOKEN>` hoặc header `Authorization: Bearer <MONITORING_SECRET_TOKEN>`.
   - Các request không hợp lệ sẽ bị từ chối với mã **403 Forbidden** và body JSON chuẩn `ErrorResponse`.

### Ví dụ lệnh gọi lấy metrics qua cURL:

```bash
# Gọi qua Header X-Monitoring-Token
curl -H "X-Monitoring-Token: monitoring-secret-token-32chars-ok" http://localhost:8080/actuator/prometheus

# Hoặc gọi qua Header Authorization Bearer
curl -H "Authorization: Bearer monitoring-secret-token-32chars-ok" http://localhost:8080/actuator/prometheus
```

---

## 3. Các chỉ số quan trọng và câu truy vấn PromQL

### 3.1. Thời gian phản hồi (Latency / Response Time)

| Chỉ số | Ý nghĩa |
|---|---|
| `http_server_requests_seconds_count` | Tổng số lượng request đã xử lý |
| `http_server_requests_seconds_sum` | Tổng thời gian xử lý tích lũy (giây) |
| `http_server_requests_seconds_max` | Thời gian phản hồi lớn nhất trong cửa sổ đo |
| `http_server_requests_seconds_bucket` | Phân bố thời gian phản hồi theo các mốc SLA |

**Các câu truy vấn PromQL mẫu:**

- **Thời gian phản hồi phân vị 95 (P95 Latency) theo từng API (giây):**
  ```promql
  histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[1m])) by (le, uri))
  ```
- **Thời gian phản hồi phân vị 99 (P99 Latency):**
  ```promql
  histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[1m])) by (le))
  ```
- **Thời gian phản hồi trung bình (Average Latency):**
  ```promql
  sum(rate(http_server_requests_seconds_sum[1m])) / sum(rate(http_server_requests_seconds_count[1m]))
  ```
- **Lưu lượng truy vấn (Throughput / Requests Per Second - RPS):**
  ```promql
  sum(rate(http_server_requests_seconds_count[1m])) by (uri, method)
  ```

---

### 3.2. Tỷ lệ lỗi (Error Rate)

Hệ thống ghi nhận tag `status` (mã HTTP) và `outcome` (`SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR`).

**Các câu truy vấn PromQL mẫu:**

- **Tỷ lệ lỗi Server (5xx Error Rate - %):**
  ```promql
  (sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m])) or vector(0))
  /
  sum(rate(http_server_requests_seconds_count[1m])) * 100
  ```
- **Tần suất request bị Rate Limit (HTTP 429):**
  ```promql
  sum(rate(http_server_requests_seconds_count{status="429"}[1m]))
  ```
- **Tỷ lệ lỗi tổng quát (4xx & 5xx - %):**
  ```promql
  sum(rate(http_server_requests_seconds_count{status=~"[45].."}[1m]))
  /
  sum(rate(http_server_requests_seconds_count[1m])) * 100
  ```

---

### 3.3. Tình trạng Database Connection Pool (HikariCP)

Các chỉ số giúp phát hiện nguy cơ nghẽn kết nối cơ sở dữ liệu khi chịu tải cao:

| Metric Name | Mô tả |
|---|---|
| `hikaricp_connections_active` | Số kết nối đang thực thi truy vấn |
| `hikaricp_connections_idle` | Số kết nối rảnh sẵn sàng phục vụ |
| `hikaricp_connections_pending` | Số luồng (threads) đang phải chờ mượn kết nối từ pool |
| `hikaricp_connections_timeout_total` | Tổng số lần xảy ra timeout khi chờ kết nối |
| `hikaricp_connections_acquire_seconds_max` | Thời gian chờ mượn kết nối lâu nhất |

**Các câu truy vấn PromQL mẫu:**

- **Tỷ lệ sử dụng connection pool (% Utilization):**
  ```promql
  hikaricp_connections_active / (hikaricp_connections_active + hikaricp_connections_idle) * 100
  ```
- **Cảnh báo nghẽn Connection Pool (Pending Threads > 0):**
  ```promql
  hikaricp_connections_pending > 0
  ```

---

## 4. Cấu hình tích hợp Prometheus Server

Khi tích hợp với Prometheus Server bên ngoài hoặc trong file `prometheus.yml` của Docker Compose, cấu hình `scrape_configs` như sau:

```yaml
scrape_configs:
  - job_name: 'diagramio-api'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 5s
    # Gửi kèm token bảo mật để qua lớp kiểm soát MonitoringSecurityFilter
    params:
      format: ['prometheus']
    static_configs:
      - targets: ['diagramio-app:8080']
    http_headers:
      X-Monitoring-Token: "monitoring-secret-token-32chars-ok"
```
