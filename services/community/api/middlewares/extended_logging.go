package middlewares

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

type LogEntry struct {
	Timestamp           string      `json:"timestamp"`
	IP                  string      `json:"ip"`
	Method              string      `json:"method"`
	URI                 string      `json:"uri"`
	RequestBody         interface{} `json:"requestBody"`
	ResponseBody        interface{} `json:"responseBody"`
	StatusCode          int         `json:"statusCode"`
	HeaderAuthorization string      `json:"headerAuthorization"`
	DurationMS          int64       `json:"duration_ms"`
}

type loggingResponseWriter struct {
	http.ResponseWriter
	statusCode int
	body       *bytes.Buffer
}

func (rw *loggingResponseWriter) WriteHeader(code int) {
	rw.statusCode = code
	rw.ResponseWriter.WriteHeader(code)
}

func (rw *loggingResponseWriter) Write(b []byte) (int, error) {
	rw.body.Write(b)
	return rw.ResponseWriter.Write(b)
}

var (
	logFile  *os.File
	logMutex sync.Mutex
)

func init() {
	logDir := os.Getenv("EXTENDED_LOG_DIR")
	if logDir == "" {
		logDir = "/app/logs"
	}
	os.MkdirAll(logDir, 0755)

	var err error
	logFile, err = os.OpenFile(logDir+"/http_extended.jsonl",
		os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		log.Printf("Warning: Could not open log file: %v", err)
	}
}

func ExtendedLoggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		startTime := time.Now()

		var requestBody interface{}
		if r.Body != nil {
			bodyBytes, _ := io.ReadAll(r.Body)
			r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))

			if len(bodyBytes) > 0 {
				if err := json.Unmarshal(bodyBytes, &requestBody); err != nil {
					requestBody = string(bodyBytes)
				}
			} else {
				requestBody = ""
			}
		}

		wrapped := &loggingResponseWriter{
			ResponseWriter: w,
			statusCode:     http.StatusOK,
			body:           &bytes.Buffer{},
		}

		next.ServeHTTP(wrapped, r)

		var responseBody interface{}
		if wrapped.body.Len() > 0 {
			if err := json.Unmarshal(wrapped.body.Bytes(), &responseBody); err != nil {
				responseBody = wrapped.body.String()
			}
		} else {
			responseBody = ""
		}

		entry := LogEntry{
			Timestamp:           time.Now().UTC().Format(time.RFC3339),
			IP:                  getClientIP(r),
			Method:              r.Method,
			URI:                 r.URL.RequestURI(),
			RequestBody:         requestBody,
			ResponseBody:        responseBody,
			StatusCode:          wrapped.statusCode,
			HeaderAuthorization: r.Header.Get("Authorization"),
			DurationMS:          time.Since(startTime).Milliseconds(),
		}

		go writeLogEntry(entry)
	})
}

func getClientIP(r *http.Request) string {
	xff := r.Header.Get("X-Forwarded-For")
	if xff != "" {
		return xff
	}
	return r.RemoteAddr
}

func writeLogEntry(entry LogEntry) {
	if logFile == nil {
		return
	}

	logMutex.Lock()
	defer logMutex.Unlock()

	data, err := json.Marshal(entry)
	if err != nil {
		log.Printf("Error marshaling log entry: %v", err)
		return
	}

	logFile.Write(data)
	logFile.Write([]byte("\n"))
}
