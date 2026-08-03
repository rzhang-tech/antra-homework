# bookstore-web — the Angular frontend, built and served.
#
# Same two-stage shape as the eight service images (10a): a build stage carrying a toolchain that never
# ships, and a runtime stage carrying only the output. The proportions are more extreme here — Node,
# npm and node_modules are hundreds of megabytes, and what reaches production is a few hundred
# kilobytes of static files plus nginx.
#
#   docker build -t bookstore/bookstore-web:latest bookstore-web/

# ---------------------------------------------------------------------------------------------------
# STAGE 1 — build. Node, npm and the whole dependency tree. None of it ships.
# ---------------------------------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

# package.json and the lockfile first, on their own. An edit to a component then re-runs only the build
# below rather than re-installing every dependency — the same layer-ordering argument the Java images
# make, and it matters more here because npm install is the slow half.
COPY package.json package-lock.json ./

# `npm ci`, not `npm install`. ci installs exactly what the lockfile pins and fails if package.json and
# the lockfile disagree; install is free to resolve something newer, which makes the image a function of
# when it was built rather than of the commit. That is the same property the SHA image tags exist for.
RUN npm ci --no-audit --no-fund

COPY . .

# Production build: ahead-of-time compilation, minification, and hashed filenames — which is what makes
# the aggressive cache headers in nginx.conf safe.
RUN npm run build -- --configuration production

# ---------------------------------------------------------------------------------------------------
# STAGE 2 — runtime. nginx and static files. No Node, no npm, no source.
# ---------------------------------------------------------------------------------------------------
FROM nginx:1.27-alpine

# Angular 17+ writes to dist/<project>/browser rather than dist/<project>. Getting this wrong produces
# a container that starts, passes its health check, and serves nginx's default welcome page — a
# failure that looks like success, which is why it is called out rather than left as a path.
COPY --from=build /build/dist/bookstore-web/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 8080 rather than 80, so the container needs no privileged port and can run as a non-root user.
# nginx:alpine ships an `nginx` user for exactly this.
RUN touch /var/run/nginx.pid \
 && chown -R nginx:nginx /var/run/nginx.pid /var/cache/nginx /usr/share/nginx/html
USER nginx

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
