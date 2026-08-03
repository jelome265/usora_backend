pub mod sliding_window;
pub mod token_bucket;

#[allow(async_fn_in_trait)]
pub trait RateLimiter: Send + Sync + 'static {
    async fn check_rate_limit(&self, key: &str, max_requests: u64, window_ms: u64) -> bool;
    async fn consume(&self, key: &str, tokens: u64) -> bool;
    async fn remaining(&self, key: &str, max_requests: u64) -> u64;
    async fn reset_in(&self, key: &str) -> Option<u64>;
}

impl RateLimiter for token_bucket::TokenBucket {
    async fn check_rate_limit(&self, _key: &str, _max_requests: u64, _window_ms: u64) -> bool {
        self.consume_async(1).await
    }

    async fn consume(&self, _key: &str, tokens: u64) -> bool {
        self.consume_async(tokens).await
    }

    async fn remaining(&self, _key: &str, _max_requests: u64) -> u64 {
        self.remaining_async().await as u64
    }

    async fn reset_in(&self, _key: &str) -> Option<u64> {
        self.reset_in_async().await
    }
}
