# Frontend: build Vite, serwowany przez nginx, który przekazuje /api do backendu (jeden origin).
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Czekanie agentów i wiadomości na żywo to długie połączenia (każde zajmuje dwa): domyślne 1024 wystarcza na kilkanaście sklepów.
RUN sed -i -e 's/worker_connections *[0-9]*;/worker_connections 8192;/' -e '1i worker_rlimit_nofile 32768;' /etc/nginx/nginx.conf \
    && grep -q 'worker_connections 8192;' /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
