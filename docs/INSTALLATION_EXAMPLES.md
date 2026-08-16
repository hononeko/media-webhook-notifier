# Installation & Deployment Examples (`INSTALLATION_EXAMPLES.md`)

This guide provides tested deployment recipes for **`media-webhook-notifier`** across various environments: Docker, Docker Compose, Kubernetes, Systemd, and Bare Metal.

---

## 📑 Table of Contents

1. [Docker CLI](#1-docker-cli)
2. [Docker Compose](#2-docker-compose)
3. [Kubernetes](#3-kubernetes)
   * [Production Deployment, Service & Secrets](#31-complete-kubernetes-manifests)
4. [Custom Templates Mount](#4-mounting-custom-card-templates)
5. [Docker Secrets & File-Based Auth (`*_FILE`)](#5-docker-secrets--file-based-credentials)
6. [Bare Metal / Systemd Service](#6-bare-metal--systemd-service)

---

## 1. Docker CLI

### Minimal Setup
```bash
docker run -d \
  --name media-webhook-notifier \
  --restart unless-stopped \
  -p 8080:8080 \
  -e SERVER_AUTH_TOKEN="my-secret-token" \
  -e NOTIFICATION_URL="telegram://123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ@-1001234567890" \
  -e QBITTORRENT_URL="http://192.168.1.100:8080" \
  -e QBITTORRENT_USERNAME="admin" \
  -e QBITTORRENT_PASSWORD="adminadmin" \
  ghcr.io/hononeko/media-webhook-notifier:latest
```

### Full Setup with Public URLs and Custom Preview Enabled
```bash
docker run -d \
  --name media-webhook-notifier \
  --restart unless-stopped \
  -p 8080:8080 \
  -e SERVER_PORT=8080 \
  -e SERVER_AUTH_TOKEN="my-secret-token" \
  -e SERVER_RATE_LIMIT_PER_MINUTE=120 \
  -e ENABLE_PREVIEW=true \
  -e NOTIFICATION_URL="telegram://123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ@-1001234567890?topic=42&photos=true&rate_limit=30" \
  -e QBITTORRENT_URL="http://qbittorrent:8080" \
  -e QBITTORRENT_USERNAME="admin" \
  -e QBITTORRENT_PASSWORD="adminadmin" \
  -e QBITTORRENT_WEBUI_PUBLIC_URL="https://downloads.example.com" \
  -e MEDIA_SERVER_TYPE="plex" \
  -e MEDIA_SERVER_URL="http://plex:32400" \
  -e MEDIA_SERVER_PUBLIC_URL="https://plex.example.com" \
  -v $(pwd)/templates.yaml:/config/templates.yaml:ro \
  ghcr.io/hononeko/media-webhook-notifier:latest
```

---

## 2. Docker Compose

Save as `docker-compose.yml`:

```yaml
services:
  media-webhook-notifier:
    image: ghcr.io/hononeko/media-webhook-notifier:latest
    container_name: media-webhook-notifier
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      - SERVER_PORT=8080
      - SERVER_AUTH_TOKEN=your-secure-secret-token
      - SERVER_RATE_LIMIT_PER_MINUTE=120
      - ENABLE_PREVIEW=false
      - NOTIFICATION_URL=telegram://123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ@-1001234567890
      - QBITTORRENT_URL=http://qbittorrent:8080
      - QBITTORRENT_USERNAME=admin
      - QBITTORRENT_PASSWORD=adminadmin
      - QBITTORRENT_WEBUI_PUBLIC_URL=https://downloads.example.com
      - MEDIA_SERVER_TYPE=plex # "plex" or "jellyfin"
      - MEDIA_SERVER_URL=http://plex:32400
      - MEDIA_SERVER_PUBLIC_URL=https://plex.example.com
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost:8080/livez || exit 1"]
      interval: 15s
      timeout: 3s
      retries: 3
      start_period: 5s
```

---

## 3. Kubernetes

### 3.1 Complete Kubernetes Manifests

Save as `media-webhook-notifier.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: media
---
apiVersion: v1
kind: Secret
metadata:
  name: notifier-secrets
  namespace: media
type: Opaque
stringData:
  auth-token: "my-production-secret-token"
  notification-url: "telegram://123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ@-1001234567890"
  qbittorrent-password: "my-secure-qb-password"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: media-webhook-notifier
  namespace: media
  labels:
    app.kubernetes.io/name: media-webhook-notifier
spec:
  replicas: 1
  strategy:
    type: RollingUpdate
  selector:
    matchLabels:
      app.kubernetes.io/name: media-webhook-notifier
  template:
    metadata:
      labels:
        app.kubernetes.io/name: media-webhook-notifier
    spec:
      containers:
        - name: notifier
          image: ghcr.io/hononeko/media-webhook-notifier:latest
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: SERVER_AUTH_TOKEN
              valueFrom:
                secretKeyRef:
                  name: notifier-secrets
                  key: auth-token
            - name: NOTIFICATION_URL
              valueFrom:
                secretKeyRef:
                  name: notifier-secrets
                  key: notification-url
            - name: QBITTORRENT_URL
              value: "http://qbittorrent.media.svc.cluster.local:8080"
            - name: QBITTORRENT_USERNAME
              value: "admin"
            - name: QBITTORRENT_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: notifier-secrets
                  key: qbittorrent-password
            - name: MEDIA_SERVER_TYPE
              value: "plex"
            - name: MEDIA_SERVER_URL
              value: "http://plex.media.svc.cluster.local:32400"
            - name: MEDIA_SERVER_PUBLIC_URL
              value: "https://plex.example.com"
          livenessProbe:
            httpGet:
              path: /livez
              port: http
            initialDelaySeconds: 2
            periodSeconds: 10
            timeoutSeconds: 2
          readinessProbe:
            httpGet:
              path: /readyz
              port: http
            initialDelaySeconds: 2
            periodSeconds: 5
            timeoutSeconds: 2
          startupProbe:
            httpGet:
              path: /startupz
              port: http
            initialDelaySeconds: 1
            periodSeconds: 2
            failureThreshold: 10
          resources:
            requests:
              cpu: 10m
              memory: 32Mi
            limits:
              cpu: 500m
              memory: 128Mi
          securityContext:
            readOnlyRootFilesystem: true
            allowPrivilegeEscalation: false
            runAsNonRoot: true
            runAsUser: 65534
---
apiVersion: v1
kind: Service
metadata:
  name: media-webhook-notifier
  namespace: media
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: media-webhook-notifier
  ports:
    - name: http
      port: 8080
      targetPort: http
```

Apply with:
```bash
kubectl apply -f media-webhook-notifier.yaml
```

---

## 4. Mounting Custom Card Templates

You can customize notification card templates by creating a `templates.yaml` file (see [TEMPLATES.md](TEMPLATES.md) for full syntax and tag references, and the built-in defaults in [src/main/resources/templates.default.yaml](../src/main/resources/templates.default.yaml)).

```bash
# Copy built-in default templates as starter
cp src/main/resources/templates.default.yaml templates.yaml
```

### Docker Compose Example with Custom Templates
```yaml
services:
  media-webhook-notifier:
    image: ghcr.io/hononeko/media-webhook-notifier:latest
    container_name: media-webhook-notifier
    ports:
      - "8080:8080"
    environment:
      - SERVER_AUTH_TOKEN=my-token
      - NOTIFICATION_URL=telegram://123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ@-1001234567890
      - TEMPLATES_FILE=/config/templates.yaml
    volumes:
      - ./templates.yaml:/config/templates.yaml:ro
```

---

## 5. Docker Secrets & File-Based Credentials

For high-security environments, you can provide sensitive secrets via file mounts instead of raw environment variables:

```yaml
services:
  media-webhook-notifier:
    image: ghcr.io/hononeko/media-webhook-notifier:latest
    ports:
      - "8080:8080"
    environment:
      - SERVER_AUTH_TOKEN_FILE=/run/secrets/server_auth_token
      - NOTIFICATION_URL_FILE=/run/secrets/notification_url
      - QBITTORRENT_PASSWORD_FILE=/run/secrets/qbittorrent_password
    secrets:
      - server_auth_token
      - notification_url
      - qbittorrent_password

secrets:
  server_auth_token:
    file: ./secrets/auth_token.txt
  notification_url:
    file: ./secrets/notification_url.txt
  qbittorrent_password:
    file: ./secrets/qb_password.txt
```

---

## 6. Bare Metal / Systemd Service

If running the compiled native binary directly on a Linux server:

1. Build or download the native binary:
   ```bash
   ./gradlew nativeCompile
   sudo cp build/native/nativeCompile/media-webhook-notifier /usr/local/bin/
   ```

2. Create an environment file at `/etc/media-webhook-notifier/config.env`:
   ```bash
   SERVER_PORT=8080
   SERVER_AUTH_TOKEN=your-secure-token
   NOTIFICATION_URL=telegram://123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ@-1001234567890
   QBITTORRENT_URL=http://localhost:8080
   QBITTORRENT_USERNAME=admin
   QBITTORRENT_PASSWORD=adminadmin
   ```

3. Create systemd unit at `/etc/systemd/system/media-webhook-notifier.service`:
   ```ini
   [Unit]
   Description=Media Webhook Notifier
   After=network.target

   [Service]
   Type=simple
   User=nobody
   Group=nogroup
   EnvironmentFile=/etc/media-webhook-notifier/config.env
   ExecStart=/usr/local/bin/media-webhook-notifier
   Restart=always
   RestartSec=5
   LimitNOFILE=65536

   [Install]
   WantedBy=multi-user.target
   ```

4. Enable and start the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now media-webhook-notifier
   sudo systemctl status media-webhook-notifier
   ```
