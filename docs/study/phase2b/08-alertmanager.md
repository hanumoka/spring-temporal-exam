# 알람 - Alertmanager

## 이 문서에서 배우는 것

- 모니터링 알람의 필요성과 원리
- Prometheus Alertmanager 아키텍처
- 알람 규칙(Alert Rules) 작성
- 알람 라우팅과 그룹핑
- 알람 채널 설정 (Slack, Email, PagerDuty)
- 알람 피로(Alert Fatigue) 방지 전략

---

## 1. 알람 시스템의 필요성

### 왜 알람이 필요한가?

```
┌─────────────────────────────────────────────────────────────────────┐
│                      알람 없는 상황                                  │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    Grafana 대시보드                          │   │
│   │                                                              │   │
│   │   에러율: 45% ↑↑↑                                           │   │
│   │   응답시간: 5초 ↑↑↑                                         │   │
│   │   CPU: 98% ↑↑↑                                              │   │
│   │                                                              │   │
│   │   ... 하지만 아무도 보고 있지 않음 ...                       │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   😴 운영팀은 잠들어 있고...                                         │
│   😤 고객은 서비스 장애를 겪고 있음                                  │
│   📞 새벽 3시에 고객 컴플레인 전화                                   │
│                                                                      │
│   ─────────────────────────────────────────────────────────────     │
│                                                                      │
│                      알람이 있는 상황                                │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                   Alertmanager                               │   │
│   │                                                              │   │
│   │   🔔 ALERT: High Error Rate                                  │   │
│   │      order-service 에러율 45% (임계치: 5%)                   │   │
│   │      → Slack #incidents 채널 알림                            │   │
│   │      → On-call 담당자 SMS 발송                               │   │
│   │      → PagerDuty 인시던트 생성                               │   │
│   │                                                              │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   📱 즉시 알림 수신 → 🔧 빠른 대응 → ✅ 장애 최소화                 │
└─────────────────────────────────────────────────────────────────────┘
```

### 좋은 알람의 조건

```
┌─────────────────────────────────────────────────────────────────────┐
│                      좋은 알람 vs 나쁜 알람                          │
│                                                                      │
│   ✅ 좋은 알람                       ❌ 나쁜 알람                    │
│   ─────────────────────────────     ─────────────────────────────   │
│   • 실행 가능한 정보 제공            • 너무 빈번함 (알람 피로)       │
│   • 명확한 임계치                   • 모호한 메시지                  │
│   • 적절한 심각도 수준              • False Positive 많음           │
│   • 충분한 컨텍스트 포함            • 조치 불가능한 정보만           │
│                                                                      │
│   예시:                                                              │
│                                                                      │
│   ✅ "order-service 에러율 45%,                                     │
│       최근 5분간 지속,                                               │
│       영향받는 고객: 약 500명,                                       │
│       Runbook: http://wiki/order-error"                             │
│                                                                      │
│   ❌ "Something went wrong"                                         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Alertmanager 아키텍처

### 전체 흐름

```
┌─────────────────────────────────────────────────────────────────────┐
│                   Prometheus + Alertmanager 아키텍처                 │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                      Prometheus Server                       │   │
│   │                                                              │   │
│   │   ┌─────────────────┐      ┌─────────────────┐             │   │
│   │   │  Alert Rules    │─────▶│  Rule Evaluation │             │   │
│   │   │  (alert.yml)    │      │  (매 15초)       │             │   │
│   │   └─────────────────┘      └────────┬────────┘             │   │
│   │                                     │                       │   │
│   │                                     │ Firing Alert          │   │
│   │                                     ▼                       │   │
│   └─────────────────────────────────────┼───────────────────────┘   │
│                                         │                           │
│                                         ▼                           │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                       Alertmanager                           │   │
│   │                                                              │   │
│   │   ┌───────────┐  ┌───────────┐  ┌───────────┐             │   │
│   │   │ Grouping  │─▶│ Inhibition│─▶│  Silencing │             │   │
│   │   │ (그룹핑)  │  │ (억제)    │  │  (무시)    │             │   │
│   │   └───────────┘  └───────────┘  └─────┬─────┘             │   │
│   │                                       │                     │   │
│   │                                       ▼                     │   │
│   │   ┌─────────────────────────────────────────────────────┐   │   │
│   │   │                    Routing                           │   │   │
│   │   │   • team=backend → #backend-alerts                  │   │   │
│   │   │   • severity=critical → PagerDuty                   │   │   │
│   │   │   • severity=warning → Email                        │   │   │
│   │   └──────────────────────┬──────────────────────────────┘   │   │
│   │                          │                                   │   │
│   └──────────────────────────┼───────────────────────────────────┘   │
│                              │                                      │
│              ┌───────────────┼───────────────┐                     │
│              ▼               ▼               ▼                     │
│   ┌─────────────────┐ ┌─────────────┐ ┌─────────────┐             │
│   │     Slack       │ │    Email    │ │  PagerDuty  │             │
│   │  #incidents     │ │  ops@...    │ │  On-call    │             │
│   └─────────────────┘ └─────────────┘ └─────────────┘             │
└─────────────────────────────────────────────────────────────────────┘
```

### 주요 개념

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Alertmanager 주요 개념                            │
│                                                                      │
│   1. Grouping (그룹핑)                                              │
│      • 유사한 알람을 하나로 묶음                                     │
│      • 예: 같은 서비스의 여러 에러를 하나의 알림으로                  │
│                                                                      │
│   2. Inhibition (억제)                                              │
│      • 특정 알람이 발생하면 관련 알람 억제                           │
│      • 예: 서버 다운 시 해당 서버의 다른 알람 억제                   │
│                                                                      │
│   3. Silencing (무시)                                               │
│      • 특정 기간 알람 무시                                          │
│      • 예: 점검 시간 동안 알람 무시                                  │
│                                                                      │
│   4. Routing (라우팅)                                               │
│      • 알람을 적절한 채널로 전달                                     │
│      • 레이블 기반 라우팅 규칙                                       │
│                                                                      │
│   5. Notification (알림)                                            │
│      • 실제 알림 전송                                               │
│      • 다양한 채널 지원 (Slack, Email, Webhook 등)                   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Docker Compose 설정

### 전체 스택 설정

```yaml
# docker-compose.yml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:v2.48.0
    container_name: prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - ./prometheus/alert.rules.yml:/etc/prometheus/alert.rules.yml
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--web.enable-lifecycle'

  alertmanager:
    image: prom/alertmanager:v0.26.0
    container_name: alertmanager
    ports:
      - "9093:9093"
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
    command:
      - '--config.file=/etc/alertmanager/alertmanager.yml'
      - '--storage.path=/alertmanager'

  grafana:
    image: grafana/grafana:10.2.0
    container_name: grafana
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin

volumes:
  grafana_data:
```

### Prometheus 설정

```yaml
# prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

alerting:
  alertmanagers:
    - static_configs:
        - targets:
          - alertmanager:9093

rule_files:
  - "alert.rules.yml"

scrape_configs:
  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

---

## 4. 알람 규칙 작성

### 알람 규칙 파일

```yaml
# prometheus/alert.rules.yml
groups:
  - name: application-alerts
    rules:
      # 높은 에러율
      - alert: HighErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          /
          sum(rate(http_server_requests_seconds_count[5m]))
          > 0.05
        for: 2m
        labels:
          severity: critical
          team: backend
        annotations:
          summary: "High error rate detected"
          description: |
            Error rate is {{ printf "%.2f" $value | mul 100 }}%
            Service: {{ $labels.job }}
            Instance: {{ $labels.instance }}
          runbook_url: "https://wiki.example.com/runbooks/high-error-rate"

      # 느린 응답 시간
      - alert: HighLatency
        expr: |
          histogram_quantile(0.99,
            sum by (le, job) (rate(http_server_requests_seconds_bucket[5m]))
          ) > 1
        for: 5m
        labels:
          severity: warning
          team: backend
        annotations:
          summary: "High latency detected"
          description: |
            99th percentile latency is {{ printf "%.2f" $value }}s
            Service: {{ $labels.job }}

      # 서비스 다운
      - alert: ServiceDown
        expr: up == 0
        for: 1m
        labels:
          severity: critical
          team: platform
        annotations:
          summary: "Service is down"
          description: |
            {{ $labels.job }} on {{ $labels.instance }} is down
          runbook_url: "https://wiki.example.com/runbooks/service-down"

  - name: infrastructure-alerts
    rules:
      # 높은 CPU 사용률
      - alert: HighCPUUsage
        expr: |
          process_cpu_usage > 0.8
        for: 5m
        labels:
          severity: warning
          team: platform
        annotations:
          summary: "High CPU usage"
          description: "CPU usage is {{ printf \"%.2f\" $value | mul 100 }}%"

      # 높은 메모리 사용률
      - alert: HighMemoryUsage
        expr: |
          jvm_memory_used_bytes{area="heap"}
          /
          jvm_memory_max_bytes{area="heap"}
          > 0.9
        for: 5m
        labels:
          severity: warning
          team: backend
        annotations:
          summary: "High JVM heap memory usage"
          description: "Heap usage is {{ printf \"%.2f\" $value | mul 100 }}%"

      # 디스크 공간 부족
      - alert: DiskSpaceLow
        expr: |
          (node_filesystem_avail_bytes / node_filesystem_size_bytes) < 0.1
        for: 10m
        labels:
          severity: warning
          team: platform
        annotations:
          summary: "Low disk space"
          description: "Available disk space is {{ printf \"%.2f\" $value | mul 100 }}%"

  - name: business-alerts
    rules:
      # 주문 실패 급증
      - alert: HighOrderFailureRate
        expr: |
          sum(rate(orders_failed_total[5m]))
          /
          sum(rate(orders_created_total[5m]))
          > 0.1
        for: 3m
        labels:
          severity: critical
          team: order
        annotations:
          summary: "High order failure rate"
          description: "Order failure rate is {{ printf \"%.2f\" $value | mul 100 }}%"

      # 결제 지연
      - alert: PaymentProcessingDelay
        expr: |
          histogram_quantile(0.95,
            sum by (le) (rate(payment_process_duration_seconds_bucket[5m]))
          ) > 5
        for: 5m
        labels:
          severity: warning
          team: payment
        annotations:
          summary: "Payment processing is slow"
          description: "95th percentile payment processing time is {{ $value }}s"
```

---

## 5. Alertmanager 설정

### 기본 설정

```yaml
# alertmanager/alertmanager.yml
global:
  resolve_timeout: 5m
  slack_api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
  smtp_smarthost: 'smtp.gmail.com:587'
  smtp_from: 'alertmanager@example.com'
  smtp_auth_username: 'your-email@gmail.com'
  smtp_auth_password: 'your-app-password'

# 라우팅 규칙
route:
  # 기본 수신자
  receiver: 'default-receiver'

  # 그룹핑 설정
  group_by: ['alertname', 'job', 'severity']
  group_wait: 30s       # 첫 알림 전 대기 시간
  group_interval: 5m    # 그룹 내 알림 간격
  repeat_interval: 4h   # 동일 알림 반복 간격

  # 하위 라우트
  routes:
    # Critical 알람 → PagerDuty + Slack
    - match:
        severity: critical
      receiver: 'critical-receiver'
      continue: true

    # 팀별 라우팅
    - match:
        team: backend
      receiver: 'backend-team'

    - match:
        team: platform
      receiver: 'platform-team'

    - match:
        team: payment
      receiver: 'payment-team'

# 억제 규칙
inhibit_rules:
  # ServiceDown 발생 시 해당 서비스의 다른 알람 억제
  - source_match:
      alertname: 'ServiceDown'
    target_match_re:
      alertname: '.+'
    equal: ['job', 'instance']

# 수신자 정의
receivers:
  - name: 'default-receiver'
    slack_configs:
      - channel: '#alerts'
        send_resolved: true
        title: '{{ .Status | toUpper }}: {{ .CommonAnnotations.summary }}'
        text: '{{ .CommonAnnotations.description }}'

  - name: 'critical-receiver'
    slack_configs:
      - channel: '#incidents'
        send_resolved: true
        color: '{{ if eq .Status "firing" }}danger{{ else }}good{{ end }}'
        title: '🚨 {{ .Status | toUpper }}: {{ .CommonAnnotations.summary }}'
        text: |
          *Description:* {{ .CommonAnnotations.description }}
          *Runbook:* {{ .CommonAnnotations.runbook_url }}
    pagerduty_configs:
      - service_key: 'YOUR_PAGERDUTY_SERVICE_KEY'
        severity: critical

  - name: 'backend-team'
    slack_configs:
      - channel: '#backend-alerts'
        send_resolved: true
    email_configs:
      - to: 'backend-team@example.com'

  - name: 'platform-team'
    slack_configs:
      - channel: '#platform-alerts'
        send_resolved: true

  - name: 'payment-team'
    slack_configs:
      - channel: '#payment-alerts'
        send_resolved: true
    pagerduty_configs:
      - service_key: 'PAYMENT_PAGERDUTY_KEY'
```

---

## 6. 알람 채널 설정

### Slack 알림

```yaml
# Slack 수신자 상세 설정
receivers:
  - name: 'slack-notifications'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/XXX/YYY/ZZZ'
        channel: '#alerts'
        send_resolved: true

        # 메시지 제목
        title: '{{ template "slack.default.title" . }}'

        # 메시지 본문
        text: |
          {{ range .Alerts }}
          *Alert:* {{ .Annotations.summary }}
          *Severity:* {{ .Labels.severity }}
          *Description:* {{ .Annotations.description }}
          *Details:*
            {{ range .Labels.SortedPairs }}• *{{ .Name }}:* `{{ .Value }}`
            {{ end }}
          {{ end }}

        # 색상
        color: '{{ if eq .Status "firing" }}danger{{ else }}good{{ end }}'

        # 액션 버튼
        actions:
          - type: button
            text: 'Runbook 📖'
            url: '{{ (index .Alerts 0).Annotations.runbook_url }}'
          - type: button
            text: 'Dashboard 📊'
            url: 'http://grafana:3000/d/xxx'
          - type: button
            text: 'Silence 🔇'
            url: '{{ template "__alertmanagerURL" . }}/#/silences/new?filter=%7B{{ range .CommonLabels.SortedPairs }}{{ .Name }}%3D{{ .Value }}%2C{{ end }}%7D'
```

### Email 알림

```yaml
receivers:
  - name: 'email-notifications'
    email_configs:
      - to: 'ops-team@example.com'
        send_resolved: true
        headers:
          Subject: '[{{ .Status | toUpper }}] {{ .CommonAnnotations.summary }}'
        html: |
          <h2>{{ .CommonAnnotations.summary }}</h2>
          <p>{{ .CommonAnnotations.description }}</p>

          <h3>Alert Details:</h3>
          <table border="1">
            <tr><th>Label</th><th>Value</th></tr>
            {{ range .CommonLabels.SortedPairs }}
            <tr><td>{{ .Name }}</td><td>{{ .Value }}</td></tr>
            {{ end }}
          </table>

          <p><a href="{{ .CommonAnnotations.runbook_url }}">View Runbook</a></p>
```

### Webhook (커스텀 알림)

```yaml
receivers:
  - name: 'webhook-notifications'
    webhook_configs:
      - url: 'http://alert-handler:8080/webhook'
        send_resolved: true
        http_config:
          bearer_token: 'YOUR_TOKEN'
```

```java
// Spring Boot에서 Webhook 수신
@RestController
@RequestMapping("/webhook")
@Slf4j
public class AlertWebhookController {

    @PostMapping
    public ResponseEntity<Void> handleAlert(@RequestBody AlertmanagerPayload payload) {
        log.info("Received alert: {}", payload);

        for (Alert alert : payload.getAlerts()) {
            if ("firing".equals(alert.getStatus())) {
                handleFiringAlert(alert);
            } else {
                handleResolvedAlert(alert);
            }
        }

        return ResponseEntity.ok().build();
    }

    private void handleFiringAlert(Alert alert) {
        // 커스텀 알림 처리 (예: 카카오톡, 텔레그램 등)
        String message = String.format("[%s] %s\n%s",
                alert.getLabels().get("severity"),
                alert.getAnnotations().get("summary"),
                alert.getAnnotations().get("description"));

        // kakaoService.sendMessage(message);
        // telegramService.sendMessage(message);
    }
}

@Data
public class AlertmanagerPayload {
    private String status;
    private List<Alert> alerts;
    private Map<String, String> commonLabels;
    private Map<String, String> commonAnnotations;
}

@Data
public class Alert {
    private String status;
    private Map<String, String> labels;
    private Map<String, String> annotations;
    private String startsAt;
    private String endsAt;
}
```

---

## 7. 알람 관리 전략

### 알람 피로(Alert Fatigue) 방지

```
┌─────────────────────────────────────────────────────────────────────┐
│                    알람 피로 방지 전략                                │
│                                                                      │
│   1. 임계치 조정                                                    │
│      • 너무 민감하지 않게                                           │
│      • 실제 영향이 있는 수준으로                                    │
│                                                                      │
│   2. 적절한 for 기간                                                │
│      • 일시적 스파이크 무시                                         │
│      • 지속적인 문제만 알림                                         │
│                                                                      │
│   3. 그룹핑 활용                                                    │
│      • 유사 알람을 하나로                                           │
│      • 알림 수 줄이기                                               │
│                                                                      │
│   4. 억제(Inhibition) 활용                                          │
│      • 상위 문제 발생 시 하위 알람 억제                              │
│      • 중복 알림 방지                                               │
│                                                                      │
│   5. 우선순위 구분                                                  │
│      • Critical: 즉시 대응 필요                                     │
│      • Warning: 업무 시간 내 확인                                   │
│      • Info: 참고용                                                 │
│                                                                      │
│   6. 정기적인 리뷰                                                  │
│      • 무시되는 알람 제거                                           │
│      • 임계치 조정                                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### 심각도 수준 정의

```yaml
# 심각도별 대응 방침
# severity: critical
#   - 즉시 대응 필요
#   - 24/7 On-call 호출
#   - 15분 내 응답 필요
#   - 채널: PagerDuty + Slack #incidents

# severity: warning
#   - 업무 시간 내 확인
#   - 24시간 내 해결
#   - 채널: Slack #alerts + Email

# severity: info
#   - 참고용 알림
#   - 트렌드 모니터링
#   - 채널: Slack #monitoring
```

### 점검 시간 알람 무시 (Silencing)

```bash
# Alertmanager API로 Silence 생성
curl -X POST http://localhost:9093/api/v2/silences \
  -H "Content-Type: application/json" \
  -d '{
    "matchers": [
      {"name": "job", "value": "order-service", "isRegex": false}
    ],
    "startsAt": "2024-01-15T02:00:00Z",
    "endsAt": "2024-01-15T04:00:00Z",
    "createdBy": "admin",
    "comment": "Scheduled maintenance"
  }'
```

---

## 8. Grafana 알람 (선택적)

### Grafana 알람 규칙

```yaml
# Grafana에서도 알람 설정 가능
# grafana/provisioning/alerting/rules.yml

apiVersion: 1

groups:
  - orgId: 1
    name: application-alerts
    folder: Alerts
    interval: 1m
    rules:
      - uid: high-error-rate
        title: High Error Rate
        condition: C
        data:
          - refId: A
            relativeTimeRange:
              from: 300
              to: 0
            datasourceUid: prometheus
            model:
              expr: sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))
          - refId: C
            datasourceUid: __expr__
            model:
              type: threshold
              expression: A
              conditions:
                - evaluator:
                    type: gt
                    params: [0.05]
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: High error rate detected
```

---

## 9. 실전 예제: 전체 설정

### 완성된 알람 시스템 구조

```
┌─────────────────────────────────────────────────────────────────────┐
│                    완성된 알람 시스템                                 │
│                                                                      │
│   prometheus/                                                       │
│   ├── prometheus.yml                                                │
│   └── alert.rules.yml                                               │
│       ├── application-alerts (에러율, 지연시간, 서비스 상태)        │
│       ├── infrastructure-alerts (CPU, 메모리, 디스크)               │
│       └── business-alerts (주문 실패율, 결제 지연)                  │
│                                                                      │
│   alertmanager/                                                     │
│   └── alertmanager.yml                                              │
│       ├── routing rules (심각도별, 팀별)                            │
│       ├── inhibit rules (상위 문제 시 하위 억제)                    │
│       └── receivers (Slack, Email, PagerDuty, Webhook)              │
│                                                                      │
│   알림 흐름:                                                        │
│   1. Prometheus가 메트릭 수집                                       │
│   2. Alert Rules 평가 (매 15초)                                     │
│   3. 조건 충족 시 Alertmanager로 전송                               │
│   4. Alertmanager가 라우팅, 그룹핑, 억제 처리                       │
│   5. 적절한 채널로 알림 전송                                        │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 10. 실습 과제

### 과제 1: 기본 알람 설정
1. Docker로 Prometheus + Alertmanager 실행
2. 기본 알람 규칙 작성 (에러율, 지연시간)
3. Alertmanager 설정 (기본 라우팅)

### 과제 2: 알람 채널 설정
1. Slack Webhook 연동
2. Email 알림 설정
3. 심각도별 라우팅 구성

### 과제 3: 고급 설정
1. 그룹핑 규칙 설정
2. 억제(Inhibition) 규칙 추가
3. 점검 시간 Silence 설정

### 과제 4: 비즈니스 알람
1. 주문 실패율 알람
2. 결제 처리 시간 알람
3. 커스텀 메트릭 기반 알람

### 체크리스트
```
[ ] Docker로 Alertmanager 실행
[ ] Prometheus alerting 설정
[ ] 기본 Alert Rules 작성
[ ] Alertmanager 라우팅 설정
[ ] Slack 알림 설정
[ ] Email 알림 설정
[ ] 심각도별 라우팅 구성
[ ] 그룹핑 설정
[ ] 억제 규칙 추가
[ ] Silence 테스트
[ ] 비즈니스 알람 추가
```

---

## 참고 자료

- [Prometheus Alerting](https://prometheus.io/docs/alerting/latest/overview/)
- [Alertmanager 설정](https://prometheus.io/docs/alerting/latest/configuration/)
- [Alertmanager Notification Templates](https://prometheus.io/docs/alerting/latest/notifications/)
- [Grafana Alerting](https://grafana.com/docs/grafana/latest/alerting/)
- [PagerDuty Integration](https://www.pagerduty.com/docs/guides/prometheus-integration-guide/)

---

## Phase 2-B 학습 완료!

축하합니다! Phase 2-B의 모든 학습 자료를 완료했습니다.

### 학습 내용 요약

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Phase 2-B 학습 완료 요약                          │
│                                                                      │
│   01. Redis 기초        - 캐싱, 자료구조, Spring 연동                │
│   02. Redis Stream      - 메시지 큐, Consumer Group                  │
│   03. Redisson          - 분산 락, 동시성 제어                       │
│   04. Outbox 패턴       - 이벤트 발행 신뢰성                         │
│   05. OpenTelemetry     - 분산 추적, Zipkin                          │
│   06. Prometheus/Grafana- 메트릭 모니터링                            │
│   07. Loki              - 중앙 집중식 로깅                           │
│   08. Alertmanager      - 알람 시스템                                │
│                                                                      │
│   이제 다음 단계로 넘어갈 준비가 되었습니다!                          │
└─────────────────────────────────────────────────────────────────────┘
```

### 다음 단계

[Phase 3: Temporal 개념](../phase3/01-temporal-concepts.md)으로 이동하여 Temporal 워크플로우 엔진을 학습하세요!
