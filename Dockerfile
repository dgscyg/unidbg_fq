FROM eclipse-temurin:25-jdk
ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
WORKDIR /app

# 使用国内镜像源加速
RUN sed -i 's|http://archive.ubuntu.com|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
    sed -i 's|http://archive.ubuntu.com|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list 2>/dev/null || true

# 安装系统依赖 + 手动安装 Maven 3.9.10
RUN apt-get update && apt-get install -y \
    git \
    python3 \
    curl \
    wget \
    unzip \
    lsof \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.10/binaries/apache-maven-3.9.10-bin.tar.gz \
       | tar -xzC /opt \
    && ln -s /opt/apache-maven-3.9.10/bin/mvn /usr/local/bin/mvn

ENV MAVEN_HOME=/opt/apache-maven-3.9.10

RUN git clone https://gh-proxy.org/https://github.com/dgscyg/unidbg_fq.git /app/unidbg
COPY unidbg_onekey.sh /app/unidbg_onekey.sh
# 配置 Maven 阿里云镜像加速依赖下载
RUN mkdir -p /root/.m2 \
    && printf '<?xml version="1.0" encoding="UTF-8"?>\n<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"\n  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">\n  <mirrors>\n    <mirror>\n      <id>aliyunmaven</id>\n      <mirrorOf>*</mirrorOf>\n      <name>aliyun maven</name>\n      <url>https://maven.aliyun.com/repository/public</url>\n    </mirror>\n  </mirrors>\n</settings>\n' > /root/.m2/settings.xml

# 编译项目
WORKDIR /app/unidbg
RUN mvn -q -DskipTests package \
    && rm -rf ~/.m2/repository
WORKDIR /app
RUN chmod +x /app/unidbg_onekey.sh
# 创建启动脚本：仅启动 Web 控制台(7860)，API 服务由面板控制启停
RUN echo '#!/bin/bash\n\
set -e\n\
\n\
# 设置环境变量\n\
export UNIDBG_APP_DIR=/app/unidbg\n\
export UNIDBG_LOG_FILE=/app/unidbg_onekey.log\n\
export UNIDBG_SCRIPT_PATH=/app/unidbg_onekey.sh\n\
export UNIDBG_API_TARGET=http://127.0.0.1:9999\n\
\n\
# 查找 JAR 文件\n\

echo "生成 Web 控制台文件..."\n\
bash /app/unidbg_onekey.sh --action install --dir /app/unidbg 2>/dev/null || true\n\
\n\
# 写入 server.py（监听 7860 端口）\n\
cat << '\''EOF_SERVER'\'' > /app/server.py\n\
import http.server\n\
import socketserver\n\
import subprocess\n\
import json\n\
import os\n\
import urllib.parse\n\
import urllib.request\n\
import urllib.error\n\
import threading\n\
import time\n\
\n\
PORT = 7860\n\
BASE_DIR = os.path.dirname(os.path.abspath(__file__))\n\
SCRIPT_PATH = os.environ.get("UNIDBG_SCRIPT_PATH", os.path.join(BASE_DIR, "unidbg_onekey.sh"))\n\
LOG_FILE = os.environ.get("UNIDBG_LOG_FILE", os.path.join(BASE_DIR, "unidbg_onekey.log"))\n\
APP_DIR = os.environ.get("UNIDBG_APP_DIR", os.path.join(BASE_DIR, "unidbg"))\n\
API_TARGET = os.environ.get("UNIDBG_API_TARGET", "http://127.0.0.1:9999").rstrip("/")\n\
\n\
def json_response(handler, payload, status=200):\n\
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")\n\
    handler.send_response(status)\n\
    handler.send_header("Content-type", "application/json; charset=utf-8")\n\
    handler.send_header("Content-Length", str(len(data)))\n\
    handler.end_headers()\n\
    handler.wfile.write(data)\n\
\n\
def proxy_request(handler, method):\n\
    target_url = f"{API_TARGET}{handler.path}"\n\
    data = None\n\
    if method in ("POST", "PUT", "PATCH"):\n\
        length = int(handler.headers.get("Content-Length", 0))\n\
        if length > 0:\n\
            data = handler.rfile.read(length)\n\
    req = urllib.request.Request(target_url, data=data, method=method)\n\
    for key, value in handler.headers.items():\n\
        lower_key = key.lower()\n\
        if lower_key in ("host", "connection", "content-length", "accept-encoding"):\n\
            continue\n\
        req.add_header(key, value)\n\
    try:\n\
        with urllib.request.urlopen(req, timeout=30) as resp:\n\
            body = resp.read()\n\
            handler.send_response(resp.status)\n\
            for key, value in resp.headers.items():\n\
                lower_key = key.lower()\n\
                if lower_key in ("transfer-encoding", "content-encoding", "connection"):\n\
                    continue\n\
                handler.send_header(key, value)\n\
            handler.end_headers()\n\
            handler.wfile.write(body)\n\
    except Exception as e:\n\
        json_response(handler, {"error": f"proxy_error: {e}"}, status=502)\n\
\n\
class RequestHandler(http.server.SimpleHTTPRequestHandler):\n\
    def do_GET(self):\n\
        parsed_path = urllib.parse.urlparse(self.path)\n\
        path = parsed_path.path\n\
        if path == "/":\n\
            self.path = "/index.html"\n\
            return http.server.SimpleHTTPRequestHandler.do_GET(self)\n\
        if path == "/api/logs":\n\
            content = ""\n\
            if os.path.exists(LOG_FILE):\n\
                try:\n\
                    with open(LOG_FILE, "r", encoding="utf-8", errors="replace") as f:\n\
                        content = f.read()\n\
                except Exception as e:\n\
                    content = f"Error reading log: {str(e)}"\n\
            json_response(self, {"logs": content})\n\
            return\n\
        if path == "/api/files":\n\
            files = []\n\
            target_dirs = [BASE_DIR, APP_DIR, os.path.join(APP_DIR, "target")]\n\
            for d in target_dirs:\n\
                if os.path.exists(d):\n\
                    for f in os.listdir(d):\n\
                        if f.endswith((".txt", ".epub", ".jar", ".log")):\n\
                            full_path = os.path.join(d, f)\n\
                            if os.path.isfile(full_path):\n\
                                rel_path = os.path.relpath(full_path, BASE_DIR).replace(os.sep, "/")\n\
                                files.append({"name": f, "path": rel_path, "size": os.path.getsize(full_path)})\n\
            json_response(self, {"files": files})\n\
            return\n\
        if path.startswith("/api/"):\n\
            proxy_request(self, "GET")\n\
            return\n\
        return http.server.SimpleHTTPRequestHandler.do_GET(self)\n\
    def do_POST(self):\n\
        parsed_path = urllib.parse.urlparse(self.path)\n\
        path = parsed_path.path\n\
        if path == "/api/run":\n\
            content_length = int(self.headers.get("Content-Length", 0))\n\
            post_data = self.rfile.read(content_length)\n\
            try:\n\
                data = json.loads(post_data.decode("utf-8"))\n\
            except Exception:\n\
                json_response(self, {"error": "Invalid JSON"}, status=400)\n\
                return\n\
            action = data.get("action")\n\
            if not action:\n\
                json_response(self, {"error": "Missing action"}, status=400)\n\
                return\n\
            def run_script():\n\
                cmd = ["bash", SCRIPT_PATH, "--action", action, "--dir", APP_DIR]\n\
                try:\n\
                    with open(LOG_FILE, "a", encoding="utf-8") as log:\n\
                        log.write(f"\\n\\n--- Executing: {action} at {time.ctime()} ---\\n")\n\
                        subprocess.run(cmd, stdout=log, stderr=log, cwd=BASE_DIR)\n\
                        log.write(f"\\n--- Finished: {action} ---\\n")\n\
                except Exception as e:\n\
                    with open(LOG_FILE, "a", encoding="utf-8") as log:\n\
                        log.write(f"\\nError executing {action}: {e}\\n")\n\
            threading.Thread(target=run_script, daemon=True).start()\n\
            json_response(self, {"status": "started", "action": action})\n\
            return\n\
        if path.startswith("/api/"):\n\
            proxy_request(self, "POST")\n\
            return\n\
        json_response(self, {"error": "Not found"}, status=404)\n\
\n\
class ReusableTCPServer(socketserver.TCPServer):\n\
    allow_reuse_address = True\n\
\n\
os.chdir(BASE_DIR)\n\
print(f"Server starting on port {PORT}...")\n\
print(f"Open http://localhost:{PORT} in your browser")\n\
with ReusableTCPServer(("0.0.0.0", PORT), RequestHandler) as httpd:\n\
    httpd.serve_forever()\n\
EOF_SERVER\n\
\n\
echo "启动 Web 控制台 (端口 7860)..."\n\
python3 /app/server.py &\n\
WEB_PID=$!\n\
\n\
echo "服务已启动:"\n\
echo "  - API 服务: http://localhost:9999"\n\
echo "  - Web 控制台: http://localhost:7860"\n\
\n\
# 等待任一进程退出\n\
wait $WEB_PID\n\
' > /app/start.sh && chmod +x /app/start.sh
RUN bash -c 'sed -n "/^cat << .EOF_INDEX_HTML/,/^EOF_INDEX_HTML$/p" /app/unidbg_onekey.sh | tail -n +2 | head -n -1 > /app/index.html'
# 暴露端口
EXPOSE 7860 9999
# 启动命令
CMD ["/app/start.sh"]