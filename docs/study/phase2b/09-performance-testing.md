# k6 성능 테스트

## 개요

### What (무엇인가)
k6는 Grafana Labs에서 개발한 현대적인 부하 테스트 도구입니다. JavaScript로 테스트 시나리오를 작성하고, CLI에서 실행합니다.

### Why (왜 k6인가)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    부하 테스트 도구 비교                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  도구       │  언어          │  특징                                │
│  ───────────┼────────────────┼───────────────────────────────────── │
│  JMeter     │  GUI/XML       │  범용, 무거움, QA 친화적             │
│  Gatling    │  Scala         │  고성능, 학습 곡선                   │
│  k6         │  JavaScript    │  경량, 개발자 친화적, CI/CD 통합     │
│  ───────────┼────────────────┼───────────────────────────────────── │
│                                                                      │
│  선택: k6                                                            │
│  ├── JavaScript 기반 (개발자 친숙)                                  │
│  ├── CLI 중심 (CI/CD 자연스러운 통합)                               │
│  ├── Grafana 통합 (메트릭 시각화)                                   │
│  └── 경량 (단일 바이너리)                                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 1. 설치

### 1.1 로컬 설치

```bash
# macOS
brew install k6

# Windows (Chocolatey)
choco install k6

# Docker
docker pull grafana/k6
```

### 1.2 확인

```bash
k6 version
# k6 v0.48.0
```

---

## 2. 기본 테스트 작성

### 2.1 첫 번째 테스트

```javascript
// tests/load/basic.js
import http from 'k6/http';
import { check, sleep } from 'k6';

// 테스트 설정
export const options = {
  vus: 10,           // 가상 사용자 수
  duration: '30s',   // 테스트 시간
};

// 테스트 시나리오
export default function () {
  const res = http.get('http://localhost:8080/api/health');

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 200ms': (r) => r.timings.duration < 200,
  });

  sleep(1);  // 1초 대기 (실제 사용자 행동 시뮬레이션)
}
```

### 2.2 실행

```bash
k6 run tests/load/basic.js
```

### 2.3 결과 해석

```
          /\      |‾‾| /‾‾/   /‾‾/
     /\  /  \     |  |/  /   /  /
    /  \/    \    |     (   /   ‾‾\
   /          \   |  |\  \ |  (‾)  |
  / __________ \  |__| \__\ \_____/

     execution: local
        script: tests/load/basic.js
        output: -

     scenarios: (100.00%) 1 scenario, 10 max VUs, 1m0s max duration
              → default: 10 looping VUs for 30s

     ✓ status is 200
     ✓ response time < 200ms

     checks.........................: 100.00% ✓ 600  ✗ 0
     data_received..................: 1.2 MB  40 kB/s
     data_sent......................: 480 kB  16 kB/s
     http_req_duration..............: avg=45.23ms  min=12.34ms  max=234.56ms  p(90)=78.90ms  p(95)=123.45ms
     http_reqs......................: 600     20/s
     iteration_duration.............: avg=1.05s    min=1.01s    max=1.24s
     iterations.....................: 600     20/s
     vus............................: 10      min=10 max=10
     vus_max........................: 10      min=10 max=10
```

---

## 3. Saga 부하 테스트

### 3.1 주문 생성 시나리오

```javascript
// tests/load/saga-order.js
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const sagaSuccessRate = new Rate('saga_success_rate');
const sagaDuration = new Trend('saga_duration');
const orderCreated = new Counter('orders_created');

// 부하 패턴: 단계적 증가
export const options = {
  stages: [
    { duration: '30s', target: 10 },   // Ramp up
    { duration: '1m', target: 50 },    // Peak load
    { duration: '30s', target: 100 },  // Stress
    { duration: '1m', target: 100 },   // Sustain
    { duration: '30s', target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],  // 95%가 2초 이내
    saga_success_rate: ['rate>0.95'],   // 성공률 95% 이상
    http_req_failed: ['rate<0.05'],     // 실패율 5% 미만
  },
};

const BASE_URL = 'http://localhost:8080';

// 테스트 데이터
function getRandomProduct() {
  const products = [
    { id: 1, price: 10000 },
    { id: 2, price: 25000 },
    { id: 3, price: 50000 },
  ];
  return products[Math.floor(Math.random() * products.length)];
}

export default function () {
  const product = getRandomProduct();

  group('Saga Order Creation', () => {
    const payload = JSON.stringify({
      customerId: `CUST-${__VU}-${__ITER}`,
      productId: product.id,
      quantity: Math.floor(Math.random() * 5) + 1,
      amount: product.price,
      paymentMethod: 'CARD',
    });

    const params = {
      headers: {
        'Content-Type': 'application/json',
      },
      timeout: '10s',
    };

    const startTime = Date.now();
    const res = http.post(`${BASE_URL}/api/saga/order`, payload, params);
    const duration = Date.now() - startTime;

    // 메트릭 기록
    sagaDuration.add(duration);

    const success = check(res, {
      'status is 200': (r) => r.status === 200,
      'has orderId': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.data && body.data.orderId;
        } catch {
          return false;
        }
      },
    });

    sagaSuccessRate.add(success);
    if (success) {
      orderCreated.add(1);
    }
  });

  sleep(Math.random() * 2 + 1);  // 1~3초 랜덤 대기
}
```

### 3.2 실행

```bash
k6 run tests/load/saga-order.js
```

---

## 4. 시나리오 패턴

### 4.1 스파이크 테스트

```javascript
// tests/load/spike.js
export const options = {
  stages: [
    { duration: '10s', target: 10 },   // 정상 부하
    { duration: '1s', target: 200 },   // 급격한 스파이크
    { duration: '30s', target: 200 },  // 스파이크 유지
    { duration: '10s', target: 10 },   // 정상화
    { duration: '30s', target: 10 },   // 복구 확인
  ],
};
```

### 4.2 내구성 테스트

```javascript
// tests/load/soak.js
export const options = {
  stages: [
    { duration: '5m', target: 50 },   // Ramp up
    { duration: '4h', target: 50 },   // 장시간 부하
    { duration: '5m', target: 0 },    // Ramp down
  ],
};
```

### 4.3 브레이크포인트 테스트

```javascript
// tests/load/breakpoint.js
export const options = {
  executor: 'ramping-arrival-rate',
  startRate: 10,
  timeUnit: '1s',
  preAllocatedVUs: 500,
  maxVUs: 1000,
  stages: [
    { duration: '2m', target: 10 },
    { duration: '2m', target: 50 },
    { duration: '2m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '2m', target: 500 },  // 시스템 한계 찾기
  ],
};
```

---

## 5. 동시성 테스트

### 5.1 재고 동시 차감 테스트

```javascript
// tests/load/concurrency-inventory.js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    concurrent_orders: {
      executor: 'shared-iterations',
      vus: 100,         // 100개 동시 요청
      iterations: 100,  // 총 100번 실행
      maxDuration: '30s',
    },
  },
};

const PRODUCT_ID = 1;  // 동일 상품에 동시 주문

export default function () {
  const payload = JSON.stringify({
    customerId: `CUST-${__VU}`,
    productId: PRODUCT_ID,
    quantity: 1,  // 각각 1개씩
    amount: 10000,
    paymentMethod: 'CARD',
  });

  const res = http.post('http://localhost:8080/api/saga/order', payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });
}

// 검증: 재고 100개 시작
// 기대: 100개 주문 중 100개 성공 (재고 0)
// 분산 락 미적용 시: 100개 초과 성공 가능 (데이터 불일치)
```

---

## 6. CI/CD 통합

### 6.1 GitHub Actions

```yaml
# .github/workflows/load-test.yml
name: Load Test

on:
  schedule:
    - cron: '0 2 * * *'  # 매일 새벽 2시
  workflow_dispatch:      # 수동 실행

jobs:
  load-test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Start Services
        run: docker-compose up -d

      - name: Wait for Services
        run: sleep 30

      - name: Run k6 Load Test
        uses: grafana/k6-action@v0.3.1
        with:
          filename: tests/load/saga-order.js
          flags: --out json=results.json

      - name: Upload Results
        uses: actions/upload-artifact@v4
        with:
          name: k6-results
          path: results.json

      - name: Check Thresholds
        run: |
          if grep -q '"thresholds":{".*":{"ok":false' results.json; then
            echo "Performance thresholds not met!"
            exit 1
          fi
```

---

## 7. Grafana 연동

### 7.1 InfluxDB로 결과 저장

```bash
# InfluxDB로 메트릭 전송
k6 run --out influxdb=http://localhost:8086/k6 tests/load/saga-order.js
```

### 7.2 Grafana 대시보드

```json
// k6 대시보드 주요 패널
{
  "panels": [
    { "title": "Virtual Users", "metric": "k6_vus" },
    { "title": "Request Rate", "metric": "k6_http_reqs" },
    { "title": "Response Time (p95)", "metric": "k6_http_req_duration{quantile=\"0.95\"}" },
    { "title": "Error Rate", "metric": "k6_http_req_failed" },
    { "title": "Saga Success Rate", "metric": "k6_saga_success_rate" }
  ]
}
```

---

## 8. 핵심 학습 포인트

### 8.1 성능 테스트 체크리스트

```
┌─────────────────────────────────────────────────────────────────────┐
│                    성능 테스트 체크리스트                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  □ 기본 부하 테스트 (Normal Load)                                   │
│    └── 예상 트래픽에서 응답 시간, 에러율 확인                       │
│                                                                      │
│  □ 스파이크 테스트 (Spike Test)                                     │
│    └── 급격한 트래픽 증가 시 시스템 동작 확인                       │
│                                                                      │
│  □ 내구성 테스트 (Soak Test)                                        │
│    └── 장시간 부하에서 메모리 누수, 성능 저하 확인                  │
│                                                                      │
│  □ 브레이크포인트 테스트 (Breakpoint Test)                          │
│    └── 시스템 한계점 파악                                           │
│                                                                      │
│  □ 동시성 테스트 (Concurrency Test)                                 │
│    └── 분산 락, 멱등성 정상 동작 확인                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 8.2 주요 메트릭

| 메트릭 | 설명 | 임계값 예시 |
|--------|------|------------|
| **p95 응답 시간** | 95%가 이 시간 이내 응답 | < 2초 |
| **에러율** | 실패한 요청 비율 | < 1% |
| **처리량 (TPS)** | 초당 처리 요청 수 | > 100 |
| **동시 사용자** | 최대 지원 VU 수 | > 500 |

---

## 관련 문서

- [D022 성능 테스트 전략](../../architecture/DECISIONS.md#d022-성능-테스트-전략)
- [k6 공식 문서](https://k6.io/docs/)
- [Grafana k6 대시보드](https://grafana.com/grafana/dashboards/2587)
