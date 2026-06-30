FROM node:24-alpine

WORKDIR /app
COPY server/package.json server/package-lock.json ./server/
RUN cd server && npm ci --omit=dev

COPY server/src ./server/src
COPY matriculas_modelos.json ./

ENV NODE_ENV=production
ENV PORT=8787
EXPOSE 8787

USER node
CMD ["node", "server/src/server.js"]
