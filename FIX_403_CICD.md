# Fix 403 Forbidden trên CICD/EKS

## Root Cause

**Local**: Keycloak không accessible → fallback to admin user → ✅ Works
**EKS**: Keycloak config tồn tại → tries to authenticate via Keycloak → user không có trong Keycloak → ❌ 403 Forbidden

## Solution

### Option 1: Disable Keycloak (Quick Fix - Recommended)

#### 1.1. Update K8s ConfigMap/Deployment

Thêm environment variable:
```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"
```

#### 1.2. Hoặc override Keycloak settings

```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"
  - name: PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAKISSUERURL
    value: ""
  - name: PLUGIN_IDENTITY_KEYCLOAK_KEYCLOAKADMINURL
    value: ""
```

#### 1.3. Apply changes

```bash
# Update deployment
kubectl set env deployment/workflow-engine SPRING_PROFILES_ACTIVE=prod

# Or edit directly
kubectl edit deployment workflow-engine

# Restart
kubectl rollout restart deployment/workflow-engine
```

### Option 2: Remove Keycloak Dependency (Clean Fix)

#### 2.1. Comment out Keycloak dependency

Edit `pom.xml`:
```xml
<!--
<dependency>
    <groupId>org.camunda.bpm.extension</groupId>
    <artifactId>camunda-platform-7-keycloak</artifactId>
    <version>7.24.0</version>
</dependency>
-->
```

#### 2.2. Rebuild và redeploy

```bash
mvn clean package -DskipTests
docker build -t workflow-engine:latest .
docker push your-registry/workflow-engine:latest
kubectl rollout restart deployment/workflow-engine
```

### Option 3: Configure Keycloak Properly (Production Fix)

#### 3.1. Create Keycloak client and users

1. Login to Keycloak admin console
2. Create client: `CAMUNDA-CLIENT`
3. Create group: `ADMIN`
4. Create user and assign to `ADMIN` group

#### 3.2. Update configuration

```properties
plugin.identity.keycloak.keycloakIssuerUrl=https://vortex-keycloak-nonprod-dev.vortex-dev.ascendtechnology.io/realms/Vortex
plugin.identity.keycloak.keycloakAdminUrl=https://vortex-keycloak-nonprod-dev.vortex-dev.ascendtechnology.io/admin/realms/Vortex
plugin.identity.keycloak.clientId=CAMUNDA-CLIENT
plugin.identity.keycloak.clientSecret=<correct-secret>
plugin.identity.keycloak.useUsernameAsCamundaUserId=true
plugin.identity.keycloak.administratorGroupName=ADMIN
```

## Quick Fix Steps (Recommended)

### Step 1: Set profile to `prod`

```bash
kubectl set env deployment/workflow-engine -n <namespace> SPRING_PROFILES_ACTIVE=prod
```

### Step 2: Verify rollout

```bash
kubectl rollout status deployment/workflow-engine -n <namespace>
```

### Step 3: Check logs

```bash
kubectl logs -f deployment/workflow-engine -n <namespace>
```

Look for:
- ✅ "Admin user created successfully"
- ✅ No Keycloak errors
- ❌ Keycloak connection errors (should not appear)

### Step 4: Test

```bash
# Test health
curl https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/actuator/health

# Test Cockpit (should show login page)
curl -I https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/camunda/app/cockpit

# Login with: admin/admin
```

## Verification Checklist

- [ ] Pod is running
- [ ] No Keycloak errors in logs
- [ ] `/actuator/health` returns `{"status":"UP"}`
- [ ] Can access Cockpit login page
- [ ] Can login with admin/admin
- [ ] No 403 errors

## Complete K8s Deployment Example

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: workflow-engine
spec:
  replicas: 2
  selector:
    matchLabels:
      app: workflow-engine
  template:
    metadata:
      labels:
        app: workflow-engine
    spec:
      containers:
      - name: workflow-engine
        image: your-registry/workflow-engine:latest
        ports:
        - containerPort: 8080
        env:
        # CRITICAL: Set profile to prod to disable Keycloak
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        
        # Database config
        - name: DB_URL
          value: "jdbc:postgresql://postgres-service:5432/camunda"
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
        
        # Admin user (optional - can override)
        - name: ADMIN_PASSWORD
          valueFrom:
            secretKeyRef:
              name: camunda-secret
              key: admin-password
        
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
        
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 5
```

## Alternative: ConfigMap Approach

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: workflow-engine-config
data:
  application-prod.properties: |
    # This will override default application.properties
    camunda.bpm.admin-user.id=admin
    camunda.bpm.admin-user.password=admin
    camunda.bpm.authorization.enabled=true
    
    # Keycloak DISABLED
    # No plugin.identity.keycloak.* properties
---
apiVersion: apps/v1
kind: Deployment
...
spec:
  template:
    spec:
      containers:
      - name: workflow-engine
        volumeMounts:
        - name: config
          mountPath: /config
          readOnly: true
        env:
        - name: SPRING_CONFIG_ADDITIONAL_LOCATION
          value: "file:/config/"
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
      volumes:
      - name: config
        configMap:
          name: workflow-engine-config
```

## Troubleshooting

### Still getting 403?

1. Check logs for Keycloak errors:
```bash
kubectl logs deployment/workflow-engine | grep -i keycloak
```

2. Verify profile is active:
```bash
kubectl exec <pod> -- env | grep SPRING_PROFILES_ACTIVE
```

3. Check effective configuration:
```bash
kubectl exec <pod> -- wget -qO- http://localhost:8080/actuator/env | jq '.propertySources'
```

### Admin user not created?

Check logs:
```bash
kubectl logs deployment/workflow-engine | grep -i "admin user"
```

Should see: "Admin user 'admin' created successfully"

## Summary

**Quick Fix**: 
```bash
kubectl set env deployment/workflow-engine SPRING_PROFILES_ACTIVE=prod
```

This will use `application-prod.properties` which disables Keycloak and uses admin user authentication instead.

**Login**: admin/admin

**URL**: https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/camunda/app/cockpit

