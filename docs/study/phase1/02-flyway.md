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

## 11. 실습 과제

1. `service-order` 모듈에 Flyway 의존성 추가
2. `db/migration` 폴더 생성
3. `V1__create_orders_table.sql` 작성
4. 애플리케이션 실행하여 테이블 생성 확인
5. `V2__add_column.sql` 추가하고 재시작하여 적용 확인
6. `flyway_schema_history` 테이블 내용 확인

---

## 참고 자료

- [Flyway 공식 문서](https://flywaydb.org/documentation/)
- [Spring Boot Flyway 가이드](https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.data-initialization.migration-tool.flyway)
- [Flyway Best Practices](https://flywaydb.org/documentation/concepts/migrations#best-practices)

---

## 다음 단계

[03-spring-profiles.md](./03-spring-profiles.md) - 환경별 설정으로 이동
