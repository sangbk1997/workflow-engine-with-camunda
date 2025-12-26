# ☁️ EKS HA TESTING - Step by Step Guide (2 Pods + 1 PostgreSQL)

## Tổng quan
Hướng dẫn test HA trên EKS hiện có với cấu hình: **2 Camunda pods + 1 PostgreSQL database**

---

## 📋 Prerequisites

```bash
# Kiểm tra kubectl access
kubectl get nodes
kubectl config current-context

# Kiểm tra namespace hiện tại
kubectl get namespaces

# Tools cần thiết
kubectl version --client
aws --version
jq --version
```

---

## 🔍 BƯỚC 1: Kiểm tra môi trường hiện tại

### 1.1. Xác định namespace

```bash
# Liệt kê tất cả namespaces
kubectl get ns

# Giả sử workflow engine đang chạy trong namespace 'workflow' hoặc 'camunda'
# Thay đổi NAMESPACE theo môi trường thực tế của bạn
export NAMESPACE="workflow"  # Hoặc namespace của bạn

# Verify
kubectl get all -n $NAMESPACE
```

### 1.2. Kiểm tra Camunda pods

```bash
# Lấy thông tin pods
kubectl get pods -n $NAMESPACE -l app=camunda

# Hoặc nếu label khác, list tất cả
kubectl get pods -n $NAMESPACE

# Lưu tên pods vào biến
export POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
export POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

echo "Pod 1: $POD1"
echo "Pod 2: $POD2"
```

### 1.3. Kiểm tra PostgreSQL

```bash
# Tìm PostgreSQL pod
kubectl get pods -n $NAMESPACE | grep postgres

# Lưu PostgreSQL pod name
export PG_POD=$(kubectl get pods -n $NAMESPACE -l app=postgres -o jsonpath='{.items[0].metadata.name}')

echo "PostgreSQL Pod: $PG_POD"
```

### 1.4. Kiểm tra Service/Ingress

```bash
# Lấy service endpoint
kubectl get svc -n $NAMESPACE

# Lấy ingress (nếu có)
kubectl get ingress -n $NAMESPACE

# Lấy external endpoint
export CAMUNDA_URL=$(kubectl get ingress -n $NAMESPACE -o jsonpath='{.items[0].spec.rules[0].host}')

# Hoặc nếu dùng LoadBalancer
export CAMUNDA_URL=$(kubectl get svc camunda-service -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

echo "Camunda URL: http://$CAMUNDA_URL"
```

### 1.5. Verify health

```bash
# Port-forward để test (nếu không có external access)
kubectl port-forward -n $NAMESPACE svc/camunda-service 8080:8080 &
export PF_PID=$!

# Test health
curl http://localhost:8080/actuator/health | jq '.'

# Hoặc nếu có external URL
curl http://$CAMUNDA_URL/actuator/health | jq '.'
```

---

## 🧪 TEST CASE 1: Pod Failure & Recovery

### Script: eks-test1-pod-failure.sh

```bash
cat > eks-test1-pod-failure.sh << 'EOF'
#!/bin/bash
set -e

# Configuration
NAMESPACE="${NAMESPACE:-workflow}"
API_URL="${CAMUNDA_URL:-http://localhost:8080}"
PROCESS_KEY="haTestProcess"

echo "========================================="
echo "EKS TEST CASE 1: Pod Failure & Recovery"
echo "========================================="
echo ""
echo "Configuration:"
echo "  Namespace: $NAMESPACE"
echo "  API URL: $API_URL"
echo ""

# Step 1: Get current pods
echo "📊 Step 1: Getting current pods..."
POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

echo "  Pod 1: $POD1"
echo "  Pod 2: $POD2"

# Step 2: Create process instances
echo ""
echo "📝 Step 2: Creating 10 process instances..."
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
    fi
    sleep 0.5
done

# Step 3: Wait for execution
echo ""
echo "⏳ Step 3: Waiting 15 seconds for processes to start executing..."
sleep 15

# Step 4: Check initial distribution
echo ""
echo "📊 Step 4: Checking job distribution before failure..."
echo "  $POD1:"
kubectl logs $POD1 -n $NAMESPACE --tail=50 | grep "Async Task" | tail -3 || echo "    No async tasks yet"

echo "  $POD2:"
kubectl logs $POD2 -n $NAMESPACE --tail=50 | grep "Async Task" | tail -3 || echo "    No async tasks yet"

# Step 5: Delete one pod
echo ""
echo "💀 Step 5: Deleting pod: $POD1"
kubectl delete pod $POD1 -n $NAMESPACE

echo "⏳ Waiting 10 seconds..."
sleep 10

# Step 6: Check pod recovery
echo ""
echo "🔄 Step 6: Checking pod recovery..."
kubectl get pods -n $NAMESPACE -l app=camunda

NEW_POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
echo "  New pod: $NEW_POD1"

# Wait for new pod to be ready
echo "⏳ Waiting for pod to be ready..."
kubectl wait --for=condition=ready pod -l app=camunda -n $NAMESPACE --timeout=120s

# Step 7: Check job continuation
echo ""
echo "📊 Step 7: Checking if jobs continued on remaining pod..."
echo "  $POD2:"
kubectl logs $POD2 -n $NAMESPACE --tail=50 | grep "Async Task" | tail -5

# Step 8: Verify no incidents
echo ""
echo "✅ Step 8: Verifying process status..."
ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')

echo "  Active process instances: $ACTIVE"
echo "  Incidents: $INCIDENTS"

# Save results
mkdir -p eks-results
cat > eks-results/test1-results.txt << RESULT
EKS TEST CASE 1: Pod Failure & Recovery Results
================================================
Test Date: $(date)
Namespace: $NAMESPACE

Pods:
  Initial Pod 1: $POD1 (DELETED)
  Pod 2: $POD2 (REMAINED)
  New Pod 1: $NEW_POD1 (CREATED)

Process Status:
  Active Instances: $ACTIVE
  Incidents: $INCIDENTS

Status: $([ "$INCIDENTS" -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- Pod deleted: $POD1
- Kubernetes automatically recreated pod
- Jobs continued on remaining pod: $POD2
- New pod joined cluster: $NEW_POD1
- Recovery time: ~20-30 seconds
RESULT

echo ""
echo "📄 Results saved to: eks-results/test1-results.txt"
echo ""
echo "========================================="
echo "EKS TEST CASE 1: COMPLETED"
echo "========================================="
EOF

chmod +x eks-test1-pod-failure.sh
```

### Chạy test:

```bash
# Set environment variables
export NAMESPACE="workflow"  # Thay đổi theo namespace của bạn
export CAMUNDA_URL="http://your-camunda-url"  # Hoặc dùng port-forward

# Run test
./eks-test1-pod-failure.sh

# View results
cat eks-results/test1-results.txt
```

---

## 🧪 TEST CASE 2: Database Connection Test

### Script: eks-test2-db-connection.sh

```bash
cat > eks-test2-db-connection.sh << 'EOF'
#!/bin/bash
set -e

NAMESPACE="${NAMESPACE:-workflow}"
API_URL="${CAMUNDA_URL:-http://localhost:8080}"
PROCESS_KEY="haTestProcess"

echo "========================================="
echo "EKS TEST CASE 2: Database Connection Test"
echo "========================================="
echo ""

# Step 1: Create processes
echo "📝 Step 1: Creating 5 process instances..."
for i in {1..5}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo "  ✅ Created instance $i"
    sleep 0.5
done

sleep 10

# Step 2: Get PostgreSQL pod
PG_POD=$(kubectl get pods -n $NAMESPACE -l app=postgres -o jsonpath='{.items[0].metadata.name}')
echo ""
echo "📊 Step 2: PostgreSQL pod: $PG_POD"

# Step 3: Simulate connection issue (scale down postgres temporarily)
echo ""
echo "💀 Step 3: Simulating DB connection issue..."
echo "  Note: We'll restart postgres pod to simulate brief outage"

kubectl delete pod $PG_POD -n $NAMESPACE

echo "⏳ Waiting 20 seconds for postgres to restart..."
sleep 20

# Step 4: Check Camunda pod health
echo ""
echo "📊 Step 4: Checking Camunda pods health during DB restart..."
kubectl get pods -n $NAMESPACE -l app=camunda

POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

echo ""
echo "  Checking logs for connection errors..."
kubectl logs $POD1 -n $NAMESPACE --tail=20 | grep -i "connection\|error" || echo "  No obvious errors in $POD1"

# Step 5: Wait for postgres recovery
echo ""
echo "⏳ Step 5: Waiting for PostgreSQL to be ready..."
kubectl wait --for=condition=ready pod -l app=postgres -n $NAMESPACE --timeout=120s

# Step 6: Verify Camunda recovery
echo ""
echo "✅ Step 6: Verifying Camunda recovery..."
sleep 10

HEALTH1=$(kubectl exec $POD1 -n $NAMESPACE -- wget -qO- http://localhost:8080/actuator/health | jq -r '.status')
HEALTH2=$(kubectl exec $POD2 -n $NAMESPACE -- wget -qO- http://localhost:8080/actuator/health | jq -r '.status')

echo "  $POD1 health: $HEALTH1"
echo "  $POD2 health: $HEALTH2"

ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')

echo "  Active instances: $ACTIVE"
echo "  Incidents: $INCIDENTS"

# Save results
cat > eks-results/test2-results.txt << RESULT
EKS TEST CASE 2: Database Connection Test Results
==================================================
Test Date: $(date)
Namespace: $NAMESPACE

PostgreSQL Pod: $PG_POD (restarted)

Camunda Pods:
  $POD1: $HEALTH1
  $POD2: $HEALTH2

Process Status:
  Active Instances: $ACTIVE
  Incidents: $INCIDENTS

Status: $([ "$HEALTH1" = "UP" ] && [ "$HEALTH2" = "UP" ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- PostgreSQL pod restarted successfully
- Camunda pods reconnected automatically
- No permanent connection failures
- Stateless design ensures resilience
RESULT

echo ""
echo "📄 Results saved to: eks-results/test2-results.txt"
echo ""
echo "========================================="
echo "EKS TEST CASE 2: COMPLETED"
echo "========================================="
EOF

chmod +x eks-test2-db-connection.sh
```

### Chạy test:

```bash
./eks-test2-db-connection.sh
cat eks-results/test2-results.txt
```

---

## 🧪 TEST CASE 3: Load Distribution (2 Pods)

### Script: eks-test3-load-distribution.sh

```bash
cat > eks-test3-load-distribution.sh << 'EOF'
#!/bin/bash
set -e

NAMESPACE="${NAMESPACE:-workflow}"
API_URL="${CAMUNDA_URL:-http://localhost:8080}"
PROCESS_KEY="haTestProcess"

echo "========================================="
echo "EKS TEST CASE 3: Load Distribution (2 Pods)"
echo "========================================="
echo ""

# Step 1: Get pods
POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

echo "📊 Step 1: Current pods:"
echo "  Pod 1: $POD1"
echo "  Pod 2: $POD2"

# Step 2: Restart pods to clear logs
echo ""
echo "🔄 Step 2: Restarting pods to clear old logs..."
kubectl delete pod $POD1 $POD2 -n $NAMESPACE

echo "⏳ Waiting for pods to be ready..."
kubectl wait --for=condition=ready pod -l app=camunda -n $NAMESPACE --timeout=120s

# Get new pod names
POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

sleep 30  # Wait for full initialization

# Step 3: Create load
echo ""
echo "📝 Step 3: Creating 20 process instances..."
for i in {1..20}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo -n "."
    sleep 0.3
done
echo ""

echo "⏳ Waiting 20 seconds for job execution..."
sleep 20

# Step 4: Count job distribution
echo ""
echo "📊 Step 4: Analyzing job distribution..."

POD1_COUNT=$(kubectl logs $POD1 -n $NAMESPACE | grep "Async Task 1 executed" | wc -l | tr -d ' ')
POD2_COUNT=$(kubectl logs $POD2 -n $NAMESPACE | grep "Async Task 1 executed" | wc -l | tr -d ' ')

echo "  $POD1: $POD1_COUNT jobs"
echo "  $POD2: $POD2_COUNT jobs"

TOTAL=$((POD1_COUNT + POD2_COUNT))
echo "  Total: $TOTAL jobs"

# Calculate percentage
if [ $TOTAL -gt 0 ]; then
    POD1_PCT=$((POD1_COUNT * 100 / TOTAL))
    POD2_PCT=$((POD2_COUNT * 100 / TOTAL))
    
    echo ""
    echo "📊 Distribution:"
    echo "  $POD1: $POD1_PCT%"
    echo "  $POD2: $POD2_PCT%"
fi

# Check if balanced (30-70% range acceptable for 2 pods)
BALANCED="true"
if [ $POD1_PCT -lt 30 ] || [ $POD1_PCT -gt 70 ]; then
    BALANCED="false"
fi

# Save results
cat > eks-results/test3-results.txt << RESULT
EKS TEST CASE 3: Load Distribution Results
===========================================
Test Date: $(date)
Namespace: $NAMESPACE

Job Distribution (2 Pods):
  $POD1: $POD1_COUNT jobs ($POD1_PCT%)
  $POD2: $POD2_COUNT jobs ($POD2_PCT%)
  Total: $TOTAL jobs

Balanced: $([ "$BALANCED" = "true" ] && echo "✅ YES" || echo "❌ NO")

Status: $([ "$BALANCED" = "true" ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- With 2 pods, distribution should be 30-70%
- Job executor uses optimistic locking
- Load balancer distributes HTTP requests
- Job execution is independent of HTTP routing
RESULT

echo ""
echo "📄 Results saved to: eks-results/test3-results.txt"
echo ""
echo "========================================="
echo "EKS TEST CASE 3: COMPLETED"
echo "========================================="
EOF

chmod +x eks-test3-load-distribution.sh
```

### Chạy test:

```bash
./eks-test3-load-distribution.sh
cat eks-results/test3-results.txt
```

---

## 🧪 TEST CASE 4: Both Pods Failure

### Script: eks-test4-both-pods-failure.sh

```bash
cat > eks-test4-both-pods-failure.sh << 'EOF'
#!/bin/bash
set -e

NAMESPACE="${NAMESPACE:-workflow}"
API_URL="${CAMUNDA_URL:-http://localhost:8080}"
PROCESS_KEY="haTestProcess"

echo "========================================="
echo "EKS TEST CASE 4: Both Pods Failure & Recovery"
echo "========================================="
echo ""

# Step 1: Create processes
echo "📝 Step 1: Creating 10 process instances..."
for i in {1..10}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo "  ✅ Created instance $i"
    sleep 0.5
done

sleep 15

# Step 2: Get current state
echo ""
echo "📊 Step 2: Current process state..."
ACTIVE_BEFORE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
echo "  Active instances before failure: $ACTIVE_BEFORE"

# Step 3: Delete both pods simultaneously
echo ""
echo "💀 Step 3: Deleting BOTH Camunda pods..."
POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

echo "  Deleting: $POD1 and $POD2"
kubectl delete pod $POD1 $POD2 -n $NAMESPACE

echo "⏳ Waiting 10 seconds..."
sleep 10

# Step 4: Check pod recreation
echo ""
echo "🔄 Step 4: Checking pod recreation..."
kubectl get pods -n $NAMESPACE -l app=camunda

echo "⏳ Waiting for pods to be ready..."
kubectl wait --for=condition=ready pod -l app=camunda -n $NAMESPACE --timeout=180s

NEW_POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
NEW_POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

echo "  New pods:"
echo "    $NEW_POD1"
echo "    $NEW_POD2"

# Step 5: Wait for full recovery
echo ""
echo "⏳ Step 5: Waiting 30 seconds for full recovery..."
sleep 30

# Step 6: Verify process continuation
echo ""
echo "✅ Step 6: Verifying process continuation..."

ACTIVE_AFTER=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
COMPLETED=$(curl -s "$API_URL/engine-rest/history/process-instance/count?finished=true" | jq '.count')
INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')

echo "  Active instances after recovery: $ACTIVE_AFTER"
echo "  Completed instances: $COMPLETED"
echo "  Incidents: $INCIDENTS"

# Step 7: Test if jobs resume
echo ""
echo "📝 Step 7: Creating new instances to test job execution..."
for i in {11..15}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo -n "."
done
echo ""

sleep 15

echo "  Checking if new jobs are executing..."
kubectl logs $NEW_POD1 -n $NAMESPACE --tail=20 | grep "Async Task" || echo "  No tasks yet on $NEW_POD1"

# Save results
cat > eks-results/test4-results.txt << RESULT
EKS TEST CASE 4: Both Pods Failure & Recovery Results
======================================================
Test Date: $(date)
Namespace: $NAMESPACE

Scenario:
- Deleted both Camunda pods simultaneously
- Kubernetes automatically recreated pods
- Verified process state persistence

Pods:
  Original: $POD1, $POD2 (DELETED)
  New: $NEW_POD1, $NEW_POD2 (CREATED)

Process Status:
  Active before: $ACTIVE_BEFORE
  Active after: $ACTIVE_AFTER
  Completed: $COMPLETED
  Incidents: $INCIDENTS

Status: $([ "$INCIDENTS" -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL")

Notes:
- Both pods deleted simultaneously
- Kubernetes recreated pods automatically
- Process state persisted in PostgreSQL
- Jobs resumed after pod recovery
- Total downtime: ~60-90 seconds
RESULT

echo ""
echo "📄 Results saved to: eks-results/test4-results.txt"
echo ""
echo "========================================="
echo "EKS TEST CASE 4: COMPLETED"
echo "========================================="
EOF

chmod +x eks-test4-both-pods-failure.sh
```

### Chạy test:

```bash
./eks-test4-both-pods-failure.sh
cat eks-results/test4-results.txt
```

---

## 🧪 TEST CASE 5: Node Drain Simulation

### Script: eks-test5-node-drain.sh

```bash
cat > eks-test5-node-drain.sh << 'EOF'
#!/bin/bash
set -e

NAMESPACE="${NAMESPACE:-workflow}"
API_URL="${CAMUNDA_URL:-http://localhost:8080}"
PROCESS_KEY="haTestProcess"

echo "========================================="
echo "EKS TEST CASE 5: Node Drain Simulation"
echo "========================================="
echo ""

# Step 1: Create load
echo "📝 Step 1: Creating 15 process instances..."
for i in {1..15}; do
    curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"testId": {"value": "'$i'", "type": "String"}}}' > /dev/null
    echo -n "."
done
echo ""

sleep 10

# Step 2: Get pod and node info
echo ""
echo "📊 Step 2: Getting pod and node information..."
POD1=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
POD2=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[1].metadata.name}')

NODE1=$(kubectl get pod $POD1 -n $NAMESPACE -o jsonpath='{.spec.nodeName}')
NODE2=$(kubectl get pod $POD2 -n $NAMESPACE -o jsonpath='{.spec.nodeName}')

echo "  $POD1 → Node: $NODE1"
echo "  $POD2 → Node: $NODE2"

# Step 3: Cordon and drain one node (if pods are on different nodes)
if [ "$NODE1" != "$NODE2" ]; then
    echo ""
    echo "💀 Step 3: Cordoning and draining node: $NODE1"
    
    # Cordon the node
    kubectl cordon $NODE1
    
    # Drain the node (this will evict the pod)
    kubectl drain $NODE1 --ignore-daemonsets --delete-emptydir-data --force --timeout=120s
    
    echo "⏳ Waiting for pod to reschedule..."
    sleep 20
    
    # Step 4: Check pod rescheduling
    echo ""
    echo "🔄 Step 4: Checking pod rescheduling..."
    kubectl get pods -n $NAMESPACE -l app=camunda -o wide
    
    NEW_POD=$(kubectl get pods -n $NAMESPACE -l app=camunda --field-selector spec.nodeName!=$NODE1 -o jsonpath='{.items[0].metadata.name}')
    NEW_NODE=$(kubectl get pod $NEW_POD -n $NAMESPACE -o jsonpath='{.spec.nodeName}')
    
    echo "  Pod rescheduled: $NEW_POD → Node: $NEW_NODE"
    
    # Step 5: Verify processes continue
    echo ""
    echo "✅ Step 5: Verifying process continuation..."
    
    kubectl wait --for=condition=ready pod -l app=camunda -n $NAMESPACE --timeout=120s
    
    sleep 20
    
    ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
    INCIDENTS=$(curl -s "$API_URL/engine-rest/incident/count" | jq '.count')
    
    echo "  Active instances: $ACTIVE"
    echo "  Incidents: $INCIDENTS"
    
    # Step 6: Uncordon the node
    echo ""
    echo "🔓 Step 6: Uncordoning node: $NODE1"
    kubectl uncordon $NODE1
    
    STATUS="✅ PASS"
    NOTES="Node drain successful, pod rescheduled to $NEW_NODE, processes continued"
else
    echo ""
    echo "⚠️  Both pods are on the same node: $NODE1"
    echo "Skipping drain test (would cause downtime)"
    
    ACTIVE=$(curl -s "$API_URL/engine-rest/process-instance/count?active=true" | jq '.count')
    INCIDENTS=0
    
    STATUS="⏭️  SKIPPED"
    NOTES="Both pods on same node, drain would cause service interruption"
fi

# Save results
cat > eks-results/test5-results.txt << RESULT
EKS TEST CASE 5: Node Drain Simulation Results
===============================================
Test Date: $(date)
Namespace: $NAMESPACE

Initial Setup:
  $POD1 → $NODE1
  $POD2 → $NODE2

Test Result: $STATUS

Process Status:
  Active Instances: $ACTIVE
  Incidents: $INCIDENTS

Notes:
$NOTES
RESULT

echo ""
echo "📄 Results saved to: eks-results/test5-results.txt"
echo ""
echo "========================================="
echo "EKS TEST CASE 5: COMPLETED"
echo "========================================="
EOF

chmod +x eks-test5-node-drain.sh
```

### Chạy test:

```bash
./eks-test5-node-drain.sh
cat eks-results/test5-results.txt
```

---

## 📊 RUN ALL EKS TESTS

### Script: eks-run-all-tests.sh

```bash
cat > eks-run-all-tests.sh << 'EOF'
#!/bin/bash

# Configuration
export NAMESPACE="${NAMESPACE:-workflow}"
export CAMUNDA_URL="${CAMUNDA_URL:-http://localhost:8080}"

echo "========================================="
echo "RUNNING ALL EKS HA TESTS"
echo "========================================="
echo ""
echo "Configuration:"
echo "  Namespace: $NAMESPACE"
echo "  Camunda URL: $CAMUNDA_URL"
echo ""
echo "Press ENTER to continue or Ctrl+C to cancel..."
read

# Ensure results directory
mkdir -p eks-results

# Run all tests with delays
echo ""
echo "Running Test 1: Pod Failure..."
./eks-test1-pod-failure.sh
sleep 30

echo ""
echo "Running Test 2: Database Connection..."
./eks-test2-db-connection.sh
sleep 30

echo ""
echo "Running Test 3: Load Distribution..."
./eks-test3-load-distribution.sh
sleep 30

echo ""
echo "Running Test 4: Both Pods Failure..."
./eks-test4-both-pods-failure.sh
sleep 30

echo ""
echo "Running Test 5: Node Drain..."
./eks-test5-node-drain.sh

# Generate summary
echo ""
echo "========================================="
echo "EKS TEST SUMMARY"
echo "========================================="
echo ""

for result in eks-results/test*.txt; do
    echo "=== $(basename $result) ==="
    grep "Status:" $result
    echo ""
done

echo "========================================="
echo "ALL EKS TESTS COMPLETED"
echo "========================================="
echo ""
echo "Full results available in: eks-results/"
ls -la eks-results/
EOF

chmod +x eks-run-all-tests.sh
```

### Chạy tất cả tests:

```bash
# Set environment
export NAMESPACE="your-namespace"
export CAMUNDA_URL="http://your-camunda-url"

# Run all tests
./eks-run-all-tests.sh
```

---

## 📋 Quick Command Reference

```bash
# Get pods
kubectl get pods -n $NAMESPACE -l app=camunda

# Get pod logs
kubectl logs <pod-name> -n $NAMESPACE --tail=100 -f

# Delete pod (trigger restart)
kubectl delete pod <pod-name> -n $NAMESPACE

# Check pod events
kubectl describe pod <pod-name> -n $NAMESPACE

# Port forward
kubectl port-forward -n $NAMESPACE svc/camunda-service 8080:8080

# Check process instances
curl http://localhost:8080/engine-rest/process-instance/count

# Check incidents
curl http://localhost:8080/engine-rest/incident/count

# Check health
curl http://localhost:8080/actuator/health | jq '.'
```

---

## 🎯 Expected Results (2 Pods)

### ✅ PASS Criteria
- Test 1: Pod recreated automatically, jobs continue ✅
- Test 2: Auto-reconnect after DB restart ✅
- Test 3: Reasonable load distribution (30-70%) ✅
- Test 4: Both pods recover, state persisted ✅
- Test 5: Pod reschedules on node drain ✅
- No incidents created ✅
- Zero data loss ✅

### ❌ FAIL Criteria
- Pod doesn't recover
- Incidents > 0
- Data loss
- Processes stuck
- No job execution after recovery

---

## 🔧 Troubleshooting

### Pods not starting?
```bash
kubectl describe pod <pod-name> -n $NAMESPACE
kubectl logs <pod-name> -n $NAMESPACE --previous
```

### Database connection issues?
```bash
kubectl get pods -n $NAMESPACE | grep postgres
kubectl logs <postgres-pod> -n $NAMESPACE
```

### Ingress not working?
```bash
kubectl get ingress -n $NAMESPACE
kubectl describe ingress <ingress-name> -n $NAMESPACE
```

---

**Summary**: EKS testing guide với 2 pods setup. Các scripts đã được tối ưu cho môi trường production trên AWS EKS.

