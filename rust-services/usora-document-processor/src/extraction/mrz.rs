use crate::extraction::ExtractionEngine;
use crate::models::{DocumentImage, ExtractedField, ExtractionMethod, MrzFormat, MrzResult};
use async_trait::async_trait;
use std::collections::HashMap;

const WEIGHT: [i32; 10] = [7, 3, 1, 7, 3, 1, 7, 3, 1, 7];

pub struct MrzEngine;

impl MrzEngine {
    pub fn new() -> Self {
        Self
    }

    fn character_value(c: char) -> Option<i32> {
        match c {
            '0'..='9' => Some(c as i32 - '0' as i32),
            'A'..='Z' => Some(c as i32 - 'A' as i32 + 10),
            '<' => Some(0),
            _ => None,
        }
    }

    fn validate_checksum(data: &str, check_digit: char) -> bool {
        let data = &data[..data.len().min(data.len())];
        let check = check_digit;
        if check == '<' {
            return true;
        }
        let expected = Self::compute_checksum(data);
        expected == Self::character_value(check).unwrap_or(-1)
    }

    fn compute_checksum(data: &str) -> i32 {
        let mut sum = 0i32;
        for (i, c) in data.chars().enumerate() {
            if let Some(val) = Self::character_value(c) {
                sum += val * WEIGHT[i % 10];
            }
        }
        sum % 10
    }

    fn compute_checksum_for_string(data: &str) -> i32 {
        Self::compute_checksum(data)
    }

    pub fn parse_td1(line1: &str, line2: &str, line3: &str) -> Option<MrzResult> {
        if line1.len() < 30 || line2.len() < 30 || line3.len() < 30 {
            return None;
        }

        let document_type = &line1[0..2];
        let issuing_country = &line1[2..5];
        let document_number = &line2[..9];
        let doc_num_check = line2.chars().nth(9)?;
        let optional_data1 = &line2[10..20];
        let optional_check = line2.chars().nth(20)?;
        let date_of_birth = &line3[..6];
        let dob_check = line3.chars().nth(6)?;
        let sex = &line3[7..8];
        let expiry_date = &line3[8..14];
        let exp_check = line3.chars().nth(14)?;
        let nationality = &line3[15..18];
        let optional_data2 = &line3[18..29];
        let final_check = line3.chars().nth(29)?;

        let composite = format!(
            "{}{}{}{}{}{}{}{}{}",
            document_number, optional_data1, date_of_birth, expiry_date, optional_data2
        );
        let expected_final = Self::compute_checksum_for_string(&composite);

        let surname_given = parse_name(&line1[5..29]);

        Some(MrzResult {
            document_type: document_type.to_string(),
            issuing_country: issuing_country.to_string(),
            document_number: clean_text(document_number),
            expiry_date: clean_text(expiry_date),
            date_of_birth: clean_text(date_of_birth),
            sex: sex.to_string(),
            nationality: nationality.to_string(),
            surname: surname_given.0,
            given_names: surname_given.1,
            optional_data: Some(format!("{}{}", optional_data1, optional_data2)),
            raw_text: format!("{}\n{}\n{}", line1, line2, line3),
            checksums_valid: {
                Self::validate_checksum(document_number, doc_num_check)
                    && Self::validate_checksum(date_of_birth, dob_check)
                    && Self::validate_checksum(expiry_date, exp_check)
                    && expected_final == Self::character_value(final_check).unwrap_or(-1)
            },
            format: MrzFormat::Td1,
        })
    }

    pub fn parse_td2(line1: &str, line2: &str) -> Option<MrzResult> {
        if line1.len() < 36 || line2.len() < 36 {
            return Self::parse_td3(line1, line2);
        }

        let document_type = &line1[0..2];
        let issuing_country = &line1[2..5];
        let surname_given = parse_name(&line1[5..36]);
        let document_number = &line2[..9];
        let doc_num_check = line2.chars().nth(9)?;
        let nationality = &line2[10..13];
        let date_of_birth = &line2[13..19];
        let dob_check = line2.chars().nth(19)?;
        let sex = &line2[20..21];
        let expiry_date = &line2[21..27];
        let exp_check = line2.chars().nth(27)?;
        let optional_data = &line2[28..35];
        let final_check = line2.chars().nth(35)?;

        let composite = format!(
            "{}{}{}{}",
            document_number, date_of_birth, expiry_date, optional_data
        );
        let expected_final = Self::compute_checksum_for_string(&composite);

        Some(MrzResult {
            document_type: document_type.to_string(),
            issuing_country: issuing_country.to_string(),
            document_number: clean_text(document_number),
            expiry_date: clean_text(expiry_date),
            date_of_birth: clean_text(date_of_birth),
            sex: sex.to_string(),
            nationality: nationality.to_string(),
            surname: surname_given.0,
            given_names: surname_given.1,
            optional_data: Some(optional_data.to_string()),
            raw_text: format!("{}\n{}", line1, line2),
            checksums_valid: {
                Self::validate_checksum(document_number, doc_num_check)
                    && Self::validate_checksum(date_of_birth, dob_check)
                    && Self::validate_checksum(expiry_date, exp_check)
                    && expected_final == Self::character_value(final_check).unwrap_or(-1)
            },
            format: MrzFormat::Td2,
        })
    }

    pub fn parse_td3(line1: &str, line2: &str) -> Option<MrzResult> {
        if line1.len() < 44 || line2.len() < 44 {
            return None;
        }

        let document_type = &line1[0..2];
        let issuing_country = &line1[2..5];
        let surname_given = parse_name(&line1[5..44]);

        let document_number = &line2[..9];
        let doc_num_check = line2.chars().nth(9)?;
        let nationality = &line2[10..13];
        let date_of_birth = &line2[13..19];
        let dob_check = line2.chars().nth(19)?;
        let sex = &line2[20..21];
        let expiry_date = &line2[21..27];
        let exp_check = line2.chars().nth(27)?;
        let personal_number = &line2[28..42];
        let personal_check = line2.chars().nth(42)?;
        let final_check = line2.chars().nth(43)?;

        let composite = format!(
            "{}{}{}{}",
            document_number, date_of_birth, expiry_date, personal_number
        );
        let expected_final = Self::compute_checksum_for_string(&composite);

        Some(MrzResult {
            document_type: document_type.to_string(),
            issuing_country: issuing_country.to_string(),
            document_number: clean_text(document_number),
            expiry_date: clean_text(expiry_date),
            date_of_birth: clean_text(date_of_birth),
            sex: sex.to_string(),
            nationality: nationality.to_string(),
            surname: surname_given.0,
            given_names: surname_given.1,
            optional_data: Some(personal_number.to_string()),
            raw_text: format!("{}\n{}", line1, line2),
            checksums_valid: {
                Self::validate_checksum(document_number, doc_num_check)
                    && Self::validate_checksum(date_of_birth, dob_check)
                    && Self::validate_checksum(expiry_date, exp_check)
                    && Self::validate_checksum(personal_number, personal_check)
                    && expected_final == Self::character_value(final_check).unwrap_or(-1)
            },
            format: MrzFormat::Td3,
        })
    }

    pub fn parse_any(mrz_text: &str) -> Option<MrzResult> {
        let lines: Vec<&str> = mrz_text.lines().map(|l| l.trim()).collect();
        match lines.len() {
            3 => Self::parse_td1(lines[0], lines[1], lines[2]),
            2 => {
                if lines[0].len() <= 36 {
                    Self::parse_td2(lines[0], lines[1])
                } else {
                    Self::parse_td3(lines[0], lines[1])
                }
            }
            _ => None,
        }
    }

    pub fn extract_fields(result: &MrzResult) -> Vec<ExtractedField> {
        let mut fields = Vec::new();

        fields.push(ExtractedField {
            name: "document_type".to_string(),
            value: result.document_type.clone(),
            confidence: 0.95,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "issuing_country".to_string(),
            value: result.issuing_country.clone(),
            confidence: 0.95,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "document_number".to_string(),
            value: result.document_number.clone(),
            confidence: if result.checksums_valid { 0.98 } else { 0.7 },
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "expiry_date".to_string(),
            value: result.expiry_date.clone(),
            confidence: 0.95,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "date_of_birth".to_string(),
            value: result.date_of_birth.clone(),
            confidence: 0.95,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "sex".to_string(),
            value: result.sex.clone(),
            confidence: 0.95,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "nationality".to_string(),
            value: result.nationality.clone(),
            confidence: 0.95,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "surname".to_string(),
            value: result.surname.clone(),
            confidence: 0.9,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields.push(ExtractedField {
            name: "given_names".to_string(),
            value: result.given_names.clone(),
            confidence: 0.9,
            method: ExtractionMethod::Mrz,
            raw_text: Some(result.raw_text.clone()),
            bounding_box: None,
        });

        fields
    }
}

fn parse_name(field: &str) -> (String, String) {
    let cleaned = clean_text(field);
    if let Some(double_less) = cleaned.find("<<") {
        let surname_part = cleaned[..double_less].trim().replace('<', " ").trim().to_string();
        let given_part = cleaned[double_less + 2..]
            .trim()
            .replace('<', " ")
            .trim()
            .to_string();
        (surname_part, given_part)
    } else if let Some(single_less) = cleaned.find('<') {
        let surname_part = cleaned[..single_less].trim().to_string();
        let given_part = cleaned[single_less + 1..]
            .trim()
            .replace('<', " ")
            .trim()
            .to_string();
        (surname_part, given_part)
    } else {
        (cleaned, String::new())
    }
}

fn clean_text(s: &str) -> String {
    s.trim_end_matches('<')
        .trim_start_matches('<')
        .to_string()
}

#[async_trait]
impl ExtractionEngine for MrzEngine {
    fn name(&self) -> &'static str {
        "mrz"
    }

    async fn extract(&self, image: &DocumentImage) -> anyhow::Result<Vec<ExtractedField>> {
        let img = image::load_from_memory(&image.data)?;
        let gray = img.to_luma8();

        let ocr_result = perform_mrz_ocr(&gray)?;
        let lines: Vec<&str> = ocr_result.lines().collect();

        let raw_text = lines.join("\n");

        let result = Self::parse_any(&raw_text)
            .ok_or_else(|| anyhow::anyhow!("Failed to parse MRZ from text: {}", raw_text))?;

        Ok(Self::extract_fields(&result))
    }

    fn supported_formats(&self) -> Vec<&'static str> {
        vec!["image/png", "image/jpeg", "image/tiff", "image/bmp"]
    }
}

fn perform_mrz_ocr(image: &image::GrayImage) -> anyhow::Result<String> {
    use imageproc::contrast::adaptive_threshold;

    let thresholded = adaptive_threshold(image, 61);
    let processed = imageproc::morphology::dilate(
        &thresholded,
        imageproc::morphology::Square(2),
        1,
    );

    let mut ocr = tesseract::Tesseract::new(
        Some("eng"),
        Some(crate::Config::from_env()
            .map(|c| c.tesseract_data_path.to_string_lossy().to_string())
            .unwrap_or_else(|_| "/usr/share/tessdata".to_string())),
    )?;

    ocr.set_image_from_bytes(processed.as_raw())?;
    ocr.set_page_seg_mode(6);
    ocr.set_variable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<");
    let text = ocr.get_text()?;

    Ok(text)
}
