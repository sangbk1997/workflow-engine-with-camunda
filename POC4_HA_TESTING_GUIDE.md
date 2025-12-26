# PoC 4 - Infrastructure & High Availability Testing for Camunda 7

## 📋 Test Overview

**Objective**: Validate Camunda 7 behavior under node failures, restarts, and scaling scenarios to confirm workflow continuity, reliability, and data consistency.

**Duration**: 2-3 days  
**Team Required**: 1 DevOps Engineer + 1 Backend Developer  
**Environment**: Kubernetes (EKS/OCP) or Docker Compose

---

## 🎯 Test Objectives

1. ✅ Validate HA deployment configuration
2. ✅ Confirm job executor clustering behavior
3. ✅ Test workflow continuity during failures
4. ✅ Verify data consistency with shared database
5. ✅ Measure recovery time and failover speed
6. ✅ Document incident handling and retry logic

---

## 🏗️ Architecture Setup

### Deployment Architecture

```
┌─────────────────────────────────────────────────────┐
│                Load Balancer / Ingress              │
└────────────────────┬────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        │            │            │
┌───────▼─────┐ ┌───▼──────┐ ┌──▼────────┐
│ Camunda Pod │ │ Camunda  │ │ Camunda   │
│   Node 1    │ │  Node 2  │ │  Node 3   │
│ (Active)    │ │ (Active) │ │ (Active)  │
└──────┬──────┘ └────┬─────┘ └─────┬─────┘
       │             │              │
       └─────────────┼──────────────┘
                     │
          ┌──────────▼──────────┐
          │  PostgreSQL DB      │
          │  (Shared State)     │
          │  - Primary          │
          │  - Read Replicas    │
          └─────────────────────┘
```

### Key Components

1. **Multiple Camunda Instances** (3 pods minimum)
   - Shared database for state persistence
   - Job executor clustering enabled
   - Session affinity disabled

2. **PostgreSQL Database**
   - Primary-replica setup
   - Connection pooling (HikariCP)
   - Automatic failover

3. **Load Balancer**
   - Round-robin distribution
   - Health check endpoints
   - Session persistence OFF

---

## 📦 Phase 1: Environment Setup

### 1.1. Kubernetes Deployment Files

#### ConfigMap - Application Configuration

```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: camunda-config
  namespace: camunda-ha
data:
  application.properties: |
    # Application
    spring.application.name=camunda-workflow-engine
    server.port=8080
    
    # Database - PostgreSQL
    spring.datasource.url=jdbc:postgresql://postgres-service:5432/camunda
    spring.datasource.username=${DB_USERNAME}
    spring.datasource.password=${DB_PASSWORD}
    spring.datasource.driver-class-name=org.postgresql.Driver
    
    # HikariCP Connection Pool
    spring.datasource.hikari.maximum-pool-size=20
    spring.datasource.hikari.minimum-idle=5
    spring.datasource.hikari.connection-timeout=30000
    spring.datasource.hikari.idle-timeout=600000
    spring.datasource.hikari.max-lifetime=1800000
    
    # JPA
    spring.jpa.hibernate.ddl-auto=validate
    spring.jpa.show-sql=false
    spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
    
    # Camunda BPM
    camunda.bpm.database.type=postgres
    camunda.bpm.database.schema-update=true
    camunda.bpm.history-level=full
    camunda.bpm.metrics.enabled=true
    camunda.bpm.authorization.enabled=true
    
    # Job Executor - CRITICAL FOR HA
    camunda.bpm.job-execution.enabled=true
    camunda.bpm.job-execution.deployment-aware=true
    camunda.bpm.job-execution.core-pool-size=3
    camunda.bpm.job-execution.max-pool-size=10
    camunda.bpm.job-execution.queue-capacity=10
    camunda.bpm.job-execution.lock-time-in-millis=300000
    camunda.bpm.job-execution.max-jobs-per-acquisition=3
    camunda.bpm.job-execution.wait-time-in-millis=5000
    camunda.bpm.job-execution.max-wait=60000
    camunda.bpm.job-execution.backoff-time-in-millis=0
    
    # Admin User (DEV only - remove in production)
    camunda.bpm.admin-user.id=admin
    camunda.bpm.admin-user.password=admin
    camunda.bpm.admin-user.first-name=Admin
    
    # Logging
    logging.level.org.camunda.bpm=INFO
    logging.level.org.camunda.bpm.engine.jobexecutor=DEBUG
    logging.level.org.camunda.bpm.engine.cmd=DEBUG
    
    # Actuator
    management.endpoints.web.exposure.include=health,info,metrics,prometheus
    management.endpoint.health.show-details=always
    management.health.db.enabled=true
```

#### PostgreSQL StatefulSet

```yaml
# k8s/postgres-statefulset.yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres-service
  namespace: camunda-ha
spec:
  ports:
    - port: 5432
      targetPort: 5432
  selector:
    app: postgres
  clusterIP: None
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: camunda-ha
spec:
  serviceName: postgres-service
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
      - name: postgres
        image: postgres:15-alpine
        ports:
        - containerPort: 5432
          name: postgres
        env:
        - name: POSTGRES_DB
          value: camunda
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: postgres-secret
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-secret
              key: password
        - name: PGDATA
          value: /var/lib/postgresql/data/pgdata
        volumeMounts:
        - name: postgres-storage
          mountPath: /var/lib/postgresql/data
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          exec:
            command:
            - pg_isready
            - -U
            - camunda
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          exec:
            command:
            - pg_isready
            - -U
            - camunda
          initialDelaySeconds: 5
          periodSeconds: 5
  volumeClaimTemplates:
  - metadata:
      name: postgres-storage
    spec:
      accessModes: [ "ReadWriteOnce" ]
      resources:
        requests:
          storage: 10Gi
```

#### Camunda Deployment (3 Replicas)

```yaml
# k8s/camunda-deployment.yaml
apiVersion: v1
kind: Service
metadata:
  name: camunda-service
  namespace: camunda-ha
spec:
  type: LoadBalancer
  ports:
    - port: 8080
      targetPort: 8080
      protocol: TCP
      name: http
  selector:
    app: camunda
  sessionAffinity: None  # IMPORTANT: No sticky sessions for HA testing
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: camunda-deployment
  namespace: camunda-ha
spec:
  replicas: 3  # 3 instances for HA
  selector:
    matchLabels:
      app: camunda
  template:
    metadata:
      labels:
        app: camunda
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      containers:
      - name: camunda
        image: your-registry/camunda-workflow-engine:latest
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: DB_USERNAME
          valueFrom:
            secretKeyRef:
              name: postgres-secret
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-secret
              key: password
        - name: POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: POD_IP
          valueFrom:
            fieldRef:
              fieldPath: status.podIP
        volumeMounts:
        - name: config
          mountPath: /config
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 120
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
        startupProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 12
      volumes:
      - name: config
        configMap:
          name: camunda-config
```

#### Secrets

```yaml
# k8s/secrets.yaml
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
  namespace: camunda-ha
type: Opaque
data:
  username: Y2FtdW5kYQ==  # base64: camunda
  password: Y2FtdW5kYQ==  # base64: camunda
```

#### HorizontalPodAutoscaler

```yaml
# k8s/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: camunda-hpa
  namespace: camunda-ha
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: camunda-deployment
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
      - type: Percent
        value: 100
        periodSeconds: 15
      - type: Pods
        value: 2
        periodSeconds: 15
      selectPolicy: Max
```

### 1.2. Deploy to Kubernetes

```bash
# Create namespace
kubectl create namespace camunda-ha

# Apply secrets
kubectl apply -f k8s/secrets.yaml

# Apply ConfigMap
kubectl apply -f k8s/configmap.yaml

# Deploy PostgreSQL
kubectl apply -f k8s/postgres-statefulset.yaml

# Wait for PostgreSQL to be ready
kubectl wait --for=condition=ready pod -l app=postgres -n camunda-ha --timeout=300s

# Deploy Camunda
kubectl apply -f k8s/camunda-deployment.yaml

# Wait for Camunda pods to be ready
kubectl wait --for=condition=ready pod -l app=camunda -n camunda-ha --timeout=300s

# Apply HPA
kubectl apply -f k8s/hpa.yaml

# Verify deployment
kubectl get all -n camunda-ha
kubectl get pvc -n camunda-ha
```

### 1.3. Docker Compose Alternative (for local testing)

```yaml
# docker-compose-ha.yml
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
    build: .
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
    ports:
      - "8081:8080"
    networks:
      - camunda-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  camunda-node2:
    build: .
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
    ports:
      - "8082:8080"
    networks:
      - camunda-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  camunda-node3:
    build: .
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
    ports:
      - "8083:8080"
    networks:
      - camunda-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

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
```

```nginx
# nginx.conf
events {
    worker_connections 1024;
}

http {
    upstream camunda_cluster {
        least_conn;  # Load balancing algorithm
        
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
            proxy_set_header X-Forwarded-Proto $scheme;
            
            # Health check
            proxy_next_upstream error timeout http_502 http_503 http_504;
            proxy_connect_timeout 10s;
            proxy_send_timeout 30s;
            proxy_read_timeout 30s;
        }
        
        location /actuator/health {
            proxy_pass http://camunda_cluster;
            access_log off;
        }
    }
}
```

---

## 🧪 Phase 2: Test Scenario Execution

### 2.1. Create Test BPMN Process

Create a test process with:
- Async service tasks
- Timer events
- Multiple user tasks
- External service calls

```xml
<!-- src/main/resources/processes/ha-test-process.bpmn -->
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
             targetNamespace="http://camunda.org/examples">
  
  <process id="haTestProcess" name="HA Test Process" isExecutable="true">
    
    <startEvent id="start" name="Start"/>
    
    <sequenceFlow sourceRef="start" targetRef="asyncTask1"/>
    
    <!-- Async Task 1 -->
    <serviceTask id="asyncTask1" name="Async Task 1" 
                 camunda:asyncBefore="true"
                 camunda:delegateExpression="${asyncTask1Delegate}"/>
    
    <sequenceFlow sourceRef="asyncTask1" targetRef="timerEvent"/>
    
    <!-- Timer Event (30 seconds) -->
    <intermediateCatchEvent id="timerEvent" name="Wait 30s">
      <timerEventDefinition>
        <timeDuration>PT30S</timeDuration>
      </timerEventDefinition>
    </intermediateCatchEvent>
    
    <sequenceFlow sourceRef="timerEvent" targetRef="asyncTask2"/>
    
    <!-- Async Task 2 -->
    <serviceTask id="asyncTask2" name="Async Task 2"
                 camunda:asyncBefore="true"
                 camunda:delegateExpression="${asyncTask2Delegate}"/>
    
    <sequenceFlow sourceRef="asyncTask2" targetRef="userTask"/>
    
    <!-- User Task -->
    <userTask id="userTask" name="Manual Approval" 
              camunda:assignee="admin"/>
    
    <sequenceFlow sourceRef="userTask" targetRef="end"/>
    
    <endEvent id="end" name="End"/>
    
  </process>
</definitions>
```

### 2.2. Test Delegates

```java
// AsyncTask1Delegate.java
package com.truemoney.workflowengine.delegate;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Slf4j
@Component("asyncTask1Delegate")
public class AsyncTask1Delegate implements JavaDelegate {
    
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        String processInstanceId = execution.getProcessInstanceId();
        String podName = System.getenv("POD_NAME");
        
        log.info("⚡ Async Task 1 executed on pod: {} for process: {}", 
                 podName, processInstanceId);
        
        // Simulate work
        Thread.sleep(5000);
        
        execution.setVariable("task1_completed_by", podName);
        execution.setVariable("task1_timestamp", System.currentTimeMillis());
        
        log.info("✅ Async Task 1 completed on pod: {}", podName);
    }
}
```

```java
// AsyncTask2Delegate.java
package com.truemoney.workflowengine.delegate;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Slf4j
@Component("asyncTask2Delegate")
public class AsyncTask2Delegate implements JavaDelegate {
    
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        String processInstanceId = execution.getProcessInstanceId();
        String podName = System.getenv("POD_NAME");
        String task1Pod = (String) execution.getVariable("task1_completed_by");
        
        log.info("⚡ Async Task 2 executed on pod: {} for process: {} (Task1 was on: {})", 
                 podName, processInstanceId, task1Pod);
        
        // Simulate work
        Thread.sleep(5000);
        
        execution.setVariable("task2_completed_by", podName);
        execution.setVariable("task2_timestamp", System.currentTimeMillis());
        
        log.info("✅ Async Task 2 completed on pod: {}", podName);
    }
}
```

### 2.3. Load Testing Script

```bash
#!/bin/bash
# load-test.sh

API_URL="http://localhost:8080"
PROCESS_KEY="haTestProcess"
NUM_INSTANCES=50

echo "🚀 Starting load test - Creating $NUM_INSTANCES process instances..."

for i in $(seq 1 $NUM_INSTANCES); do
    response=$(curl -s -X POST "$API_URL/engine-rest/process-definition/key/$PROCESS_KEY/start" \
      -H "Content-Type: application/json" \
      -d '{
        "variables": {
          "testId": {"value": "'$i'", "type": "String"},
          "timestamp": {"value": "'$(date -u +%Y-%m-%dT%H:%M:%S)'", "type": "String"}
        }
      }')
    
    instance_id=$(echo $response | jq -r '.id')
    echo "✅ Created instance $i: $instance_id"
    
    # Small delay to avoid overwhelming the system
    sleep 0.5
done

echo "🎉 Load test completed - $NUM_INSTANCES instances created"
```

---

## 🔥 Phase 3: Failure Simulation Tests

### Test Case 1: Pod Termination During Execution

**Objective**: Verify job executor failover when a pod is killed

```bash
#!/bin/bash
# test-pod-kill.sh

echo "📊 Test Case 1: Pod Termination During Execution"
echo "================================================="

# 1. Start process instances
echo "1️⃣ Starting 20 process instances..."
./load-test.sh

# 2. Wait for processes to start executing
echo "2️⃣ Waiting 10 seconds for processes to start..."
sleep 10

# 3. Kill one pod
POD_NAME=$(kubectl get pods -n camunda-ha -l app=camunda -o jsonpath='{.items[0].metadata.name}')
echo "3️⃣ Killing pod: $POD_NAME"
kubectl delete pod $POD_NAME -n camunda-ha

# 4. Monitor recovery
echo "4️⃣ Monitoring pod recovery..."
kubectl wait --for=condition=ready pod -l app=camunda -n camunda-ha --timeout=120s

# 5. Check process status
echo "5️⃣ Checking process instance status..."
curl -s "http://localhost:8080/engine-rest/process-instance?active=true" | jq '.[] | {id: .id, suspended: .suspended}'

# 6. Check for incidents
echo "6️⃣ Checking for incidents..."
curl -s "http://localhost:8080/engine-rest/incident" | jq '.'

echo "✅ Test Case 1 completed"
```

**Expected Results:**
- ✅ Jobs locked by killed pod are released after lock timeout
- ✅ Other pods pick up the released jobs
- ✅ No data loss
- ✅ Process instances continue execution
- ✅ Recovery time < lock timeout (5 minutes)

---

### Test Case 2: Database Connection Failure

**Objective**: Test behavior when database connection is temporarily lost

```bash
#!/bin/bash
# test-db-failure.sh

echo "📊 Test Case 2: Database Connection Failure"
echo "==========================================="

# 1. Start process instances
echo "1️⃣ Starting 10 process instances..."
./load-test.sh

# 2. Block database access
echo "2️⃣ Blocking database connection using network policy..."

cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: block-postgres
  namespace: camunda-ha
spec:
  podSelector:
    matchLabels:
      app: postgres
  policyTypes:
  - Ingress
  ingress: []
EOF

# 3. Wait and observe
echo "3️⃣ Waiting 30 seconds to observe connection failures..."
sleep 30

# 4. Check application logs
echo "4️⃣ Checking application logs for connection errors..."
kubectl logs -l app=camunda -n camunda-ha --tail=20

# 5. Restore database connection
echo "5️⃣ Restoring database connection..."
kubectl delete networkpolicy block-postgres -n camunda-ha

# 6. Wait for recovery
echo "6️⃣ Waiting for recovery..."
sleep 30

# 7. Verify recovery
echo "7️⃣ Verifying recovery..."
kubectl get pods -n camunda-ha
curl -s "http://localhost:8080/actuator/health" | jq '.'

echo "✅ Test Case 2 completed"
```

**Expected Results:**
- ✅ Applications show database connection errors
- ✅ Health checks fail during outage
- ✅ Kubernetes may restart pods (liveness probe)
- ✅ Auto-recovery after connection restored
- ✅ No permanent data loss
- ✅ Jobs retry after recovery

---

### Test Case 3: Rolling Update

**Objective**: Verify zero-downtime during deployment updates

```bash
#!/bin/bash
# test-rolling-update.sh

echo "📊 Test Case 3: Rolling Update"
echo "=============================="

# 1. Start continuous load
echo "1️⃣ Starting continuous process creation..."
(
  while true; do
    curl -s -X POST "http://localhost:8080/engine-rest/process-definition/key/haTestProcess/start" \
      -H "Content-Type: application/json" \
      -d '{"variables": {"timestamp": {"value": "'$(date -u +%Y-%m-%dT%H:%M:%S)'", "type": "String"}}}' > /dev/null
    sleep 2
  done
) &
LOAD_PID=$!

# 2. Trigger rolling update
echo "2️⃣ Triggering rolling update..."
kubectl set image deployment/camunda-deployment camunda=your-registry/camunda-workflow-engine:v2 -n camunda-ha

# 3. Monitor rollout
echo "3️⃣ Monitoring rollout status..."
kubectl rollout status deployment/camunda-deployment -n camunda-ha

# 4. Stop load generation
echo "4️⃣ Stopping load generation..."
kill $LOAD_PID

# 5. Verify all instances
echo "5️⃣ Verifying process instances..."
TOTAL=$(curl -s "http://localhost:8080/engine-rest/process-instance/count" | jq '.count')
INCIDENTS=$(curl -s "http://localhost:8080/engine-rest/incident/count" | jq '.count')

echo "Total process instances: $TOTAL"
echo "Incidents: $INCIDENTS"

echo "✅ Test Case 3 completed"
```

**Expected Results:**
- ✅ No failed process starts during update
- ✅ Jobs continue execution
- ✅ Max 1-2 pods down at any time
- ✅ Total downtime < rolling update window
- ✅ No incidents created

---

### Test Case 4: Scale Down and Scale Up

**Objective**: Test behavior during horizontal scaling

```bash
#!/bin/bash
# test-scaling.sh

echo "📊 Test Case 4: Horizontal Scaling"
echo "=================================="

# 1. Create baseline load
echo "1️⃣ Creating baseline load (30 instances)..."
./load-test.sh

# 2. Check initial distribution
echo "2️⃣ Checking job distribution..."
for pod in $(kubectl get pods -n camunda-ha -l app=camunda -o name); do
  echo "Pod: $pod"
  kubectl logs $pod -n camunda-ha | grep "Async Task" | tail -5
done

# 3. Scale down to 2 replicas
echo "3️⃣ Scaling down to 2 replicas..."
kubectl scale deployment camunda-deployment --replicas=2 -n camunda-ha

# 4. Wait and observe
sleep 30

# 5. Check job redistribution
echo "4️⃣ Checking job redistribution after scale down..."
for pod in $(kubectl get pods -n camunda-ha -l app=camunda -o name); do
  echo "Pod: $pod"
  kubectl logs $pod -n camunda-ha | grep "Async Task" | tail -5
done

# 6. Scale up to 5 replicas
echo "5️⃣ Scaling up to 5 replicas..."
kubectl scale deployment camunda-deployment --replicas=5 -n camunda-ha

# 7. Create more load
echo "6️⃣ Creating additional load..."
./load-test.sh

# 8. Final distribution check
echo "7️⃣ Checking final job distribution..."
for pod in $(kubectl get pods -n camunda-ha -l app=camunda -o name); do
  echo "Pod: $pod"
  kubectl logs $pod -n camunda-ha | grep "Async Task" | tail -5
done

# 9. Verify no incidents
INCIDENTS=$(curl -s "http://localhost:8080/engine-rest/incident/count" | jq '.count')
echo "Incidents: $INCIDENTS"

echo "✅ Test Case 4 completed"
```

**Expected Results:**
- ✅ Jobs redistributed after scale down
- ✅ New pods pick up jobs after scale up
- ✅ Even distribution across all pods
- ✅ No job execution failures
- ✅ No incidents

---

### Test Case 5: Simulated Network Partition

**Objective**: Test split-brain scenarios

```bash
#!/bin/bash
# test-network-partition.sh

echo "📊 Test Case 5: Network Partition"
echo "================================="

# 1. Start processes
echo "1️⃣ Starting process instances..."
./load-test.sh

# 2. Create network policy to isolate one pod
POD_TO_ISOLATE=$(kubectl get pods -n camunda-ha -l app=camunda -o jsonpath='{.items[0].metadata.name}')

echo "2️⃣ Isolating pod: $POD_TO_ISOLATE"

cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: isolate-pod
  namespace: camunda-ha
spec:
  podSelector:
    matchLabels:
      statefulset.kubernetes.io/pod-name: $POD_TO_ISOLATE
  policyTypes:
  - Ingress
  - Egress
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: postgres
  ingress: []
EOF

# 3. Observe behavior
echo "3️⃣ Observing for 60 seconds..."
sleep 60

# 4. Check health status
echo "4️⃣ Checking health status of all pods..."
for pod in $(kubectl get pods -n camunda-ha -l app=camunda -o name); do
  echo "Pod: $pod"
  kubectl exec $pod -n camunda-ha -- wget -qO- http://localhost:8080/actuator/health | jq '.status'
done

# 5. Remove network policy
echo "5️⃣ Removing network isolation..."
kubectl delete networkpolicy isolate-pod -n camunda-ha

# 6. Wait for recovery
sleep 30

# 7. Final check
echo "6️⃣ Final verification..."
curl -s "http://localhost:8080/engine-rest/incident/count" | jq '.'

echo "✅ Test Case 5 completed"
```

**Expected Results:**
- ✅ Isolated pod stops picking up new jobs
- ✅ Other pods continue normally
- ✅ Isolated pod rejoins after network restored
- ✅ Lock timeouts prevent duplicate execution
- ✅ Minimal to no incidents

---

## 📊 Phase 4: Monitoring & Observability

### 4.1. Monitoring Stack Setup

```yaml
# k8s/prometheus-config.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-config
  namespace: camunda-ha
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
      evaluation_interval: 15s
    
    scrape_configs:
      - job_name: 'camunda'
        kubernetes_sd_configs:
          - role: pod
            namespaces:
              names:
                - camunda-ha
        relabel_configs:
          - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
            action: keep
            regex: true
          - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
            action: replace
            target_label: __metrics_path__
            regex: (.+)
          - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
            action: replace
            regex: ([^:]+)(?::\d+)?;(\d+)
            replacement: $1:$2
            target_label: __address__
```

### 4.2. Key Metrics to Monitor

```bash
# metrics-queries.sh

# Job Executor Metrics
echo "Job Executor Active Jobs:"
curl -s "http://localhost:8080/actuator/metrics/camunda.job.executor.active.count" | jq '.measurements[0].value'

echo "Job Executor Queue Size:"
curl -s "http://localhost:8080/actuator/metrics/camunda.job.executor.queue.size" | jq '.measurements[0].value'

echo "Job Execution Duration:"
curl -s "http://localhost:8080/actuator/metrics/camunda.job.execution.duration" | jq '.'

# Process Instance Metrics
echo "Active Process Instances:"
curl -s "http://localhost:8080/engine-rest/process-instance/count?active=true" | jq '.count'

echo "Suspended Process Instances:"
curl -s "http://localhost:8080/engine-rest/process-instance/count?suspended=true" | jq '.count'

# Incident Metrics
echo "Open Incidents:"
curl -s "http://localhost:8080/engine-rest/incident/count" | jq '.count'

# Database Connection Pool
echo "HikariCP Active Connections:"
curl -s "http://localhost:8080/actuator/metrics/hikaricp.connections.active" | jq '.measurements[0].value'

echo "HikariCP Idle Connections:"
curl -s "http://localhost:8080/actuator/metrics/hikaricp.connections.idle" | jq '.measurements[0].value'
```

### 4.3. Grafana Dashboard JSON

Create a Grafana dashboard to visualize:
- Pod health status
- Job executor queue size
- Active/completed jobs per pod
- Incident count over time
- Database connection pool metrics
- HTTP request latency

---

## 📋 Phase 5: Test Results Documentation

### Test Results Template

```markdown
# HA Test Results - Camunda 7

## Test Environment
- **Date**: YYYY-MM-DD
- **Kubernetes Version**: v1.28
- **Camunda Version**: 7.20.0
- **PostgreSQL Version**: 15
- **Number of Pods**: 3
- **Load Profile**: 50 concurrent process instances

## Test Case 1: Pod Termination
- **Status**: ✅ PASS / ❌ FAIL
- **Recovery Time**: XX seconds
- **Jobs Lost**: 0
- **Incidents Created**: 0
- **Notes**: ...

## Test Case 2: Database Failure
- **Status**: ✅ PASS / ❌ FAIL
- **Downtime**: XX seconds
- **Recovery Time**: XX seconds
- **Data Loss**: None
- **Notes**: ...

## Test Case 3: Rolling Update
- **Status**: ✅ PASS / ❌ FAIL
- **Update Duration**: XX seconds
- **Failed Requests**: 0
- **Incidents**: 0
- **Notes**: ...

## Test Case 4: Scaling
- **Status**: ✅ PASS / ❌ FAIL
- **Scale Down Time**: XX seconds
- **Scale Up Time**: XX seconds
- **Job Redistribution**: Even
- **Notes**: ...

## Test Case 5: Network Partition
- **Status**: ✅ PASS / ❌ FAIL
- **Isolation Duration**: 60 seconds
- **Recovery Time**: XX seconds
- **Duplicate Executions**: 0
- **Notes**: ...

## Overall Assessment
- **High Availability**: ✅ Achieved / ❌ Not Achieved
- **Zero Downtime**: ✅ Yes / ❌ No
- **Data Consistency**: ✅ Maintained / ❌ Issues Found
- **Recommendations**: ...
```

---

## 📝 Phase 6: Cleanup

```bash
#!/bin/bash
# cleanup.sh

echo "🧹 Cleaning up test environment..."

# Delete Kubernetes resources
kubectl delete namespace camunda-ha

# Or for Docker Compose
docker-compose -f docker-compose-ha.yml down -v

echo "✅ Cleanup completed"
```

---

## 🎯 Success Criteria

### ✅ PASS Criteria

1. **Pod Failure Recovery**
   - Jobs locked by failed pod are released within lock timeout (5 min)
   - Other pods pick up released jobs automatically
   - No data loss or duplicate execution

2. **Database Resilience**
   - Application reconnects after DB recovery
   - No permanent data corruption
   - Jobs retry successfully

3. **Zero-Downtime Deployment**
   - Rolling updates complete without errors
   - Process creation continues during update
   - Max 1 pod down at any time

4. **Horizontal Scaling**
   - Jobs distribute evenly across pods
   - New pods join cluster seamlessly
   - Scale down doesn't lose jobs

5. **Data Consistency**
   - No split-brain scenarios
   - Optimistic locking prevents conflicts
   - Database transactions maintain ACID

### ❌ FAIL Criteria

- Job loss during pod failure
- Duplicate job execution
- Data corruption
- Split-brain scenarios
- Recovery time > 5 minutes
- Incidents created during normal operations

---

## 📚 References

- [Camunda Job Executor](https://docs.camunda.org/manual/latest/user-guide/process-engine/the-job-executor/)
- [Camunda Clustering](https://docs.camunda.org/manual/latest/introduction/architecture/#clustering)
- [Kubernetes Best Practices](https://kubernetes.io/docs/concepts/configuration/overview/)

---

**Document Version**: 1.0  
**Last Updated**: December 17, 2025  
**Prepared by**: DevOps & Workflow Team

