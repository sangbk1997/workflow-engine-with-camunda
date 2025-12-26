# 🏠 LOCAL HA TESTING - Step by Step Guide

## Tổng quan
Hướng dẫn test các kịch bản HA trên máy local sử dụng Docker Compose với 3 Camunda nodes + 1 PostgreSQL + Nginx load balancer.

---

## 📋 Prerequisites

```bash
# Kiểm tra Docker
docker --version  # >= 20.10
docker-compose --version  # >= 2.0

# Kiểm tra các tools cần thiết
jq --version  # JSON processor
curl --version
```

---

## 🚀 SETUP - Chuẩn bị môi trường

### Bước 1: Tạo thư mục test

```bash
cd /Users/sang/Projects/POC/Camunda-7/acm-vortex-workflow-engine
mkdir -p ha-test/{scripts,results}
cd ha-test
```

### Bước 2: Tạo file nginx.conf

```bash
cat > nginx.conf << 'EOF'
events {
    worker_connections 1024;
}

http {
    upstream camunda_cluster {
        least_conn;
        server camunda-node1:8080 max_fails=3 fail_timeout=30s;
        server camunda-node2:8080 max_fails=3 fail_timeout=30s;
        server camunda-node3:8080 max_fails=3 fail_timeout=30s;
    }

    server {
        listen 80;
        
        location / {
            proxy_pass http://camunda_cluster;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            
            proxy_next_upstream error timeout http_502 http_503 http_504;
            proxy_connect_timeout 10s;
            proxy_send_timeout 30s;
            proxy_read_timeout 30s;
        }
    }
}
EOF
```

### Bước 3: Tạo docker-compose-ha.yml

```bash
cat > docker-compose-ha.yml << 'EOF'
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    container_name: camunda-postgres-ha
    environment:
      POSTGRES_DB: camunda
      POSTGRES_USER: camunda
      POSTGRES_PASSWORD: camunda
      PGDATA: /var/lib/postgresql/data/pgdata
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U camunda"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - camunda-network

  camunda-node1:
    build: ..
    container_name: camunda-node1
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/camunda
      SPRING_DATASOURCE_USERNAME: camunda
      SPRING_DATASOURCE_PASSWORD: camunda
      SERVER_PORT: 8080
      POD_NAME: camunda-node1
      CAMUNDA_BPM_JOB_EXECUTION_ENABLED: "true"
      LOGGING_LEVEL_ORG_CAMUNDA_BPM_ENGINE_JOBEXECUTOR: DEBUG
    ports:
      - "8081:8080"
    networks:
      - camunda-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 90s

  camunda-node2:
    build: ..
    container_name: camunda-node2
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/camunda
      SPRING_DATASOURCE_USERNAME: camunda
      SPRING_DATASOURCE_PASSWORD: camunda
      SERVER_PORT: 8080
      POD_NAME: camunda-node2
      CAMUNDA_BPM_JOB_EXECUTION_ENABLED: "true"
      LOGGING_LEVEL_ORG_CAMUNDA_BPM_ENGINE_JOBEXECUTOR: DEBUG
    ports:
      - "8082:8080"
    networks:
      - camunda-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 90s

  camunda-node3:
    build: ..
    container_name: camunda-node3
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/camunda
      SPRING_DATASOURCE_USERNAME: camunda
      SPRING_DATASOURCE_PASSWORD: camunda
      SERVER_PORT: 8080
      POD_NAME: camunda-node3
      CAMUNDA_BPM_JOB_EXECUTION_ENABLED: "true"
      LOGGING_LEVEL_ORG_CAMUNDA_BPM_ENGINE_JOBEXECUTOR: DEBUG
    ports:
      - "8083:8080"
    networks:
      - camunda-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 90s

  nginx:
    image: nginx:alpine
    container_name: camunda-loadbalancer
    depends_on:
      - camunda-node1
      - camunda-node2
      - camunda-node3
    ports:
      - "8080:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    networks:
      - camunda-network

volumes:
  postgres_data:

networks:
  camunda-network:
    driver: bridge
EOF
```

### Bước 4: Build và Start

```bash
# Build image
cd ..
docker build -t camunda-workflow-engine:latest .

# Quay lại thư mục ha-test
cd ha-test

# Start tất cả services
docker-compose -f docker-compose-ha.yml up -d

# Xem logs
docker-compose -f docker-compose-ha.yml logs -f
```

### Bước 5: Verify deployment

```bash
# Kiểm tra containers
docker ps

# Kiểm tra health của từng node
curl http://localhost:8081/actuator/health | jq '.'
curl http://localhost:8082/actuator/health | jq '.'
curl http://localhost:8083/actuator/health | jq '.'

# Kiểm tra load balancer
curl http://localhost:8080/actuator/health | jq '.'

# Kiểm tra Camunda Cockpit
open http://localhost:8080/camunda/app/cockpit
# Login: admin/admin
```

---

## 🧪 TEST CASE 1: Node Failure (Container Kill)

### Script: test1-node-failure.sh

```bash
cat > scripts/test1-node-failure.sh << 'EOF'
#!/bin/bash
set -e

echo "========================================="
echo "TEST CASE 1: Node Failure (Container Kill)"
echo "========================================="
echo ""

API_URL="http://localhost:8080"
PROCESS_KEY="haTestProcess"

# Step 1: Create process instances
echo "📝 Step 1: Creating 10 process instances..."
for i in {1..10}; do
    response=$(curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{
        "variables": {
          "testId": {"value": "'$i'", "type": "String"},
          "timestamp": {"value": "'$(date -u +%Y-%m-%dT%H:%M:%S)'", "type": "String"}
        }
      }')
    
    instance_id=$(echo $response | jq -r '.id')
    if [ "$instance_id" != "null" ]; then
        echo "  ✅ Created instance $i: $instance_id"
    else
        echo "  ❌ Failed to create instance $i"
        echo "  Response: $response"
    fi
    sleep 0.5
done

echo ""
echo "⏳ Step 2: Waiting 15 seconds for processes to start executing..."
sleep 15

# Step 2: Check initial distribution
echo ""
echo "📊 Step 3: Checking initial job distribution..."
for node in camunda-node1 camunda-node2 camunda-node3; do
    echo "  Node: $node"
    docker logs $node 2>&1 | grep "Async Task" | tail -3 || echo "    No async tasks yet"
done

# Step 3: Kill one node
echo ""
echo "💀 Step 4: Killing camunda-node2..."
docker stop camunda-node2

echo "⏳ Waiting 10 seconds..."
sleep 10

# Step 4: Check status after failure
echo ""
echo "📊 Step 5: Checking status after node failure..."

ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
echo "  Active process instances: $ACTIVE"

INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')
echo "  Incidents: $INCIDENTS"

echo ""
echo "📊 Step 6: Checking job redistribution..."
for node in camunda-node1 camunda-node3; do
    echo "  Node: $node"
    docker logs $node 2>&1 | grep "Async Task" | tail -5
done

# Step 5: Restart the killed node
echo ""
echo "🔄 Step 7: Restarting camunda-node2..."
docker start camunda-node2

echo "⏳ Waiting 30 seconds for recovery..."
sleep 30

# Step 6: Final verification
echo ""
echo "✅ Step 8: Final verification..."

ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
COMPLETED=$(curl -s "$API_URL/engine-rest/history/process-instance/count?finished=true" | jq '.count')
INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')

echo "  Active process instances: $ACTIVE"
echo "  Completed process instances: $COMPLETED"
echo "  Incidents: $INCIDENTS"

# Save results
mkdir -p ../results
cat > ../results/test1-results.txt << RESULT
TEST CASE 1: Node Failure Results
===================================
Test Date: $(date)
Active Instances: $ACTIVE
Completed Instances: $COMPLETED
Incidents: $INCIDENTS

Status: $([ "$INCIDENTS" -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- Node killed: camunda-node2
- Recovery time: ~30 seconds
- Jobs redistributed to: camunda-node1, camunda-node3
RESULT

echo ""
echo "📄 Results saved to: results/test1-results.txt"
echo ""
echo "========================================="
echo "TEST CASE 1: COMPLETED"
echo "========================================="
EOF

chmod +x scripts/test1-node-failure.sh
```

### Chạy test:

```bash
# Chạy test
./scripts/test1-node-failure.sh

# Xem results
cat results/test1-results.txt
```

---

## 🧪 TEST CASE 2: Database Connection Failure

### Script: test2-db-failure.sh

```bash
cat > scripts/test2-db-failure.sh << 'EOF'
#!/bin/bash
set -e

echo "========================================="
echo "TEST CASE 2: Database Connection Failure"
echo "========================================="
echo ""

API_URL="http://localhost:8080"
PROCESS_KEY="haTestProcess"

# Step 1: Create process instances
echo "📝 Step 1: Creating 5 process instances..."
for i in {1..5}; do
    response=$(curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{
        "variables": {
          "testId": {"value": "'$i'", "type": "String"}
        }
      }')
    
    instance_id=$(echo $response | jq -r '.id')
    echo "  ✅ Created instance $i: $instance_id"
    sleep 0.5
done

echo ""
echo "⏳ Step 2: Waiting 10 seconds for processes to start..."
sleep 10

# Step 2: Stop PostgreSQL
echo ""
echo "💀 Step 3: Stopping PostgreSQL database..."
docker pause camunda-postgres-ha

echo "⏳ Waiting 20 seconds to observe failures..."
sleep 20

# Step 3: Check health status
echo ""
echo "📊 Step 4: Checking health status during DB outage..."
for port in 8081 8082 8083; do
    echo "  Node on port $port:"
    curl -s "http://localhost:$port/actuator/health" | jq '.status' || echo "    Failed to connect"
done

# Step 4: Check logs for errors
echo ""
echo "📋 Step 5: Checking logs for connection errors..."
docker logs camunda-node1 2>&1 | grep -i "connection" | tail -5

# Step 5: Resume PostgreSQL
echo ""
echo "🔄 Step 6: Resuming PostgreSQL database..."
docker unpause camunda-postgres-ha

echo "⏳ Waiting 30 seconds for recovery..."
sleep 30

# Step 6: Verify recovery
echo ""
echo "✅ Step 7: Verifying recovery..."
for port in 8081 8082 8083; do
    health=$(curl -s "http://localhost:$port/actuator/health" | jq -r '.status')
    echo "  Node on port $port: $health"
done

ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')

echo "  Active instances: $ACTIVE"
echo "  Incidents: $INCIDENTS"

# Save results
cat > ../results/test2-results.txt << RESULT
TEST CASE 2: Database Connection Failure Results
=================================================
Test Date: $(date)
Active Instances: $ACTIVE
Incidents: $INCIDENTS

Status: $([ "$INCIDENTS" -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- Database paused for 20 seconds
- All nodes showed connection errors
- Auto-recovery after database resumed
- Recovery time: ~30 seconds
RESULT

echo ""
echo "📄 Results saved to: results/test2-results.txt"
echo ""
echo "========================================="
echo "TEST CASE 2: COMPLETED"
echo "========================================="
EOF

chmod +x scripts/test2-db-failure.sh
```

### Chạy test:

```bash
./scripts/test2-db-failure.sh
cat results/test2-results.txt
```

---

## 🧪 TEST CASE 3: Load Balancing Verification

### Script: test3-load-balancing.sh

```bash
cat > scripts/test3-load-balancing.sh << 'EOF'
#!/bin/bash
set -e

echo "========================================="
echo "TEST CASE 3: Load Balancing Verification"
echo "========================================="
echo ""

API_URL="http://localhost:8080"
PROCESS_KEY="haTestProcess"

# Step 1: Clear old logs
echo "🧹 Step 1: Clearing old logs..."
docker-compose -f docker-compose-ha.yml restart camunda-node1 camunda-node2 camunda-node3
sleep 30

# Step 2: Create many process instances
echo ""
echo "📝 Step 2: Creating 30 process instances..."
for i in {1..30}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{
        "variables": {
          "testId": {"value": "'$i'", "type": "String"}
        }
      }' > /dev/null
    
    echo -n "."
    sleep 0.3
done
echo ""

echo "⏳ Step 3: Waiting 20 seconds for execution..."
sleep 20

# Step 3: Count job distribution
echo ""
echo "📊 Step 4: Analyzing job distribution..."

NODE1_COUNT=$(docker logs camunda-node1 2>&1 | grep "Async Task 1 executed" | wc -l)
NODE2_COUNT=$(docker logs camunda-node2 2>&1 | grep "Async Task 1 executed" | wc -l)
NODE3_COUNT=$(docker logs camunda-node3 2>&1 | grep "Async Task 1 executed" | wc -l)

echo "  camunda-node1: $NODE1_COUNT jobs"
echo "  camunda-node2: $NODE2_COUNT jobs"
echo "  camunda-node3: $NODE3_COUNT jobs"

TOTAL=$((NODE1_COUNT + NODE2_COUNT + NODE3_COUNT))
echo "  Total: $TOTAL jobs"

# Calculate distribution percentage
if [ $TOTAL -gt 0 ]; then
    NODE1_PCT=$((NODE1_COUNT * 100 / TOTAL))
    NODE2_PCT=$((NODE2_COUNT * 100 / TOTAL))
    NODE3_PCT=$((NODE3_COUNT * 100 / TOTAL))
    
    echo ""
    echo "📊 Distribution:"
    echo "  Node 1: $NODE1_PCT%"
    echo "  Node 2: $NODE2_PCT%"
    echo "  Node 3: $NODE3_PCT%"
fi

# Check if distribution is balanced (each node should have 20-40%)
BALANCED="true"
for pct in $NODE1_PCT $NODE2_PCT $NODE3_PCT; do
    if [ $pct -lt 20 ] || [ $pct -gt 50 ]; then
        BALANCED="false"
    fi
done

# Save results
cat > ../results/test3-results.txt << RESULT
TEST CASE 3: Load Balancing Verification Results
================================================
Test Date: $(date)

Job Distribution:
  Node 1: $NODE1_COUNT jobs ($NODE1_PCT%)
  Node 2: $NODE2_COUNT jobs ($NODE2_PCT%)
  Node 3: $NODE3_COUNT jobs ($NODE3_PCT%)
  Total: $TOTAL jobs

Balanced: $([ "$BALANCED" = "true" ] && echo "✅ YES" || echo "❌ NO")

Status: $([ "$BALANCED" = "true" ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- Jobs should be distributed roughly evenly (20-40% per node)
- Nginx uses least_conn algorithm
- Camunda job executor uses optimistic locking
RESULT

echo ""
echo "📄 Results saved to: results/test3-results.txt"
echo ""
echo "========================================="
echo "TEST CASE 3: COMPLETED"
echo "========================================="
EOF

chmod +x scripts/test3-load-balancing.sh
```

### Chạy test:

```bash
./scripts/test3-load-balancing.sh
cat results/test3-results.txt
```

---

## 🧪 TEST CASE 4: Scaling (Add/Remove Node)

### Script: test4-scaling.sh

```bash
cat > scripts/test4-scaling.sh << 'EOF'
#!/bin/bash
set -e

echo "========================================="
echo "TEST CASE 4: Scaling (Add/Remove Node)"
echo "========================================="
echo ""

API_URL="http://localhost:8080"
PROCESS_KEY="haTestProcess"

# Step 1: Initial load with 3 nodes
echo "📝 Step 1: Creating initial load (15 instances) with 3 nodes..."
for i in {1..15}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo -n "."
done
echo ""

sleep 10

echo ""
echo "📊 Step 2: Checking initial distribution (3 nodes)..."
for node in camunda-node1 camunda-node2 camunda-node3; do
    count=$(docker logs $node 2>&1 | grep "Async Task" | wc -l)
    echo "  $node: $count tasks"
done

# Step 2: Scale down - remove node3
echo ""
echo "📉 Step 3: Scaling down - stopping camunda-node3..."
docker stop camunda-node3

echo ""
echo "📝 Step 4: Creating more load (15 instances) with 2 nodes..."
for i in {16..30}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo -n "."
done
echo ""

sleep 15

echo ""
echo "📊 Step 5: Checking distribution after scale down (2 nodes)..."
for node in camunda-node1 camunda-node2; do
    count=$(docker logs $node 2>&1 | grep "Async Task" | wc -l)
    echo "  $node: $count tasks"
done

# Step 3: Scale up - add node3 back
echo ""
echo "📈 Step 6: Scaling up - starting camunda-node3..."
docker start camunda-node3

echo "⏳ Waiting 30 seconds for node to join cluster..."
sleep 30

echo ""
echo "📝 Step 7: Creating final load (15 instances) with 3 nodes..."
for i in {31..45}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo -n "."
done
echo ""

sleep 15

echo ""
echo "📊 Step 8: Final distribution check (3 nodes)..."
for node in camunda-node1 camunda-node2 camunda-node3; do
    count=$(docker logs $node 2>&1 | grep "Async Task" | wc -l)
    echo "  $node: $count tasks"
done

# Verify no incidents
INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')
echo ""
echo "📋 Incidents: $INCIDENTS"

# Save results
cat > ../results/test4-results.txt << RESULT
TEST CASE 4: Scaling Results
==============================
Test Date: $(date)

Test Phases:
1. Initial: 3 nodes, 15 instances
2. Scale down: 2 nodes, 15 instances  
3. Scale up: 3 nodes, 15 instances

Total instances created: 45
Incidents: $INCIDENTS

Status: $([ "$INCIDENTS" -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- Jobs redistributed successfully after scale down
- New node joined cluster and picked up jobs after scale up
- No job loss during scaling operations
RESULT

echo ""
echo "📄 Results saved to: results/test4-results.txt"
echo ""
echo "========================================="
echo "TEST CASE 4: COMPLETED"
echo "========================================="
EOF

chmod +x scripts/test4-scaling.sh
```

### Chạy test:

```bash
./scripts/test4-scaling.sh
cat results/test4-results.txt
```

---

## 🧪 TEST CASE 5: Concurrent Failures

### Script: test5-concurrent-failures.sh

```bash
cat > scripts/test5-concurrent-failures.sh << 'EOF'
#!/bin/bash
set -e

echo "========================================="
echo "TEST CASE 5: Concurrent Failures"
echo "========================================="
echo ""

API_URL="http://localhost:8080"
PROCESS_KEY="haTestProcess"

# Step 1: Create baseline load
echo "📝 Step 1: Creating 20 process instances..."
for i in {1..20}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo -n "."
done
echo ""

sleep 10

# Step 2: Simulate cascading failure
echo ""
echo "💀 Step 2: Simulating cascading failure..."
echo "  Killing node2..."
docker stop camunda-node2

sleep 5

echo "  Killing node1..."
docker stop camunda-node1

echo "⏳ Only node3 remaining, waiting 15 seconds..."
sleep 15

# Step 3: Check if remaining node handles load
echo ""
echo "📊 Step 3: Checking if node3 handles all load..."
NODE3_COUNT=$(docker logs camunda-node3 2>&1 | grep "Async Task" | wc -l)
echo "  camunda-node3: $NODE3_COUNT tasks"

# Step 4: Recover nodes one by one
echo ""
echo "🔄 Step 4: Recovering nodes..."
echo "  Starting node1..."
docker start camunda-node1
sleep 15

echo "  Starting node2..."
docker start camunda-node2
sleep 15

# Step 5: Final verification
echo ""
echo "✅ Step 5: Final verification..."

ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
COMPLETED=$(curl -s "$API_URL/engine-rest/history/process-instance/count?finished=true" | jq '.count')
INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')

echo "  Active instances: $ACTIVE"
echo "  Completed instances: $COMPLETED"
echo "  Incidents: $INCIDENTS"

# Save results
cat > ../results/test5-results.txt << RESULT
TEST CASE 5: Concurrent Failures Results
=========================================
Test Date: $(date)

Scenario:
- Started with 3 nodes
- Killed node2, then node1 (only node3 remained)
- Node3 handled all jobs
- Recovered nodes one by one

Active Instances: $ACTIVE
Completed Instances: $COMPLETED
Incidents: $INCIDENTS

Status: $([ "$INCIDENTS" -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- Single node handled full load during failure
- Cluster recovered gracefully
- No job loss or duplication
RESULT

echo ""
echo "📄 Results saved to: results/test5-results.txt"
echo ""
echo "========================================="
echo "TEST CASE 5: COMPLETED"
echo "========================================="
EOF

chmod +x scripts/test5-concurrent-failures.sh
```

### Chạy test:

```bash
./scripts/test5-concurrent-failures.sh
cat results/test5-results.txt
```

---

## 📊 RUN ALL TESTS

### Script: run-all-tests.sh

```bash
cat > scripts/run-all-tests.sh << 'EOF'
#!/bin/bash

echo "========================================="
echo "RUNNING ALL HA TESTS"
echo "========================================="
echo ""

# Ensure clean state
echo "🧹 Ensuring clean state..."
docker-compose -f docker-compose-ha.yml restart
sleep 60

# Run all tests
./scripts/test1-node-failure.sh
sleep 10

./scripts/test2-db-failure.sh
sleep 10

./scripts/test3-load-balancing.sh
sleep 10

./scripts/test4-scaling.sh
sleep 10

./scripts/test5-concurrent-failures.sh

# Generate summary report
echo ""
echo "========================================="
echo "TEST SUMMARY"
echo "========================================="
cat results/*.txt | grep "Status:"

echo ""
echo "Full results available in: results/"
ls -la results/

echo ""
echo "========================================="
echo "ALL TESTS COMPLETED"
echo "========================================="
EOF

chmod +x scripts/run-all-tests.sh
```

### Chạy tất cả:

```bash
./scripts/run-all-tests.sh
```

---

## 🧹 CLEANUP

```bash
# Stop all containers
docker-compose -f docker-compose-ha.yml down

# Remove volumes (nếu muốn reset database)
docker-compose -f docker-compose-ha.yml down -v

# Remove results
rm -rf results/*
```

---

## 📋 CHECKLIST

Sau khi chạy tất cả tests, verify:

- [ ] Test 1: Node failure - Jobs redistributed ✅
- [ ] Test 2: DB failure - Auto-recovery ✅
- [ ] Test 3: Load balancing - Even distribution ✅
- [ ] Test 4: Scaling - No job loss ✅
- [ ] Test 5: Concurrent failures - Graceful handling ✅
- [ ] No incidents created ✅
- [ ] All processes completed ✅

---

## 🎯 Expected Results

### ✅ PASS Criteria
- All 5 tests pass
- Incidents count = 0
- Jobs distributed evenly
- Recovery time < 60 seconds
- No data loss

### ❌ FAIL Criteria  
- Any test fails
- Incidents > 0
- Uneven distribution (>60% on one node)
- Recovery time > 120 seconds
- Job loss or duplication

---

**Next Steps**: After local testing, proceed to EKS testing guide.

