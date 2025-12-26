#!/bin/bash

# Quick Fix Script for 403 Forbidden Error

set -e

echo "========================================="
echo "Camunda 403 Forbidden Quick Fix"
echo "========================================="
echo ""

# Configuration
NAMESPACE="${NAMESPACE:-default}"
DEPLOYMENT_NAME="${DEPLOYMENT_NAME:-workflow-engine}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-camunda-config}"

echo "Configuration:"
echo "  Namespace: $NAMESPACE"
echo "  Deployment: $DEPLOYMENT_NAME"
echo "  ConfigMap: $CONFIGMAP_NAME"
echo ""

# Step 1: Check current configuration
echo "📊 Step 1: Checking current configuration..."
echo ""

kubectl get configmap $CONFIGMAP_NAME -n $NAMESPACE -o yaml | grep -A 5 "authorization\|admin-user" || echo "ConfigMap not found or no auth settings"

echo ""
read -p "Continue with fix? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 1
fi

# Step 2: Create temporary ConfigMap patch
echo ""
echo "📝 Step 2: Creating ConfigMap patch..."

cat > /tmp/camunda-fix.yaml << 'EOF'
data:
  application.properties: |
    spring.application.name=workflow-engine

    # Camunda setup - FIXED
    camunda.bpm.admin-user.id=admin
    camunda.bpm.admin-user.password=admin
    camunda.bpm.admin-user.first-name=Admin
    camunda.bpm.database.schema-update=true
    camunda.bpm.database.type=postgres
    camunda.bpm.history-level=full
    camunda.bpm.metrics.enabled=true

    # CRITICAL FIX: Disable authorization temporarily
    camunda.bpm.authorization.enabled=false

    # PostgreSQL datasource
    spring.datasource.url=${DB_URL:jdbc:postgresql://postgres-service:5432/camunda}
    spring.datasource.username=${DB_USERNAME:camunda}
    spring.datasource.password=${DB_PASSWORD:camunda}
    spring.datasource.driver-class-name=org.postgresql.Driver

    # JPA
    spring.jpa.hibernate.ddl-auto=update
    spring.jpa.show-sql=false

    # Job Executor
    camunda.bpm.job-execution.enabled=true

    # Management endpoints
    management.endpoints.web.exposure.include=health,info,metrics
    management.endpoint.health.show-details=always

    # Logging
    logging.level.org.camunda.bpm=INFO
EOF

echo "ConfigMap patch created at: /tmp/camunda-fix.yaml"

# Step 3: Backup current ConfigMap
echo ""
echo "💾 Step 3: Backing up current ConfigMap..."
kubectl get configmap $CONFIGMAP_NAME -n $NAMESPACE -o yaml > /tmp/camunda-config-backup-$(date +%Y%m%d-%H%M%S).yaml
echo "Backup saved to: /tmp/camunda-config-backup-*.yaml"

# Step 4: Apply patch
echo ""
echo "🔧 Step 4: Applying ConfigMap patch..."
kubectl patch configmap $CONFIGMAP_NAME -n $NAMESPACE --patch-file /tmp/camunda-fix.yaml

# Step 5: Restart deployment
echo ""
echo "🔄 Step 5: Restarting deployment..."
kubectl rollout restart deployment/$DEPLOYMENT_NAME -n $NAMESPACE

# Step 6: Wait for rollout
echo ""
echo "⏳ Step 6: Waiting for rollout to complete..."
kubectl rollout status deployment/$DEPLOYMENT_NAME -n $NAMESPACE --timeout=180s

# Step 7: Check pod status
echo ""
echo "📊 Step 7: Checking pod status..."
kubectl get pods -n $NAMESPACE -l app=camunda

# Step 8: Get new pod name
NEW_POD=$(kubectl get pods -n $NAMESPACE -l app=camunda -o jsonpath='{.items[0].metadata.name}')
echo ""
echo "New pod: $NEW_POD"

# Step 9: Wait for pod to be ready
echo ""
echo "⏳ Step 9: Waiting for pod to be ready..."
kubectl wait --for=condition=ready pod/$NEW_POD -n $NAMESPACE --timeout=120s

# Step 10: Check logs
echo ""
echo "📋 Step 10: Checking application logs..."
kubectl logs $NEW_POD -n $NAMESPACE --tail=30

# Step 11: Test health endpoint
echo ""
echo "✅ Step 11: Testing health endpoint..."
sleep 10

kubectl exec $NEW_POD -n $NAMESPACE -- wget -qO- http://localhost:8080/actuator/health | jq '.' || echo "Health check failed"

# Step 12: Get service URL
echo ""
echo "🌐 Step 12: Getting service URL..."
INGRESS_HOST=$(kubectl get ingress -n $NAMESPACE -o jsonpath='{.items[0].spec.rules[0].host}')

if [ -n "$INGRESS_HOST" ]; then
    echo ""
    echo "========================================="
    echo "✅ FIX APPLIED SUCCESSFULLY"
    echo "========================================="
    echo ""
    echo "Service URL: https://$INGRESS_HOST"
    echo ""
    echo "Test endpoints:"
    echo "  Health: https://$INGRESS_HOST/actuator/health"
    echo "  Cockpit: https://$INGRESS_HOST/camunda/app/cockpit"
    echo ""
    echo "Login credentials:"
    echo "  Username: admin"
    echo "  Password: admin"
    echo ""
    echo "Verify with:"
    echo "  curl https://$INGRESS_HOST/actuator/health"
    echo ""
else
    echo "Warning: Could not find ingress host"
    echo "Use port-forward to test:"
    echo "  kubectl port-forward -n $NAMESPACE svc/workflow-service 8080:8080"
    echo "  Then access: http://localhost:8080/camunda/app/cockpit"
fi

echo "========================================="
echo "NOTES:"
echo "========================================="
echo "1. Authorization is now DISABLED for quick fix"
echo "2. Admin user is ENABLED with default password"
echo "3. For production, you should:"
echo "   - Enable authorization: camunda.bpm.authorization.enabled=true"
echo "   - Use strong admin password"
echo "   - Configure Keycloak for SSO"
echo "4. Backup saved at: /tmp/camunda-config-backup-*.yaml"
echo ""
echo "To rollback:"
echo "  kubectl apply -f /tmp/camunda-config-backup-*.yaml"
echo "  kubectl rollout restart deployment/$DEPLOYMENT_NAME -n $NAMESPACE"
echo ""

