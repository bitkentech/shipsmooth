//! Port of `TaskStore.getXmlDate`/`getXmlDateTime`: the XSD lexical timestamp
//! forms Java produces via `XMLGregorianCalendar`, plus the injectable clock
//! seam TaskStore's mutations draw timestamps from.
//!
//! The lexical contract (probed against the real JDK, plan-107 Task 3):
//! - dateTime always carries exactly three fractional digits, `.000` included;
//! - a zero UTC offset prints `Z`, never `+00:00`;
//! - sub-millisecond precision is floor-truncated, not rounded;
//! - date is the plain `yyyy-MM-dd` with no offset.

use time::{Date, OffsetDateTime, UtcOffset};

/// The clock TaskStore mutations read timestamps from. The shipped default is
/// [`system_now`]; tests and the golden-replay harness pin a fixed instant.
pub type Clock = Box<dyn Fn() -> OffsetDateTime>;

/// Java's `OffsetDateTime.now()`: the current instant at the local UTC offset.
/// Falls back to UTC if the platform offset cannot be determined.
pub fn system_now() -> OffsetDateTime {
    OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc())
}

/// `xs:date` as Java renders `LocalDate.toString()` — e.g. `2026-08-06`.
pub fn xml_date(date: Date) -> String {
    format!("{:04}-{:02}-{:02}", date.year(), u8::from(date.month()), date.day())
}

/// `xs:dateTime` as `XMLGregorianCalendar` renders a `GregorianCalendar` —
/// e.g. `2026-08-06T18:15:26.599+05:30`, `2026-08-06T18:15:26.000Z`.
pub fn xml_date_time(dt: OffsetDateTime) -> String {
    let millis = dt.nanosecond() / 1_000_000;
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}{}",
        dt.year(),
        u8::from(dt.month()),
        dt.day(),
        dt.hour(),
        dt.minute(),
        dt.second(),
        millis,
        xml_offset(dt.offset())
    )
}

/// Offset as `XMLGregorianCalendar` prints its minute-granular timezone:
/// `Z` when zero, otherwise `±hh:mm` (seconds truncated toward zero, as
/// Java's millis-to-minutes integer division does).
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
    use time::macros::{date, datetime};

    // Spec values produced by the real JDK (DatatypeFactory probe, Task 3).
    #[test]
    fn renders_java_xml_gregorian_lexical_forms() {
        let cases = [
            (datetime!(2026-08-06 18:15:26 +05:30), "2026-08-06T18:15:26.000+05:30"),
            (datetime!(2026-08-06 18:15:26 UTC), "2026-08-06T18:15:26.000Z"),
            (datetime!(2026-08-06 18:15:26.599 UTC), "2026-08-06T18:15:26.599Z"),
            (datetime!(2026-08-06 18:15:26.050 -04:00), "2026-08-06T18:15:26.050-04:00"),
            (datetime!(2026-08-06 18:15:26.005 +05:30), "2026-08-06T18:15:26.005+05:30"),
            (datetime!(2026-08-06 18:15:26.599999999 +05:30), "2026-08-06T18:15:26.599+05:30"),
        ];
        for (dt, expected) in cases {
            assert_eq!(xml_date_time(dt), expected);
        }
        assert_eq!(xml_date(date!(2026-08-06)), "2026-08-06");
    }

    // Every timestamp the Java CLI wrote into the Task 1 mutation fixtures
    // must survive a parse → re-render round trip byte-identically.
    #[test]
    fn golden_fixture_timestamps_round_trip() {
        let dt_format = time::macros::format_description!(
            "[year]-[month]-[day]T[hour]:[minute]:[second].[subsecond digits:3][offset_hour sign:mandatory]:[offset_minute]"
        );
        let date_format = time::macros::format_description!("[year]-[month]-[day]");

        let fixtures = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/xml/gw");
        let mut timestamps = 0;
        let mut dates = 0;
        for entry in std::fs::read_dir(fixtures).unwrap() {
            let xml = std::fs::read_to_string(entry.unwrap().path()).unwrap();
            for lexical in values_of(&xml, "timestamp") {
                let parsed = OffsetDateTime::parse(lexical, &dt_format).unwrap();
                assert_eq!(xml_date_time(parsed), lexical);
                timestamps += 1;
            }
            for lexical in values_of(&xml, "created") {
                let parsed = Date::parse(lexical, &date_format).unwrap();
                assert_eq!(xml_date(parsed), lexical);
                dates += 1;
            }
        }
        assert!(timestamps > 20, "expected a real corpus, found {timestamps} timestamps");
        assert!(dates >= 18, "expected a created date per fixture, found {dates}");
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

    #[test]
    fn system_now_renders_a_valid_lexical_form() {
        let rendered = xml_date_time(system_now());
        // 2026-08-13T..:..:...sss±hh:mm or ...Z — shape, not value.
        assert!(rendered.len() == 29 || rendered.len() == 24, "unexpected form: {rendered}");
        assert_eq!(&rendered[10..11], "T");
        assert_eq!(&rendered[19..20], ".");
    }
}
