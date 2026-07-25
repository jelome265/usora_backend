use crate::models::{ExtractedField, ExtractionMethod};
use crate::pipeline::{PipelineContext, PipelineStage};
use async_trait::async_trait;
use std::collections::HashMap;

pub struct PostprocessingStage;

impl PostprocessingStage {
    fn deduplicate_fields(fields: Vec<ExtractedField>) -> Vec<ExtractedField> {
        let mut field_map: HashMap<String, ExtractedField> = HashMap::new();

        for field in fields {
            let key = field.name.clone();
            field_map
                .entry(key)
                .and_modify(|existing| {
                    if field.confidence > existing.confidence {
                        *existing = field.clone();
                    }
                })
                .or_insert(field);
        }

        field_map.into_values().collect()
    }

    fn cross_validate_fields(fields: &mut Vec<ExtractedField>) {
        let mrz_dob = fields
            .iter()
            .find(|f| f.name == "date_of_birth" && f.method == ExtractionMethod::Mrz)
            .map(|f| f.value.clone());

        let ocr_dob = fields
            .iter()
            .find(|f| f.name == "date_of_birth" && f.method == ExtractionMethod::Ocr)
            .map(|f| f.value.clone());

        if let (Some(mrz), Some(ref ocr)) = (&mrz_dob, &ocr_dob) {
            if mrz != ocr {
                if let Some(field) = fields
                    .iter_mut()
                    .find(|f| f.name == "date_of_birth" && f.method == ExtractionMethod::Mrz)
                {
                    field.confidence *= 0.8;
                }
            }
        }

        let mrz_name = fields
            .iter()
            .find(|f| f.name == "surname" && f.method == ExtractionMethod::Mrz)
            .map(|f| f.value.clone());

        let ocr_name = fields
            .iter()
            .find(|f| f.name == "surname" && f.method == ExtractionMethod::Ocr)
            .map(|f| f.value.clone());

        if let (Some(mrz), Some(ref ocr)) = (&mrz_name, &ocr_name) {
            if mrz.to_lowercase() == ocr.to_lowercase() {
                if let Some(field) = fields
                    .iter_mut()
                    .find(|f| f.name == "surname" && f.method == ExtractionMethod::Ocr)
                {
                    field.confidence = (field.confidence + 0.95) / 2.0;
                }
            }
        }
    }

    fn aggregate_confidence(fields: &[ExtractedField]) -> f32 {
        if fields.is_empty() {
            return 0.0;
        }
        let total: f32 = fields.iter().map(|f| f.confidence).sum();
        total / fields.len() as f32
    }

    fn format_result(fields: Vec<ExtractedField>) -> Vec<ExtractedField> {
        fields
    }
}

#[async_trait]
impl PipelineStage for PostprocessingStage {
    fn name(&self) -> &'static str {
        "postprocessing"
    }

    async fn process(&self, ctx: &mut PipelineContext) -> anyhow::Result<()> {
        if let Some(ref doc) = ctx.document {
            let mut fields = doc.data.fields.clone();
            fields = Self::deduplicate_fields(fields);
            Self::cross_validate_fields(&mut fields);
            let overall_confidence = Self::aggregate_confidence(&fields);
            let formatted = Self::format_result(fields);

            if let Some(ref mut d) = ctx.document {
                d.data.fields = formatted;
            }

            tracing::info!(
                "Post-processing complete - overall confidence: {:.2}",
                overall_confidence
            );
        } else {
            tracing::warn!("Post-processing stage called but no document in context");
        }

        Ok(())
    }
}
