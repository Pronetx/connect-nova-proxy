FROM alpine:3
RUN apk update && apk add openjdk21-jre-headless
RUN addgroup voipgw && adduser -G voipgw -S voipgw
RUN mkdir -p /app && chmod 755 /app && chown voipgw:voipgw /app
COPY s2s-voip-gateway-*.jar /app/s2s-voip-gateway.jar
RUN chmod 644 /app/s2s-voip-gateway.jar
USER voipgw
CMD ["java","-jar","/app/s2s-voip-gateway.jar"]
