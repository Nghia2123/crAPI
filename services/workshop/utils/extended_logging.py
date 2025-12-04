import json
import logging
import time
import os
from datetime import datetime

logger = logging.getLogger('extended_http_log')

LOG_DIR = os.environ.get('EXTENDED_LOG_DIR', '/app/logs')
LOG_FILE = os.path.join(LOG_DIR, 'http_extended.jsonl')

class ExtendedHTTPLogMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response
        self.setup_json_logger()

    def setup_json_logger(self):
        os.makedirs(LOG_DIR, exist_ok=True)
        handler = logging.FileHandler(LOG_FILE)
        handler.setFormatter(logging.Formatter('%(message)s'))
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)

    def __call__(self, request):
        start_time = time.time()

        try:
            request_body = request.body.decode('utf-8')
            try:
                request_body = json.loads(request_body)
            except json.JSONDecodeError:
                pass
        except Exception:
            request_body = ""

        response = self.get_response(request)

        try:
            if hasattr(response, 'content'):
                response_body = response.content.decode('utf-8')
                try:
                    response_body = json.loads(response_body)
                except json.JSONDecodeError:
                    pass
            else:
                response_body = ""
        except Exception:
            response_body = ""

        log_entry = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "ip": self.get_client_ip(request),
            "method": request.method,
            "uri": request.get_full_path(),
            "requestBody": request_body,
            "responseBody": response_body,
            "statusCode": response.status_code,
            "headerAuthorization": request.META.get('HTTP_AUTHORIZATION', ''),
            "duration_ms": round((time.time() - start_time) * 1000, 2)
        }

        self.log_async(log_entry)

        return response

    def get_client_ip(self, request):
        x_forwarded_for = request.META.get('HTTP_X_FORWARDED_FOR')
        if x_forwarded_for:
            return x_forwarded_for.split(',')[0].strip()
        return request.META.get('REMOTE_ADDR', '')

    def log_async(self, log_entry):
        logger.info(json.dumps(log_entry, ensure_ascii=False))
