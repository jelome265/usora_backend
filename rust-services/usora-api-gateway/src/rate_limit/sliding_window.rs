use redis::aio::ConnectionManager;

pub struct SlidingWindowRateLimiter {
    redis: ConnectionManager,
    prefix: String,
}

impl SlidingWindowRateLimiter {
    pub fn new(redis: ConnectionManager, prefix: Option<String>) -> Self {
        Self {
            redis,
            prefix: prefix.unwrap_or_else(|| "sliding_window".to_string()),
        }
    }

    pub async fn allow_request(&self, key: &str, max_requests: u64, window_ms: u64) -> Result<bool, redis::RedisError> {
        let redis_key = format!("{}:sw:{}", self.prefix, key);
        let now = chrono::Utc::now().timestamp_millis() as u64;
        let window_start = now.saturating_sub(window_ms);
        let mut con = self.redis.clone();

        let script = r#"
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window_start = tonumber(ARGV[2])
            local max_req = tonumber(ARGV[3])

            redis.call("ZREMRANGEBYSCORE", key, "-inf", window_start)
            local count = redis.call("ZCARD", key)

            if count < max_req then
                redis.call("ZADD", key, now, now .. ":" .. math.random())
                redis.call("EXPIRE", key, math.ceil((now - window_start) / 1000) + 1)
                return 1
            else
                return 0
            end
        "#;

        let result: i64 = redis::cmd("EVAL")
            .arg(script)
            .arg(1)
            .arg(&redis_key)
            .arg(now)
            .arg(window_start)
            .arg(max_requests)
            .query_async(&mut con)
            .await?;

        Ok(result == 1)
    }
}
