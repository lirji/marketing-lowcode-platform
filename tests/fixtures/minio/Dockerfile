FROM alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.description="CI-only source build of MinIO with its catalog provisioning client"
RUN apk add --no-cache ca-certificates \
    && addgroup -g 10001 minio && adduser -D -u 10001 -G minio minio \
    && mkdir -p /data /licenses && chown 10001:10001 /data
COPY --chmod=755 bin/minio bin/mc /usr/local/bin/
COPY bin/MINIO-LICENSE bin/MC-LICENSE /licenses/
USER 10001:10001
EXPOSE 9000 9001
HEALTHCHECK --interval=10s --timeout=5s --retries=20 \
  CMD wget -q -O /dev/null http://127.0.0.1:9000/minio/health/live || exit 1
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
