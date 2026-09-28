use crate::extraction::ExtractionEngine;
use crate::models::{DocumentImage, ExtractedField, ExtractionMethod};
use async_trait::async_trait;
use sha2::{Sha256, Digest};

pub struct NfcEngine;

#[derive(Debug, Clone)]
pub struct BacKeys {
    pub k_enc: Vec<u8>,
    pub k_mac: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct DataGroup {
    pub number: u8,
    pub data: Vec<u8>,
}

impl NfcEngine {
    pub fn new() -> Self {
        Self
    }

    pub fn compute_bac_keys(
        document_number: &str,
        date_of_birth: &str,
        expiry_date: &str,
    ) -> BacKeys {
        let mrz_info = format!("{}{}{}", document_number, date_of_birth, expiry_date);
        let mut hasher = Sha256::new();
        hasher.update(mrz_info.as_bytes());
        let hash = hasher.finalize();
        let seed = &hash[..16];

        let k_enc = Self::derive_key(seed, 1);
        let k_mac = Self::derive_key(seed, 2);

        BacKeys { k_enc, k_mac }
    }

    fn derive_key(seed: &[u8], counter: u8) -> Vec<u8> {
        let mut key = seed.to_vec();
        for b in key.iter_mut() {
            *b ^= counter;
        }
        key
    }

    pub fn compute_pace_keys(can: &str, pin: &str) -> (Vec<u8>, Vec<u8>) {
        let shared_secret = format!("{}{}", can, pin);
        let mut hasher = Sha256::new();
        hasher.update(shared_secret.as_bytes());
        let hash = hasher.finalize();
        let k_enc = hash[..16].to_vec();
        let k_mac = hash[16..32].to_vec();
        (k_enc, k_mac)
    }

    pub fn parse_dg1(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        let text = String::from_utf8_lossy(data);
        let mut fields = Vec::new();

        fields.push(ExtractedField {
            name: "nfc_dg1_type".to_string(),
            value: "MRZ".to_string(),
            confidence: 0.99,
            method: ExtractionMethod::Nfc,
            raw_text: Some(hex::encode(data)),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "nfc_mrz_raw".to_string(),
            value: text.to_string(),
            confidence: 0.99,
            method: ExtractionMethod::Nfc,
            raw_text: Some(text.to_string()),
            bounding_box: None,
        });

        if let Some(result) = crate::extraction::mrz::MrzEngine::parse_any(&text) {
            for field in crate::extraction::mrz::MrzEngine::extract_fields(&result) {
                fields.push(ExtractedField {
                    name: format!("nfc_{}", field.name),
                    value: field.value,
                    confidence: field.confidence * 0.99,
                    method: ExtractionMethod::Nfc,
                    raw_text: field.raw_text,
                    bounding_box: None,
                });
            }
        }

        Ok(fields)
    }

    pub fn parse_dg2(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        let jpeg_start = data.windows(3).position(|w| w == [0xFF, 0xD8, 0xFF]);
        let face_data = if let Some(pos) = jpeg_start {
            let mut end = data.len();
            for i in (pos..data.len().saturating_sub(2)).rev() {
                if data[i] == 0xFF && data[i + 1] == 0xD9 {
                    end = i + 2;
                    break;
                }
            }
            &data[pos..end]
        } else {
            data
        };

        Ok(vec![
            ExtractedField {
                name: "nfc_face_image".to_string(),
                value: base64::engine::general_purpose::STANDARD.encode(face_data),
                confidence: 0.99,
                method: ExtractionMethod::Nfc,
                raw_text: None,
                bounding_box: None,
            },
            ExtractedField {
                name: "nfc_face_image_size".to_string(),
                value: face_data.len().to_string(),
                confidence: 0.99,
                method: ExtractionMethod::Nfc,
                raw_text: None,
                bounding_box: None,
            },
        ])
    }

    pub fn parse_dg3(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        Ok(vec![ExtractedField {
            name: "nfc_fingerprint".to_string(),
            value: base64::engine::general_purpose::STANDARD.encode(data),
            confidence: 0.98,
            method: ExtractionMethod::Nfc,
            raw_text: None,
            bounding_box: None,
        }])
    }

    pub fn parse_dg4(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        Ok(vec![ExtractedField {
            name: "nfc_fingerprint_iris".to_string(),
            value: base64::engine::general_purpose::STANDARD.encode(data),
            confidence: 0.98,
            method: ExtractionMethod::Nfc,
            raw_text: None,
            bounding_box: None,
        }])
    }

    pub fn parse_dg5(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        Ok(vec![ExtractedField {
            name: "nfc_displayed_portrait".to_string(),
            value: base64::engine::general_purpose::STANDARD.encode(data),
            confidence: 0.97,
            method: ExtractionMethod::Nfc,
            raw_text: None,
            bounding_box: None,
        }])
    }

    pub fn parse_dg7(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        Ok(vec![ExtractedField {
            name: "nfc_signature_image".to_string(),
            value: base64::engine::general_purpose::STANDARD.encode(data),
            confidence: 0.97,
            method: ExtractionMethod::Nfc,
            raw_text: None,
            bounding_box: None,
        }])
    }

    pub fn parse_dg11(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        let text = String::from_utf8_lossy(data);
        let mut fields = Vec::new();

        fields.push(ExtractedField {
            name: "nfc_additional_details".to_string(),
            value: text.to_string(),
            confidence: 0.95,
            method: ExtractionMethod::Nfc,
            raw_text: Some(text.to_string()),
            bounding_box: None,
        });

        let fields_to_check = [
            ("nfc_issuing_authority", "issuing authority"),
            ("nfc_place_of_birth", "place of birth"),
            ("nfc_residence", "residence"),
            ("nfc_other_names", "other names"),
        ];

        for (field_name, keyword) in &fields_to_check {
            if text.to_lowercase().contains(keyword) {
                fields.push(ExtractedField {
                    name: field_name.to_string(),
                    value: keyword.to_string(),
                    confidence: 0.85,
                    method: ExtractionMethod::Nfc,
                    raw_text: Some(text.to_string()),
                    bounding_box: None,
                });
            }
        }

        Ok(fields)
    }

    pub fn parse_dg12(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        Ok(vec![ExtractedField {
            name: "nfc_document_image".to_string(),
            value: base64::engine::general_purpose::STANDARD.encode(data),
            confidence: 0.97,
            method: ExtractionMethod::Nfc,
            raw_text: None,
            bounding_box: None,
        }])
    }

    pub fn parse_dg13(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        Ok(vec![ExtractedField {
            name: "nfc_optional_details".to_string(),
            value: String::from_utf8_lossy(data).to_string(),
            confidence: 0.9,
            method: ExtractionMethod::Nfc,
            raw_text: Some(String::from_utf8_lossy(data).to_string()),
            bounding_box: None,
        }])
    }

    pub fn parse_dg14(data: &[u8]) -> anyhow::Result<Vec<ExtractedField>> {
        let fields = vec![
            ExtractedField {
                name: "nfc_security_data".to_string(),
                value: hex::encode(data),
                confidence: 0.95,
                method: ExtractionMethod::Nfc,
                raw_text: None,
                bounding_box: None,
            },
            ExtractedField {
                name: "nfc_chip_auth_present".to_string(),
                value: if data.len() > 4 { "true" } else { "false" }.to_string(),
                confidence: 0.99,
                method: ExtractionMethod::Nfc,
                raw_text: None,
                bounding_box: None,
            },
        ];
        Ok(fields)
    }

    pub fn parse_data_groups(groups: &[DataGroup]) -> Vec<ExtractedField> {
        let mut fields = Vec::new();

        for dg in groups {
            let parsed = match dg.number {
                1 => Self::parse_dg1(&dg.data),
                2 => Self::parse_dg2(&dg.data),
                3 => Self::parse_dg3(&dg.data),
                4 => Self::parse_dg4(&dg.data),
                5 => Self::parse_dg5(&dg.data),
                7 => Self::parse_dg7(&dg.data),
                11 => Self::parse_dg11(&dg.data),
                12 => Self::parse_dg12(&dg.data),
                13 => Self::parse_dg13(&dg.data),
                14 => Self::parse_dg14(&dg.data),
                _ => Ok(vec![ExtractedField {
                    name: format!("nfc_dg{}", dg.number),
                    value: hex::encode(&dg.data),
                    confidence: 0.9,
                    method: ExtractionMethod::Nfc,
                    raw_text: None,
                    bounding_box: None,
                }]),
            };
            if let Ok(f) = parsed {
                fields.extend(f);
            }
        }

        fields.push(ExtractedField {
            name: "nfc_data_groups_found".to_string(),
            value: groups.iter().map(|g| g.number.to_string()).collect::<Vec<_>>().join(","),
            confidence: 1.0,
            method: ExtractionMethod::Nfc,
            raw_text: None,
            bounding_box: None,
        });

        fields
    }

    pub fn simulate_nfc_extraction(
        document_number: &str,
        date_of_birth: &str,
        expiry_date: &str,
    ) -> Vec<ExtractedField> {
        let keys = Self::compute_bac_keys(document_number, date_of_birth, expiry_date);

        let mut fields = vec![
            ExtractedField {
                name: "nfc_bac_keys_generated".to_string(),
                value: "true".to_string(),
                confidence: 1.0,
                method: ExtractionMethod::Nfc,
                raw_text: None,
                bounding_box: None,
            },
            ExtractedField {
                name: "nfc_k_enc".to_string(),
                value: hex::encode(&keys.k_enc),
                confidence: 1.0,
                method: ExtractionMethod::Nfc,
                raw_text: None,
                bounding_box: None,
            },
            ExtractedField {
                name: "nfc_k_mac".to_string(),
                value: hex::encode(&keys.k_mac),
                confidence: 1.0,
                method: ExtractionMethod::Nfc,
                raw_text: None,
                bounding_box: None,
            },
        ];

        let mock_groups = vec![
            DataGroup { number: 1, data: format!("P<UTOSTEVENSON<<HENRY<<<<<<<<<<<<<<<<<<<<<<<<").into_bytes() },
            DataGroup { number: 2, data: vec![0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xD9] },
            DataGroup { number: 11, data: b"PLACE OF BIRTH: LONDON\nISSUING AUTHORITY: UKPA\nRESIDENCE: UK".to_vec() },
            DataGroup { number: 14, data: vec![0x30, 0x82, 0x01, 0x0A, 0x02, 0x01, 0x01] },
        ];

        fields.extend(Self::parse_data_groups(&mock_groups));
        fields
    }
}

#[async_trait]
impl ExtractionEngine for NfcEngine {
    fn name(&self) -> &'static str {
        "nfc"
    }

    async fn extract(&self, _image: &DocumentImage) -> anyhow::Result<Vec<ExtractedField>> {
        Err(anyhow::anyhow!(
            "NFC extraction requires physical document and NFC hardware interface"
        ))
    }

    fn supported_formats(&self) -> Vec<&'static str> {
        vec![]
    }
}
