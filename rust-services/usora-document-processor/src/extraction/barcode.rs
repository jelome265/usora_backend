use crate::extraction::ExtractionEngine;
use crate::models::{DocumentImage, ExtractedField, ExtractionMethod};
use async_trait::async_trait;
use rxing::{BarcodeFormat, DecodingHintDictionary, RXingResult, Result as RxingResult};

pub struct BarcodeEngine;

impl BarcodeEngine {
    pub fn new() -> Self {
        Self
    }

    fn decode_with_hints(
        data: &[u8],
        width: usize,
        height: usize,
        format: BarcodeFormat,
    ) -> Vec<RXingResult> {
        let mut hints = DecodingHintDictionary::new();
        let mut possible_formats = rxing::DecodeHintType::PossibleFormats(Vec::new());
        if let rxing::DecodeHintType::PossibleFormats(ref mut formats) = possible_formats {
            formats.push(format);
        }
        hints.insert(
            rxing::DecodeHintType::PossibleFormats(Vec::new()),
            possible_formats,
        );

        let mut reader = rxing::MultiUseReader::default();
        match reader.decode_with_hints(data, width, height, &hints) {
            Ok(result) => vec![result],
            Err(_) => Vec::new(),
        }
    }

    fn try_decode_all(data: &[u8], width: usize, height: usize) -> Vec<(String, String)> {
        let mut results = Vec::new();
        let formats = [
            (BarcodeFormat::PDF_417, "PDF417"),
            (BarcodeFormat::QR_CODE, "QR_CODE"),
            (BarcodeFormat::DATA_MATRIX, "DATA_MATRIX"),
            (BarcodeFormat::AZTEC, "AZTEC"),
            (BarcodeFormat::CODE_39, "CODE_39"),
            (BarcodeFormat::CODE_128, "CODE_128"),
            (BarcodeFormat::EAN_13, "EAN_13"),
            (BarcodeFormat::EAN_8, "EAN_8"),
            (BarcodeFormat::UPC_A, "UPC_A"),
            (BarcodeFormat::UPC_E, "UPC_E"),
            (BarcodeFormat::CODABAR, "CODABAR"),
            (BarcodeFormat::CODE_93, "CODE_93"),
            (BarcodeFormat::ITF, "ITF"),
            (BarcodeFormat::MAXICODE, "MAXICODE"),
            (BarcodeFormat::RSS_14, "RSS_14"),
            (BarcodeFormat::RSS_EXPANDED, "RSS_EXPANDED"),
        ];

        for &(format, name) in &formats {
            let decoded = Self::decode_with_hints(data, width, height, format);
            for r in decoded {
                let text = r.getText();
                results.push((name.to_string(), text));
            }
        }

        if results.is_empty() {
            let mut reader = rxing::MultiUseReader::default();
            if let Ok(result) =
                reader.decode_with_hints(data, width, height, &DecodingHintDictionary::new())
            {
                let format_name = format!("{:?}", result.getBarcodeFormat());
                results.push((format_name, result.getText()));
            }
        }

        results
    }
}

#[async_trait]
impl ExtractionEngine for BarcodeEngine {
    fn name(&self) -> &'static str {
        "barcode"
    }

    async fn extract(&self, image: &DocumentImage) -> anyhow::Result<Vec<ExtractedField>> {
        let img = image::load_from_memory(&image.data)?;
        let (width, height) = img.dimensions();
        let gray = img.to_luma8();

        let raw = gray.as_raw();
        let results = Self::try_decode_all(raw, width as usize, height as usize);

        if results.is_empty() {
            anyhow::bail!("No barcodes found in document image");
        }

        let fields: Vec<ExtractedField> = results
            .into_iter()
            .enumerate()
            .flat_map(|(i, (barcode_type, data))| {
                vec![
                    ExtractedField {
                        name: format!("barcode_{}_type", i),
                        value: barcode_type,
                        confidence: 0.95,
                        method: ExtractionMethod::Barcode,
                        raw_text: Some(data.clone()),
                        bounding_box: None,
                    },
                    ExtractedField {
                        name: format!("barcode_{}_data", i),
                        value: data,
                        confidence: 0.95,
                        method: ExtractionMethod::Barcode,
                        raw_text: None,
                        bounding_box: None,
                    },
                ]
            })
            .collect();

        Ok(fields)
    }

    fn supported_formats(&self) -> Vec<&'static str> {
        vec!["image/png", "image/jpeg", "image/tiff", "image/bmp"]
    }
}
