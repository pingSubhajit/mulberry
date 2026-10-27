FROM python:3.14-slim
WORKDIR /lab
COPY server.py .
EXPOSE 8787
CMD ["python", "server.py", "--host", "0.0.0.0", "--database", "/data/lab.sqlite"]
