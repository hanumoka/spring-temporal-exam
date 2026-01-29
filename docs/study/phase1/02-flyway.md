# Flyway - DB 마이그레이션

## 이 문서에서 배우는 것

- DB 마이그레이션이 무엇이고 왜 필요한지 이해
- Flyway의 동작 원리 파악
- Spring Boot에서 Flyway 설정 방법
- 마이그레이션 스크립트 작성법
- 무료 버전(Community Edition) 제약사항과 대응 전략

---

## 1. DB 마이그레이션이란?

### 문제 상황: DB 스키마 변경

개발하다 보면 DB 스키마가 자주 변경됩니다:

```
[1주차] 테이블 생성
CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    product_id BIGINT,
    quantity INT
);

[2주차] 컬럼 추가
ALTER TABLE orders ADD COLUMN customer_id BIGINT;

[3주차] 인덱스 추가
CREATE INDEX idx_orders_customer ON orders(customer_id);
```

### 마이그레이션 없이 발생하는 문제

```
[문제 1] 팀원 간 DB 불일치
개발자 A: customer_id 컬럼 있음
개발자 B: customer_id 컬럼 없음 (추가 안 함)
→ "내 PC에서는 되는데요?" 🤦

[문제 2] 운영 배포 누락
개발 서버: 스키마 변경 적용됨
운영 서버: 스키마 변경 누락
→ 배포 후 에러 발생 🔥

[문제 3] 변경 이력 추적 불가
"이 컬럼 언제 추가했더라?"
"누가 이 인덱스 삭제했지?"
→ 히스토리 없음 😱
```

### DB 마이그레이션의 정의

**DB 마이그레이션**은 데이터베이스 스키마의 버전을 관리하는 것입니다:

```
마이그레이션 도구의 역할:
1. 스키마 변경을 버전별 파일로 관리
2. 어떤 버전까지 적용되었는지 추적
3. 적용되지 않은 변경만 자동 실행
```

---

## 2. Flyway 소개

### Flyway란?

**Flyway**는 가장 널리 사용되는 DB 마이그레이션 도구입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                         Flyway                               │
│                                                              │
│  ┌─────────────┐    ┌─────────────────────────────────────┐ │
│  │ 마이그레이션 │───▶│ V1__create_orders.sql               │ │
│  │   스크립트   │    │ V2__add_customer_id.sql             │ │
│  │             │    │ V3__add_index.sql                   │ │
│  └─────────────┘    └─────────────────────────────────────┘ │
│          │                                                   │
│          ▼                                                   │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                    flyway_schema_history                ││
│  │  ┌─────────┬──────────────────────────┬────────────┐   ││
│  │  │ version │ script                   │ installed  │   ││
│  │  ├─────────┼──────────────────────────┼────────────┤   ││
│  │  │ 1       │ V1__create_orders.sql    │ 2024-01-01 │   ││
│  │  │ 2       │ V2__add_customer_id.sql  │ 2024-01-15 │   ││
│  │  │ 3       │ V3__add_index.sql        │ 2024-02-01 │   ││
│  │  └─────────┴──────────────────────────┴────────────┘   ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### 동작 원리

1. **애플리케이션 시작 시** Flyway가 자동 실행
2. **flyway_schema_history 테이블** 확인 (적용된 버전 기록)
3. **마이그레이션 폴더**에서 스크립트 파일 스캔
4. **적용되지 않은 스크립트**만 순서대로 실행
5. **실행 결과**를 history 테이블에 기록

```
[시나리오: 새로운 개발자가 프로젝트 참여]

1. 프로젝트 clone
2. 애플리케이션 실행
3. Flyway가 V1~V10 스크립트 자동 실행
4. DB 스키마가 최신 상태로 자동 구성!

→ 별도의 DB 설정 없이 바로 개발 시작 가능
```

---

## 3. Spring Boot + Flyway 설정

### 3.1 의존성 추가

```groovy
// build.gradle
dependencies {
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-mysql'  // MySQL 사용 시
    runtimeOnly 'com.mysql:mysql-connector-j'
}
```

**주의**: MySQL 8.x 이상에서는 `flyway-mysql` 의존성이 필수입니다.

### 3.2 application.yml 설정

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/order_db
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver

  flyway:
    enabled: true                    # Flyway 활성화 (기본값: true)
    locations: classpath:db/migration  # 마이그레이션 파일 위치
    baseline-on-migrate: true        # 기존 DB에 Flyway 적용 시 필요
    validate-on-migrate: true        # 실행 전 스크립트 검증
```

### 3.3 마이그레이션 파일 위치

```
src/
└── main/
    └── resources/
        └── db/
            └── migration/           ← 여기에 SQL 파일 저장
                ├── V1__create_orders_table.sql
                ├── V2__add_customer_id.sql
                └── V3__create_index.sql
```

---

## 4. 마이그레이션 스크립트 작성

### 4.1 파일 네이밍 규칙

Flyway는 **파일 이름으로 버전을 인식**합니다:

```
V{버전}__{설명}.sql

예시:
V1__create_orders_table.sql
V2__add_customer_id.sql
V3__create_index.sql
V10__add_status_column.sql
```

**규칙**:
| 부분 | 설명 | 예시 |
|------|------|------|
| V | 버전 마이그레이션 표시 (필수) | V |
| {버전} | 숫자 버전 (순서대로 실행) | 1, 2, 10 |
| __ | 언더스코어 2개 (필수 구분자) | __ |
| {설명} | 변경 내용 설명 | create_orders_table |
| .sql | SQL 파일 확장자 | .sql |

**버전 순서 예시**:
```
V1__  →  V2__  →  V3__  →  V10__  →  V11__
(숫자 순서대로 실행, V10이 V2보다 나중)
```

### 4.2 스크립트 작성 예시

**V1__create_orders_table.sql**
```sql
-- 주문 테이블 생성
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 코멘트 추가
ALTER TABLE orders COMMENT '주문 테이블';
```

**V2__add_customer_id.sql**
```sql
-- 고객 ID 컬럼 추가
ALTER TABLE orders ADD COLUMN customer_id BIGINT NOT NULL AFTER product_id;

-- 외래키 제약조건 (customers 테이블이 있다면)
-- ALTER TABLE orders ADD CONSTRAINT fk_orders_customer
--     FOREIGN KEY (customer_id) REFERENCES customers(id);
```

**V3__create_indexes.sql**
```sql
-- 인덱스 생성
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created_at ON orders(created_at);
```

---

## 5. 마이그레이션 타입

### 5.1 Versioned Migration (V)

**가장 일반적인 타입**. 한 번만 실행되고 변경 불가.

```
V1__create_table.sql    ← 버전 1
V2__add_column.sql      ← 버전 2
```

**특징**:
- 순서대로 한 번만 실행
- 실행 후 수정하면 에러 (checksum 불일치)
- DDL 변경에 사용

### 5.2 Repeatable Migration (R)

**반복 실행 가능**. 파일이 변경될 때마다 재실행.

```
R__create_views.sql     ← 변경될 때마다 실행
R__stored_procedures.sql
```

**특징**:
- 버전이 없음 (R__ 로 시작)
- 파일 내용이 변경되면 다시 실행
- View, Stored Procedure 등에 사용

### 5.3 Undo Migration (U) - 유료 버전

```
U1__undo_create_table.sql  ← V1의 롤백 스크립트
```

**특징**:
- Flyway Teams 버전에서만 사용 가능
- 무료 버전에서는 직접 롤백 스크립트 작성 필요

---

## 6. 무료 버전(Community Edition) 제약사항과 대응 전략

### 6.1 무료 vs 유료 기능 비교

| 기능 | Community (무료) | Teams/Enterprise (유료) |
|------|------------------|------------------------|
| 기본 마이그레이션 (V) | ✅ | ✅ |
| 반복 마이그레이션 (R) | ✅ | ✅ |
| **Undo 마이그레이션 (U)** | ❌ | ✅ |
| **Dry Run (미리보기)** | ❌ | ✅ |
| **Cherry Pick (선택 적용)** | ❌ | ✅ |
| **Git 기반 버전 관리** | ❌ | ✅ |
| 여러 DB 스키마 | 제한적 | ✅ |

---

### 6.2 이슈 1: Undo(롤백) 기능 없음

**문제 상황**
```
V5__add_phone_column.sql 배포 후 문제 발생!
→ 롤백하고 싶지만 Undo 마이그레이션 사용 불가
```

**대응 전략: 수동 롤백 스크립트 관리**

```
db/migration/
├── V5__add_phone_column.sql
└── rollback/                    ← 별도 폴더에 롤백 스크립트 관리
    └── V5__add_phone_column_rollback.sql
```

```sql
-- V5__add_phone_column.sql (적용)
ALTER TABLE users ADD COLUMN phone VARCHAR(20);

-- rollback/V5__add_phone_column_rollback.sql (롤백용, 수동 실행)
ALTER TABLE users DROP COLUMN phone;
```

**롤백 실행 방법**
```bash
# 1. 롤백 스크립트 수동 실행
mysql -u root -p orderdb < rollback/V5__add_phone_column_rollback.sql

# 2. flyway_schema_history에서 해당 버전 삭제
DELETE FROM flyway_schema_history WHERE version = '5';
```

**자동화 스크립트 예시**
```bash
#!/bin/bash
# rollback.sh

VERSION=$1
ROLLBACK_FILE="src/main/resources/db/rollback/V${VERSION}__*_rollback.sql"

if [ -f $ROLLBACK_FILE ]; then
    mysql -u $DB_USER -p$DB_PASS $DB_NAME < $ROLLBACK_FILE
    mysql -u $DB_USER -p$DB_PASS $DB_NAME -e \
        "DELETE FROM flyway_schema_history WHERE version = '${VERSION}';"
    echo "Rollback V${VERSION} completed"
else
    echo "Rollback file not found: $ROLLBACK_FILE"
fi
```

---

### 6.3 이슈 2: Dry Run 없음 (실행 전 미리보기 불가)

**문제 상황**
```
운영 DB에 마이그레이션 적용 전, 어떤 SQL이 실행될지 확인하고 싶음
→ Dry Run 기능이 유료
```

**대응 전략 1: flywayInfo로 대기 중인 마이그레이션 확인**
```bash
./gradlew flywayInfo

# 출력 예시:
# +-----------+---------+---------------------+--------+
# | Category  | Version | Description         | State  |
# +-----------+---------+---------------------+--------+
# | Versioned | 1       | create users        | Success|
# | Versioned | 2       | add email           | Success|
# | Versioned | 3       | create orders       | Pending| ← 아직 미적용
# +-----------+---------+---------------------+--------+
```

**대응 전략 2: 테스트 DB에서 먼저 실행**
```yaml
# application-staging.yml
spring:
  datasource:
    url: jdbc:mysql://staging-db:3306/orderdb
```

```bash
# 스테이징에서 먼저 테스트
./gradlew flywayMigrate -Pprofile=staging

# 문제 없으면 운영 적용
./gradlew flywayMigrate -Pprofile=prod
```

**대응 전략 3: 커스텀 Dry Run 구현**
```java
@Component
@Profile("dryrun")
public class FlywayDryRunLogger implements FlywayMigrationStrategy {

    @Override
    public void migrate(Flyway flyway) {
        // 마이그레이션 실행 안 하고 정보만 출력
        MigrationInfo[] pending = flyway.info().pending();

        System.out.println("=== Pending Migrations (Dry Run) ===");
        for (MigrationInfo info : pending) {
            System.out.println("Version: " + info.getVersion());
            System.out.println("Description: " + info.getDescription());
            System.out.println("Script: " + info.getScript());
            System.out.println("---");
        }

        // 실제 마이그레이션 실행 안 함
        System.out.println("Dry run completed. No changes applied.");
    }
}
```

---

### 6.4 이슈 3: 마이그레이션 실패 시 복구 어려움

**문제 상황**
```sql
-- V5__complex_migration.sql
ALTER TABLE orders ADD COLUMN discount DECIMAL(10,2);  -- 성공
UPDATE orders SET discount = 0;                         -- 성공
ALTER TABLE orders ADD CONSTRAINT chk CHECK (discount >= 0);  -- 실패!

-- 중간에 실패하면 부분 적용 상태로 남음
```

**대응 전략 1: 트랜잭션 활용 (DDL 지원 DB)**
```sql
-- PostgreSQL은 DDL도 트랜잭션 지원
BEGIN;
ALTER TABLE orders ADD COLUMN discount DECIMAL(10,2);
UPDATE orders SET discount = 0;
ALTER TABLE orders ADD CONSTRAINT chk CHECK (discount >= 0);
COMMIT;
```

> ⚠️ **MySQL은 DDL에 암시적 커밋** - 트랜잭션으로 묶어도 각 DDL마다 자동 커밋됨

**대응 전략 2: 마이그레이션 분리 (MySQL 권장)**
```
-- 큰 마이그레이션을 작은 단위로 분리
V5_1__add_discount_column.sql
V5_2__set_discount_default.sql
V5_3__add_discount_constraint.sql

→ 실패 지점 명확, 롤백 범위 최소화
```

**대응 전략 3: flywayRepair 사용**
```bash
# 실패한 마이그레이션 기록 정리
./gradlew flywayRepair

# 수동으로 DB 정리 후 다시 시도
./gradlew flywayMigrate
```

---

### 6.5 이슈 4: Cherry Pick 불가 (특정 버전만 선택 적용)

**문제 상황**
```
V1, V2, V3, V4 중에서 V3만 건너뛰고 싶음
→ Cherry Pick이 유료 기능
```

**대응 전략 1: outOfOrder 옵션**
```yaml
spring:
  flyway:
    out-of-order: true  # 순서 무시하고 누락된 버전 적용 허용
```

**대응 전략 2: 조건부 마이그레이션**
```sql
-- V3__optional_feature.sql
-- 특정 조건에서만 실행되도록 작성

SET @feature_enabled = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_name = 'feature_flags');

-- 조건부 실행 (프로시저 활용)
DELIMITER //
CREATE PROCEDURE conditional_migration()
BEGIN
    IF @feature_enabled > 0 THEN
        ALTER TABLE orders ADD COLUMN feature_x_data JSON;
    END IF;
END //
DELIMITER ;

CALL conditional_migration();
DROP PROCEDURE conditional_migration;
```

**대응 전략 3: 환경별 마이그레이션 분리**
```
db/migration/
├── common/           ← 모든 환경 공통
│   ├── V1__create_users.sql
│   └── V2__create_orders.sql
├── dev/              ← 개발 환경만
│   └── V100__test_data.sql
└── prod/             ← 운영 환경만
    └── V100__prod_indexes.sql
```

```yaml
# application-dev.yml
spring:
  flyway:
    locations:
      - classpath:db/migration/common
      - classpath:db/migration/dev

# application-prod.yml
spring:
  flyway:
    locations:
      - classpath:db/migration/common
      - classpath:db/migration/prod
```

---

### 6.6 이슈 5: 대용량 테이블 마이그레이션

**문제 상황**
```sql
-- 1억 건 테이블에 컬럼 추가
ALTER TABLE huge_table ADD COLUMN new_col VARCHAR(100);
-- → 테이블 락, 서비스 중단!
```

**대응 전략 1: pt-online-schema-change (Percona)**
```bash
# 무중단 스키마 변경 도구
pt-online-schema-change \
  --alter "ADD COLUMN new_col VARCHAR(100)" \
  D=orderdb,t=huge_table \
  --execute
```

**대응 전략 2: 단계별 마이그레이션**
```sql
-- V10_1__add_column_nullable.sql
-- 1단계: NULL 허용 컬럼 추가 (빠름)
ALTER TABLE huge_table ADD COLUMN new_col VARCHAR(100) NULL;

-- V10_2__backfill_data.sql
-- 2단계: 배치로 데이터 채우기 (점진적)
-- 애플리케이션에서 배치 작업으로 처리

-- V10_3__add_not_null.sql
-- 3단계: NOT NULL 제약 추가 (데이터 채운 후)
ALTER TABLE huge_table MODIFY new_col VARCHAR(100) NOT NULL;
```

**대응 전략 3: gh-ost (GitHub Online Schema Change)**
```bash
# GitHub에서 개발한 무중단 스키마 변경 도구
gh-ost \
  --alter="ADD COLUMN new_col VARCHAR(100)" \
  --database=orderdb \
  --table=huge_table \
  --execute
```

---

### 6.7 무료 버전 운영 체크리스트

```
[ ] 모든 마이그레이션에 대응하는 롤백 스크립트 작성
[ ] 롤백 스크립트 테스트 (개발 환경에서)
[ ] 스테이징 환경에서 먼저 마이그레이션 테스트
[ ] 대용량 테이블 변경은 pt-online-schema-change 또는 gh-ost 고려
[ ] 마이그레이션은 가능한 작은 단위로 분리
[ ] 운영 배포 전 flywayInfo로 대기 마이그레이션 확인
[ ] 실패 시 복구 절차 문서화
[ ] 환경별 마이그레이션 분리 (dev/staging/prod)
```

---

## 7. 실무 베스트 프랙티스

### 7.1 스크립트 작성 원칙

```sql
-- ✓ 좋은 예: 멱등성 있는 스크립트
CREATE TABLE IF NOT EXISTS orders (...);

-- ✗ 나쁜 예: 테이블 있으면 에러
CREATE TABLE orders (...);
```

```sql
-- ✓ 좋은 예: 컬럼 존재 체크
SET @exist := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_name = 'orders' AND column_name = 'customer_id');
SET @sql := IF(@exist = 0,
    'ALTER TABLE orders ADD COLUMN customer_id BIGINT',
    'SELECT "Column already exists"');
PREPARE stmt FROM @sql;
EXECUTE stmt;

-- 또는 MySQL 8에서는 IF NOT EXISTS 사용
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_id BIGINT;
```

### 7.2 대용량 테이블 변경 주의

```sql
-- ⚠️ 주의: 대용량 테이블에서는 락 발생
ALTER TABLE orders ADD COLUMN new_column VARCHAR(100);

-- ✓ 권장: pt-online-schema-change 사용 (별도 도구)
-- 또는 새 테이블 생성 + 데이터 복사 + 테이블 교체
```

### 7.3 환경별 마이그레이션 분리

```
resources/
└── db/
    └── migration/
        ├── common/          ← 공통 (모든 환경)
        │   └── V1__create_tables.sql
        ├── dev/             ← 개발 환경 전용
        │   └── V100__insert_test_data.sql
        └── prod/            ← 운영 환경 전용
            └── V100__add_production_indexes.sql
```

```yaml
# application-dev.yml
spring:
  flyway:
    locations:
      - classpath:db/migration/common
      - classpath:db/migration/dev

# application-prod.yml
spring:
  flyway:
    locations:
      - classpath:db/migration/common
      - classpath:db/migration/prod
```

### 7.4 롤백 전략

무료 버전에서는 Undo가 없으므로 **새로운 마이그레이션으로 롤백**:

```sql
-- V5__add_temporary_column.sql
ALTER TABLE orders ADD COLUMN temp_data VARCHAR(100);

-- 문제 발생! 롤백 필요

-- V6__remove_temporary_column.sql (롤백용 새 버전)
ALTER TABLE orders DROP COLUMN temp_data;
```

---

## 8. 문제 해결

### 8.1 Checksum Mismatch 에러

```
FlywayException: Migration checksum mismatch for migration version 1
-> Applied to database: 1234567890
-> Resolved locally: 9876543210
```

**원인**: 이미 실행된 스크립트 파일을 수정함

**해결**:
```bash
# 방법 1: repair 명령으로 checksum 재계산
./gradlew flywayRepair

# 방법 2: 히스토리 테이블 직접 수정 (주의!)
UPDATE flyway_schema_history SET checksum = NULL WHERE version = '1';
```

**예방**: 실행된 스크립트는 절대 수정하지 않기!

### 8.2 Version Already Applied 에러

```
FlywayException: Found non-empty schema(s) "order_db" without schema history table
```

**원인**: 기존 DB에 Flyway를 처음 적용할 때

**해결**:
```yaml
spring:
  flyway:
    baseline-on-migrate: true  # 기존 스키마를 baseline으로 설정
    baseline-version: 0        # baseline 버전
```

### 8.3 Out of Order 에러

```
FlywayException: Validate failed: Migration V3 was applied out of order
```

**원인**: V1, V3 적용 후 V2 추가 (순서 뒤바뀜)

**해결**:
```yaml
spring:
  flyway:
    out-of-order: true  # 순서 무시하고 적용 (권장하지 않음)
```

**권장**: 새로운 버전 번호로 다시 작성

---

## 9. 우리 프로젝트 적용

### 각 서비스별 마이그레이션 구조

```
service-order/
└── src/main/resources/
    └── db/migration/
        ├── V1__create_orders_table.sql
        └── V2__add_order_items_table.sql

service-inventory/
└── src/main/resources/
    └── db/migration/
        ├── V1__create_products_table.sql
        └── V2__create_inventory_table.sql

service-payment/
└── src/main/resources/
    └── db/migration/
        ├── V1__create_payments_table.sql
        └── V2__add_payment_methods.sql
```

### 주문 서비스 예시

**V1__create_orders_table.sql**
```sql
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_number VARCHAR(50) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_orders_customer (customer_id),
    INDEX idx_orders_status (status),
    INDEX idx_orders_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주문';
```

**V2__create_order_items_table.sql**
```sql
CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price DECIMAL(15, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders(id)
        ON DELETE CASCADE,

    INDEX idx_order_items_order (order_id),
    INDEX idx_order_items_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주문 상품';
```

---

## 10. Gradle 태스크

Spring Boot와 별개로 Gradle에서 Flyway를 직접 실행할 수도 있습니다:

```groovy
// build.gradle
plugins {
    id 'org.flywaydb.flyway' version '10.8.1'
}

flyway {
    url = 'jdbc:mysql://localhost:3306/order_db'
    user = 'root'
    password = 'password'
    locations = ['classpath:db/migration']
}
```

```bash
# 마이그레이션 실행
./gradlew flywayMigrate

# 현재 상태 확인
./gradlew flywayInfo

# 스키마 전체 삭제 (주의!)
./gradlew flywayClean

# checksum 재계산
./gradlew flywayRepair

# 마이그레이션 검증
./gradlew flywayValidate
```

---

## 11. 실습 가이드 (Step-by-Step)

### 환경 정보

```
MySQL Host: localhost:21306
Database: order_db
User: app_user
Password: app1234
```

---

### Step 1: Flyway 의존성 추가

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | Gradle 빌드 파일에 Flyway 라이브러리 의존성 추가 |
| **Why** | Spring Boot가 시작할 때 Flyway가 자동으로 DB 마이그레이션을 실행하도록 함 |
| **Structure** | `flyway-core`: 핵심 마이그레이션 엔진<br>`flyway-mysql`: MySQL 특화 기능 (MySQL 8.x 필수) |
| **How** | Spring Boot 자동 설정이 classpath에서 Flyway를 감지하면 `FlywayAutoConfiguration`이 활성화됨 |

#### 🔧 작업 내용

**파일**: `service-order/build.gradle`

```groovy
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation project(':common')

    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

    // Flyway 추가
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-mysql'

    runtimeOnly 'com.mysql:mysql-connector-j'
}
```

> 💡 **왜 flyway-mysql이 별도로 필요한가?**
> MySQL 8.x부터 `caching_sha2_password` 인증 방식이 기본값이 되면서, Flyway가 MySQL 전용 드라이버 확장이 필요해졌습니다.

---

### Step 2: application.yml 생성

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | Spring Boot 애플리케이션 설정 파일 생성 |
| **Why** | DB 연결 정보, JPA 설정, Flyway 설정을 외부화하여 환경별로 다르게 적용 가능 |
| **Structure** | YAML 계층 구조로 설정을 그룹화 (spring.datasource, spring.jpa, spring.flyway) |
| **How** | Spring Boot가 시작 시 classpath의 application.yml을 읽어 `Environment`에 바인딩 |

#### 🔧 작업 내용

**파일**: `service-order/src/main/resources/application.yml`

```yaml
server:
  port: 8081

spring:
  application:
    name: service-order

  datasource:
    url: jdbc:mysql://localhost:21306/order_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
    username: app_user
    password: app1234
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: validate    # Flyway가 스키마 관리하므로 Hibernate는 검증만
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

#### 📖 설정 상세 설명

| 설정 | 값 | 설명 |
|------|-----|------|
| `server.port` | 8081 | 주문 서비스 포트 (서비스별로 다르게 설정) |
| `ddl-auto: validate` | validate | Flyway가 DDL 관리, Hibernate는 Entity와 테이블 일치 검증만 |
| `flyway.locations` | classpath:db/migration | 마이그레이션 SQL 파일 위치 |
| `baseline-on-migrate` | true | 기존 DB에 Flyway 최초 적용 시 baseline 자동 생성 |

> 💡 **왜 ddl-auto를 validate로 설정하나?**
> - `create`, `update`: Hibernate가 스키마 자동 변경 → **운영에서 위험**
> - `validate`: Flyway가 스키마 관리, Hibernate는 Entity 매핑 검증만 → **안전**

---

### Step 3: 마이그레이션 폴더 생성

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | Flyway 마이그레이션 SQL 파일을 저장할 폴더 생성 |
| **Why** | Flyway가 이 위치에서 버전 순서대로 SQL 파일을 찾아 실행 |
| **Structure** | `src/main/resources/db/migration/` (Spring Boot 기본 경로) |
| **How** | 앱 시작 시 Flyway가 이 경로를 스캔하여 `flyway_schema_history` 테이블과 비교 후 미적용 스크립트 실행 |

#### 🔧 작업 내용

**폴더 구조 생성**:

```
service-order/
└── src/
    └── main/
        └── resources/
            ├── application.yml        ← Step 2에서 생성
            └── db/
                └── migration/         ← 이 폴더 생성
```

> 💡 **왜 이 경로인가?**
> Spring Boot의 Flyway 자동 설정이 기본적으로 `classpath:db/migration`을 스캔합니다.
> 다른 경로를 원하면 `spring.flyway.locations`에서 변경 가능합니다.

---

### Step 4: 첫 번째 마이그레이션 스크립트 작성

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | 주문(orders) 테이블을 생성하는 SQL 스크립트 |
| **Why** | DB 스키마를 버전 관리하여 팀원 간 일관성 유지, 배포 자동화 |
| **Structure** | 파일명 규칙: `V{버전}__{설명}.sql` (언더스코어 2개 필수) |
| **How** | Flyway가 버전 번호 순서대로 실행, 한 번 실행된 스크립트는 다시 실행 안 됨 |

#### 🔧 작업 내용

**파일**: `service-order/src/main/resources/db/migration/V1__create_orders_table.sql`

```sql
-- 주문 테이블 생성
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '주문 ID',
    order_number VARCHAR(50) NOT NULL UNIQUE COMMENT '주문 번호',
    customer_id BIGINT NOT NULL COMMENT '고객 ID',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '주문 상태',
    total_amount DECIMAL(15, 2) NOT NULL DEFAULT 0 COMMENT '총 금액',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '낙관적 락 버전',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',

    INDEX idx_orders_customer (customer_id),
    INDEX idx_orders_status (status),
    INDEX idx_orders_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주문';
```

#### 📖 테이블 설계 의도

| 컬럼 | 목적 |
|------|------|
| `order_number` | 비즈니스 식별자 (외부 노출용, UUID 또는 규칙 기반) |
| `status` | 주문 상태 (PENDING → CONFIRMED → COMPLETED / CANCELLED) |
| `version` | JPA 낙관적 락 (`@Version`) - Phase 2-A에서 학습 |
| `INDEX` | 자주 조회하는 컬럼에 인덱스 추가로 조회 성능 향상 |

> 💡 **파일명 규칙 V1__create_orders_table.sql**
> - `V`: Versioned Migration (한 번만 실행)
> - `1`: 버전 번호 (숫자 순서대로 실행)
> - `__`: 언더스코어 2개 (필수 구분자)
> - `create_orders_table`: 설명 (가독성용, 스네이크 케이스 권장)

---

### Step 5: 애플리케이션 실행

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | Spring Boot 애플리케이션 시작 |
| **Why** | Flyway가 앱 시작 시점에 자동으로 마이그레이션 실행 |
| **How** | 1) DataSource 연결 → 2) Flyway 초기화 → 3) 마이그레이션 실행 → 4) JPA 초기화 |

#### 🔧 작업 내용

**명령어** (프로젝트 루트에서):

```bash
./gradlew :service-order:bootRun
```

**예상 로그**:

```
Flyway Community Edition 10.x.x
Database: jdbc:mysql://localhost:21306/order_db (MySQL 8.0)
Successfully validated 1 migration
Creating Schema History table `order_db`.`flyway_schema_history`
Current version of schema `order_db`: << Empty Schema >>
Migrating schema `order_db` to version "1 - create orders table"
Successfully applied 1 migration to schema `order_db`
```

#### 📖 실행 순서 이해

```
Spring Boot 시작
    │
    ▼
┌─────────────────────────────────────┐
│ 1. DataSource 빈 생성               │
│    (MySQL 연결)                     │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 2. Flyway 빈 생성 및 실행            │
│    - flyway_schema_history 확인     │
│    - 미적용 마이그레이션 실행         │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 3. JPA/Hibernate 초기화             │
│    - Entity와 테이블 매핑 검증       │
│    (ddl-auto: validate)            │
└─────────────────────────────────────┘
    │
    ▼
    앱 시작 완료
```

> 💡 **왜 Flyway가 JPA보다 먼저 실행되나?**
> Spring Boot의 `FlywayAutoConfiguration`이 `DataSourceInitializedEvent` 전에 실행되도록 설정되어 있습니다.
> 이로써 JPA가 테이블 검증할 때 이미 테이블이 존재하게 됩니다.

---

### Step 6: 테이블 생성 확인

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | 생성된 테이블과 Flyway 히스토리 확인 |
| **Why** | 마이그레이션이 정상 적용되었는지 검증 |
| **Structure** | `flyway_schema_history` 테이블이 마이그레이션 이력 관리 |
| **How** | version, checksum, installed_on 등으로 적용 이력 추적 |

#### 🔧 작업 내용

**DataGrip 또는 MySQL CLI에서 확인**:

```sql
-- 테이블 목록 확인
SHOW TABLES;

-- 예상 결과:
-- flyway_schema_history
-- orders

-- orders 테이블 구조 확인
DESC orders;

-- flyway 히스토리 확인
SELECT version, description, installed_on, success
FROM flyway_schema_history;
```

#### 📖 flyway_schema_history 테이블 구조

| 컬럼 | 설명 |
|------|------|
| `installed_rank` | 설치 순서 |
| `version` | 마이그레이션 버전 |
| `description` | 설명 (파일명에서 추출) |
| `type` | SQL, JDBC, SPRING_JDBC 등 |
| `script` | 스크립트 파일명 |
| `checksum` | 파일 내용 해시값 (변경 감지용) |
| `installed_by` | 실행한 DB 사용자 |
| `installed_on` | 실행 시각 |
| `execution_time` | 실행 소요 시간 (ms) |
| `success` | 성공 여부 (1/0) |

> 💡 **checksum의 역할**
> 이미 적용된 스크립트를 수정하면 checksum이 달라져 에러 발생 → 스크립트 변조 방지

---

### Step 7: 두 번째 마이그레이션 추가

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | 주문 상품(order_items) 테이블 생성 |
| **Why** | 1:N 관계 테이블 추가, 마이그레이션 누적 실행 이해 |
| **Structure** | 외래키(FK)로 orders 테이블과 연결 |
| **How** | Flyway가 V1 이후 V2만 실행 (이미 적용된 V1은 건너뜀) |

#### 🔧 작업 내용

**파일**: `service-order/src/main/resources/db/migration/V2__create_order_items_table.sql`

```sql
-- 주문 상품 테이블 생성
CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '주문상품 ID',
    order_id BIGINT NOT NULL COMMENT '주문 ID',
    product_id BIGINT NOT NULL COMMENT '상품 ID',
    product_name VARCHAR(200) NOT NULL COMMENT '상품명',
    quantity INT NOT NULL DEFAULT 1 COMMENT '수량',
    unit_price DECIMAL(15, 2) NOT NULL COMMENT '단가',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',

    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders(id)
        ON DELETE CASCADE,

    INDEX idx_order_items_order (order_id),
    INDEX idx_order_items_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주문 상품';
```

#### 📖 외래키 설계 포인트

| 설정 | 의미 |
|------|------|
| `FOREIGN KEY (order_id) REFERENCES orders(id)` | order_items.order_id → orders.id 참조 |
| `ON DELETE CASCADE` | 주문 삭제 시 주문 상품도 함께 삭제 |

> 💡 **ON DELETE CASCADE 주의사항**
> - 편리하지만 의도치 않은 대량 삭제 위험
> - 운영에서는 `ON DELETE RESTRICT` (삭제 방지) 또는 소프트 삭제 권장
> - 학습 목적으로 CASCADE 사용

---

### Step 8: 애플리케이션 재시작 및 확인

#### 📚 학습 포인트

| 항목 | 설명 |
|------|------|
| **What** | 앱 재시작하여 V2 마이그레이션 적용 |
| **Why** | 새로운 마이그레이션이 자동으로 적용되는지 확인 |
| **How** | Flyway가 히스토리 테이블에서 현재 버전(1) 확인 → V2만 실행 |

#### 🔧 작업 내용

```bash
# 앱 재시작
./gradlew :service-order:bootRun
```

**예상 로그**:

```
Current version of schema `order_db`: 1
Migrating schema `order_db` to version "2 - create order items table"
Successfully applied 1 migration to schema `order_db`
```

**DB 확인**:

```sql
-- flyway 히스토리 확인
SELECT version, description, installed_on, success
FROM flyway_schema_history;

-- 예상 결과:
-- | version | description              | success |
-- |---------|--------------------------|---------|
-- | 1       | create orders table      | 1       |
-- | 2       | create order items table | 1       |
```

#### 📖 Flyway 버전 관리 동작

```
앱 시작
    │
    ▼
flyway_schema_history 조회
    │
    ├── 현재 버전: 1
    │
    ▼
db/migration/ 폴더 스캔
    │
    ├── V1__create_orders_table.sql      → 이미 적용됨 (건너뜀)
    └── V2__create_order_items_table.sql → 미적용 (실행!)
    │
    ▼
V2 실행 완료 → 히스토리에 기록
```

---

### 실습 완료 체크리스트

- [ ] Flyway 의존성 추가됨
- [ ] application.yml 생성됨
- [ ] V1 마이그레이션 실행됨
- [ ] orders 테이블 생성 확인
- [ ] V2 마이그레이션 실행됨
- [ ] order_items 테이블 생성 확인
- [ ] flyway_schema_history 테이블 내용 확인

---

### 핵심 개념 정리

| 개념 | 설명 |
|------|------|
| **버전 관리** | SQL 파일로 스키마 변경 이력 관리 |
| **자동 적용** | 앱 시작 시 미적용 마이그레이션 자동 실행 |
| **멱등성** | 이미 적용된 스크립트는 다시 실행 안 함 |
| **변경 감지** | checksum으로 스크립트 변조 감지 |
| **순서 보장** | 버전 번호 순서대로 실행 |

---

## 참고 자료

- [Flyway 공식 문서](https://flywaydb.org/documentation/)
- [Spring Boot Flyway 가이드](https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.data-initialization.migration-tool.flyway)
- [Flyway Best Practices](https://flywaydb.org/documentation/concepts/migrations#best-practices)

---

## 다음 단계

[03-spring-profiles.md](./03-spring-profiles.md) - 환경별 설정으로 이동
