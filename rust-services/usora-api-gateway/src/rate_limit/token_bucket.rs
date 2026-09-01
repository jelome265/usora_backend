use std::sync::Arc;
use std::time::Instant;
use redis::aio::ConnectionManager;
use tokio::sync::Mutex;

#[derive(Clone)]
pub struct TokenBucket {
    tokens: Arc<Mutex<Inner>>,
    max_tokens: f64,
    refill_rate: f64,
}

#[derive(Clone)]
struct Inner {
    tokens: f64,
    last_refill: Instant,
}

impl TokenBucket {
    pub fn new(rps: u64, burst_size: u64, window_ms: u64) -> Self {
        let max_tokens = burst_size as f64;
        let refill_rate = rps as f64 / window_ms as f64;
        Self {
            tokens: Arc::new(Mutex::new(Inner {
                tokens: max_tokens,
                last_refill: Instant::now(),
            })),
            max_tokens,
            refill_rate,
        }
    }

    pub fn consume(&mut self, tokens: u64) -> bool {
        let mut inner = self.tokens.blocking_lock();
        let now = Instant::now();
        let elapsed = now.duration_since(inner.last_refill).as_secs_f64() * 1000.0;
        inner.tokens = (inner.tokens + elapsed * self.refill_rate).min(self.max_tokens);
        inner.last_refill = now;

        if inner.tokens >= tokens as f64 {
            inner.tokens -= tokens as f64;
            true
        } else {
            false
        }
    }

    pub async fn consume_async(&self, tokens: u64) -> bool {
        let mut inner = self.tokens.lock().await;
        let now = Instant::now();
        let elapsed = now.duration_since(inner.last_refill).as_secs_f64() * 1000.0;
        inner.tokens = (inner.tokens + elapsed * self.refill_rate).min(self.max_tokens);
        inner.last_refill = now;

        if inner.tokens >= tokens as f64 {
            inner.tokens -= tokens as f64;
            true
        } else {
            false
        }
    }

    pub fn remaining_sync(&self) -> f64 {
        let inner = self.tokens.blocking_lock();
        let now = Instant::now();
        let elapsed = now.duration_since(inner.last_refill).as_secs_f64() * 1000.0;
        (inner.tokens + elapsed * self.refill_rate).min(self.max_tokens)
    }

    pub async fn remaining_async(&self) -> f64 {
        let inner = self.tokens.lock().await;
        let now = Instant::now();
        let elapsed = now.duration_since(inner.last_refill).as_secs_f64() * 1000.0;
        (inner.tokens + elapsed * self.refill_rate).min(self.max_tokens)
    }

    pub fn reset_in_sync(&self) -> Option<u64> {
        let inner = self.tokens.blocking_lock();
        let now = Instant::now();
        let elapsed = now.duration_since(inner.last_refill).as_millis() as u64;
        let current_tokens = (inner.tokens + elapsed as f64 * self.refill_rate).min(self.max_tokens);
        if current_tokens >= self.max_tokens - 0.5 {
            return Some(0);
        }
        let deficit = self.max_tokens - current_tokens;
        let ms_remaining = (deficit / self.refill_rate) as u64;
        Some(ms_remaining)
    }

    pub async fn reset_in_async(&self) -> Option<u64> {
        let inner = self.tokens.lock().await;
        let now = Instant::now();
        let elapsed = now.duration_since(inner.last_refill).as_millis() as u64;
        let current_tokens = (inner.tokens + elapsed as f64 * self.refill_rate).min(self.max_tokens);
        if current_tokens >= self.max_tokens - 0.5 {
            return Some(0);
        }
        let deficit = self.max_tokens - current_tokens;
        let ms_remaining = (deficit / self.refill_rate) as u64;
        Some(ms_remaining)
    }
}

pub struct RedisTokenBucket {
    redis: ConnectionManager,
    prefix: String,
}

impl RedisTokenBucket {
    pub fn new(redis: ConnectionManager, prefix: Option<String>) -> Self {
        Self {
            redis,
            prefix: prefix.unwrap_or_else(|| "rate_limit".to_string()),
        }
    }

    pub async fn consume(&self, key: &str, max_tokens: u64, refill_seconds: u64) -> Result<bool, redis::RedisError> {
        let redis_key = format!("{}:token_bucket:{}", self.prefix, key);
        let now = chrono::Utc::now().timestamp_millis() as u64;
        let mut con = self.redis.clone();

        let script = r#"
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local max_tokens = tonumber(ARGV[2])
            local refill_seconds = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])

            local last_tokens = redis.call("get", key .. ":tokens")
            local last_refill = redis.call("get", key .. ":ts")

            if last_tokens == false then
                last_tokens = max_tokens
                last_refill = now
            else
                last_tokens = tonumber(last_tokens)
                last_refill = tonumber(last_refill)
            end

            local elapsed = math.max(0, (now - last_refill) / 1000)
            local refill = elapsed / refill_seconds * max_tokens
            local tokens = math.min(max_tokens, last_tokens + refill)

            if tokens >= cost then
                tokens = tokens - cost
                redis.call("set", key .. ":tokens", tokens)
                redis.call("set", key .. ":ts", now)
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
            .arg(max_tokens)
            .arg(refill_seconds)
            .arg(1u64)
            .query_async(&mut con)
            .await?;

        Ok(result == 1)
    }
}
