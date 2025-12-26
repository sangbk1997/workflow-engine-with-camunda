# Troubleshooting 403 Forbidden Error on EKS

## Vấn đề
```
URL: https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/
Error: 403 Forbidden
Logs: Request login không vào được application
```

## Nguyên nhân có thể

### 1. ✅ MOST LIKELY: Authorization enabled nhưng không có admin user

**Triệu chứng:**
- `camunda.bpm.authorization.enabled=true`
- Admin user bị comment out
- Keycloak chưa được cấu hình đúng

**Giải pháp:**

#### Option A: Disable Authorization (Quick Fix)
```properties
# application.properties
camunda.bpm.authorization.enabled=false
camunda.bpm.admin-user.id=admin
camunda.bpm.admin-user.password=admin
camunda.bpm.admin-user.first-name=Admin
```

#### Option B: Enable Admin User với Authorization
```properties
# application.properties
camunda.bpm.authorization.enabled=true
camunda.bpm.admin-user.id=admin
camunda.bpm.admin-user.password=admin
camunda.bpm.admin-user.first-name=Admin
```

#### Option C: Sử dụng Keycloak (Production)
```properties
# Disable built-in authorization
camunda.bpm.authorization.enabled=true

# Disable admin user creation
# camunda.bpm.admin-user.id=admin

# Enable Keycloak
plugin.identity.keycloak.keycloakIssuerUrl=https://your-keycloak/realms/Vortex
plugin.identity.keycloak.keycloakAdminUrl=https://your-keycloak/admin/realms/Vortex
plugin.identity.keycloak.clientId=CAMUNDA-CLIENT
plugin.identity.keycloak.clientSecret=your-secret
plugin.identity.keycloak.useUsernameAsCamundaUserId=true
plugin.identity.keycloak.administratorGroupName=ADMIN
```

---

## 2. Keycloak Configuration Issues

**Check:**
```bash
# Verify Keycloak is accessible from EKS
kubectl exec -it <workflow-pod> -- wget -O- https://vortex-keycloak-nonprod-dev.vortex-dev.ascendtechnology.io/realms/Vortex

# Check client secret
kubectl get secret <keycloak-secret> -o yaml

# Check logs for Keycloak errors
kubectl logs <workflow-pod> | grep -i keycloak
```

**Common Issues:**
- ❌ Client secret incorrect
- ❌ Realm name wrong
- ❌ Administrator group not assigned
- ❌ Network policy blocking access

---

## 3. Ingress/Load Balancer Configuration

**Check:**
```bash
# Check ingress
kubectl get ingress -n <namespace>
kubectl describe ingress <ingress-name>

# Check service
kubectl get svc -n <namespace>

# Check if backend is healthy
kubectl get pods -n <namespace>
kubectl logs <workflow-pod>
```

**Common Issues:**
- ❌ Ingress backend path wrong
- ❌ Service port mismatch
- ❌ SSL/TLS termination issues
- ❌ CORS configuration

---

## 4. Application Configuration in K8s

**Check ConfigMap/Secrets:**
```bash
# Check ConfigMap
kubectl get configmap -n <namespace>
kubectl describe configmap <config-name>

# Check if authorization is enabled
kubectl exec <workflow-pod> -- env | grep CAMUNDA

# Check admin user settings
kubectl exec <workflow-pod> -- cat /config/application.properties
```

---

## Immediate Fix Steps

### Step 1: Update ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: camunda-config
data:
  application.properties: |
    # Disable authorization temporarily
    camunda.bpm.authorization.enabled=false
    
    # Enable admin user
    camunda.bpm.admin-user.id=admin
    camunda.bpm.admin-user.password=admin
    camunda.bpm.admin-user.first-name=Admin
    
    # Comment out Keycloak
    #plugin.identity.keycloak.keycloakIssuerUrl=...
```

Apply:
```bash
kubectl apply -f configmap.yaml
kubectl rollout restart deployment/workflow-engine
```

### Step 2: Verify Deployment

```bash
# Wait for pods to be ready
kubectl wait --for=condition=ready pod -l app=camunda --timeout=120s

# Check logs
kubectl logs -f deployment/workflow-engine

# Test health
kubectl exec <pod> -- wget -O- http://localhost:8080/actuator/health

# Test locally via port-forward
kubectl port-forward svc/workflow-service 8080:8080

# Access: http://localhost:8080/camunda/app/cockpit
# Login: admin/admin
```

### Step 3: Test External URL

```bash
# Test health endpoint
curl https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/actuator/health

# Test Camunda UI
curl -I https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/camunda/app/cockpit

# If 403, check response
curl -v https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/camunda/app/cockpit
```

---

## Debug Logs

Enable debug logging để xem chi tiết:

```properties
# application.properties
logging.level.org.camunda.bpm.webapp.impl.security=DEBUG
logging.level.org.camunda.bpm.engine.rest.security=DEBUG
logging.level.org.springframework.security=DEBUG

# If using Keycloak
logging.level.org.camunda.bpm.extension.keycloak=DEBUG
```

---

## Recommended Configuration for EKS

### Development Environment

```properties
# application-dev.properties
camunda.bpm.authorization.enabled=false
camunda.bpm.admin-user.id=admin
camunda.bpm.admin-user.password=admin

# No Keycloak
```

### Production Environment

```properties
# application-prod.properties
camunda.bpm.authorization.enabled=true

# Option 1: Use built-in auth with strong password
camunda.bpm.admin-user.id=${ADMIN_USER}
camunda.bpm.admin-user.password=${ADMIN_PASSWORD}

# Option 2: Use Keycloak (recommended)
plugin.identity.keycloak.keycloakIssuerUrl=${KEYCLOAK_ISSUER_URL}
plugin.identity.keycloak.keycloakAdminUrl=${KEYCLOAK_ADMIN_URL}
plugin.identity.keycloak.clientId=${KEYCLOAK_CLIENT_ID}
plugin.identity.keycloak.clientSecret=${KEYCLOAK_CLIENT_SECRET}
plugin.identity.keycloak.useUsernameAsCamundaUserId=true
plugin.identity.keycloak.administratorGroupName=ADMIN
```

---

## Quick Fix Commands

```bash
# 1. Check current configuration
kubectl get configmap camunda-config -o yaml

# 2. Edit ConfigMap directly
kubectl edit configmap camunda-config

# 3. Restart deployment
kubectl rollout restart deployment/workflow-engine

# 4. Watch rollout
kubectl rollout status deployment/workflow-engine

# 5. Check new pods
kubectl get pods -w

# 6. Check logs
kubectl logs -f deployment/workflow-engine --tail=100

# 7. Test
curl https://workflow-engine-dev.public-cloud1n.vortex.ascendtechnology.io/actuator/health
```

---

## Verification Checklist

After fix:

- [ ] Pod is running: `kubectl get pods`
- [ ] Health check passes: `/actuator/health` returns `{"status":"UP"}`
- [ ] Can access Cockpit: `/camunda/app/cockpit` returns login page
- [ ] Can login with admin/admin
- [ ] No 403 errors in logs
- [ ] Can view process definitions
- [ ] Can start process instances

---

## Common Mistakes to Avoid

❌ **Don't:**
- Enable authorization without admin user or Keycloak
- Use default admin password in production
- Expose admin credentials in ConfigMap (use Secrets)
- Comment out admin user when authorization is enabled

✅ **Do:**
- Use Secrets for sensitive data
- Disable authorization in dev, enable in prod
- Use strong passwords
- Configure Keycloak properly for production
- Test locally before deploying to EKS

---

## Contact Points

If issue persists:

1. Check application logs: `kubectl logs <pod>`
2. Check pod events: `kubectl describe pod <pod>`
3. Check ingress logs: `kubectl logs -n ingress-nginx <ingress-pod>`
4. Check database connectivity: `kubectl exec <pod> -- psql -h postgres -U camunda`

---

**Summary**: Most likely cause is authorization enabled without admin user. Quick fix: disable authorization or enable admin user, then redeploy.

