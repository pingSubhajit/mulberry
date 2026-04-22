FROM node:22-alpine AS deps
WORKDIR /app/apps/backend
COPY apps/backend/package.json apps/backend/package-lock.json ./
RUN npm ci

FROM deps AS build
COPY apps/backend/tsconfig.json ./
COPY apps/backend/src ./src
COPY apps/backend/test ./test
RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app/apps/backend
ENV NODE_ENV=production
COPY apps/backend/package.json apps/backend/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/apps/backend/dist ./dist
EXPOSE 8080
CMD ["node", "dist/src/server.js"]
