# Build stage
FROM node:24-alpine AS builder

WORKDIR /app

# Copy package files
COPY package.json package-lock.json ./

# Install dependencies
RUN npm ci

# Copy source code (.env injected by Jenkins via build secret, not put into image)
COPY . .

# Build app: env mounted as secret, only visible during build, not written to image layer
RUN --mount=type=secret,id=env,target=/app/.env npm run build

# Runtime stage
FROM nginx:1.25-alpine

RUN apk add --no-cache gettext

ENV BACKEND_HOST=dublin-bikes-backend
ENV BACKEND_PORT=8080

# Copy build output to nginx directory
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx template and custom entrypoint script (referencing firmament-take-out-admin)
COPY deploy/nginx/admin.conf.tpl /etc/nginx/templates/default.conf.template
COPY deploy/nginx/docker-entrypoint.d/99-envsubst.sh /docker-entrypoint.d/99-envsubst.sh
RUN chmod +x /docker-entrypoint.d/99-envsubst.sh

# Expose port
EXPOSE 80

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
