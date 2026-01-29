-- 데이터베이스 생성 (서비스별 분리 - MSA 원칙)
CREATE DATABASE IF NOT EXISTS order_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS inventory_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS payment_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 애플리케이션 사용자 생성
CREATE USER IF NOT EXISTS 'app_user'@'%' IDENTIFIED BY 'app1234';

-- 권한 부여
GRANT ALL PRIVILEGES ON order_db.* TO 'app_user'@'%';
GRANT ALL PRIVILEGES ON inventory_db.* TO 'app_user'@'%';
GRANT ALL PRIVILEGES ON payment_db.* TO 'app_user'@'%';

FLUSH PRIVILEGES;
