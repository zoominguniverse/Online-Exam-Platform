FROM node:22.22.2-bookworm-slim@sha256:9f6d5975c7dca860947d3915877f85607946403fc55349f39b4bc3688448bb6e AS build
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web ./
RUN npm run build

FROM nginx:1.30.6-alpine@sha256:583b7f2fce1cdb8da5ad5c39ebd3c33b98b87d493190e548bdee1fa8c619c311
COPY --from=build /build/web/admin/dist /usr/share/nginx/html/admin
COPY --from=build /build/web/student/dist /usr/share/nginx/html/student
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY deploy/nginx/15-exam-routing.envsh /docker-entrypoint.d/15-exam-routing.envsh
RUN mkdir -p /var/lib/exam-routing && chmod +x /docker-entrypoint.d/15-exam-routing.envsh
ENV EXAM_UPSTREAM=api-blue:8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1/readyz || exit 1
