pub mod ingestion;
pub mod postprocessing;
pub mod preprocessing;

use crate::models::{DocumentImage, ProcessedDocument};
use async_trait::async_trait;

#[async_trait]
pub trait PipelineStage: Send + Sync {
    fn name(&self) -> &'static str;
    async fn process(&self, ctx: &mut PipelineContext) -> anyhow::Result<()>;
}

pub struct PipelineContext {
    pub image: DocumentImage,
    pub document: Option<ProcessedDocument>,
    pub stage_results: Vec<StageResult>,
    pub errors: Vec<String>,
}

impl PipelineContext {
    pub fn new(image: DocumentImage) -> Self {
        Self {
            image,
            document: None,
            stage_results: Vec::new(),
            errors: Vec::new(),
        }
    }

    pub fn add_result(&mut self, stage: &str, success: bool, detail: String) {
        self.stage_results.push(StageResult {
            stage: stage.to_string(),
            success,
            detail,
        });
    }

    pub fn has_errors(&self) -> bool {
        !self.errors.is_empty()
    }
}

pub struct StageResult {
    pub stage: String,
    pub success: bool,
    pub detail: String,
}

pub struct ProcessingPipeline {
    stages: Vec<Box<dyn PipelineStage>>,
}

impl ProcessingPipeline {
    pub fn new() -> Self {
        Self {
            stages: Vec::new(),
        }
    }

    pub fn add_stage(mut self, stage: Box<dyn PipelineStage>) -> Self {
        self.stages.push(stage);
        self
    }

    pub async fn execute(&self, mut ctx: PipelineContext) -> anyhow::Result<PipelineContext> {
        for stage in &self.stages {
            let stage_name = stage.name();
            tracing::info!("Running pipeline stage: {}", stage_name);
            match stage.process(&mut ctx).await {
                Ok(()) => {
                    ctx.add_result(stage_name, true, format!("Stage '{}' completed", stage_name));
                    tracing::info!("Pipeline stage '{}' succeeded", stage_name);
                }
                Err(e) => {
                    let err_msg = format!("Stage '{}' failed: {}", stage_name, e);
                    ctx.add_result(stage_name, false, err_msg.clone());
                    ctx.errors.push(err_msg);
                    tracing::error!("Pipeline stage '{}' failed: {}", stage_name, e);
                }
            }
        }
        Ok(ctx)
    }
}

impl Default for ProcessingPipeline {
    fn default() -> Self {
        Self::new()
    }
}

pub struct PipelineBuilder {
    stages: Vec<Box<dyn PipelineStage>>,
}

impl PipelineBuilder {
    pub fn new() -> Self {
        Self {
            stages: Vec::new(),
        }
    }

    pub fn with_preprocessing(mut self) -> Self {
        self.stages.push(Box::new(preprocessing::PreprocessingStage));
        self
    }

    pub fn with_ingestion(mut self) -> Self {
        self.stages.push(Box::new(ingestion::IngestionStage));
        self
    }

    pub fn with_postprocessing(mut self) -> Self {
        self.stages.push(Box::new(postprocessing::PostprocessingStage));
        self
    }

    pub fn build(self) -> ProcessingPipeline {
        ProcessingPipeline {
            stages: self.stages,
        }
    }
}

impl Default for PipelineBuilder {
    fn default() -> Self {
        Self::new()
    }
}
