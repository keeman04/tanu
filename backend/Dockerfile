FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py device_auth.py secure_app.py ./
RUN mkdir -p /data

ENV PYTHONUNBUFFERED=1
ENV MAI_AUTH_DB=/data/mai-auth.sqlite3
EXPOSE 8080
CMD ["uvicorn", "secure_app:app", "--host", "0.0.0.0", "--port", "8080"]
