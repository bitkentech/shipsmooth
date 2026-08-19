//! Port of `TaskStore.getXmlDate`/`getXmlDateTime`: the XSD lexical timestamp
//! forms Java produces via `XMLGregorianCalendar`, plus the injectable clock
//! seam TaskStore's mutations draw timestamps from.
//!
//! The lexical contract (probed against the real JDK, plan-107 Task 3):
//! - dateTime always carries exactly three fractional digits, `.000` included;
//! - a zero UTC offset prints `Z`, never `+00:00`;
//! - sub-millisecond precision is floor-truncated, not rounded;
//! - date is the plain `yyyy-MM-dd` with no offset.
//!
//! Rendering is hand-rolled rather than driven by a `format_description!`,
//! because none of the crate's built-in descriptions produce Java's
//! `Z`-for-zero-offset rule. The tests parse the output back with a crate
//! format description, so the hand-rolled writer is cross-checked rather
//! than merely self-consistent.

use time::error::IndeterminateOffset;
use time::{Date, OffsetDateTime, UtcOffset};

/// The clock TaskStore mutations read timestamps from. The shipped default is
/// [`system_now`]; tests and the golden-replay harness pin a fixed instant.
pub type Clock = Box<dyn Fn() -> OffsetDateTime>;

/// Java's `OffsetDateTime.now()`: the current instant at the local UTC offset.
///
/// `now_local` refuses to read the platform offset once the process is
/// multi-threaded (a soundness rule in `time`), so the offset is not always
/// available. The CLI is single-threaded and gets the real local offset;
/// anything else falls back to UTC, which still renders a valid lexical form.
pub fn system_now() -> OffsetDateTime {
    local_or_utc(OffsetDateTime::now_local())
}

/// The fallback half of [`system_now`], split out so both arms are testable.
fn local_or_utc(local: Result<OffsetDateTime, IndeterminateOffset>) -> OffsetDateTime {
    local.unwrap_or_else(|_| OffsetDateTime::now_utc())
}

/// `xs:date` as Java renders `LocalDate.toString()` — e.g. `2026-08-06`.
pub fn xml_date(date: Date) -> String {
    format!("{:04}-{:02}-{:02}", date.year(), u8::from(date.month()), date.day())
}

/// `xs:dateTime` as `XMLGregorianCalendar` renders a `GregorianCalendar` —
/// e.g. `2026-08-06T18:15:26.599+05:30`, `2026-08-06T18:15:26.000Z`.
pub fn xml_date_time(dt: OffsetDateTime) -> String {
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}{}",
        dt.year(),
        u8::from(dt.month()),
        dt.day(),
        dt.hour(),
        dt.minute(),
        dt.second(),
        dt.millisecond(),
        xml_offset(dt.offset())
    )
}

/// Offset as `XMLGregorianCalendar` prints its minute-granular timezone:
/// `Z` when zero, otherwise `±hh:mm`. Any seconds component is truncated
/// toward zero, matching Java's millis-to-minutes integer division.
fn xml_offset(offset: UtcOffset) -> String {
    let total_minutes = offset.whole_minutes();
    if total_minutes == 0 {
        return "Z".to_string();
    }
    let sign = if total_minutes < 0 { '-' } else { '+' };
    let abs = total_minutes.unsigned_abs();
    format!("{}{:02}:{:02}", sign, abs / 60, abs % 60)
}

#[cfg(test)]
mod tests {
    use super::*;
    use time::macros::{date, datetime, format_description};

    // ---- lexical forms: spec values produced by the real JDK ----

    #[test]
    fn renders_a_whole_second_with_three_zero_fractional_digits() {
        let dt = datetime!(2026-08-06 18:15:26 +05:30);
        assert_eq!(xml_date_time(dt), "2026-08-06T18:15:26.000+05:30");
    }

    #[test]
    fn renders_a_zero_offset_as_z_not_plus_zero() {
        let dt = datetime!(2026-08-06 18:15:26 UTC);
        assert_eq!(xml_date_time(dt), "2026-08-06T18:15:26.000Z");
    }

    #[test]
    fn renders_millisecond_precision_verbatim() {
        let dt = datetime!(2026-08-06 18:15:26.599 UTC);
        assert_eq!(xml_date_time(dt), "2026-08-06T18:15:26.599Z");
    }

    #[test]
    fn zero_pads_sub_hundred_millisecond_values() {
        assert_eq!(
            xml_date_time(datetime!(2026-08-06 18:15:26.050 -04:00)),
            "2026-08-06T18:15:26.050-04:00"
        );
        assert_eq!(
            xml_date_time(datetime!(2026-08-06 18:15:26.005 +05:30)),
            "2026-08-06T18:15:26.005+05:30"
        );
    }

    #[test]
    fn floor_truncates_sub_millisecond_precision() {
        let dt = datetime!(2026-08-06 18:15:26.599999999 +05:30);
        assert_eq!(xml_date_time(dt), "2026-08-06T18:15:26.599+05:30");
    }

    #[test]
    fn renders_a_negative_offset_with_a_minus_sign() {
        let dt = datetime!(2026-08-06 18:15:26 -09:30);
        assert_eq!(xml_date_time(dt), "2026-08-06T18:15:26.000-09:30");
    }

    #[test]
    fn truncates_an_offset_seconds_component_toward_zero() {
        // Java divides the offset's millis by 60_000; the seconds never print.
        let offset = UtcOffset::from_hms(5, 30, 45).unwrap();
        assert_eq!(xml_offset(offset), "+05:30");
        assert_eq!(xml_offset(UtcOffset::from_hms(-5, -30, -45).unwrap()), "-05:30");
    }

    #[test]
    fn pads_a_year_below_four_digits() {
        let dt = datetime!(0999-01-02 03:04:05 UTC);
        assert_eq!(xml_date_time(dt), "0999-01-02T03:04:05.000Z");
    }

    #[test]
    fn renders_a_date_without_an_offset() {
        assert_eq!(xml_date(date!(2026-08-06)), "2026-08-06");
        assert_eq!(xml_date(date!(0999-01-02)), "0999-01-02");
    }

    // ---- the clock seam ----

    #[test]
    fn local_or_utc_keeps_the_local_offset_when_it_is_available() {
        let pinned = datetime!(2026-08-06 18:15:26.123 +05:30);
        assert_eq!(local_or_utc(Ok(pinned)), pinned);
    }

    #[test]
    fn local_or_utc_falls_back_to_utc_when_the_offset_is_indeterminate() {
        let fallback = local_or_utc(Err(IndeterminateOffset));
        assert_eq!(fallback.offset(), UtcOffset::UTC);
        assert!(xml_date_time(fallback).ends_with('Z'));
    }

    #[test]
    fn system_now_renders_a_form_that_parses_back_to_the_same_instant() {
        let now = system_now();
        let rendered = xml_date_time(now);
        let reparsed = parse_xml_date_time(&rendered);
        // Rendering drops sub-millisecond precision; compare at that grain.
        assert_eq!(xml_date_time(reparsed), rendered);
        assert_eq!(reparsed.unix_timestamp(), now.unix_timestamp());
    }

    // ---- golden corpus ----

    // Every timestamp the Java CLI wrote into the Task 1 mutation fixtures
    // must survive a parse → re-render round trip byte-identically.
    #[test]
    fn golden_fixture_timestamps_round_trip() {
        let fixtures = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/xml/gw");
        let mut timestamps = 0;
        let mut dates = 0;
        for entry in std::fs::read_dir(&fixtures).expect("Task 1 fixture corpus is missing") {
            let xml = std::fs::read_to_string(entry.unwrap().path()).unwrap();
            for lexical in values_of(&xml, "timestamp") {
                assert_eq!(xml_date_time(parse_xml_date_time(lexical)), lexical);
                timestamps += 1;
            }
            for lexical in values_of(&xml, "created") {
                let parsed = Date::parse(lexical, DATE_FORMAT).unwrap();
                assert_eq!(xml_date(parsed), lexical);
                dates += 1;
            }
        }
        // Floors, not exact counts: they guard against an empty or unreadable
        // fixture dir turning this into a vacuously passing test.
        assert!(timestamps > 20, "expected a real corpus, found {timestamps} timestamps");
        assert!(dates >= 18, "expected a created date per fixture, found {dates}");
    }

    // Parsed with a crate format description so the hand-rolled writer in
    // `xml_date_time` is checked against an independent implementation.
    const DATE_TIME_FORMAT: &[time::format_description::FormatItem] = format_description!(
        "[year]-[month]-[day]T[hour]:[minute]:[second].[subsecond digits:3][offset_hour sign:mandatory]:[offset_minute]"
    );
    const DATE_FORMAT: &[time::format_description::FormatItem] =
        format_description!("[year]-[month]-[day]");

    fn parse_xml_date_time(lexical: &str) -> OffsetDateTime {
        // `Z` is not an offset the description above accepts; expand it.
        let expanded = lexical.strip_suffix('Z').map(|head| format!("{head}+00:00"));
        let text = expanded.as_deref().unwrap_or(lexical);
        OffsetDateTime::parse(text, DATE_TIME_FORMAT)
            .unwrap_or_else(|e| panic!("unparseable lexical form {lexical}: {e}"))
    }

    fn values_of<'a>(xml: &'a str, element: &str) -> Vec<&'a str> {
        let open = format!("<{element}>");
        let close = format!("</{element}>");
        xml.match_indices(&open)
            .filter_map(|(at, _)| {
                let rest = &xml[at + open.len()..];
                rest.find(&close).map(|end| &rest[..end])
            })
            .collect()
    }
}
