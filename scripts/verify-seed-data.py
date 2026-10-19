#!/usr/bin/env python3
"""在无网络、无持久卷的 MySQL 中验证演示种子，不读取项目 .env 或访问开发库。"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


ROOT = Path(__file__).resolve().parent.parent
SERVICES = {
    "marketing-control-service": "marketing_control",
    "rule-compiler-worker": "marketing_compiler",
    "audience-service": "marketing_audience",
    "offer-decision-service": "marketing_decision",
    "benefit-funding-service": "marketing_benefit",
    "event-gateway-service": "marketing_events",
    "journey-service": "marketing_journey",
    "engagement-service": "marketing_engagement",
    "measurement-service": "marketing_measurement",
}
PROTECTED_ACCOUNTS = (
    ("retail-cn", "BUDGET:PLATFORM:seed-guard:CNY"),
    ("marketing-platform", "BUDGET:PLATFORM:seed-guard:CNY"),
    ("seed-guard-tenant", "BUDGET:PLATFORM:mock-platform:CNY"),
    ("seed-guard-tenant", "BUDGET:PLATFORM:rcn-platform:CNY"),
)


def run(args, sql=None):
    """捕获输出并在失败时保留诊断；所有参数使用 argv，避免 shell 展开。"""
    result = subprocess.run(args, input=sql, text=True, capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip()


def verify(output_dir):
    """重复导入真实 SQL，并检查账户范围、主键稳定性和资金约束。"""
    container = "marketing-seed-check-" + uuid.uuid4().hex[:12]
    created = False
    checks = []

    def mysql(sql, database=None):
        args = ["docker", "exec", "-i", container, "mysql", "--host=127.0.0.1",
                "--user=root", "--batch", "--raw", "--skip-column-names",
                "--default-character-set=utf8mb4"]
        if database:
            args.append("--database=" + database)
        return run(args, sql)

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)
        print("PASS: " + name, flush=True)

    try:
        # 只复用本机镜像；无网络和端口、临时内存数据目录，不触碰共享实例。
        run(["docker", "run", "--detach", "--rm", "--pull=never", "--name", container,
             "--network=none", "--memory=1g", "--cpus=1", "--tmpfs", "/var/lib/mysql:rw",
             "--env", "MYSQL_ALLOW_EMPTY_PASSWORD=yes", "mysql:8.4.11",
             "--innodb-buffer-pool-size=128M", "--character-set-server=utf8mb4",
             "--collation-server=utf8mb4_0900_ai_ci"])
        created = True
        print("Isolated MySQL: " + container, flush=True)
        for attempt in range(120):
            try:
                mysql("SELECT 1;")
                break
            except RuntimeError:
                if attempt == 119:
                    raise RuntimeError("隔离 MySQL 未就绪：" + run(["docker", "logs", container])[-2000:])
                time.sleep(1)

        migration_count = 0
        for service, database in SERVICES.items():
            mysql(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
            directory = ROOT / "services" / service / "src/main/resources/db/migration"
            migrations = sorted(directory.glob("V*.sql"),
                                key=lambda path: int(re.match(r"V(\d+)__", path.name).group(1)))
            if not migrations:
                raise AssertionError("缺少迁移：" + service)
            for migration in migrations:
                mysql(migration.read_text(), database)
                migration_count += 1
        check(f"当前 {migration_count} 份迁移可建立种子所需的 9 个数据库", True)

        base = (ROOT / "scripts/seed-data/system-mock.sql").read_text()
        retail = base.replace("SET @tenant = 'marketing-platform';", "SET @tenant = 'retail-cn';")
        retail = retail.replace("SET @organization = 'marketing-platform';",
                                "SET @organization = 'retail-business';")
        extra = (ROOT / "scripts/seed-data/retail-cn-extra.sql").read_text()

        # 先布置跨租户及同租户非演示账户，旧的无 WHERE 回填会改变它们的分桶。
        for tenant, resource in PROTECTED_ACCOUNTS:
            mysql(f"""
                INSERT INTO marketing_benefit.mk_resource_account VALUES
                ('{tenant}','{resource}','BUDGET','CNY',101,70,20,11,0,2,73,'ACTIVE','2026-01-01T00:00:00Z');
                INSERT INTO marketing_benefit.mk_resource_escrow_bucket VALUES
                ('{tenant}','{resource}',9,101,70,20,11,0,2,73,'ACTIVE','2026-01-01T00:00:00Z');
            """)

        def protected_snapshot(table):
            clauses = " OR ".join(f"(tenant_id='{tenant}' AND resource_key='{resource}')"
                                  for tenant, resource in PROTECTED_ACCOUNTS)
            return mysql(f"SELECT * FROM marketing_benefit.{table} WHERE {clauses} ORDER BY 1,2,3;")

        before_accounts = protected_snapshot("mk_resource_account")
        before_buckets = protected_snapshot("mk_resource_escrow_bucket")
        for sql in (base, retail, extra):
            mysql(sql)
        check("首次导入不修改其他租户及同租户非演示账户",
              protected_snapshot("mk_resource_account") == before_accounts
              and protected_snapshot("mk_resource_escrow_bucket") == before_buckets)

        # 给保护账户加入真实外键关系，验证重跑不会将既有分配复制到 0 号桶。
        for index, (tenant, resource) in enumerate(PROTECTED_ACCOUNTS):
            application = "seed-guard-app-" + str(index)
            mysql(f"""
                INSERT INTO marketing_benefit.mk_promotion_application
                (tenant_id,application_id,quote_id,decision_request_id,order_id,organization_id,
                 shop_ids_json,cart_digest,token_digest,generation_no,state_name,total_discount,
                 currency_code,expires_at,created_at,updated_at,expiry_next_attempt_at)
                SELECT '{tenant}','{application}','{application}','{application}','{application}',
                       organization_id,shop_ids_json,cart_digest,token_digest,generation_no,state_name,
                       total_discount,currency_code,expires_at,created_at,updated_at,expiry_next_attempt_at
                FROM marketing_benefit.mk_promotion_application
                WHERE tenant_id='retail-cn' AND application_id='mock-pa-ha-001';
                INSERT INTO marketing_benefit.mk_reservation_item VALUES
                ('{tenant}','{application}','{resource}','BUDGET','CNY',31,20,11,0,0,2);
                INSERT INTO marketing_benefit.mk_reservation_escrow_allocation VALUES
                ('{tenant}','{application}','{resource}',9,31,20,11,0,0,2);
            """)
        before_allocations = protected_snapshot("mk_reservation_escrow_allocation")
        tables = mysql("SELECT CONCAT(table_schema,'.',table_name) FROM information_schema.tables "
                       "WHERE table_schema LIKE 'marketing\\_%' ORDER BY 1;").splitlines()

        def row_counts():
            return {table: int(mysql(f"SELECT COUNT(*) FROM {table};")) for table in tables}

        first_counts = row_counts()
        for sql in (base, retail, extra):
            mysql(sql)
        check("重复导入各表记录数不增长", first_counts == row_counts())
        check("重复导入保留非演示账户、分桶及原分配",
              protected_snapshot("mk_resource_account") == before_accounts
              and protected_snapshot("mk_resource_escrow_bucket") == before_buckets
              and protected_snapshot("mk_reservation_escrow_allocation") == before_allocations)

        check("预算账户与分桶逐项守恒", mysql("""
            SELECT COUNT(*) FROM marketing_benefit.mk_resource_account a
            LEFT JOIN (SELECT tenant_id,resource_key,SUM(authorized_amount) authorized_amount,
                SUM(available_amount) available_amount,SUM(reserved_amount) reserved_amount,
                SUM(consumed_amount) consumed_amount,SUM(returned_amount) returned_amount
                FROM marketing_benefit.mk_resource_escrow_bucket GROUP BY tenant_id,resource_key) b
              ON a.tenant_id=b.tenant_id AND a.resource_key=b.resource_key
            WHERE a.resource_type='BUDGET' AND (b.resource_key IS NULL
              OR a.authorized_amount<>b.authorized_amount OR a.available_amount<>b.available_amount
              OR a.reserved_amount<>b.reserved_amount OR a.consumed_amount<>b.consumed_amount
              OR a.returned_amount<>b.returned_amount);
        """) == "0")
        check("预算预留与分配逐项守恒", mysql("""
            SELECT COUNT(*) FROM marketing_benefit.mk_reservation_item i
            LEFT JOIN (SELECT tenant_id,application_id,resource_key,SUM(original_amount) original_amount,
                SUM(reserved_amount) reserved_amount,SUM(consumed_amount) consumed_amount,
                SUM(refunded_amount) refunded_amount,SUM(released_amount) released_amount
                FROM marketing_benefit.mk_reservation_escrow_allocation
                GROUP BY tenant_id,application_id,resource_key) a
              ON i.tenant_id=a.tenant_id AND i.application_id=a.application_id AND i.resource_key=a.resource_key
            WHERE i.resource_type='BUDGET' AND (a.resource_key IS NULL
              OR i.original_amount<>a.original_amount OR i.reserved_amount<>a.reserved_amount
              OR i.consumed_amount<>a.consumed_amount OR i.refunded_amount<>a.refunded_amount
              OR i.released_amount<>a.released_amount);
        """) == "0")
        check("两个租户的活动场景完整", mysql("""
            SELECT tenant_id,COUNT(*) FROM marketing_control.mk_campaign
            GROUP BY tenant_id ORDER BY tenant_id;
        """) == "marketing-platform\t3\nretail-cn\t7")
        check("演示预算恰好生成 6 组 16 路分桶", mysql("""
            SELECT COUNT(*) FROM (SELECT tenant_id,resource_key,COUNT(*) c
                FROM marketing_benefit.mk_resource_escrow_bucket
                GROUP BY tenant_id,resource_key HAVING c=16) counts;
        """) == "6")

        graphs = mysql("SELECT graph_json FROM marketing_control.mk_definition_version "
                       "ORDER BY tenant_id,definition_id,version_no;").splitlines()
        for graph in graphs:
            json.loads(graph)
        check("8 个已入库图定义可解析", len(graphs) == 8)
        if output_dir:
            output_dir.mkdir(parents=True, exist_ok=True)
            (output_dir / "seed-graphs.jsonl").write_text("\n".join(graphs) + "\n")
            (output_dir / "seed-result.json").write_text(json.dumps(
                {"status": "PASS", "migrations": migration_count, "checks": checks,
                 "table_count": len(tables), "row_counts": first_counts}, ensure_ascii=False, indent=2) + "\n")
        print(f"Seed validation passed ({len(checks)} checks).", flush=True)
    finally:
        # 容器属于本次测试，退出即释放其临时数据；不停止任何已有服务。
        if created:
            run(["docker", "stop", "--time=5", container])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, help="保存验证结果及图定义到指定证据目录")
    options = parser.parse_args()
    verify(options.output_dir)
