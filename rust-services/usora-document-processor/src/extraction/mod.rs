pub mod barcode;
pub mod mrz;
pub mod nfc;

use crate::models::{DocumentImage, ExtractedField};
use async_trait::async_trait;
use std::sync::Arc;

#[async_trait]
pub trait ExtractionEngine: Send + Sync {
    fn name(&self) -> &'static str;
    async fn extract(&self, image: &DocumentImage) -> anyhow::Result<Vec<ExtractedField>>;
    fn supported_formats(&self) -> Vec<&'static str>;
}

pub struct Extractor {
    engines: Vec<Arc<dyn ExtractionEngine>>,
}

impl Extractor {
    pub fn new() -> Self {
        Self {
            engines: Vec::new(),
        }
    }

    pub fn with_engine(mut self, engine: Arc<dyn ExtractionEngine>) -> Self {
        self.engines.push(engine);
        self
    }

    pub fn engines(&self) -> &[Arc<dyn ExtractionEngine>] {
        &self.engines
    }

    pub async fn extract_all(&self, image: &DocumentImage) -> anyhow::Result<Vec<ExtractedField>> {
        let mut all_fields = Vec::new();
        for engine in &self.engines {
            match engine.extract(image).await {
                Ok(fields) => all_fields.extend(fields),
                Err(e) => {
                    tracing::warn!("Extraction engine {} failed: {:?}", engine.name(), e);
                }
            }
        }
        Ok(all_fields)
    }

    pub async fn extract_from(
        &self,
        image: &DocumentImage,
        engine_names: &[&str],
    ) -> anyhow::Result<Vec<ExtractedField>> {
        let mut all_fields = Vec::new();
        for engine in &self.engines {
            if engine_names.contains(&engine.name()) {
                match engine.extract(image).await {
                    Ok(fields) => all_fields.extend(fields),
                    Err(e) => {
                        tracing::warn!("Extraction engine {} failed: {:?}", engine.name(), e);
                    }
                }
            }
        }
        Ok(all_fields)
    }
}

impl Default for Extractor {
    fn default() -> Self {
        Self::new()
    }
}
